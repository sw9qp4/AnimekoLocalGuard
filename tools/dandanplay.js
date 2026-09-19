// AnimekoLocalGuard dev tool: credential-aware DanDanPlay access for measurement.
//
// Why this exists: DanDanPlay is the only one of Animeko's two shipped danmaku providers that
// needs credentials, and it is the one that carries the aggregate Bilibili/AcFun/Tucao/Baha
// feed. Until its coverage is measured we cannot say whether a candidate work is a good
// demonstration target. Without credentials the API answers errorCode 3 "应用不存在", so the
// measurement has to wait for the user's app id/secret — and when they arrive, the signing
// must be byte-exact or the requests silently fail.
//
// Signing rule (mirrors DandanplayClient.generateSignature in this repo):
//   X-AppId     = appId
//   X-Timestamp = unix seconds
//   X-Signature = base64( sha256( appId + timestamp + encodedPath + appSecret ) )
// The signed path is the URL's encoded path WITHOUT the query string.
'use strict';
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const { request } = require('./http_util.js');

const DANDANPLAY_HOST = 'api.dandanplay.net';

/** Read `ani.dandanplay.app.id` / `.secret` from local.properties. Returns null when unset. */
function readCredentials(projectRoot) {
    const root = projectRoot || path.join(__dirname, '..');
    const file = path.join(root, 'local.properties');
    if (!fs.existsSync(file)) return null;
    const text = fs.readFileSync(file, 'utf8');
    const pick = (key) => {
        const m = text.match(new RegExp('^\\s*' + key.replace(/\./g, '\\.') + '\\s*=\\s*(.*)$', 'm'));
        return m ? m[1].trim() : '';
    };
    const appId = pick('ani.dandanplay.app.id');
    const appSecret = pick('ani.dandanplay.app.secret');
    return appId && appSecret ? { appId, appSecret } : null;
}

/** base64(sha256(appId + timestamp + path + appSecret)); path excludes the query string. */
function generateSignature(appId, timestamp, encodedPath, appSecret) {
    const data = String(appId) + String(timestamp) + String(encodedPath) + String(appSecret);
    return crypto.createHash('sha256').update(data, 'utf8').digest('base64');
}

/**
 * One authenticated DanDanPlay GET. Returns the parsed body, or throws with the message the
 * API reported so that a credential problem is never mistaken for "this work has no danmaku".
 */
async function getSigned(apiPathWithQuery, creds, { timeoutMs = 60000 } = {}) {
    if (!creds) throw new Error('DanDanPlay credentials are not configured (local.properties)');
    const url = new URL('https://' + DANDANPLAY_HOST + apiPathWithQuery);
    const timestamp = Math.floor(Date.now() / 1000);
    const signature = generateSignature(creds.appId, timestamp, url.pathname, creds.appSecret);
    const r = await request(url.toString(), {
        headers: {
            'X-AppId': creds.appId,
            'X-Timestamp': String(timestamp),
            'X-Signature': signature,
        },
        timeoutMs,
    });
    let body = r.json;
    if (!body) {
        try { body = JSON.parse(r.body); } catch (_) {}
    }
    if (!body) throw new Error('DanDanPlay response is not JSON (status ' + (r.status || r.error) + ')');
    if (body.success === false || (body.errorCode != null && body.errorCode !== 0)) {
        throw new Error(`DanDanPlay errorCode ${body.errorCode}: ${body.errorMessage || ''}`);
    }
    return body;
}

/** DanDanPlay episode ids for a Bangumi subject id (via the bgmtv mapping endpoint). */
async function episodesByBgmtvSubjectId(subjectId, creds) {
    const body = await getSigned(`/api/v2/bangumi/bgmtv/${subjectId}`, creds);
    const episodes = (body.bangumi && body.bangumi.episodes) || [];
    return episodes.map((e) => ({ episodeId: e.episodeId, episodeTitle: e.episodeTitle, episodeNumber: e.episodeNumber }));
}

/** Comment count for one DanDanPlay episode id. `null` means "could not measure". */
async function commentCount(episodeId, creds) {
    try {
        const body = await getSigned(`/api/v2/comment/${episodeId}?chConvert=0&withRelated=true`, creds);
        return (body.comments || []).length;
    } catch (e) {
        return null;
    }
}

module.exports = { readCredentials, generateSignature, getSigned, episodesByBgmtvSubjectId, commentCount, DANDANPLAY_HOST };

if (require.main === module) {
    (async () => {
        const creds = readCredentials();
        if (!creds) {
            console.log('local.properties 中没有 ani.dandanplay.app.id / ani.dandanplay.app.secret。');
            console.log('凭据未配置 —— 这不是"没有弹幕"，而是"未测量"。');
            process.exit(1);
        }
        const subjectId = process.argv[2];
        console.log('凭据已读取 (appId 长度 ' + creds.appId.length + ')');
        // Self-test of the signature shape against a known path, independent of the network.
        console.log('签名样例 (ts=0, path=/api/v2/comment/1): ' +
            generateSignature(creds.appId, 0, '/api/v2/comment/1', creds.appSecret).slice(0, 16) + '...');
        if (!subjectId) return;
        const eps = await episodesByBgmtvSubjectId(Number(subjectId), creds);
        console.log(`Bangumi ${subjectId} → DanDanPlay 章节 ${eps.length} 个`);
        for (const ep of eps.slice(0, 3)) {
            console.log(`  ep${ep.episodeNumber} id=${ep.episodeId} 弹幕=${await commentCount(ep.episodeId, creds)}`);
        }
    })().catch((e) => { console.error('失败: ' + e.message); process.exit(1); });
}
