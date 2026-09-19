#!/usr/bin/env node
/**
 * Probe an HTTPS URL through an HTTP CONNECT proxy.
 * Used to confirm the local proxy can reach a host that direct connections cannot.
 *
 * Usage: node proxy_probe.js <host> <path> [proxyPort] [timeoutSec]
 */
'use strict';
const http = require('http');
const tls = require('tls');

const host = process.argv[2];
const reqPath = process.argv[3] || '/';
const proxyPort = Number(process.argv[4] || 7897);
const timeoutSec = Number(process.argv[5] || 15);
if (!host) { console.error('usage: node proxy_probe.js <host> <path> [proxyPort] [timeoutSec]'); process.exit(2); }

const t0 = Date.now();
const connectReq = http.request({
  host: '127.0.0.1',
  port: proxyPort,
  method: 'CONNECT',
  path: `${host}:443`,
  timeout: timeoutSec * 1000,
});

connectReq.on('connect', (res, socket) => {
  if (res.statusCode !== 200) {
    console.log('CONNECT failed status=' + res.statusCode);
    process.exit(1);
  }
  console.log(`CONNECT tunnel established in ${Date.now() - t0} ms`);
  const tlsSocket = tls.connect({ socket, servername: host }, () => {
    tlsSocket.write(
      `GET ${reqPath} HTTP/1.1\r\nHost: ${host}\r\nUser-Agent: probe\r\nConnection: close\r\n\r\n`
    );
  });
  let buf = '';
  tlsSocket.on('data', (d) => { buf += d.toString('utf8'); });
  tlsSocket.on('end', () => {
    const statusLine = buf.split('\r\n')[0];
    console.log(`response: ${statusLine}  (total ${Date.now() - t0} ms, ${buf.length} bytes)`);
    process.exit(0);
  });
  tlsSocket.on('error', (e) => { console.log('TLS error: ' + e.message); process.exit(1); });
});

connectReq.on('error', (e) => { console.log('proxy error: ' + e.message); process.exit(1); });
connectReq.on('timeout', () => { connectReq.destroy(); console.log('proxy CONNECT timeout'); process.exit(1); });
connectReq.end();
