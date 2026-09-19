// AnimekoLocalGuard dev tool: summarise Gradle test-result XML into exact counts.
//
// Why not PowerShell: the test names contain non-ASCII characters that PowerShell 5.1
// mis-decodes, and its XML cast then fails. Counting with a plain text scan avoids both.
//
// Usage: node tools/test_result_summary.js
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');

function scan(dir) {
    if (!fs.existsSync(dir)) return null;
    let tests = 0, failures = 0, errors = 0, skipped = 0, files = 0;
    const classes = [];
    for (const f of fs.readdirSync(dir)) {
        if (!f.endsWith('.xml')) continue;
        const text = fs.readFileSync(path.join(dir, f), 'utf8');
        const m = text.match(/<testsuite\b[^>]*>/);
        if (!m) continue;
        const num = (key) => {
            const r = m[0].match(new RegExp(key + '="(\\d+)"'));
            return r ? Number(r[1]) : 0;
        };
        tests += num('tests');
        failures += num('failures');
        errors += num('errors');
        skipped += num('skipped');
        files++;
        const name = m[0].match(/name="([^"]+)"/);
        if (name) classes.push(name[1]);
    }
    return { dir: path.relative(ROOT, dir), files, tests, failures, errors, skipped, classes };
}

const targets = [
    'danmaku/localguard/build/test-results/testAndroidHostTest',
    'app/shared/build/test-results/testAndroidHostTest',
];

let totalTests = 0, totalFail = 0;
for (const rel of targets) {
    const r = scan(path.join(ROOT, rel));
    if (!r) {
        console.log(rel + ': (no results)');
        continue;
    }
    totalTests += r.tests;
    totalFail += r.failures + r.errors;
    console.log(`${r.dir}: files=${r.files} tests=${r.tests} failures=${r.failures} errors=${r.errors} skipped=${r.skipped}`);
    for (const c of r.classes.sort()) {
        const short = c.split('.').pop();
        if (/LocalGuard|Guard|Semantics|Subtitle|Alignment|Knowledge|Pipeline|Tier|EpisodeOrder|Fact|DanmakuGuard|TextStory|Duplicate/.test(short)) {
            console.log('    ' + short);
        }
    }
}
console.log(`TOTAL tests=${totalTests} failures=${totalFail}`);
