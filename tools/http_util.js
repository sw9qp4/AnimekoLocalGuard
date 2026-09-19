// AnimekoLocalGuard dev tool: minimal HTTP(S) client that works on this machine.
//
// Why this exists:
//  - The Windows TLS stack is broken (schannel SEC_E_NO_CREDENTIALS), so PowerShell/curl
//    cannot do HTTPS here; Node can.
//  - api.bgm.tv and api.dandanplay.net are unreachable directly (connection timeout), so
//    requests must go through the local proxy via an HTTP CONNECT tunnel.
//  - undici's ProxyAgent is not resolvable from this script location, hence the manual
//    tunnel built on `net` + `tls`.
//
// Chunked responses MUST be de-chunked at the **byte** level: chunk sizes are byte counts
// while JavaScript string indices are UTF-16 code units, so mixing the two misaligns the
// parse as soon as the payload contains a multi-byte character.
//
// Usage: node tools/http_util.js <get|post> <url> [jsonBodyFile]
'use strict';
const net = require('net');
const tls = require('tls');

const PROXY = { host: '127.0.0.1', port: 7897 };
const UA = 'AnimekoLocalGuard-research/0.1 (local non-commercial use)';

function dechunk(buf) {
    const parts = [];
    let i = 0;
    while (i < buf.length) {
        const nl = buf.indexOf('\r\n', i);
        if (nl < 0) break;
        const sizeHex = buf.subarray(i, nl).toString('ascii').trim();
        const size = parseInt(sizeHex, 16);
        if (!Number.isFinite(size) || size < 0) break;
        if (size === 0) break;
        const start = nl + 2;
        parts.push(buf.subarray(start, start + size));
        i = start + size + 2;
    }
    return Buffer.concat(parts);
}

/**
 * Perform one HTTP(S) request through the proxy.
 * @returns {Promise<{ok:boolean,status?:number,head?:string,body?:string,json?:any,error?:string}>}
 */
function request(targetUrl, { method = 'GET', body = null, headers = {}, timeoutMs = 30000 } = {}) {
    return new Promise((resolve) => {
        let u;
        try {
            u = new URL(targetUrl);
        } catch (e) {
            return resolve({ ok: false, error: 'bad url: ' + e.message });
        }
        const port = u.port ? Number(u.port) : 443;
        const socket = net.connect(PROXY.port, PROXY.host);
        const done = (r) => { try { socket.destroy(); } catch (_) {} resolve(r); };
        socket.setTimeout(timeoutMs, () => done({ ok: false, error: 'proxy connect timeout' }));
        socket.on('error', (e) => done({ ok: false, error: 'proxy socket: ' + e.message }));
        socket.on('connect', () => {
            socket.write(
                `CONNECT ${u.hostname}:${port} HTTP/1.1\r\n` +
                `Host: ${u.hostname}:${port}\r\nProxy-Connection: keep-alive\r\n\r\n`,
            );
        });

        let phase = 'connect';
        let buf = '';
        socket.on('data', (chunk) => {
            if (phase !== 'connect') return;
            buf += chunk.toString('latin1');
            if (!buf.includes('\r\n\r\n')) return;
            const statusLine = buf.split('\r\n')[0];
            if (!/ 200/.test(statusLine)) return done({ ok: false, error: 'CONNECT rejected: ' + statusLine });

            phase = 'tls';
            socket.removeAllListeners('data');
            const t = tls.connect({ socket, servername: u.hostname }, () => {
                const hdrs = Object.assign({
                    Host: u.hostname,
                    'User-Agent': UA,
                    Accept: 'application/json',
                    Connection: 'close',
                }, headers);
                if (body != null) {
                    const payload = Buffer.isBuffer(body) ? body : Buffer.from(String(body), 'utf8');
                    hdrs['Content-Type'] = hdrs['Content-Type'] || 'application/json; charset=utf-8';
                    hdrs['Content-Length'] = payload.length;
                    t.write(
                        `${method} ${u.pathname}${u.search} HTTP/1.1\r\n` +
                        Object.entries(hdrs).map(([k, v]) => `${k}: ${v}`).join('\r\n') +
                        '\r\n\r\n',
                    );
                    t.write(payload);
                } else {
                    t.write(
                        `${method} ${u.pathname}${u.search} HTTP/1.1\r\n` +
                        Object.entries(hdrs).map(([k, v]) => `${k}: ${v}`).join('\r\n') +
                        '\r\n\r\n',
                    );
                }
            });
            t.setTimeout(timeoutMs, () => done({ ok: false, error: 'tls request timeout' }));
            t.on('error', (e) => done({ ok: false, error: 'tls: ' + e.message }));
            const chunks = [];
            t.on('data', (c) => chunks.push(c));
            t.on('end', () => {
                const raw = Buffer.concat(chunks);
                const sep = raw.indexOf('\r\n\r\n');
                const head = (sep >= 0 ? raw.subarray(0, sep) : raw).toString('utf8');
                const bodyBuf = sep >= 0 ? raw.subarray(sep + 4) : Buffer.alloc(0);
                const status = Number((head.split('\r\n')[0] || '').split(' ')[1] || 0);
                const text = /transfer-encoding:\s*chunked/i.test(head)
                    ? dechunk(bodyBuf).toString('utf8')
                    : bodyBuf.toString('utf8');
                let json = null;
                try { json = JSON.parse(text); } catch (_) {}
                done({ ok: status >= 200 && status < 300, status, head, body: text, json });
            });
        });
    });
}

/** GET returning parsed JSON, or throws with a readable message. */
async function getJson(url, opts) {
    const r = await request(url, opts);
    if (!r.ok) throw new Error(r.error || ('HTTP ' + r.status));
    if (!r.json) throw new Error('response is not JSON: ' + String(r.body).slice(0, 200));
    return r.json;
}

/** POST JSON returning parsed JSON, or throws with a readable message. */
async function postJson(url, obj, opts) {
    const r = await request(url, Object.assign({ method: 'POST', body: JSON.stringify(obj) }, opts));
    if (!r.ok) throw new Error(r.error || ('HTTP ' + r.status));
    if (!r.json) throw new Error('response is not JSON: ' + String(r.body).slice(0, 200));
    return r.json;
}

module.exports = { request, getJson, postJson, PROXY, UA };

if (require.main === module) {
    (async () => {
        const [cmd, url, bodyFile] = process.argv.slice(2);
        if (!url) { console.error('usage: node http_util.js <get|post> <url> [jsonBodyFile]'); process.exit(2); }
        const opts = {};
        if (cmd === 'post') {
            const fs = require('fs');
            opts.method = 'POST';
            opts.body = bodyFile ? fs.readFileSync(bodyFile, 'utf8') : '{}';
        }
        const r = await request(url, opts);
        console.log('status=' + (r.status || ('ERR ' + r.error)));
        console.log(String(r.body || '').slice(0, 2000));
    })();
}
