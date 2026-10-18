import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { validateCatalog, mergeRelease, saveCatalog } from './catalog.mjs';
import os from 'node:os';
import path from 'node:path';
const catalog=()=>JSON.parse(fs.readFileSync(new URL('../catalog/index.json',import.meta.url),'utf8'));
const release={versionCode:1000,versionName:'0.1.0',minSdk:26,architectures:['universal'],url:'https://github.com/owner/app/releases/download/v0.1.0/app.apk',size:1024,sha256:'a'.repeat(64),certificateSha256:'b'.repeat(64),changelog:'First release'};
test('accepts the shipped catalog',()=>assert.equal(validateCatalog(catalog()).apps.length,4));
test('rejects duplicate identities',()=>{const c=catalog();c.apps.push(c.apps[0]);assert.throws(()=>validateCatalog(c),/Duplicate/);});
test('rejects unsupported schema',()=>assert.throws(()=>validateCatalog({...catalog(),schemaVersion:2}),/schemaVersion/));
test('rejects HTTP downloads and malformed hashes',()=>{for(const patch of [{url:'http://example.com/a.apk'},{sha256:'a'},{certificateSha256:''},{size:-1},{versionCode:1.5}]){const c=catalog();c.apps[0].release={...release,...patch};assert.throws(()=>validateCatalog(c));}});
test('rejects release for another package',()=>assert.throws(()=>mergeRelease(catalog().apps[0],{schemaVersion:1,packageName:'other.app',release}),/mismatch/));
test('accepts a valid initial release',()=>{const app={...catalog().apps[0],release:null};assert.equal(mergeRelease(app,{schemaVersion:1,packageName:app.packageName,release}).release.versionCode,1000);});
test('publishing keeps unchanged revisions and increments changed content',()=>{
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'winali-catalog-'));
  try{
    fs.mkdirSync(path.join(root,'catalog'));const c=catalog();saveCatalog(c,root);const first=c.revision;
    saveCatalog(c,root);assert.equal(c.revision,first);
    c.apps[0].summary+=' Updated';saveCatalog(c,root);assert.equal(c.revision,first+1);
    assert.deepEqual(JSON.parse(fs.readFileSync(path.join(root,'catalog/index.json'),'utf8')),JSON.parse(fs.readFileSync(path.join(root,'app/src/main/assets/catalog.json'),'utf8')));
  }finally{assert.equal(path.dirname(path.resolve(root)),path.resolve(os.tmpdir()));assert(path.basename(root).startsWith('winali-catalog-'));fs.rmSync(root,{recursive:true});}
});
test('validates optional English text and rejects invalid revisions',()=>{
  const c=catalog();c.apps[0].locales={en:{summary:'English',description:'Description',category:'Utilities',permissions:[]}};validateCatalog(c);
  delete c.apps[0].locales.en.summary;assert.throws(()=>validateCatalog(c),/English summary/);
  for(const revision of [0,-1,1.5])assert.throws(()=>validateCatalog({...catalog(),revision}),/revision/);
});
test('rejects regression, key substitution and replacement of published bytes',()=>{const app={...catalog().apps[0],release};for(const patch of [{versionCode:999},{versionCode:1001,certificateSha256:'c'.repeat(64)},{sha256:'c'.repeat(64)}])assert.throws(()=>mergeRelease(app,{schemaVersion:1,packageName:app.packageName,release:{...release,...patch}}));});
