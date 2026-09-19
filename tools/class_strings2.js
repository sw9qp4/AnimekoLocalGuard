// AnimekoLocalGuard dev tool: dump the string constants and method names of a compiled
// .class file, so a test's intent (method names + assertion messages) can be recovered
// without a Java decompiler.
//
// Usage: node tools/class_strings2.js <class file>
const fs = require('fs');

const file = process.argv[2];
if (!file) {
    console.error('usage: node tools/class_strings2.js <class-file>');
    process.exit(2);
}
const buf = fs.readFileSync(file);
if (buf.readUInt32BE(0) !== 0xCAFEBABE) {
    console.error('not a class file');
    process.exit(2);
}
const cpCount = buf.readUInt16BE(8);
let off = 10;
const utf8 = [];
for (let i = 1; i < cpCount; i++) {
    const tag = buf[off++];
    switch (tag) {
        case 1: { // Utf8
            const len = buf.readUInt16BE(off); off += 2;
            utf8[i] = buf.toString('utf8', off, off + len);
            off += len;
            break;
        }
        case 7: case 8: case 16: case 19: case 20: off += 2; break;
        case 15: off += 3; break;
        case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18: off += 4; break;
        case 5: case 6: off += 8; i++; break;
        default:
            console.error('unknown constant pool tag ' + tag + ' at ' + (off - 1));
            process.exit(2);
    }
}
const strings = utf8.filter((s) => typeof s === 'string');
const interesting = strings.filter((s) =>
    !/^[a-zA-Z0-9_$/;(\[<]+$/.test(s) && s.length > 1,
);
console.log('=== 非标识符字符串（含中文断言文案） ===');
for (const s of [...new Set(interesting)]) console.log(s);
console.log('\n=== 方法名候选（test 相关） ===');
for (const s of [...new Set(strings)]) {
    if (/^[a-z][A-Za-z0-9_$]*$/.test(s) && s.length > 3) console.log(s);
}
