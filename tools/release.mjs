#!/usr/bin/env node
/*
  Makes a release's files in dist/:
    mifi-<version>.apk          the signed app (from assembleRelease)
    web-<n>.zip, web-<n>.zip.sig  the screens, signed so the app accepts them over the air

  node tools/release.mjs              build both
  node tools/release.mjs --web-only   just the screens (a screens-only release)
  node tools/release.mjs --publish    also create the GitHub release with gh

  Needs: Node 22+, the web key at $MIFI_WEB_KEY (default D:/Android/keys/web-bundle.key),
  and for the APK, `mifi.signing` set in your user gradle.properties.
*/
import { readFileSync, readdirSync, writeFileSync, mkdirSync, copyFileSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createPrivateKey, createPublicKey, sign, verify } from 'node:crypto';
import { deflateRawSync, crc32 } from 'node:zlib';
import { execFileSync } from 'node:child_process';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const web = join(root, 'app/src/main/assets/web');
const dist = join(root, 'dist');
const args = new Set(process.argv.slice(2));
const LEAVE_OUT = new Set(['dev.js']);
mkdirSync(dist, { recursive: true });
const mtime = p => { try { return statSync(p).mtimeMs; } catch { return 0; } };

const versionName = readFileSync(join(root, 'app/build.gradle.kts'), 'utf8').match(/versionName\s*=\s*"([^"]+)"/)[1];
const webVersion = JSON.parse(readFileSync(join(web, 'version.json'), 'utf8')).web;

// ---- the screens ----
function zip(files) {
  const local = [], central = [];
  let offset = 0;
  for (const { name, data } of files) {
    const n = Buffer.from(name), body = deflateRawSync(data, { level: 9 }), crc = crc32(data);
    const h = Buffer.alloc(30);
    h.writeUInt32LE(0x04034b50, 0); h.writeUInt16LE(20, 4); h.writeUInt16LE(0x0800, 6); h.writeUInt16LE(8, 8);
    h.writeUInt32LE(0, 10); h.writeUInt32LE(crc, 14); h.writeUInt32LE(body.length, 18); h.writeUInt32LE(data.length, 22);
    h.writeUInt16LE(n.length, 26); h.writeUInt16LE(0, 28);
    local.push(h, n, body);
    const c = Buffer.alloc(46);
    c.writeUInt32LE(0x02014b50, 0); c.writeUInt16LE(20, 4); c.writeUInt16LE(20, 6); c.writeUInt16LE(0x0800, 8); c.writeUInt16LE(8, 10);
    c.writeUInt32LE(0, 12); c.writeUInt32LE(crc, 16); c.writeUInt32LE(body.length, 20); c.writeUInt32LE(data.length, 24);
    c.writeUInt16LE(n.length, 28); c.writeUInt32LE(offset, 42);
    central.push(c, n);
    offset += 30 + n.length + body.length;
  }
  const cd = Buffer.concat(central), end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(files.length, 8); end.writeUInt16LE(files.length, 10);
  end.writeUInt32LE(cd.length, 12); end.writeUInt32LE(offset, 16);
  return Buffer.concat([...local, cd, end]);
}

const walk = (d, pre = '') => readdirSync(d, { withFileTypes: true }).flatMap(e =>
  e.isDirectory() ? walk(join(d, e.name), `${pre}${e.name}/`) : [`${pre}${e.name}`]);
const files = walk(web).filter(f => !LEAVE_OUT.has(f) && !f.startsWith('.')).sort()
  .map(name => ({ name, data: readFileSync(join(web, name)) }));
const bundle = zip(files);
const keyPath = process.env.MIFI_WEB_KEY || 'D:/Android/keys/web-bundle.key';
const key = createPrivateKey(readFileSync(keyPath));
const sig = sign('sha256', bundle, key); // DER, which is what SHA256withECDSA verifies
const pub = readFileSync(join(root, 'keys/web-bundle.pub'), 'utf8').trim();
const pubKey = createPublicKey({ key: Buffer.from(pub, 'base64'), format: 'der', type: 'spki' });
if (!verify('sha256', bundle, pubKey, sig)) throw new Error('keys/web-bundle.pub does not match the private key; the app would refuse these screens.');
const zipName = `web-${webVersion}.zip`;
writeFileSync(join(dist, zipName), bundle);
writeFileSync(join(dist, `${zipName}.sig`), sig.toString('base64') + '\n');
console.log(`${zipName}: ${files.length} files, ${bundle.length} bytes, signature checked`);

// ---- the app ----
const out = [join(dist, zipName), join(dist, `${zipName}.sig`)];
if (!args.has('--web-only')) {
  const gradlew = join(root, process.platform === 'win32' ? 'gradlew.bat' : 'gradlew');
  execFileSync(process.platform === 'win32' ? `"${gradlew}"` : gradlew, ['assembleRelease', '--console=plain', '-q'], { cwd: root, stdio: 'inherit', shell: process.platform === 'win32' });
  // The APK lands in app/build, or under `mifi.buildRoot` when that is set in your user gradle.properties.
  const candidates = [join(root, 'app/build/outputs/apk/release/app-release.apk'), 'D:/Android/b/mifi-app/app/outputs/apk/release/app-release.apk'];
  const apk = candidates.sort((a, b) => mtime(b) - mtime(a)).find(p => mtime(p) > 0);
  if (!apk) throw new Error(`No release APK found (looked in ${candidates.join(', ')}).`);
  const apkName = `mifi-${versionName}.apk`;
  copyFileSync(apk, join(dist, apkName));
  out.unshift(join(dist, apkName));
  console.log(`${apkName}: ${statSync(join(dist, apkName)).size} bytes`);
}

if (args.has('--publish')) {
  const tag = args.has('--web-only') ? `web-${webVersion}` : `v${versionName}`;
  const notes = args.has('--web-only') ? `Screens version ${webVersion}. The app installs these by itself.` : `Version ${versionName}, with screens version ${webVersion}.`;
  const ask = '\n\nFeedback or a feature request? Message me on [WhatsApp](https://wa.me/2348149322169) or [Facebook](https://www.facebook.com/hebronmimam).';
  execFileSync('gh', ['release', 'create', tag, ...out, '--title', tag, '--notes', notes + (args.has('--web-only') ? '' : ask)], { cwd: root, stdio: 'inherit' });
}
