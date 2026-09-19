#!/usr/bin/env node
/**
 * Search jars for a given UTF-8 string in their entries (class files).
 * Used to locate which AGP/Gradle jar implements a specific check, so we can
 * read what property name it actually honours instead of guessing.
 *
 * Usage: node find_in_jars.js <dir> <searchString> [maxJars]
 */
'use strict';
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const [dir, needle, maxJarsArg] = process.argv.slice(2);
if (!dir || !needle) { console.error('usage: node find_in_jars.js <dir> <string> [maxJars]'); process.exit(2); }
const maxJars = Number(maxJarsArg || 500);

function* walk(d, depth = 0) {
  if (depth > 8) return;
  let entries;
  try { entries = fs.readdirSync(d, { withFileTypes: true }); } catch { return; }
  for (const e of entries) {
    const p = path.join(d, e.name);
    if (e.isDirectory()) yield* walk(p, depth + 1);
    else if (e.isFile() && e.name.endsWith('.jar')) yield p;
  }
}

const buf = Buffer.from(needle, 'utf8');
let scanned = 0;
const hits = [];

for (const jar of walk(dir)) {
  if (scanned >= maxJars) break;
  scanned++;
  let data;
  try { data = fs.readFileSync(jar); } catch { continue; }
  // Simple scan: look for the needle directly in the (compressed) file is useless,
  // so decompress each entry.
  try {
    const entries = readCentralDirectory(data);
    for (const e of entries) {
      if (!e.name.endsWith('.class')) continue;
      let raw;
      try { raw = readEntry(data, e); } catch { continue; }
      if (raw.includes(buf)) {
        hits.push({ jar: path.basename(jar), cls: e.name });
        break;
      }
    }
  } catch { /* not a zip we can parse; skip */ }
}

function readCentralDirectory(b) {
  let eocd = -1;
  for (let i = b.length - 22; i >= 0 && i >= b.length - 22 - 65535; i--) {
    if (b.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error('no eocd');
  const count = b.readUInt16LE(eocd + 10);
  const cdOff = b.readUInt32LE(eocd + 16);
  const out = [];
  let o = cdOff;
  for (let i = 0; i < count && o + 46 <= b.length; i++) {
    if (b.readUInt32LE(o) !== 0x02014b50) break;
    const method = b.readUInt16LE(o + 10);
    const compSize = b.readUInt32LE(o + 20);
    const nameLen = b.readUInt16LE(o + 28);
    const extraLen = b.readUInt16LE(o + 30);
    const commentLen = b.readUInt16LE(o + 32);
    const localOff = b.readUInt32LE(o + 42);
    out.push({ name: b.slice(o + 46, o + 46 + nameLen).toString('utf8'), method, compSize, localOff });
    o += 46 + nameLen + extraLen + commentLen;
  }
  return out;
}

function readEntry(b, entry) {
  const o = entry.localOff;
  const nameLen = b.readUInt16LE(o + 26);
  const extraLen = b.readUInt16LE(o + 28);
  const dataOff = o + 30 + nameLen + extraLen;
  const raw = b.slice(dataOff, dataOff + entry.compSize);
  if (entry.method === 0) return raw;
  if (entry.method === 8) return zlib.inflateRawSync(raw);
  throw new Error('method ' + entry.method);
}

console.log(`scanned jars: ${scanned}`);
console.log(JSON.stringify(hits, null, 2));
