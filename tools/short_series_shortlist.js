// AnimekoLocalGuard dev tool: pick SHORT TV series from the Bangumi candidate pool and
// measure how much danmaku the available source actually has for them.
//
// Why short series: the user's decision is to start with works that have few episodes, so
// that a complete per-episode timeline closure stays affordable.
//
// Why measure danmaku at all: a work the danmaku sources cannot serve gives the guard
// nothing to filter, so it is a poor demonstration target regardless of its rating.
// Two sources are reported:
//   * Animeko's own danmaku API  — public, no credentials (always measured).
//   * DanDanPlay aggregate       — needs appId/appSecret. If local.properties has them,
//     pass them here; otherwise the column is reported as UNMEASURED (not as zero).
//
// Usage: node tools/short_series_shortlist.js [minScore] [maxEpisodes] [topN]
'use strict';
const fs = require('fs');
const path = require('path');
const { getJson } = require('./http_util.js');

const dandanplay = require('./dandanplay.js');

const BGM = 'https://api.bgm.tv';
const DANMAKU = 'https://danmaku-cn.myani.org';

const minScore = Number(process.argv[2] || 8.0);
const maxEpisodes = Number(process.argv[3] || 14);
const topN = Number(process.argv[4] || 12);

/**
 * DanDanPlay episode ids are its own, not Bangumi's, so the mapping has to be resolved first.
 * A failure anywhere here yields `null` (UNMEASURED) rather than 0 — "could not measure" and
 * "measured zero" are different findings and must not be collapsed.
 */
let dandanplayEpisodeMap = null;

async function dandanplayEpisodeIds(subjectId, creds) {
    if (!creds) return null;
    if (!dandanplayEpisodeMap) dandanplayEpisodeMap = new Map();
    if (dandanplayEpisodeMap.has(subjectId)) return dandanplayEpisodeMap.get(subjectId);
    let value = null;
    try {
        value = await dandanplay.episodesByBgmtvSubjectId(subjectId, creds);
    } catch (_) {
        value = null;
    }
    dandanplayEpisodeMap.set(subjectId, value);
    return value;
}

async function fetchEpisodes(subjectId) {
    const out = [];
    let offset = 0;
    for (let guard = 0; guard < 10; guard++) {
        const page = await getJson(`${BGM}/v0/episodes?subject_id=${subjectId}&type=0&limit=100&offset=${offset}`);
        const list = page.data || [];
        out.push(...list);
        const total = page.total != null ? page.total : out.length;
        if (out.length >= total || list.length === 0) break;
        offset += list.length;
    }
    return out;
}

async function animekoDanmakuCount(episodeId) {
    try {
        const r = await getJson(`${DANMAKU}/v1/danmaku/${episodeId}`);
        return (r.danmakuList || []).length;
    } catch (_) {
        return -1;
    }
}

(async () => {
    const poolPath = path.join(__dirname, '..', 'docs', 'evidence', 'bangumi_candidates_6_8-9_9.json');
    const pool = JSON.parse(fs.readFileSync(poolPath, 'utf8'));
    const creds = dandanplay.readCredentials();
    console.log('DanDanPlay 凭据: ' + (creds ? '已配置' : '未配置（该列将为 UNMEASURED）'));

    const shorts = pool
        .filter((s) => s.eps != null && s.eps >= 1 && s.eps <= maxEpisodes && (s.score || 0) >= minScore)
        .sort((a, b) => (b.score || 0) - (a.score || 0))
        .slice(0, topN);

    console.log(`\n短片候选（评分 ≥ ${minScore}，集数 ≤ ${maxEpisodes}）：${shorts.length} 部\n`);
    const rows = [];
    for (const s of shorts) {
        process.stdout.write(`  ${String(s.score).padEnd(4)} ${String(s.id).padEnd(7)} ${s.eps}集  ${s.name_cn || s.name} ... `);
        let eps = [];
        try {
            eps = await fetchEpisodes(s.id);
        } catch (e) {
            console.log('章节失败');
            rows.push({ ...s, animeko: null, dandanplay: null, error: 'episodes failed' });
            continue;
        }
        const sample = eps.slice(0, Math.min(eps.length, 4));
        const ddpEpisodes = await dandanplayEpisodeIds(s.id, creds);
        const ddpByNumber = new Map();
        for (const d of ddpEpisodes || []) ddpByNumber.set(d.episodeNumber, d.episodeId);
        let animekoTotal = 0;
        let animekoWith = 0;
        let ddpTotal = 0;
        let ddpMeasuredCount = 0;
        for (const [index, ep] of sample.entries()) {
            const n = await animekoDanmakuCount(ep.id);
            if (n > 0) { animekoWith++; animekoTotal += n; }
            // Bangumi's episode `sort` is the in-series number DanDanPlay records as episodeNumber.
            const ddpId = ddpByNumber.get(Number(ep.sort != null ? ep.sort : ep.ep)) ?? (ddpEpisodes ? (ddpEpisodes[index] || {}).episodeId : null);
            if (ddpId != null) {
                const d = await dandanplay.commentCount(ddpId, creds);
                if (d != null) { ddpMeasuredCount++; ddpTotal += d; }
            }
        }
        const perEp = (animekoTotal / Math.max(1, sample.length)).toFixed(1);
        const ddpText = ddpMeasuredCount > 0
            ? `${(ddpTotal / ddpMeasuredCount).toFixed(1)}/集 (${ddpMeasuredCount} 集)`
            : 'UNMEASURED';
        console.log(`Animeko ${animekoWith}/${sample.length} 集, ${perEp}/集 | DanDanPlay ${ddpText}`);
        rows.push({
            id: s.id, name_cn: s.name_cn, name: s.name, score: s.score, eps: s.eps, date: s.date,
            episodeCount: eps.length, sampled: sample.length,
            animekoEpisodesWithDanmaku: animekoWith, animekoPerEpisode: Number(perEp),
            dandanplaySampled: ddpMeasuredCount,
            dandanplayPerEpisode: ddpMeasuredCount > 0 ? Number((ddpTotal / ddpMeasuredCount).toFixed(1)) : null,
        });
    }

    const out = path.join(__dirname, '..', 'docs', 'evidence', 'short_series_shortlist.json');
    fs.writeFileSync(out, JSON.stringify({
        criteria: { minScore, maxEpisodes, sampledEpisodesPerWork: 4, dandanplayCredentials: creds ? 'configured' : 'missing' },
        works: rows,
    }, null, 2), 'utf8');
    console.log('\n已写入: ' + out);
    console.log('注意：抽样每部前 4 集；DanDanPlay 未配置凭据时为 UNMEASURED，不是 0。');
})();
