// dev helper: check imports and declaration counts in a Kotlin source file.
//
// Why: this project has twice produced confusing compiler errors because an edit left a
// duplicate declaration or merged two import lines into one. Counting occurrences is the cheap
// check that catches both before a five-minute build does.
//
// Usage: node tools/check_decls.js <file> <importSubstring>... -- <declName>...
'use strict';
const fs = require('fs');

const argv = process.argv.slice(2);
const file = argv.shift();
if (!file) { console.error('usage: node tools/check_decls.js <file> <import>... -- <decl>...'); process.exit(2); }
const sep = argv.indexOf('--');
const imports = sep < 0 ? argv : argv.slice(0, sep);
const decls = sep < 0 ? [] : argv.slice(sep + 1);

const text = fs.readFileSync(file, 'utf8');
const lines = text.split(/\r?\n/);
const importLines = lines.filter((l) => l.startsWith('import'));

console.log('=== imports ===');
for (const needle of imports) {
    const hit = importLines.find((l) => l.includes(needle));
    console.log((hit ? 'OK   ' : 'MISS ') + needle + (hit ? '' : '   <-- 需要补 import'));
}

console.log('=== merged import lines (两行粘成一行) ===');
const merged = lines.map((l, i) => [i + 1, l]).filter(([, l]) => /^import \S+import /.test(l));
console.log(merged.length === 0 ? 'none' : JSON.stringify(merged));

console.log('=== declaration counts (每个应为 1) ===');
let bad = 0;
for (const d of decls) {
    const c = lines.filter((l) => l.includes(d)).length;
    if (c !== 1) bad++;
    console.log(String(c).padStart(2) + '  ' + d + (c === 1 ? '' : '   <-- 异常'));
}
process.exit(bad === 0 && merged.length === 0 ? 0 : 1);
