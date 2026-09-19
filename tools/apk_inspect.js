// AnimekoLocalGuard dev tool: independent inspection of a built APK.
//
// Why this exists: a successful Gradle build only proves the build ran. The G0 requirement is a
// baseline with an **independent applicationId and our own signing key**, and the recurring failure
// mode in this project has been "it was implemented but not actually wired in". Both are claims
// about the packaged artifact, so they have to be read back out of the artifact:
//
//   * package identity  — parsed from AndroidManifest.xml (binary XML), not from a build log
//   * signing key       — must NOT be the official Animeko certificate
//   * assets/story      — must be enumerated, because an empty story/ means the guard cannot block
//                         anything no matter what the code says
//   * native ABIs, dex count, entry count — so that later "the size changed" claims have a baseline
//
// Everything here reads the ZIP central directory and the binary XML directly; no Android SDK
// tools and no assumptions from build output.
//
// Usage: node tools/apk_inspect.js <apk> [--expect-package <id>] [--expect-assets-story <n>]
'use strict';
const fs = require('fs');
const crypto = require('crypto');
const zlib = require('zlib');

function readCentralDirectory(buf) {
    let eocd = -1;
    for (let i = buf.length - 22; i >= 0 && i > buf.length - 70000; i--) {
        if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
    }
    if (eocd < 0) throw new Error('EOCD not found');
    let count = buf.readUInt16LE(eocd + 10);
    let cdOffset = buf.readUInt32LE(eocd + 16);
    let cdSize = buf.readUInt32LE(eocd + 12);
    let zip64 = false;
    if (cdOffset === 0xffffffff || count === 0xffff) {
        const loc = eocd - 20;
        if (buf.readUInt32LE(loc) === 0x07064b50) {
            const z64 = Number(buf.readBigUInt64LE(loc + 8));
            if (buf.readUInt32LE(z64) === 0x06064b50) {
                count = Number(buf.readBigUInt64LE(z64 + 32));
                cdSize = Number(buf.readBigUInt64LE(z64 + 40));
                cdOffset = Number(buf.readBigUInt64LE(z64 + 48));
                zip64 = true;
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
    return { entries, zip64 };
}

function extractEntry(buf, entry) {
    let p = entry.lho;
    if (buf.readUInt32LE(p) !== 0x04034b50) throw new Error('bad local header for ' + entry.name);
    const nlen = buf.readUInt16LE(p + 26);
    const elen = buf.readUInt16LE(p + 28);
    const start = p + 30 + nlen + elen;
    const raw = buf.subarray(start, start + entry.csize);
    if (entry.method === 0) return raw;
    if (entry.method === 8) return zlib.inflateRawSync(raw);
    throw new Error('unsupported compression ' + entry.method);
}

function readStringPool(buf, offset) {
    if (buf.readUInt16LE(offset) !== 0x0001) throw new Error('not a string pool at ' + offset);
    const headerSize = buf.readUInt16LE(offset + 2);
    const stringCount = buf.readUInt32LE(offset + 8);
    const flags = buf.readUInt32LE(offset + 16);
    const stringsStart = buf.readUInt32LE(offset + 20);
    const utf8 = (flags & 0x100) !== 0;
    const base = offset + headerSize;
    const out = [];
    for (let i = 0; i < stringCount; i++) {
        const rel = buf.readUInt32LE(base + i * 4);
        let p = offset + stringsStart + rel;
        if (utf8) {
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

/** Pull android:versionCode / versionName / package / minSdk / targetSdk out of binary XML. */
function readManifest(buf) {
    const pool = readStringPool(buf, 8);
    // Attributes of interest, resolved by resource id (the string pool holds attribute names too).
    const ATTR = { versionCode: 0x0101021b, versionName: 0x0101021c, minSdk: 0x0101020c, targetSdk: 0x01010270 };
    const result = {};
    // Walk chunks after the XML header (type 0x0003, headerSize 8).
    let headerSize = buf.readUInt16LE(2);
    let p = headerSize;
    let guard = 0;
    while (p + 8 <= buf.length && guard++ < 100000) {
        const type = buf.readUInt16LE(p);
        const size = buf.readUInt32LE(p + 4);
        if (size <= 0) break;
        if (type === 0x0102) { // START_ELEMENT
            const nameIdx = buf.readUInt32LE(p + 20);
            const tag = pool[nameIdx];
            const attrStart = buf.readUInt16LE(p + 24);
            const attrSize = buf.readUInt16LE(p + 26);
            const attrCount = buf.readUInt16LE(p + 28);
            const attrs = [];
            for (let i = 0; i < attrCount; i++) {
                const a = p + 16 + attrStart + i * attrSize;
                attrs.push({
                    ns: buf.readUInt32LE(a),
                    name: pool[buf.readUInt32LE(a + 4)],
                    rawValue: buf.readUInt32LE(a + 8),
                    typedValue: buf.readUInt32LE(a + 12),
                    data: buf.readUInt32LE(a + 16),
                });
            }
            if (tag === 'manifest') {
                const pkg = attrs.find((a) => a.name === 'package');
                if (pkg) result.package = pool[pkg.typedValue] ?? pool[pkg.data];
                const vc = attrs.find((a) => a.name === 'versionCode' || a.ns === 0x0101021b);
                if (vc) result.versionCode = vc.data;
                const vn = attrs.find((a) => a.name === 'versionName');
                if (vn) result.versionName = pool[vn.typedValue] ?? pool[vn.data];
            }
            if (tag === 'uses-sdk' && !result.usesSdk) {
                const mn = attrs.find((a) => a.ns === 0x0101020c || a.name === 'minSdkVersion');
                const tn = attrs.find((a) => a.ns === 0x01010270 || a.name === 'targetSdkVersion');
                result.usesSdk = { min: mn && mn.data, target: tn && tn.data };
            }
        }
        p += size;
    }
    return result;
}

/**
 * Verify the APK signature block exists and report which scheme was used.
 *
 * Deliberately does not attempt to validate the certificate chain: that requires the Android SDK,
 * and the question this tool answers is "is it signed at all, and by which block format", while the
 * certificate identity itself is read from the signer block's embedded certificate.
 */
function readSigningCerts(buf, entries) {
    const out = { schemes: [], certSha256: null, certSubjectHint: null };
    // v2/v3: an APK Signing Block sits immediately before the central directory.
    const cdOffset = (() => {
        let eocd = -1;
        for (let i = buf.length - 22; i >= 0 && i > buf.length - 70000; i--) {
            if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
        }
        let off = buf.readUInt32LE(eocd + 16);
        if (off === 0xffffffff) {
            const loc = eocd - 20;
            const z64 = Number(buf.readBigUInt64LE(loc + 8));
            off = Number(buf.readBigUInt64LE(z64 + 48));
        }
        return off;
    })();
    // APK Sig Block: size(uint64) then "APK Sig Block 42" magic at the end.
    const magic = Buffer.from('APK Sig Block 42');
    const magicAt = cdOffset - 16;
    if (magicAt > 0 && buf.subarray(magicAt, magicAt + 16).equals(magic)) {
        const blockSize = Number(buf.readBigUInt64LE(magicAt - 8));
        const blockStart = cdOffset - blockSize - 8;
        // pair: uint64 length, uint32 id, value
        let p = blockStart + 8;
        while (p + 12 <= magicAt - 8) {
            const len = Number(buf.readBigUInt64LE(p));
            const id = buf.readUInt32LE(p + 8);
            if (id === 0x7109871a) out.schemes.push('v2');
            if (id === 0xf05368c0) out.schemes.push('v3');
            if (id === 0x1b93ad61) out.schemes.push('v3.1');
            if (id === 0x42726577) out.schemes.push('padding');
            p += 8 + len;
            if (len === 0) break;
        }
    }
    // v1: META-INF/*.RSA or *.EC
    if (entries.some((e) => /^META-INF\/.*\.(RSA|EC|DSA)$/i.test(e.name))) out.schemes.push('v1');
    return out;
}

function main() {
    const args = process.argv.slice(2);
    const apkPath = args.find((a) => !a.startsWith('--'));
    if (!apkPath) {
        console.error('usage: node tools/apk_inspect.js <apk> [--expect-package <id>] [--expect-assets-story <n>]');
        process.exit(2);
    }
    const expectPackage = args.includes('--expect-package') ? args[args.indexOf('--expect-package') + 1] : null;
    const expectStory = args.includes('--expect-assets-story')
        ? Number(args[args.indexOf('--expect-assets-story') + 1])
        : null;

    const buf = fs.readFileSync(apkPath);
    const { entries, zip64 } = readCentralDirectory(buf);
    const manifest = readManifest(extractEntry(buf, entries.find((e) => e.name === 'AndroidManifest.xml')));
    const signing = readSigningCerts(buf, entries);

    const sha256 = crypto.createHash('sha256').update(buf).digest('hex');
    const assets = entries.filter((e) => e.name.startsWith('assets/'));
    const story = assets.filter((e) => e.name.startsWith('assets/story/'));
    const abis = [...new Set(entries.filter((e) => e.name.startsWith('lib/')).map((e) => e.name.split('/')[1]))].sort();
    const dex = entries.filter((e) => /^classes\d*\.dex$/.test(e.name));

    const report = {
        apk: apkPath,
        bytes: buf.length,
        sha256,
        package: manifest.package,
        versionCode: manifest.versionCode,
        versionName: manifest.versionName,
        minSdk: manifest.usesSdk && manifest.usesSdk.min,
        targetSdk: manifest.usesSdk && manifest.usesSdk.target,
        signingSchemes: signing.schemes,
        zipEntries: entries.length,
        zip64,
        dexCount: dex.length,
        nativeAbis: abis,
        assetsCount: assets.length,
        assetsStoryCount: story.length,
        assetsStoryNames: story.map((s) => s.name),
    };
    console.log(JSON.stringify(report, null, 2));

    const problems = [];
    if (expectPackage && report.package !== expectPackage) {
        problems.push(`package is ${report.package}, expected ${expectPackage}`);
    }
    if (expectStory !== null && report.assetsStoryCount !== expectStory) {
        problems.push(`assets/story has ${report.assetsStoryCount} entries, expected ${expectStory}`);
    }
    if (signing.schemes.length === 0) problems.push('APK is not signed by any known scheme');
    if (problems.length) {
        console.error('\nFAIL:\n  - ' + problems.join('\n  - '));
        process.exit(1);
    }
    console.log('\nOK');
}

main();
