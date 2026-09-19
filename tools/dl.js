#!/usr/bin/env node
/**
 * BiliLocalGuard - tooling/downloader
 *
 * Why this exists: on this machine the Windows TLS stack is broken
 * (schannel SEC_E_NO_CREDENTIALS) so PowerShell/curl cannot fetch HTTPS.
 * Node.js ships its own CA bundle and works, so downloads go through here.
 *
 * Usage:
 *   node dl.js get <url> <outPath>      download to file
 *   node dl.js head <url>               HEAD-ish probe (status only)
 *   node dl.js api <github-api-path>    print JSON from api.github.com
 *
 * No archive is executed by this script. It only writes bytes to disk.
 */
'use strict';
const https = require('https');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const UA = 'BiliLocalGuard-tooling/0.1';

function request(url, { method = 'GET', headers = {}, redirects = 6, onResponse } = {}) {
  return new Promise((resolve, reject) => {
    const go = (u, left) => {
      const req = https.request(u, { method, headers: Object.assign({ 'User-Agent': UA, 'Accept': '*/*' }, headers) }, (res) => {
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location && left > 0) {
          res.resume();
          return go(new URL(res.headers.location, u).toString(), left - 1);
        }
        resolve({ res, url: u });
      });
      req.setTimeout(60000, () => req.destroy(new Error('timeout after 60s: ' + u)));
      req.on('error', reject);
      req.end();
    };
    go(url, redirects);
  });
}

async function download(url, outPath) {
  const { res, url: finalUrl } = await request(url);
  if (res.statusCode !== 200) {
    res.resume();
    throw new Error(`HTTP ${res.statusCode} for ${url}`);
  }
  fs.mkdirSync(path.dirname(outPath), { recursive: true });
  const tmp = outPath + '.part';
  const hash = crypto.createHash('sha256');
  let bytes = 0;
  await new Promise((resolve, reject) => {
    const ws = fs.createWriteStream(tmp);
    res.on('data', (c) => { bytes += c.length; hash.update(c); });
    res.pipe(ws);
    ws.on('finish', resolve);
    ws.on('error', reject);
    res.on('error', reject);
  });
  fs.renameSync(tmp, outPath);
  const sha256 = hash.digest('hex');
  fs.writeFileSync(outPath + '.sha256', `${sha256}  ${path.basename(outPath)}\n`);
  console.log(JSON.stringify({
    url, finalUrl, out: outPath, bytes, sha256,
    contentLength: res.headers['content-length'] || null,
    contentType: res.headers['content-type'] || null,
  }, null, 2));
}

async function head(url) {
  const { res, url: finalUrl } = await request(url, { method: 'GET', headers: { Range: 'bytes=0-0' } });
  res.resume();
  console.log(JSON.stringify({
    url, finalUrl, status: res.statusCode,
    contentLength: res.headers['content-range'] || res.headers['content-length'] || null,
    contentType: res.headers['content-type'] || null,
  }, null, 2));
}

async function api(p) {
  const url = p.startsWith('http') ? p : 'https://api.github.com' + p;
  const { res } = await request(url, { headers: { 'Accept': 'application/vnd.github+json' } });
  let body = '';
  res.setEncoding('utf8');
  for await (const chunk of res) body += chunk;
  console.log(`HTTP ${res.statusCode}`);
  try { console.log(JSON.stringify(JSON.parse(body), null, 2)); }
  catch { console.log(body); }
}

const [cmd, a, b] = process.argv.slice(2);
(async () => {
  if (cmd === 'get') { if (!a || !b) throw new Error('usage: get <url> <out>'); await download(a, b); }
  else if (cmd === 'head') { if (!a) throw new Error('usage: head <url>'); await head(a); }
  else if (cmd === 'api') { if (!a) throw new Error('usage: api <path>'); await api(a); }
  else { console.error('usage: node dl.js <get|head|api> ...'); process.exit(2); }
})().catch((e) => { console.error('ERROR: ' + e.message); process.exit(1); });
