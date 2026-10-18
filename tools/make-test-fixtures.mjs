// Regenerates the tiny installable APKs used by the instrumented installation tests.
// They hold no code or resources: only a manifest. Signing keys are throwaway and deleted afterwards,
// so the fixtures are tests-only and cannot impersonate any real app.
//
//   node tools/make-test-fixtures.mjs
//
// Requires ANDROID_HOME (build-tools 36.0.0, platform 36) and JAVA_HOME (keytool).
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {execFileSync} from 'node:child_process';

const sdk=process.env.ANDROID_HOME??process.env.ANDROID_SDK_ROOT;
if(!sdk)throw new Error('ANDROID_HOME must point to the Android SDK');
const exe=name=>process.platform==='win32'?`${name}.exe`:name;
const tools=path.join(sdk,'build-tools','36.0.0');
const androidJar=path.join(sdk,'platforms','android-36','android.jar');
const keytool=process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin',exe('keytool')):exe('keytool');
const apksigner=path.join(tools,process.platform==='win32'?'apksigner.bat':'apksigner');
const output=path.join(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/,'$1')),'..','app','src','androidTest','assets');
const work=fs.mkdtempSync(path.join(os.tmpdir(),'winali-fixtures-'));
const packageName='io.github.willianchavez.winali.fixture';
const password='fixture-only';

function key(name){
  const store=path.join(work,`${name}.jks`);
  execFileSync(keytool,['-genkeypair','-keystore',store,'-alias','k','-keyalg','RSA','-keysize','2048','-validity','36500','-dname',`CN=winali-test-${name}`,'-storepass',password,'-keypass',password],{stdio:'ignore'});
  return store;
}
function build(file,versionCode,store){
  const manifest=path.join(work,'AndroidManifest.xml');
  fs.writeFileSync(manifest,`<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="${packageName}">
  <application android:label="Winali fixture" android:hasCode="false"/>
</manifest>
`);
  const unsigned=path.join(work,`unsigned-${file}`),aligned=path.join(work,`aligned-${file}`);
  execFileSync(path.join(tools,exe('aapt2')),['link','-o',unsigned,'--manifest',manifest,'-I',androidJar,'--min-sdk-version','26','--target-sdk-version','34','--version-code',String(versionCode),'--version-name',String(versionCode)]);
  execFileSync(path.join(tools,exe('zipalign')),['-f','-p','4',unsigned,aligned]);
  fs.mkdirSync(output,{recursive:true});
  const target=path.join(output,file);
  fs.rmSync(target,{force:true});
  execFileSync(apksigner,['sign','--v4-signing-enabled','false','--ks',store,'--ks-pass',`pass:${password}`,'--key-pass',`pass:${password}`,'--out',target,aligned],{shell:process.platform==='win32'});
  console.log(`${file}: ${fs.statSync(target).size} bytes`);
}
try{
  const a=key('a'),b=key('b');
  build('fixture-1.apk',1,a);
  build('fixture-2.apk',2,a);
  build('fixture-2-foreign.apk',2,b);
}finally{
  fs.rmSync(work,{recursive:true,force:true});
}
