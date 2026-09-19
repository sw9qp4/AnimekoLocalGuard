// dev helper: list test class names and the case count per class, by parsing the test sources.
//
// Purpose: give exact, checkable numbers for the status documents instead of deriving them by
// arithmetic from a previously reported total. The standalone runner reports only a grand total,
// and `-Only <substr>` filters by class name, so a single pass over the sources is the cheapest
// way to see the real distribution.
//
// Usage: node tools/test_inventory.js
'use strict';
const fs = require('fs');
const path = require('path');

const DIR = path.join(__dirname, '..', 'danmaku', 'localguard', 'src', 'commonTest', 'kotlin',
    'me', 'him188', 'ani', 'danmaku', 'localguard');

function main() {
    const rows = [];
    let total = 0;
    for (const file of fs.readdirSync(DIR).filter((f) => f.endsWith('.kt'))) {
        const text = fs.readFileSync(path.join(DIR, file), 'utf8');
        if (!/^\s*class\s+\w+/m.test(text)) continue;
        // Count `@Test` annotations rather than `fun` declarations: some helpers are functions.
        const cases = (text.match(/^\s*@Test\b/gm) || []).length;
        if (cases === 0) continue;
        const classNames = [...text.matchAll(/^\s*class\s+(\w+)/gm)].map((m) => m[1]);
        rows.push({ file, classes: classNames, cases });
        total += cases;
    }
    rows.sort((a, b) => b.cases - a.cases);
    for (const r of rows) {
        console.log(String(r.cases).padStart(4) + '  ' + r.classes.join(', ') + '   (' + r.file + ')');
    }
    console.log('');
    console.log('files with tests : ' + rows.length);
    console.log('test classes     : ' + rows.reduce((n, r) => n + r.classes.length, 0));
    console.log('TOTAL @Test      : ' + total);
}

main();
