#!/usr/bin/env node
/**
 * List Android platform packages (and optionally others) with their archives,
 * straight from Google's repository2-3.xml.
 *
 * Usage: node sdk_platforms.js [pathPrefixRegex]
 *   default filter: ^platforms;android-
 */
'use strict';

const filter = process.argv[2] ? new RegExp(process.argv[2]) : /^platforms;android-/;

function field(text, name) {
  // First try an element form <name>value</name>; some fields are attributes.
  const el = text.match(new RegExp('<' + name + '>([^<]*)</' + name + '>'));
  if (el) return el[1];
  const at = text.match(new RegExp(name + '="([^"]*)"'));
  return at ? at[1] : null;
}

(async () => {
  const r = await fetch('https://dl.google.com/android/repository/repository2-3.xml');
  const xml = await r.text();
  const blocks = xml.split('<remotePackage ').slice(1);
  const rows = [];
  for (const b of blocks) {
    const path = field(b, 'path');
    if (!path || !filter.test(path)) continue;
    const revision = [
      field(b, 'major'),
      field(b, 'minor'),
      field(b, 'micro'),
    ].filter(Boolean).join('.');
    const archives = [...b.matchAll(/<archive>([\s\S]*?)<\/archive>/g)].map((m) => m[1]);
    const list = archives.map((a) => ({
      os: field(a, 'host-os'),
      url: field(a, 'url'),
      size: field(a, 'size'),
      sha1: (a.match(/<checksum type="sha1">([^<]*)</) || [])[1] || null,
    }));
    rows.push({ path, revision, displayName: field(b, 'display-name'), archives: list });
  }
  console.log(JSON.stringify(rows, null, 2));
})().catch((e) => { console.error('ERROR: ' + e.message); process.exit(1); });
