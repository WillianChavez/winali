// Serves a catalog and a newer Winali APK on 127.0.0.1:8099 to demonstrate self-update on an emulator.
//
//   WINALI_CATALOG_URL=http://127.0.0.1:8099/catalog.json VERSION_CODE=1000 VERSION_NAME=0.1.0 ./gradlew :app:assembleDebug   # the installed "old" build
//   WINALI_CATALOG_URL=http://127.0.0.1:8099/catalog.json ./gradlew :app:assembleDebug                                    # the served "new" build
//   node tools/local-update-server.mjs NEW.apk APK_SIGNER_SHA256 [chunkMs]
//   adb reverse tcp:8099 tcp:8099
//
// Only debug builds accept the WINALI_CATALOG_URL override and loopback HTTP; release builds always use the public catalog.
import fs from 'node:fs';
import http from 'node:http';
import crypto from 'node:crypto';
import path from 'node:path';
import {execFileSync} from 'node:child_process';

const [apkPath, certificate, chunkMs = '250'] = process.argv.slice(2);
if (!apkPath || !/^[a-f0-9]{64}$/.test(certificate ?? '')) throw new Error('Usage: node tools/local-update-server.mjs NEW.apk SIGNER_SHA256 [chunkMs]');
const sdk = process.env.ANDROID_HOME ?? process.env.ANDROID_SDK_ROOT;
const aapt2 = path.join(sdk, 'build-tools', '36.0.0', process.platform === 'win32' ? 'aapt2.exe' : 'aapt2');
const badging = execFileSync(aapt2, ['dump', 'badging', apkPath], {encoding: 'utf8'});
const versionCode = Number(badging.match(/versionCode='(\d+)'/)[1]);
const versionName = badging.match(/versionName='([^']+)'/)[1];
const bytes = fs.readFileSync(apkPath);
const catalog = JSON.parse(fs.readFileSync(new URL('../app/src/main/assets/catalog.json', import.meta.url), 'utf8'));
catalog.revision += 100;
const winali = catalog.apps.find(app => app.id === 'winali');
winali.release = {
  ...winali.release,
  versionCode,
  versionName,
  url: 'http://127.0.0.1:8099/winali.apk',
  size: bytes.length,
  sha256: crypto.createHash('sha256').update(bytes).digest('hex'),
  certificateSha256: certificate,
  changelog: `Local test build ${versionName}.`,
};
const text = JSON.stringify(catalog, null, 2);
http.createServer((req, res) => {
  console.log(new Date().toISOString(), req.method, req.url, req.headers.range ?? '');
  if (req.url === '/catalog.json') { res.writeHead(200, {'Content-Type': 'application/json'}); res.end(text); return; }
  if (req.url !== '/winali.apk') { res.writeHead(404); res.end(); return; }
  const range = req.headers.range?.match(/bytes=(\d+)-/);
  const start = range ? Number(range[1]) : 0;
  res.writeHead(range ? 206 : 200, {
    'Content-Type': 'application/vnd.android.package-archive',
    'Content-Length': bytes.length - start,
    ...(range ? {'Content-Range': `bytes ${start}-${bytes.length - 1}/${bytes.length}`} : {}),
  });
  let sent = start;
  const timer = setInterval(() => {
    if (res.destroyed || sent >= bytes.length) { clearInterval(timer); res.end(); return; }
    res.write(bytes.subarray(sent, Math.min(sent + 262144, bytes.length)));
    sent += 262144;
  }, Number(chunkMs));
  res.on('close', () => clearInterval(timer));
}).listen(8099, '127.0.0.1', () => console.log(`Serving Winali ${versionName} (${versionCode}) on 127.0.0.1:8099`));
