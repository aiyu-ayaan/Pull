#!/usr/bin/env node
// Works out the next version from the commit subjects of a push to master.
//
// A release is asked for by a marker at the START of a commit subject:
//
//   !major   1.4.2         -> 2.0.0          stable
//   !feat    1.4.2         -> 1.5.0          stable
//   !fix     1.4.2         -> 1.4.3          stable
//   !alpha   0.0.1         -> 0.0.2-alpha.1  pre-release
//            0.0.2-alpha.1 -> 0.0.2-alpha.2
//   !beta    0.0.2-alpha.3 -> 0.0.2-beta.1   promote, same base
//            0.0.2-beta.1  -> 0.0.2-beta.2
//   !stable  0.0.2-beta.2  -> 0.0.2          promote to stable, no bump
//   !patch   0.0.2         -> 0.0.2          no bump at all: rebuild in place
//
// A scope is a plain conventional-commit scope: `!feat(guard): x` is `!feat`.
//
// A push with no marker is not a release. When several pushed commits carry
// markers the strongest wins, in the order listed above.
//
// A channel can be promoted but not demoted in place: !alpha on a beta starts
// a fresh alpha on the NEXT patch, because 0.0.2-alpha.1 sorts below a
// 0.0.2-beta.1 that has already shipped.
//
// Usage:
//   node scripts/release-version.mjs --current 0.0.1 --subjects-file subjects.txt
//   node scripts/release-version.mjs --check      # self-check, no arguments

import { strictEqual, throws } from 'node:assert';
import { readFileSync } from 'node:fs';

const CHANNELS = { alpha: 1, beta: 2 };

// Strongest first. `patch` is last: a push asking for a real release AND a
// rebuild of the old one wants the release, which already contains the rebuild.
const MARKERS = ['major', 'feat', 'fix', 'stable', 'beta', 'alpha', 'patch'];

const VERSION_RE = /^(\d+)\.(\d+)\.(\d+)(?:-(alpha|beta)\.(\d+))?$/;

// A commit the release flow wrote itself. Ignored, so re-running over a range
// that includes one does not release twice.
const RELEASE_COMMIT_RE = /^\s*chore\(release\):/i;

export function parseVersion(version) {
  const match = VERSION_RE.exec(String(version).trim());
  if (!match) throw new Error(`Invalid version '${version}'. Expected X.Y.Z or X.Y.Z-alpha.N.`);
  return {
    major: Number(match[1]),
    minor: Number(match[2]),
    patch: Number(match[3]),
    channel: match[4] ?? '',
    pre: match[5] ? Number(match[5]) : 0,
  };
}

export function detectMarker(subjects) {
  const found = new Set();
  for (const subject of subjects.map((s) => s.trim())) {
    if (!subject || RELEASE_COMMIT_RE.test(subject)) continue;
    const match = /^!(major|feat|fix|stable|alpha|beta|patch)(?![a-z0-9])/i.exec(subject);
    if (match) found.add(match[1].toLowerCase());
  }
  return MARKERS.find((marker) => found.has(marker)) ?? '';
}

export function nextVersion(current, marker) {
  const { major, minor, patch, channel, pre } = parseVersion(current);
  const stable = (m, n, p) => `${m}.${n}.${p}`;

  switch (marker) {
    case 'major':
      return stable(major + 1, 0, 0);
    case 'feat':
      return stable(major, minor + 1, 0);
    case 'fix':
      // From a pre-release this moves past it: !stable is what declares a
      // pre-release finished, !fix asks for the next fix.
      return stable(major, minor, patch + 1);
    case 'stable':
      if (!channel) throw new Error(`!stable needs a pre-release to promote; ${current} is already stable.`);
      return stable(major, minor, patch);
    case 'patch':
      // Not a bump: `!patch` rebuilds the version that is already released.
      return channel ? `${stable(major, minor, patch)}-${channel}.${pre}` : stable(major, minor, patch);
    case 'alpha':
    case 'beta': {
      if (channel && CHANNELS[channel] <= CHANNELS[marker]) {
        const counter = channel === marker ? pre + 1 : 1;
        return `${stable(major, minor, patch)}-${marker}.${counter}`;
      }
      return `${stable(major, minor, patch + 1)}-${marker}.1`;
    }
    default:
      throw new Error(`Unknown marker '${marker}'.`);
  }
}

function selfCheck() {
  const eq = (current, marker, expected) =>
    strictEqual(nextVersion(current, marker), expected, `${current} + !${marker}`);

  eq('0.0.0', 'fix', '0.0.1');
  eq('0.0.0', 'alpha', '0.0.1-alpha.1');
  eq('0.0.1-alpha.1', 'alpha', '0.0.1-alpha.2');
  eq('0.0.1-alpha.3', 'beta', '0.0.1-beta.1');
  eq('0.0.1-beta.1', 'beta', '0.0.1-beta.2');
  eq('0.0.1-beta.2', 'stable', '0.0.1');
  eq('0.0.1-beta.2', 'alpha', '0.0.2-alpha.1');
  eq('0.0.1', 'alpha', '0.0.2-alpha.1');
  eq('0.0.1', 'feat', '0.1.0');
  eq('0.1.0', 'major', '1.0.0');
  eq('1.4.2', 'fix', '1.4.3');
  eq('0.0.2-beta.1', 'fix', '0.0.3');
  eq('1.4.2', 'patch', '1.4.2');
  eq('0.0.2-alpha.3', 'patch', '0.0.2-alpha.3');

  throws(() => nextVersion('0.0.1', 'stable'), /already stable/);
  throws(() => parseVersion('1.2'), /Invalid version/);
  throws(() => parseVersion('1.2.3-rc.1'), /Invalid version/);

  strictEqual(detectMarker(['!feat: a thing']), 'feat');
  strictEqual(detectMarker(['docs: nothing']), '');
  strictEqual(detectMarker(['!alpha: x', '!fix: y']), 'fix');
  strictEqual(detectMarker(['!major: x', '!feat: y']), 'major');
  strictEqual(detectMarker(['chore(release): v0.0.1']), '');
  strictEqual(detectMarker(['fix: mentions !feat in passing']), '');
  strictEqual(detectMarker(['!feature: not a marker']), '');
  strictEqual(detectMarker(['!feat(guard): x']), 'feat');
  strictEqual(detectMarker(['!patch: x', '!fix: y']), 'fix');

  console.log('release-version self-check passed');
}

function main(argv) {
  if (argv.includes('--check')) return selfCheck();

  const arg = (name) => {
    const index = argv.indexOf(name);
    return index === -1 ? '' : (argv[index + 1] ?? '');
  };

  const subjectsFile = arg('--subjects-file');
  const subjects = subjectsFile ? readFileSync(subjectsFile, 'utf8').split('\n') : arg('--subject').split('\n');

  const marker = detectMarker(subjects);
  if (!marker) return console.log('release=false');

  const version = nextVersion(arg('--current'), marker);
  const { channel } = parseVersion(version);
  console.log(
    [
      'release=true',
      `marker=${marker}`,
      `version=${version}`,
      `channel=${channel || 'latest'}`,
      `prerelease=${channel ? 'true' : 'false'}`,
    ].join('\n'),
  );
}

main(process.argv.slice(2));
