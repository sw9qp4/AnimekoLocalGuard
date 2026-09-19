// AnimekoLocalGuard dev tool: refuse to publish a repository that contains secrets.
//
// Why this exists, and why it must run before any push:
//   This tree was assembled locally: local.properties holds the DanDanPlay app id/secret and the
//   release-keystore passwords, keystore/ holds the signing key itself, and .tools/ holds a JDK,
//   an Android SDK and a 3 GB Gradle cache. Committing any of those is irreversible in practice —
//   a pushed secret must be treated as compromised even if the commit is later removed, because
//   it stays reachable in the repository's history and in every clone and fork.
//
//   The scan therefore works on **what git would actually publish** (tracked files plus anything
//   not ignored), not on a hand-written list, so a file added later is covered automatically.
//
// Usage: node tools/secret_scan.js [--all] [--files <listFile>]
//   --all            also scan files that git would ignore (slower; audits the whole tree)
//   --files <path>   read the candidate file list from a file (one path per line, relative to the
//                    repository root) instead of asking git.
//
// Why --files exists: under the DSH sandbox Node cannot spawn a child process whose stdout is
// captured (the sandbox forbids the pipe), so `execFileSync('git', ...)` fails with EPERM. Letting
// the caller produce the list with git and pass it in keeps the tool usable **and** keeps the
// "scan what git would publish" property, because the caller uses the same git commands.
'use strict';
const { execFileSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const argv = process.argv.slice(2);
const scanAll = argv.includes('--all');
const filesArgIndex = argv.indexOf('--files');
const filesFromArg = filesArgIndex >= 0 ? argv[filesArgIndex + 1] : null;

/** Paths that must never be published, by suffix or by directory. */
const FORBIDDEN_PATH_PATTERNS = [
    { re: /(^|\/)local\.properties$/, why: 'holds DanDanPlay credentials and keystore passwords' },
    { re: /(^|\/)keystore\/[^/]*\.(jks|keystore|p12|pfx)$/i, why: 'signing key material' },
    // Deliberately not matching a bare `keystore` path segment: upstream ships VLC's keystore
    // *plugins* (app/desktop/appResources/**/lib/vlc/plugins/keystore/*.so), which are ordinary
    // bundled libraries. Flagging those would be noise, and noise is how a scanner gets ignored.
    { re: /\.(jks|keystore|p12|pfx)$/i, why: 'signing key material' },
    { re: /(^|\/)\.gh-token\.tmp$/, why: 'credential stub' },
    { re: /(^|\/)\.tools\//, why: 'local toolchain and caches, huge and machine-specific' },
    { re: /(^|\/)\.gradle\//, why: 'local build state' },
    { re: /(^|\/)build\//, why: 'build output' },
    { re: /(^|\/)artifacts\//, why: 'local APK outputs; may embed credentials baked in at build time' },
    // Evidence files are raw pulls of the danmaku APIs and of the official Animeko APK, kept
    // locally for auditability. The generated-danmaku coverage data stays publishable on purpose:
    // it contains only titles and counts, and it documents a negative result worth keeping.
    { re: /^docs\/evidence\/(?!danmaku_coverage|short_series_shortlist)/, why: 'raw local evidence, not source' },
    { re: /(^|\/)node_modules\//, why: 'vendored dependencies' },
    { re: /\.(apk|aab|apks)$/i, why: 'binary artifacts' },
    { re: /(^|\/)\.ssh\//, why: 'private keys' },
    { re: /(^|\/)\.scan-filelist\.txt$/, why: 'local scratch file' },
];

/**
 * Content patterns. Kept deliberately narrow so the scan stays trustworthy: a scanner that flags
 * ordinary code gets ignored, and an ignored scanner protects nothing.
 *
 * These patterns are written to match a credential **with a literal value**. A parameter name, a
 * property lookup or a variable reference carries no secret, and flagging those produced 7 false
 * positives on the first run — including three places that merely read `dandanplayAppId` out of
 * `AniBuildConfig`. The actual credentials live in local.properties, which the path rules above
 * already block outright.
 */
const CONTENT_PATTERNS = [
    { re: /gho_[A-Za-z0-9]{20,}/, label: 'GitHub OAuth token' },
    { re: /ghp_[A-Za-z0-9]{20,}/, label: 'GitHub personal access token' },
    { re: /github_pat_[A-Za-z0-9_]{20,}/, label: 'GitHub fine-grained token' },
    { re: /-----BEGIN [A-Z ]*PRIVATE KEY-----/, label: 'PEM private key' },
    { re: /AKIA[0-9A-Z]{16}/, label: 'AWS access key id' },
    { re: /xox[baprs]-[A-Za-z0-9-]{10,}/, label: 'Slack token' },
    // A dandanplay-style secret assigned a string literal.
    {
        re: /(?:appSecret|app_secret|dandanplayAppSecret)\s*[=:]\s*["'][^"'\s]{12,}["']/i,
        label: 'literal app secret',
    },
    // A keystore password assigned a literal (Kotlin/Gradle/shell forms).
    {
        re: /(?:storePassword|keyPassword)\s*[=:]\s*(?!getProperty|getLocalProperty|System\.getenv|process\.env)["'][^"'\s]{6,}["']/i,
        label: 'literal keystore password',
    },
    // A local.properties-style assignment with a real value (the committed file must never have one).
    {
        re: /^\s*signing_release_(?:store|key)Password\s*=\s*\S+/m,
        label: 'keystore password in a properties file',
    },
];

/** Files large enough that scanning is pointless (also usually binary). */
const MAX_SCAN_BYTES = 2 * 1024 * 1024;
const BINARY_EXT = new Set([
    '.png', '.jpg', '.jpeg', '.gif', '.webp', '.ico', '.ttf', '.otf', '.woff', '.woff2',
    '.zip', '.gz', '.jar', '.so', '.dll', '.dylib', '.dex', '.bin', '.mp3', '.mp4', '.wav',
]);

function gitList(args) {
    return execFileSync('git', args, { cwd: ROOT, encoding: 'utf8', maxBuffer: 256 * 1024 * 1024 })
        .split('\n')
        .map((l) => l.trim())
        .filter(Boolean);
}

function main() {
    let files;
    if (scanAll) {
        // Walk the tree, skipping directories that only ever hold generated or huge content.
        const skip = new Set(['.git', '.tools', '.gradle', '.kotlin', 'node_modules', 'build', 'artifacts']);
        files = [];
        const walk = (dir) => {
            let entries;
            try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch { return; }
            for (const e of entries) {
                if (e.isDirectory()) {
                    if (skip.has(e.name)) continue;
                    walk(path.join(dir, e.name));
                } else if (e.isFile()) {
                    files.push(path.relative(ROOT, path.join(dir, e.name)).replace(/\\/g, '/'));
                }
            }
        };
        walk(ROOT);
    } else if (filesFromArg) {
        files = fs.readFileSync(filesFromArg, 'utf8')
            .split('\n')
            .map((l) => l.trim().replace(/\\/g, '/'))
            .filter(Boolean);
    } else {
        // What git would actually publish: tracked files + untracked-but-not-ignored files.
        const tracked = gitList(['ls-files']);
        const untracked = gitList(['ls-files', '--others', '--exclude-standard']);
        files = [...new Set([...tracked, ...untracked])];
    }

    const problems = [];
    const notes = [];

    for (const rel of files) {
        for (const p of FORBIDDEN_PATH_PATTERNS) {
            if (p.re.test(rel)) {
                problems.push({ kind: 'PATH', file: rel, detail: p.why });
                break;
            }
        }
    }

    let scanned = 0;
    for (const rel of files) {
        const ext = path.extname(rel).toLowerCase();
        if (BINARY_EXT.has(ext)) continue;
        let stat;
        try { stat = fs.statSync(path.join(ROOT, rel)); } catch { continue; }
        if (!stat.isFile() || stat.size > MAX_SCAN_BYTES) continue;
        let text;
        try { text = fs.readFileSync(path.join(ROOT, rel), 'utf8'); } catch { continue; }
        scanned++;
        for (const p of CONTENT_PATTERNS) {
            const m = text.match(p.re);
            if (m) {
                // Report the matched text with the secret body masked, so the report itself is safe
                // to paste into a chat or an issue.
                const hit = m[0];
                const masked = hit.length <= 12
                    ? hit.slice(0, 4) + '***'
                    : hit.slice(0, 4) + '***' + hit.slice(-2);
                problems.push({ kind: 'CONTENT', file: rel, detail: `${p.label}: ${masked}` });
            }
        }
    }

    console.log(`scanned ${scanned} text files; ${files.length} files considered for publication`);
    console.log(`mode: ${scanAll ? 'whole working tree (--all)' : 'what git would publish'}`);

    if (problems.length === 0) {
        console.log('\nOK - no forbidden paths and no secret-looking content found.');
        return 0;
    }

    console.log(`\nFOUND ${problems.length} problem(s) - DO NOT PUBLISH until these are resolved:\n`);
    for (const p of problems) console.log(`  [${p.kind}] ${p.file}\n         ${p.detail}`);
    console.log('\nIf a path problem is a false positive, adjust the pattern in this file and say why.');
    console.log('If a content problem is a real secret, rotate it first: publishing is irreversible.');
    return 1;
}

process.exit(main());
