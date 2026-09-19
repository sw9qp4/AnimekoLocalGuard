#!/usr/bin/env node
/**
 * Diagnose a slow/stalled download: report time-to-first-byte and progress
 * over a few seconds, so we can tell "slow" from "hung".
 *
 * Usage: node dl_probe.js <url> [seconds]
 */
'use strict';
const https = require('https');

const url = process.argv[2];
const seconds = Number(process.argv[3] || 15);
if (!url) { console.error('usage: node dl_probe.js <url> [seconds]'); process.exit(2); }

const t0 = Date.now();
let bytes = 0;
let firstByteAt = null;
let lastReport = 0;

function follow(u, depth) {
  if (depth > 6) { console.log('too many redirects'); process.exit(1); }
  const req = https.get(u, { headers: { 'User-Agent': 'AnimekoLocalGuard-dlprobe/0.1', Accept: '*/*' } }, (res) => {
    if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
      res.resume();
      console.log(`redirect ${res.statusCode} -> ${String(res.headers.location).slice(0, 90)}...`);
      return follow(new URL(res.headers.location, u).toString(), depth + 1);
    }
    console.log(`status=${res.statusCode} content-length=${res.headers['content-length'] || res.headers['content-range'] || '?'}`);
    res.on('data', (c) => {
      if (firstByteAt === null) {
        firstByteAt = Date.now();
        console.log(`first byte after ${firstByteAt - t0} ms`);
      }
      bytes += c.length;
    });
    setTimeout(() => {
      const elapsed = (Date.now() - t0) / 1000;
      console.log(`after ${elapsed.toFixed(1)}s: ${bytes} bytes (${(bytes / 1024).toFixed(0)} KB)`);
      if (bytes === 0) console.log('VERDICT: no data received (hung or blocked)');
      else console.log(`VERDICT: ${(bytes / 1024 / elapsed).toFixed(1)} KB/s`);
      res.destroy();
      process.exit(0);
    }, seconds * 1000);
  });
  req.on('error', (e) => { console.log('ERROR: ' + e.message); process.exit(1); });
  req.setTimeout(20000, () => { req.destroy(new Error('socket timeout 20s')); });
}

follow(url, 0);
