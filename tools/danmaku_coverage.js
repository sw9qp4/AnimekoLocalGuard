// AnimekoLocalGuard dev tool: measure danmaku availability for candidate works.
//
// Why this exists: before committing to a set of works for G2, it is worth knowing whether
// the danmaku sources Animeko actually ships can serve them. A work with no danmaku gives the
// guard nothing to do, so it is a poor demonstration target no matter how highly rated.
//
// Sources probed (both are the ones Animeko really ships — see DanmakuProviderFactory):
//   * Animeko's own danmaku API: GET https://danmaku-cn.myani.org/v1/danmaku/{episodeId}
//     Public, no credentials. Episode ids are Bangumi episode ids.
//   * DanDanPlay: requires appId/appSecret, which this project does not have.
//     Verified: GET /api/v2/search/episodes returns errorCode 3 "应用不存在" without them.
//     So its aggregate feed (Bilibili/AcFun/Tucao/Baha) is NOT reachable here.
//
// Usage: node tools/danmaku_coverage.js [topN]
'use strict';
const fs = require('fs');
const path = require('path');
const { getJson } = require('./http_util.js');

const BGM = 'https://api.bgm.tv';
const DANMAKU = 'https://danmaku-cn.myani.org';

async function fetchEpisodes(subjectId, { limit = 200, offset = 0 } = {}) {
    const out = [];
    for (let guard = 0; guard < 20; guard++) {
        const page = await getJson(
            `${BGM}/v0/episodes?subject_id=${subjectId}&type=0&limit=${limit}&offset=${offset}`,
        );
        const list = page.data || [];
        out.push(...list);
        const total = page.total != null ? page.total : out.length;
        if (out.length >= total || list.length === 0) break;
        offset += list.length;
    }
    return out;
}

async function danmakuCount(episodeId) {
    try {
        const r = await getJson(`${DANMAKU}/v1/danmaku/${episodeId}`);
        const list = r.danmakuList || [];
        return list.length;
    } catch (e) {
        return -1; // unreachable / error
    }
}

(async () => {
    const topN = Number(process.argv[2] || 10);
    const candidatesPath = path.join(__dirname, '..', 'docs', 'evidence', 'bangumi_candidates.json');
    const all = JSON.parse(fs.readFileSync(candidatesPath, 'utf8'));
    // Candidates are already ordered by Bangumi rating (descending).
    const picks = all.filter((c) => c.eps != null && c.eps > 0 && c.eps <= 60).slice(0, topN);

    const report = [];
    for (const c of picks) {
        process.stdout.write(`${c.id} ${c.name_cn || c.name} ... `);
        let eps = [];
        try {
            eps = await fetchEpisodes(c.id);
        } catch (e) {
            console.log('章节拉取失败: ' + e.message);
            report.push({ ...c, episodes: 0, withDanmaku: 0, totalDanmaku: 0, error: e.message });
            continue;
        }
        let withDanmaku = 0;
        let totalDanmaku = 0;
        // Sample the first few episodes to keep the probe quick and polite.
        const sample = eps.slice(0, Math.min(eps.length, 6));
        for (const ep of sample) {
            const n = await danmakuCount(ep.id);
            if (n > 0) {
                withDanmaku++;
                totalDanmaku += n;
            }
        }
        console.log(`章节=${eps.length} 抽样=${sample.length} 有弹幕=${withDanmaku} 弹幕数=${totalDanmaku}`);
        report.push({
            id: c.id,
            name_cn: c.name_cn,
            name: c.name,
            score: c.score,
            eps: c.eps,
            date: c.date,
            episodeCount: eps.length,
            sampledEpisodes: sample.length,
            episodesWithDanmaku: withDanmaku,
            danmakuInSample: totalDanmaku,
        });
    }

    const out = path.join(__dirname, '..', 'docs', 'evidence', 'danmaku_coverage.json');
    fs.writeFileSync(out, JSON.stringify(report, null, 2), 'utf8');
    console.log('\n已写入: ' + out);
    console.log('\n注意：抽样只取前 6 集；"有弹幕"指该集弹幕数 > 0。');
    console.log('DanDanPlay 因缺少 appId/appSecret 完全不可用（实测 errorCode 3「应用不存在」）。');
})();
