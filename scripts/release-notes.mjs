#!/usr/bin/env node
// Builds the CHANGELOG entry for a release out of the commits behind it.
//
// One section per kind of change: breaking first, then features, fixes, the
// rest. A commit's kind comes from its release marker if it has one and from
// its conventional-commit type if it does not, so `!feat: X` and `feat: X`
// land in the same place. Marker and type prefix are stripped from the note.
//
// Usage:
//   node scripts/release-notes.mjs --version 0.0.1 --subjects-file subjects.txt \
//     [--previous 0.0.0] [--repository owner/name] [--date 2026-08-21]
//   node scripts/release-notes.mjs --section --version 0.0.1   # read one entry back
//   node scripts/release-notes.mjs --check                     # self-check

import { strictEqual, ok as assertOk } from 'node:assert';
import { readFileSync } from 'node:fs';

/** A commit the release flow wrote itself never appears in its own notes. */
const NOISE = [/^\s*chore\(release\):/i, /^\s*Merge (pull request|branch|remote-tracking)/i, /^\s*docs\(changelog\):/i];

const MARKER = /^\s*!(major|feat|fix|stable|alpha|beta|patch)(?![a-z0-9])(?:\([^)]*\))?[:\s-]*/i;
const TYPE = /^(feat|fix|chore|docs|refactor|perf|test|build|ci|style|revert)(\([^)]*\))?(!)?:\s*/i;

const SECTIONS = [
  ['breaking', '### ⚠ Breaking changes'],
  ['features', '### Features'],
  ['fixes', '### Bug fixes'],
  ['other', '### Other changes'],
];

/** Which section a subject belongs in. */
export function classify(subject) {
  const kind = MARKER.exec(subject)?.[1]?.toLowerCase();
  if (kind === 'major') return 'breaking';
  const type = TYPE.exec(subject.replace(MARKER, ''));
  if (type?.[3] === '!' || /\bBREAKING[ -]CHANGE\b/.test(subject)) return 'breaking';
  if (kind === 'feat') return 'features';
  if (kind === 'fix') return 'fixes';
  const name = type?.[1]?.toLowerCase();
  if (name === 'feat') return 'features';
  if (name === 'fix') return 'fixes';
  return 'other';
}

/** The subject with the tooling stripped off and a capital at the front. */
export function clean(subject) {
  const text = subject.replace(MARKER, '').replace(TYPE, '').trim().replace(/\.+$/, '');
  return text ? text[0].toUpperCase() + text.slice(1) : text;
}

export function notes(subjects, { version, previous, repository, date } = {}) {
  const grouped = new Map(SECTIONS.map(([key]) => [key, []]));
  for (const subject of subjects.map((s) => s.trim())) {
    if (!subject || NOISE.some((pattern) => pattern.test(subject))) continue;
    const line = clean(subject);
    const bucket = grouped.get(classify(subject));
    if (line && !bucket.includes(line)) bucket.push(line);
  }

  const compare = repository && previous ? `https://github.com/${repository}/compare/v${previous}...v${version}` : '';
  const heading = compare ? `## [${version}](${compare})` : `## ${version}`;
  const stamp = date ?? new Date().toISOString().slice(0, 10);

  let body = '';
  for (const [key, title] of SECTIONS) {
    const items = grouped.get(key);
    if (items.length) body += `${title}\n\n${items.map((item) => `* ${item}`).join('\n')}\n\n`;
  }
  if (!body) body = `### Notes\n\n* Release ${version}\n\n`;

  return { heading: `${heading} (${stamp})`, body: body.trimEnd() };
}

/** Puts a new entry at the top of an existing CHANGELOG, below its title. */
export function insert(changelog, entry) {
  const block = `${entry.heading}\n\n${entry.body}\n`;
  if (changelog.includes(entry.heading)) return changelog;
  const title = '# Changelog\n\n';
  return changelog.startsWith(title) ? changelog.replace(title, `${title}${block}\n`) : `${title}${block}\n${changelog}`;
}

/** The section for one version, for a GitHub Release body. */
export function section(changelog, version) {
  const escaped = version.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const match = new RegExp(`^##\\s*\\[?${escaped}\\]?.*?(?=^##\\s|$(?![\\s\\S]))`, 'ms').exec(changelog);
  if (!match) return '';
  const [, ...rest] = match[0].trim().split('\n');
  return rest.join('\n').trim();
}

function selfCheck() {
  strictEqual(classify('!major: drop old storage'), 'breaking');
  strictEqual(classify('feat(guard)!: drop old storage'), 'breaking');
  strictEqual(classify('!feat: a new thing'), 'features');
  strictEqual(classify('feat(focus): a new thing'), 'features');
  strictEqual(classify('!fix: a broken thing'), 'fixes');
  strictEqual(classify('fix: a broken thing'), 'fixes');
  strictEqual(classify('docs: a written thing'), 'other');
  strictEqual(classify('something with no prefix'), 'other');
  strictEqual(classify('!alpha: fix(guard): a broken thing'), 'fixes');
  strictEqual(classify('!patch: fix(guard): rebuild'), 'fixes');

  strictEqual(clean('!feat(focus): add presets.'), 'Add presets');
  strictEqual(clean('fix: the thing'), 'The thing');

  const entry = notes(
    [
      '!feat(focus): add presets',
      'fix(guard): stop the loop',
      'chore(release): v0.0.1',
      'Merge pull request #3 from x',
      'docs: explain it',
      'feat(matrix)!: drop v1 charts',
      'fix(guard): stop the loop',
    ],
    { version: '0.1.0', previous: '0.0.1', repository: 'a/b', date: '2026-08-21' },
  );
  assertOk(entry.heading.includes('compare/v0.0.1...v0.1.0'), 'links the comparison');
  assertOk(entry.heading.endsWith('(2026-08-21)'), 'carries the date');
  assertOk(entry.body.includes('* Drop v1 charts'), 'breaking listed');
  assertOk(entry.body.includes('* Add presets'), 'feature listed');
  assertOk(entry.body.includes('* Stop the loop'), 'fix listed');
  assertOk(entry.body.includes('* Explain it'), 'other listed');
  assertOk(!entry.body.includes('Merge pull request'), 'the merge commit is not a note');
  strictEqual(entry.body.match(/Stop the loop/g).length, 1, 'a repeated subject is one line');
  assertOk(entry.body.indexOf('Breaking') < entry.body.indexOf('Features'), 'breaking comes first');

  assertOk(notes(['chore(release): v1'], { version: '1.0.0' }).body.includes('Release 1.0.0'));

  const changelog = insert('# Changelog\n\n## 0.0.1 (2026-01-01)\n\n### Notes\n\n* old\n', entry);
  assertOk(changelog.indexOf('0.1.0') < changelog.indexOf('0.0.1'), 'newest first');
  strictEqual(insert(changelog, entry), changelog, 'inserting twice changes nothing');
  assertOk(section(changelog, '0.1.0').includes('Add presets'), 'the section is readable back');
  assertOk(!section(changelog, '0.1.0').includes('old'), 'and stops at the next version');
  assertOk(section(changelog, '0.0.1').includes('* old'), 'including the last one');
  strictEqual(section(changelog, '9.9.9'), '', 'a version with no section is empty');

  console.log('release-notes self-check passed');
}

function main(argv) {
  if (argv.includes('--check')) return selfCheck();

  const arg = (name) => {
    const index = argv.indexOf(name);
    return index === -1 ? '' : (argv[index + 1] ?? '');
  };
  const path = arg('--changelog') || 'CHANGELOG.md';

  // What ships as the Release note is what was reviewed on the release PR.
  if (argv.includes('--section')) return console.log(section(readFileSync(path, 'utf8'), arg('--version')));

  const subjectsFile = arg('--subjects-file');
  const subjects = subjectsFile ? readFileSync(subjectsFile, 'utf8').split('\n') : arg('--subject').split('\n');
  const entry = notes(subjects, {
    version: arg('--version'),
    previous: arg('--previous') || undefined,
    repository: arg('--repository') || undefined,
    date: arg('--date') || undefined,
  });

  let existing = '# Changelog\n\n';
  try {
    existing = readFileSync(path, 'utf8');
  } catch {}
  process.stdout.write(insert(existing, entry));
}

main(process.argv.slice(2));
