// AnimekoLocalGuard dev tool: pack the audit documents into one paste-able file and a zip.
//
// Why a single file as well as a zip:
//   The reviewer is a chat model. A zip must be uploaded and unpacked, and the reviewer may only
//   be given text. One concatenated markdown file can be pasted or attached directly and is
//   self-contained, so the review does not depend on getting a file-handling step right.
//
// Usage: node tools/pack_audit.js
'use strict';
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const ROOT = path.join(__dirname, '..');
const AUDIT_DIR = path.join(ROOT, 'docs', 'audit');
const OUT_DIR = path.join(ROOT, 'artifacts', 'audit-package');

// Order matters: the index first explains how to read the rest, and the risks document is placed
// second because it is the one that should actually be reviewed closely.
const ORDER = [
    '00-INDEX-给审阅者.md',
    'D-GAPS-AND-RISKS.md',
    'A-FACTS.md',
    'B-TASKS.md',
    'C-DESIGN.md',
    'E-ROADMAP.md',
    'F-BUILD-AND-VERIFICATION.md',
];

function main() {
    if (!fs.existsSync(AUDIT_DIR)) {
        console.error('no docs/audit directory');
        process.exit(2);
    }
    fs.mkdirSync(OUT_DIR, { recursive: true });

    const present = fs.readdirSync(AUDIT_DIR).filter((f) => f.endsWith('.md'));
    const missing = ORDER.filter((f) => !present.includes(f));
    const extra = present.filter((f) => !ORDER.includes(f));
    if (missing.length) console.log('WARNING: expected file(s) missing: ' + missing.join(', '));
    if (extra.length) console.log('note: extra file(s) also included: ' + extra.join(', '));

    const files = [...ORDER.filter((f) => present.includes(f)), ...extra];

    // ---- 1. concatenated markdown -------------------------------------------------------------
    const parts = [];
    parts.push('# AnimekoLocalGuard —— 外部审阅材料（单一文件版）');
    parts.push('');
    parts.push('本文件由以下文档按顺序拼接而成。每份文档之间用水平分隔线分开，');
    parts.push('每份都有各自的一级标题，可单独阅读。');
    parts.push('');
    parts.push('> 阅读建议：**先读第 2 部分（缺陷与风险清单）**，那是本次审阅最该看的内容。');
    parts.push('');
    parts.push('目录：');
    files.forEach((f, i) => {
        const title = (fs.readFileSync(path.join(AUDIT_DIR, f), 'utf8').match(/^#\s+(.+)$/m) || [, f])[1];
        parts.push(`${i + 1}. \`${f}\` — ${title}`);
    });
    parts.push('');
    parts.push('---');
    parts.push('');

    let totalBytes = 0;
    for (const f of files) {
        const text = fs.readFileSync(path.join(AUDIT_DIR, f), 'utf8');
        totalBytes += Buffer.byteLength(text, 'utf8');
        parts.push(`<!-- ===== BEGIN ${f} ===== -->`);
        parts.push('');
        parts.push(text.trimEnd());
        parts.push('');
        parts.push(`<!-- ===== END ${f} ===== -->`);
        parts.push('');
        parts.push('---');
        parts.push('');
    }
    const combined = parts.join('\n');
    const combinedPath = path.join(OUT_DIR, 'AnimekoLocalGuard-审阅材料-单一文件.md');
    fs.writeFileSync(combinedPath, combined, 'utf8');
    console.log(`combined: ${path.relative(ROOT, combinedPath)}  (${files.length} docs, ${(Buffer.byteLength(combined, 'utf8') / 1024).toFixed(0)} KB)`);

    // ---- 2. copy the individual docs into the package ------------------------------------------
    for (const f of files) {
        fs.copyFileSync(path.join(AUDIT_DIR, f), path.join(OUT_DIR, f));
    }
    console.log(`copied ${files.length} individual document(s)`);

    // ---- 3. include the maintained state files so claims can be cross-checked ------------------
    for (const f of ['PROGRESS.md', 'NEXT.md', 'STATE.json', 'ANIMEKOLOCALGUARD.md']) {
        const src = path.join(ROOT, f);
        if (fs.existsSync(src)) {
            fs.copyFileSync(src, path.join(OUT_DIR, f));
            console.log(`included ${f}`);
        }
    }

    // ---- 4. zip --------------------------------------------------------------------------------
    const zipPath = path.join(ROOT, 'artifacts', 'AnimekoLocalGuard-审阅材料.zip');
    if (fs.existsSync(zipPath)) fs.unlinkSync(zipPath);
    // Use .NET compression through PowerShell rather than spawning zip/pwsh from Node: the sandbox
    // blocks Node from capturing a child's output, but this script is invoked from the shell anyway,
    // so the zip step is done by the caller. See the note printed below.
    console.log('');
    console.log('next: create the zip from the shell, e.g.');
    console.log(`  Compress-Archive -Path "${OUT_DIR}\\*" -DestinationPath "${zipPath}" -Force`);
}

main();
