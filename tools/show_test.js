// dev helper: print a Kotlin test function (and its KDoc) with UTF-8 preserved.
// PowerShell console mangles non-ASCII, so reading test bodies through it is unreliable.
// Usage: node tools/show_test.js <file> <substring> [...]
'use strict';
const fs = require('fs');
const [file, ...needles] = process.argv.slice(2);
if (!file) { console.error('usage: node tools/show_test.js <file> <substring> [...]'); process.exit(2); }
const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/);
for (const needle of needles) {
    const i = lines.findIndex((l) => l.includes('fun ') && l.includes(needle));
    if (i < 0) { console.log('NOT FOUND: ' + needle); continue; }
    console.log('=========== ' + needle + '  (line ' + (i + 1) + ') ===========');
    let start = i;
    while (start > 0 && !/^\s*(@Test|\/\*\*)/.test(lines[start - 1])) start--;
    if (start > 0 && /^\s*\*/.test(lines[start - 1])) {
        while (start > 0 && !/^\s*\/\*\*/.test(lines[start - 1])) start--;
    }
    let depth = 0, started = false;
    for (let j = start; j < lines.length; j++) {
        console.log(String(j + 1).padStart(4) + '| ' + lines[j]);
        depth += (lines[j].match(/\{/g) || []).length - (lines[j].match(/\}/g) || []).length;
        if (lines[j].includes('{')) started = true;
        if (started && depth <= 0) break;
    }
    console.log('');
}
