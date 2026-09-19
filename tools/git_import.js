// AnimekoLocalGuard dev tool: publish the current tree as a single, honestly-attributed import
// commit into a fresh local git repository.
//
// Why a fresh single commit instead of pushing this clone's history:
//   The working tree is a clone of open-ani/animeko with 4788 commits (156 MB, including VLC and
//   FFmpeg binaries for macOS/Linux). Publishing that history is neither necessary nor useful for a
//   modified build, and re-uploading upstream's entire history is slow and impolite. What the
//   licence and any reviewer actually need is (a) the source, (b) clear attribution, and (c) the
//   exact upstream revision this derives from. A single import commit satisfies all three, and the
//   provenance is recorded in the commit message itself rather than only in a file.
//
// Why a separate repository directory:
//   Creating commits inside the working tree would add a commit on top of upstream's history, which
//   would make the local checkout diverge from upstream more than the two documented local edits
//   (gradle.properties / local.properties) that the build depends on. The import repo only holds a
//   copy, so the working tree keeps its exact relationship to upstream.
//
// Usage: node tools/git_import.js <destDir> --files <listFile> [--dry-run]
//
// --files is required: Node cannot spawn `git` here (the DSH sandbox forbids creating the pipe a
// captured child needs, so execFileSync fails with EPERM). The caller therefore produces the file
// list with git and passes it in. That keeps the important property — the import is exactly the set
// git would publish — while letting this tool do the part Node is actually needed for: copying
// thousands of files whose names are not ASCII, which PowerShell mangles.
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const args = process.argv.slice(2);
const dest = args.find((a) => !a.startsWith('--'));
const dryRun = args.includes('--dry-run');
const filesArgIndex = args.indexOf('--files');
const listFile = filesArgIndex >= 0 ? args[filesArgIndex + 1] : null;

if (!dest || !listFile) {
    console.error('usage: node tools/git_import.js <destDir> --files <listFile> [--dry-run]');
    process.exit(2);
}

/**
 * Files to import, one per line, produced by the caller with
 *   git ls-files && git ls-files --others --exclude-standard
 * so the import is exactly the set that passed the secret scan.
 */
function publishableFiles() {
    return fs.readFileSync(listFile, 'utf8')
        .split('\n')
        .map((l) => l.trim())
        .filter(Boolean);
}

function main() {
    const files = publishableFiles();
    console.log(`files to import: ${files.length}`);

    if (dryRun) {
        let total = 0;
        let missing = 0;
        for (const f of files) {
            try { total += fs.statSync(path.join(ROOT, f)).size; } catch { missing++; }
        }
        console.log(`total size: ${(total / 1024 / 1024).toFixed(1)} MB`);
        if (missing) console.log(`WARNING: ${missing} listed file(s) do not exist`);
        console.log('dry run: nothing written');
        return missing ? 1 : 0;
    }

    // Start from an empty directory so no stale .git can leak unexpected history.
    if (fs.existsSync(dest)) {
        // Refuse to silently destroy something. A bare `git init` is enough to identify a directory
        // as ours, because the caller must run it deliberately; an unrelated directory will not have
        // one. (Requiring ANIMEKOLOCALGUARD.md as well would deadlock the first run, since that file
        // is copied by this very step.)
        const isOurs = fs.existsSync(path.join(dest, '.git'));
        if (!isOurs) {
            console.error(`refusing to write into ${dest}: it exists, has no .git, and may hold unrelated files`);
            process.exit(3);
        }
    } else {
        fs.mkdirSync(dest, { recursive: true });
    }

    let copied = 0;
    let bytes = 0;
    const skipped = [];
    for (const rel of files) {
        const src = path.join(ROOT, rel);
        let stat;
        try { stat = fs.statSync(src); } catch { skipped.push(rel); continue; }
        if (!stat.isFile()) continue;
        const out = path.join(dest, rel);
        fs.mkdirSync(path.dirname(out), { recursive: true });
        fs.copyFileSync(src, out);
        copied++;
        bytes += stat.size;
    }
    console.log(`copied ${copied} files (${(bytes / 1024 / 1024).toFixed(1)} MB)`);
    if (skipped.length) {
        console.log(`skipped ${skipped.length} missing file(s), e.g. ${skipped.slice(0, 3).join(', ')}`);
    }
    console.log('');
    console.log('next: run the git steps from the caller (init / add / commit / push),');
    console.log('and check that no forbidden path got staged.');
}

process.exit(main() || 0);
