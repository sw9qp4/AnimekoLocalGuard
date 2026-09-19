// AnimekoLocalGuard dev tool: prove the localguard user-facing strings are really inside the
// packaged APK, by decoding resources.arsc directly.
//
// Why not `aapt2 dump strings`: that command lists the **filename** string pool
// (res/layout/..., res/anim/...), not the values in the resource table's value pool, so it can
// never find a string resource by its text. Grepping it produces a false negative.
//
// The check matters because "the build succeeded" does not prove the new settings UI text was
// packaged; a resource merge problem would silently ship an app whose new group has no labels.
//
// Usage: node tools/apk_find_strings.js <apk> <substring> [substring...]
'use strict';
const fs = require('fs');
const zlib = require('zlib');

/** Read the ZIP central directory and return {name, method, lho, csize, usize} for each entry. */
function readCentralDirectory(buf) {
    let eocd = -1;
    for (let i = buf.length - 22; i >= 0 && i > buf.length - 70000; i--) {
        if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
    }
    if (eocd < 0) throw new Error('EOCD not found');
    let count = buf.readUInt16LE(eocd + 10);
    let cdOffset = buf.readUInt32LE(eocd + 16);
    let cdSize = buf.readUInt32LE(eocd + 12);

    // ZIP64: the real values live in the ZIP64 EOCD record, located via the locator.
    if (cdOffset === 0xffffffff || count === 0xffff) {
        const loc = eocd - 20;
        if (buf.readUInt32LE(loc) === 0x07064b50) {
            const z64 = Number(buf.readBigUInt64LE(loc + 8));
            if (buf.readUInt32LE(z64) === 0x06064b50) {
                count = Number(buf.readBigUInt64LE(z64 + 32));
                cdSize = Number(buf.readBigUInt64LE(z64 + 40));
                cdOffset = Number(buf.readBigUInt64LE(z64 + 48));
            }
        }
    }

    const entries = [];
    let p = cdOffset;
    for (let i = 0; i < count && p + 46 <= buf.length; i++) {
        if (buf.readUInt32LE(p) !== 0x02014b50) break;
        const method = buf.readUInt16LE(p + 10);
        let csize = buf.readUInt32LE(p + 20);
        let usize = buf.readUInt32LE(p + 24);
        const nlen = buf.readUInt16LE(p + 28);
        const elen = buf.readUInt16LE(p + 30);
        const clen = buf.readUInt16LE(p + 32);
        let lho = buf.readUInt32LE(p + 42);
        const name = buf.subarray(p + 46, p + 46 + nlen).toString('utf8');

        const extra = buf.subarray(p + 46 + nlen, p + 46 + nlen + elen);
        let q = 0;
        while (q + 4 <= extra.length) {
            const id = extra.readUInt16LE(q);
            const sz = extra.readUInt16LE(q + 2);
            if (id === 0x0001) {
                let r = q + 4;
                if (usize === 0xffffffff) { usize = Number(extra.readBigUInt64LE(r)); r += 8; }
                if (csize === 0xffffffff) { csize = Number(extra.readBigUInt64LE(r)); r += 8; }
                if (lho === 0xffffffff) { lho = Number(extra.readBigUInt64LE(r)); r += 8; }
            }
            q += 4 + sz;
        }
        entries.push({ name, method, csize, usize, lho });
        p += 46 + nlen + elen + clen;
    }
    return entries;
}

/** Extract one entry's bytes, inflating it when stored with deflate. */
function extractEntry(buf, entry) {
    if (entry.method !== 0 && entry.method !== 8) {
        throw new Error('unsupported compression method ' + entry.method + ' for ' + entry.name);
    }
    // Locate the local header to learn its actual name/extra lengths (they may differ from CD).
    let p = entry.lho;
    if (buf.readUInt32LE(p) !== 0x04034b50) throw new Error('bad local header for ' + entry.name);
    const nlen = buf.readUInt16LE(p + 26);
    const elen = buf.readUInt16LE(p + 28);
    const dataStart = p + 30 + nlen + elen;
    const raw = buf.subarray(dataStart, dataStart + entry.csize);
    const data = entry.method === 0 ? raw : zlib.inflateRawSync(raw);
    if (data.length !== entry.usize) {
        throw new Error(`size mismatch for ${entry.name}: got ${data.length}, expected ${entry.usize}`);
    }
    return data;
}

/**
 * Decode every UTF-8 string of a ResStringPool chunk.
 *
 * The pool header is `type,headerSize,size,stringCount,styleCount,flags,stringsStart,stylesStart`
 * (7 x uint32). When `flags & 0x100` is set the offsets are 4 bytes each, otherwise 2.
 */
function readStringPool(buf, offset) {
    const type = buf.readUInt16LE(offset);
    const headerSize = buf.readUInt16LE(offset + 2);
    if (type !== 0x0001) throw new Error('not a string pool at ' + offset);
    const stringCount = buf.readUInt32LE(offset + 8);
    const flags = buf.readUInt32LE(offset + 16);
    const stringsStart = buf.readUInt32LE(offset + 20);
    const utf8 = (flags & 0x100) !== 0;
    const offsetsBase = offset + headerSize;

    const out = [];
    for (let i = 0; i < stringCount; i++) {
        const rel = buf.readUInt32LE(offsetsBase + i * 4);
        let p = offset + stringsStart + rel;
        if (utf8) {
            // u16len (varint-ish: 1 or 2 bytes), u8len, bytes, 0x00
            let len = buf[p];
            if (len & 0x80) { len = ((len & 0x7f) << 8) | buf[p + 1]; p += 2; } else { p += 1; }
            let blen = buf[p];
            if (blen & 0x80) { blen = ((blen & 0x7f) << 8) | buf[p + 1]; p += 2; } else { p += 1; }
            out.push(buf.subarray(p, p + blen).toString('utf8'));
        } else {
            let len = buf.readUInt16LE(p);
            if (len & 0x8000) { len = ((len & 0x7fff) << 16) | buf.readUInt16LE(p + 2); p += 4; } else { p += 2; }
            out.push(buf.subarray(p, p + len * 2).toString('utf16le'));
        }
    }
    return out;
}

const [apkPath, ...needles] = process.argv.slice(2);
if (!apkPath) {
    console.error('usage: node tools/apk_find_strings.js <apk> <substring> [substring...]');
    process.exit(2);
}
const buf = fs.readFileSync(apkPath);
const entries = readCentralDirectory(buf);
const arsc = entries.find((e) => e.name === 'resources.arsc');
if (!arsc) throw new Error('resources.arsc not found');
const data = extractEntry(buf, arsc);

// The value pool is the ResStringPool that is globally UTF-8; find it by trying the first chunk.
let pool = null;
let type = data.readUInt16LE(0);
if (type === 0x0002) {
    // RES_TABLE_TYPE layout: ResChunk_header (8 bytes: type, headerSize, size)
    //                          + packageCount (uint32)
    //                          + global value string pool (a ResStringPool chunk)
    // so the pool starts at `headerSize`, NOT at headerSize + 4.
    const headerSize = data.readUInt16LE(2);
    const packageCount = data.readUInt32LE(8);
    console.log(`RES_TABLE_TYPE: headerSize=${headerSize} packageCount=${packageCount}`);
    pool = readStringPool(data, headerSize);
} else if (type === 0x0001) {
    pool = readStringPool(data, 0);
}
if (!pool) throw new Error('could not locate the value string pool');

console.log(`resources.arsc: ${data.length} bytes, value-pool strings: ${pool.length}`);
if (needles.length === 0) {
    process.exit(0);
}
let hitTotal = 0;
for (const needle of needles) {
    const hits = pool.filter((s) => s.includes(needle));
    console.log(`\n"${needle}": ${hits.length} match(es)`);
    for (const h of hits.slice(0, 30)) console.log('    ' + JSON.stringify(h));
    hitTotal += hits.length;
}
process.exit(hitTotal > 0 ? 0 : 1);
