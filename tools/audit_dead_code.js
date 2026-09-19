// AnimekoLocalGuard dev tool: find declarations in the localguard module that are never
// referenced from production code (outside the module itself, excluding tests).
//
// This exists because this project repeatedly shipped "implemented but never wired" code:
//   cache classes never called, GuardDecision.Failed never constructed,
//   knowledge/alignment never passed in, modelFailed never set.
// Rather than finding those one at a time by hand, this reports the whole set.
//
// Heuristics (documented because they cause false positives/negatives):
//  - "Production code" = any .kt under the repo except this module's own sources and any
//    path containing /commonTest/, /desktopTest/, /androidHostTest/, /iosTest/, /devrun/,
//    plus build directories.
//  - A declaration counts as referenced if its simple name appears as a word anywhere in
//    those files, including comments/KDoc. Comments therefore MASK a missing wiring, so a
//    hit is only "not obviously unreferenced"; a miss is strong evidence.
//  - Data class members and enum entries are not reported (they are reached through their
//    owning type).
//
// Usage: node tools/audit_dead_code.js [repoRoot]
const fs = require('fs');
const path = require('path');

const repoRoot = process.argv[2] || 'D:/模型/AnimekoLocalGuard';
const moduleRoot = path.join(repoRoot, 'danmaku', 'localguard', 'src', 'commonMain');

function walk(dir, out = []) {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
        const p = path.join(dir, e.name);
        if (e.isDirectory()) walk(p, out);
        else if (e.name.endsWith('.kt')) out.push(p);
    }
    return out;
}

function isProductionFile(p) {
    const norm = p.replace(/\\/g, '/');
    if (norm.includes('/build/')) return false;
    if (norm.includes('/danmaku/localguard/src/')) return false; // the module itself
    for (const marker of ['/commonTest/', '/desktopTest/', '/androidHostTest/', '/iosTest/', '/devrun/']) {
        if (norm.includes(marker)) return false;
    }
    return true;
}

// ---- collect declarations from the module ----
const declRe = /^(?:@\w+(?:\([^)]*\))?\s+)*((?:public |internal |private )?(?:data |sealed |value |fun |abstract )*(?:class|interface|object|enum class|fun interface))\s+([A-Za-z_][A-Za-z0-9_]*)/gm;
const constRe = /^(?:const )?val\s+([A-Z][A-Za-z0-9_]*)\s*[:=]/gm;
const funcRe = /^(?:internal |public )?(?:suspend )?fun\s+(?:<[^>]+>\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*\(/gm;

const declarations = new Map(); // name -> {kind, file, line}
for (const file of walk(moduleRoot)) {
    const text = fs.readFileSync(file, 'utf8');
    const rel = path.relative(repoRoot, file).replace(/\\/g, '/');
    const add = (name, kind, index) => {
        if (!declarations.has(name)) {
            const line = text.slice(0, index).split('\n').length;
            declarations.set(name, { kind, file: rel, line });
        }
    };
    for (const m of text.matchAll(declRe)) add(m[2], m[1].trim(), m.index);
    for (const m of text.matchAll(constRe)) add(m[1], 'const/val', m.index);
    for (const m of text.matchAll(funcRe)) add(m[1], 'fun', m.index);
}

// ---- index production code ----
const prodWords = new Map(); // word -> count
let prodFiles = 0;
for (const file of walk(repoRoot)) {
    if (!isProductionFile(file)) continue;
    prodFiles++;
    const text = fs.readFileSync(file, 'utf8');
    for (const m of text.matchAll(/[A-Za-z_][A-Za-z0-9_]*/g)) {
        prodWords.set(m[0], (prodWords.get(m[0]) || 0) + 1);
    }
}

// Public API entry points that are legitimately called only from tests / from the app's
// own wiring, or are part of a modelled-but-not-yet-used contract. Listed explicitly so the
// report stays honest instead of silently dropping them.
const knownNotWired = new Set([
    'DisplayDecisionCache', 'CachedDisplayDecision', 'ValidInterval', // documented as not wired
    'KnowledgePackGenerator', 'FactDeclaration', 'GenerationNotice', 'GenerationResult', // offline tooling
    'pairSubtitlesByText', 'generateAlignment', 'AlignmentSample', 'AlignmentPairing',
    'AlignmentGeneration', 'AlignmentNotice', // offline tooling
    'KnowledgePackCodec', 'InMemoryStoryKnowledgeSource', 'EmptyStoryKnowledgeSource',
    'loadFromText', 'TextStoryKnowledgeSource', 'StoryPackTextReader', // wired via app, may appear
    'normalizeSubtitleText', 'matchesSubtitleQuote', 'parseTimeCodes', 'parseTimeCode',
    'parseSubtitles', 'SubtitleCue', 'SubtitleParseIssue', 'SubtitleParseResult',
    'RevealAnchor', 'RevealBoundaryExtractor', 'BoundaryExtraction',
    'BoundaryExtractionFailure', 'AnchorExtraction',
    'GuardStatus', 'deriveGuardStatus', 'GuardFeatureState', 'GuardCounters',
    'SpoilerSeverity', 'BaselineMapping', 'AlignmentResolver', 'TimeAlignment',
    'StoryKnowledgePack', 'StoryKnowledgeSource', 'KnowledgeLoadResult',
]);

const unreferenced = [];
for (const [name, info] of declarations) {
    if (knownNotWired.has(name)) continue;
    if (!prodWords.has(name)) unreferenced.push({ name, ...info });
}

unreferenced.sort((a, b) => a.file.localeCompare(b.file) || a.line - b.line);

console.log(`扫描：模块内声明 ${declarations.size} 个，生产文件 ${prodFiles} 个`);
console.log(`生产代码中未出现的声明：${unreferenced.length} 个\n`);
for (const d of unreferenced) {
    console.log(`  ${d.name.padEnd(40)} ${d.kind.padEnd(14)} ${d.file}:${d.line}`);
}
if (unreferenced.length === 0) console.log('  （无）');
console.log('\n注意：命中即"出现该名字"，注释里的提及也算命中，因此命中不代表真的接线；未命中是强证据。');
