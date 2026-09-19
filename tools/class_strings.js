#!/usr/bin/env node
/**
 * Hexdump the constant pool region of a class file to reveal string constants,
 * without needing a full Java decompiler. Used to inspect what property names a
 * library actually reads.
 *
 * Usage: node class_strings.js <classFile>
 */
'use strict';
const fs = require('fs');

const file = process.argv[2];
if (!file) { console.error('usage: node class_strings.js <classFile>'); process.exit(2); }
const b = fs.readFileSync(file);

// Walk the constant pool and print CONSTANT_Utf8 entries.
let p = 8; // skip magic(4) + minor(2) + major(2)
const cpCount = b.readUInt16BE(p); p += 2;
const utf8s = [];
for (let i = 1; i < cpCount; i++) {
  const tag = b[p];
  if (tag === 1) { // Utf8
    const len = b.readUInt16BE(p + 1);
    utf8s.push(b.slice(p + 3, p + 3 + len).toString('utf8'));
    p += 3 + len;
  } else if (tag === 7 || tag === 8 || tag === 16 || tag === 19 || tag === 20) p += 3;
  else if (tag === 15) p += 4;
  else if (tag === 3 || tag === 4 || tag === 9 || tag === 10 || tag === 11 || tag === 12 || tag === 17 || tag === 18) p += 5;
  else if (tag === 5 || tag === 6) { p += 9; i++; }
  else { console.error('unknown tag ' + tag + ' at ' + p); break; }
}

const interesting = utf8s.filter((s) => /android\.|override|path|Path|Option|option/.test(s));
console.log('total utf8 constants: ' + utf8s.length);
console.log('--- matching ---');
for (const s of interesting) console.log(JSON.stringify(s));
