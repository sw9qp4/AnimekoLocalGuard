#!/usr/bin/env node
/**
 * Streaming file downloader with visible progress.
 *
 * Difference from dl.js: this one writes each chunk straight to disk, so the
 * .part file grows and progress can be observed. dl.js accumulates in memory
 * (fine for small files, opaque for large ones).
 *
 * Usage: node dl_stream.js <url> <outPath> [--sha256 <expectedHex>] [--timeoutSec N]
 */
'use strict';
const https = require('https');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const [url, outPath] = process.argv.slice(2);
if (!url || !outPath) { console.error('usage: node dl_stream.js <url> <outPath>'); process.exit(2); }

function argValue(name) {
  const i = process.argv.indexOf(name);
  return i >= 0 ? process.argv[i + 1] : null;
}
const expectedSha256 = argValue('--sha256');

const UA = 'AnimekoLocalGuard-tooling/0.1';

function open(u, depth) {
  return new Promise((resolve, reject) => {
    if (depth > 8) return reject(new Error('too many redirects'));
    const req = https.get(u, { headers: { 'User-Agent': UA, Accept: '*/*' } }, (res) => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        res.resume();
        return resolve(open(new URL(res.headers.location, u).toString(), depth + 1));
      }
      if (res.statusCode !== 200) { res.resume(); return reject(new Error('HTTP ' + res.statusCode)); }
      resolve(res);
    });
    req.on('error', reject);
    req.setTimeout(30000, () => req.destroy(new Error('socket timeout 30s')));
  });
}

(async () => {
  fs.mkdirSync(path.dirname(outPath), { recursive: true });
  const tmp = outPath + '.part';
  const total = Number(process.env.DL_TOTAL || 0);

  let res;
  for (let attempt = 1; attempt <= 3; attempt++) {
    try { res = await open(url, 0); break; }
    catch (e) {
      console.log(`attempt ${attempt} failed: ${e.message}`);
      if (attempt === 3) throw e;
      await new Promise((r) => setTimeout(r, 3000));
    }
  }

  const declared = Number(res.headers['content-length'] || 0);
  const ws = fs.createWriteStream(tmp);
  const hash = crypto.createHash('sha256');
  let bytes = 0;
  const t0 = Date.now();
  let lastLog = 0;

  await new Promise((resolve, reject) => {
    res.on('data', (c) => {
      bytes += c.length;
      hash.update(c);
      ws.write(c);
      const now = Date.now();
      if (now - lastLog > 15000) {
        lastLog = now;
        const secs = (now - t0) / 1000;
        const pct = declared ? ((bytes / declared) * 100).toFixed(1) + '%' : '?';
        const kbs = (bytes / 1024 / secs).toFixed(0);
        console.log(`progress ${bytes}/${declared || '?'} bytes (${pct}) ${kbs} KB/s elapsed ${secs.toFixed(0)}s`);
      }
    });
    res.on('end', () => ws.end(resolve));
    res.on('error', reject);
    ws.on('error', reject);
  });

  fs.renameSync(tmp, outPath);
  const sha256 = hash.digest('hex');
  const secs = ((Date.now() - t0) / 1000).toFixed(1);
  console.log(JSON.stringify({
    url, out: outPath, bytes, declaredContentLength: declared || null,
    sha256, seconds: Number(secs), avgKBps: Math.round(bytes / 1024 / Number(secs)),
    sha256MatchesExpected: expectedSha256 ? (sha256.toLowerCase() === expectedSha256.toLowerCase()) : null,
  }, null, 2));
})().catch((e) => { console.error('ERROR: ' + e.message); process.exit(1); });
