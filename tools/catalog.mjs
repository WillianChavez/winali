import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const STORE_REPOSITORY='https://github.com/WillianChavez/winali';
export const DOWNLOADS_BASE_URL='https://willianchavez.github.io/winali-downloads';
export function assert(condition, message) { if (!condition) throw new Error(message); }
export function releaseManifestUrl(appId, nonce = Date.now()) {
  const url=new URL(`${DOWNLOADS_BASE_URL}/apps/${appId}/latest/release.json`);
  url.searchParams.set('catalog_sync',`${nonce}`);
  return url;
}
export function https(value) { try {const url=new URL(value);return url.protocol==='https:' && !url.username && !url.password;}catch{return false;} }
export function validateRelease(release) {
  assert(Number.isSafeInteger(release.versionCode) && release.versionCode>0,'Invalid versionCode');
  assert(typeof release.versionName==='string' && release.versionName.length>0,'Missing versionName');
  assert(Number.isInteger(release.minSdk) && release.minSdk>=1,'Invalid minSdk');
  assert(Array.isArray(release.architectures) && release.architectures.every(v=>['universal','arm64-v8a','armeabi-v7a','x86','x86_64'].includes(v)),'Invalid architectures');
  assert(https(release.url),'APK URL must use HTTPS');
  assert(Number.isSafeInteger(release.size) && release.size>0 && release.size<=1073741824,'Invalid APK size');
  for(const field of ['sha256','certificateSha256'])assert(/^[a-f0-9]{64}$/.test(release[field]),`Invalid ${field}`);
  assert(typeof release.changelog==='string','Missing changelog');
  return release;
}
export function validateCatalog(catalog) {
  assert(catalog.schemaVersion===1,'Unsupported schemaVersion');
  if(catalog.revision!==undefined)assert(Number.isSafeInteger(catalog.revision) && catalog.revision>0,'Invalid catalog revision');
  assert(Array.isArray(catalog.apps) && catalog.apps.length<=1000,'Invalid apps');
  const ids=new Set(), packages=new Set();
  for(const app of catalog.apps){
    assert(/^[a-z][a-z0-9-]{0,63}$/.test(app.id),'Invalid id');
    assert(/^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$/.test(app.packageName),'Invalid packageName');
    assert(!ids.has(app.id) && !packages.has(app.packageName),'Duplicate identity');ids.add(app.id);packages.add(app.packageName);
    for(const field of ['name','summary','description','category'])assert(typeof app[field]==='string' && app[field].trim().length>0,`Missing ${field}`);
    assert(https(app.repository) && new URL(app.repository).hostname==='github.com' && /^\/[^/]+\/[^/]+\/?$/.test(new URL(app.repository).pathname),'Invalid GitHub repository');
    assert(Array.isArray(app.screenshots) && app.screenshots.every(https),'Invalid screenshots');
    assert(app.icon===undefined || (https(app.icon) && /\.png$/.test(app.icon)),'Invalid icon');
    assert(Array.isArray(app.permissions) && app.permissions.every(p=>typeof p==='string'),'Invalid permissions');
    if(app.locales?.en){
      for(const field of ['summary','description','category'])assert(typeof app.locales.en[field]==='string' && app.locales.en[field].trim().length>0,`Missing English ${field}`);
      assert(Array.isArray(app.locales.en.permissions) && app.locales.en.permissions.every(p=>typeof p==='string'),'Invalid English permissions');
    }
    if(app.release!==null)validateRelease(app.release);
  }
  return catalog;
}
export function mergeRelease(app, manifest){
  assert(manifest.schemaVersion===1 && manifest.packageName===app.packageName,'Release package mismatch');
  validateRelease(manifest.release);
  if(app.release){
    assert(manifest.release.versionCode>=app.release.versionCode,'Version regression');
    assert(manifest.release.certificateSha256===app.release.certificateSha256,'Certificate changed: manual key migration required');
    if(manifest.release.versionCode===app.release.versionCode)assert(manifest.release.sha256===app.release.sha256,'Published version bytes changed');
  }
  return {...app,release:manifest.release};
}
// Kept for validating and comparing legacy GitHub tags. New binaries are served from the static downloads repository.
export function releaseTag(app,releases){
  const prefix=app.id==='winali'?'v':`${app.id}-v`;
  const version=tag=>tag.slice(prefix.length).split('.').map(Number);
  const candidates=releases.filter(r=>!r.draft && !r.prerelease && typeof r.tag_name==='string' && r.tag_name.startsWith(prefix) && /^\d+\.\d+\.\d+$/.test(r.tag_name.slice(prefix.length)));
  assert(candidates.length>0,`${app.id}: no published release with tag ${prefix}X.Y.Z`);
  return candidates.map(r=>r.tag_name).sort((a,b)=>{const x=version(a),y=version(b);return x[0]-y[0]||x[1]-y[1]||x[2]-y[2];}).at(-1);
}
export function saveCatalog(catalog,root=process.cwd()){
  validateCatalog(catalog);
  const assets=path.join(root,'app/src/main/assets');
  let previous;try{previous=JSON.parse(fs.readFileSync(path.join(assets,'catalog.json'),'utf8'));}catch{}
  const payload=value=>JSON.stringify({...value,revision:undefined});
  const revision=Math.max(catalog.revision??0,previous?.revision??0);
  catalog.revision=previous && payload(previous)===payload(catalog)?Math.max(1,revision):revision+1;
  const text=JSON.stringify(catalog,null,2)+'\n';
  fs.writeFileSync(path.join(root,'catalog/index.json'),text);
  fs.mkdirSync(assets,{recursive:true});fs.writeFileSync(path.join(assets,'catalog.json'),text);
}
async function main(){
  const mode=process.argv[2] ?? 'validate';
  const catalog=validateCatalog(JSON.parse(fs.readFileSync('catalog/index.json','utf8')));
  if(mode==='sync'){
    const ids=process.argv.slice(3);
    for(let i=0;i<catalog.apps.length;i++){
      const app=catalog.apps[i];if(ids.length && !ids.includes(app.id))continue;
      const url=releaseManifestUrl(app.id);
      const response=await fetch(url,{signal:AbortSignal.timeout(30000)});
      assert(response.ok,`${app.id}: release manifest returned HTTP ${response.status}`);
      const text=await response.text();assert(text.length<=64000,'Release manifest too large');
      catalog.apps[i]=mergeRelease(app,JSON.parse(text));
    }
    saveCatalog(catalog);console.log('Updated and validated catalog; review the diff before publishing.');
  }else if(mode==='bundle'){saveCatalog(catalog);console.log('Bundled validated catalog.');}
  else {assert(mode==='validate','Use validate, bundle or sync [app ids]');const bundled=JSON.parse(fs.readFileSync('app/src/main/assets/catalog.json','utf8'));assert(JSON.stringify(bundled)===JSON.stringify(catalog),'Bundled catalog is stale');console.log(`Valid catalog v1: ${catalog.apps.length} applications`);}
}
if(process.argv[1] && path.resolve(process.argv[1])===fileURLToPath(import.meta.url))main().catch(error=>{console.error(error.message);process.exitCode=1;});
