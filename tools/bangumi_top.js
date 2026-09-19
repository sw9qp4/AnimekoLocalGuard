// AnimekoLocalGuard dev tool: query Bangumi's public API for candidate works.
//
// Why this exists: G2 needs a short list of works to build reveal-timeline packs for.
// The metadata (id / title / air date / episode count / rating) is public and contains no
// spoilers, so it can be fetched automatically. The *timed subtitle* material needed to
// derive reveal times is NOT available from this API and must come from the user.
//
// This machine cannot reach the open internet directly (api.bgm.tv times out), so requests
// go through the local proxy via an HTTP CONNECT tunnel. undici is not resolvable from this
// script location, hence the manual tunnel with `net` + `tls`.
//
// Usage: node tools/bangumi_top.js [ratingMax] [ratingMin] [limit]
//   e.g. node tools/bangumi_top.js 9.5 7.0 40
const fs = require('fs');
const path = require('path');
const net = require('net');
const tls = require('tls');

const PROXY = { host: '127.0.0.1', port: 7897 };
const UA = 'AnimekoLocalGuard-research/0.1 (local non-commercial use)';

const ratingMax = Number(process.argv[2] || 9.5);
const ratingMin = Number(process.argv[3] || 7.0);
const limit = Number(process.argv[4] || 40);

/** GET through the local proxy using an HTTP CONNECT tunnel. */
function proxiedGet(targetUrl, { timeoutMs = 30000 } = {}) {
    return new Promise((resolve) => {
        const u = new URL(targetUrl);
        const port = u.port ? Number(u.port) : 443;
        const connectReq =
            `CONNECT ${u.hostname}:${port} HTTP/1.1\r\n` +
            `Host: ${u.hostname}:${port}\r\n` +
            `Proxy-Connection: keep-alive\r\n\r\n`;

        const socket = net.connect(PROXY.port, PROXY.host);
        const done = (r) => { try { socket.destroy(); } catch (_) {} resolve(r); };
        socket.setTimeout(timeoutMs, () => done({ ok: false, error: 'proxy connect timeout' }));
        socket.on('error', (e) => done({ ok: false, error: 'proxy socket: ' + e.message }));

        let phase = 'connect';
        let buf = '';
        socket.on('data', (chunk) => {
            if (phase !== 'connect') return;
            buf += chunk.toString('latin1');
            if (!buf.includes('\r\n\r\n')) return;
            const statusLine = buf.split('\r\n')[0];
            if (!/ 200/.test(statusLine)) {
                return done({ ok: false, error: 'CONNECT rejected: ' + statusLine });
            }
            // Tunnel established → upgrade to TLS over the same socket.
            phase = 'tls';
            socket.removeAllListeners('data');
            const tlsSocket = tls.connect({ socket, servername: u.hostname }, () => {
                const req =
                    `GET ${u.pathname}${u.search} HTTP/1.1\r\n` +
                    `Host: ${u.hostname}\r\n` +
                    `User-Agent: ${UA}\r\n` +
                    `Accept: application/json\r\n` +
                    `Connection: close\r\n\r\n`;
                tlsSocket.write(req);
            });
            tlsSocket.setTimeout(timeoutMs, () => done({ ok: false, error: 'tls request timeout' }));
            tlsSocket.on('error', (e) => done({ ok: false, error: 'tls: ' + e.message }));
            const chunks = [];
            tlsSocket.on('data', (c) => chunks.push(c));
            tlsSocket.on('end', () => {
                const rawBuf = Buffer.concat(chunks);
                // Split headers/body at the first CRLFCRLF **byte** offset.
                const sep = rawBuf.indexOf('\r\n\r\n');
                const headBuf = sep >= 0 ? rawBuf.subarray(0, sep) : rawBuf;
                const bodyBuf = sep >= 0 ? rawBuf.subarray(sep + 4) : Buffer.alloc(0);
                const head = headBuf.toString('utf8');
                const status = Number((head.split('\r\n')[0] || '').split(' ')[1] || 0);
                // De-chunking MUST happen on bytes: chunk sizes are byte counts, while
                // JavaScript string indices are UTF-16 code units. Mixing the two misaligns
                // as soon as the payload contains a multi-byte character.
                const body = /transfer-encoding:\s*chunked/i.test(head)
                    ? dechunk(bodyBuf)
                    : bodyBuf.toString('utf8');
                done({ ok: status >= 200 && status < 300, status, head, body });
            });
        });

        socket.on('connect', () => socket.write(connectReq));
    });
}

/** Decode an HTTP/1.1 chunked body at the byte level. Returns text. */
function dechunk(buf) {
    const parts = [];
    let i = 0;
    while (i < buf.length) {
        const nl = buf.indexOf('\r\n', i);
        if (nl < 0) break;
        const sizeHex = buf.subarray(i, nl).toString('ascii').trim();
        const size = parseInt(sizeHex, 16);
        if (!Number.isFinite(size) || size < 0) break; // not chunked after all
        if (size === 0) break; // terminating chunk
        const start = nl + 2;
        parts.push(buf.subarray(start, start + size));
        i = start + size + 2; // skip payload + trailing CRLF
    }
    return Buffer.concat(parts).toString('utf8');
}

(async () => {
    // NOTE: api.bgm.tv ignores `rating_min` / `rating_max` (measured: a request with
    // rating_max=8.0 still returned a 9.2 work), and `limit` must be <= 100. So the pool is
    // fetched in pages and the band is applied **client-side**.
    const pageSize = 100;
    const pages = Math.max(1, Math.ceil(limit / pageSize));
    const raw = [];
    for (let page = 0; page < pages; page++) {
        const url = `https://api.bgm.tv/v0/subjects?type=2&cat=1&sort=rank` +
            `&limit=${pageSize}&offset=${page * pageSize}`;
        const res = await proxiedGet(url);
        if (!res.ok) {
            console.error('请求失败: ' + (res.error || ('HTTP ' + res.status)));
            if (res.body) console.error(res.body.slice(0, 400));
            process.exit(2);
        }
        let data;
        try {
            data = JSON.parse(res.body);
        } catch (e) {
            console.error('响应不是 JSON: ' + e.message);
            console.error('长度=' + res.body.length);
            console.error('前 40 字符=' + JSON.stringify(res.body.slice(0, 40)));
            process.exit(2);
        }
        const pageItems = data.data || data;
        if (!Array.isArray(pageItems) || pageItems.length === 0) break;
        raw.push(...pageItems);
    }
    if (raw.length === 0) {
        console.error('没有拿到任何条目');
        process.exit(2);
    }
    const list = raw
        .filter((s) => {
            const sc = s.rating && s.rating.score;
            return sc != null && sc >= ratingMin && sc <= ratingMax;
        })
        .slice(0, limit);
    console.log(`\n池子 ${raw.length} 条，客户端筛选后 ${list.length} 条` +
        `（type=2 动画, cat=1 TV, 评分 ${ratingMin}~${ratingMax}）\n`);
    console.log('rank  score    id      集数  首播        标题');
    for (const s of list) {
        const score = s.rating && s.rating.score != null ? s.rating.score.toFixed(1) : '  -  ';
        const eps = s.eps != null ? String(s.eps).padStart(4) : '   ?';
        const date = s.date || '          ';
        console.log(
            String(s.rank == null ? '-' : s.rank).padStart(4) + '  ' +
            String(score).padStart(5) + '  ' +
            String(s.id).padStart(7) + '  ' + eps + '  ' + date + '  ' +
            (s.name_cn || s.name),
        );
    }
    // 文件名带上评分区间：同一目录下多次不同区间的查询不应互相覆盖。
    const tag = `${String(ratingMin).replace('.', '_')}-${String(ratingMax).replace('.', '_')}`;
    const out = path.join(__dirname, '..', 'docs', 'evidence', `bangumi_candidates_${tag}.json`);
    fs.mkdirSync(path.dirname(out), { recursive: true });
    fs.writeFileSync(out, JSON.stringify(list.map((s) => ({
        id: s.id,
        rank: s.rank,
        score: s.rating && s.rating.score,
        rankTotal: s.rating && s.rating.total,
        name: s.name,
        name_cn: s.name_cn,
        date: s.date,
        eps: s.eps,
        platform: s.platform,
    })), null, 2), 'utf8');
    console.log('\n已写入: ' + out);
})();
