#!/usr/bin/env node
/**
 * Guards the two things that are easy to break silently and expensive to
 * notice late:
 *
 *   1. AGENTS.md drift. Every path it mentions must still exist. That file had
 *      gone stale four times in one session — it named removed classes, claimed
 *      `dependencies {}` was empty when it held a project dep, and told a future
 *      reader not to investigate a wallpaper bug I had myself disproved. A prose
 *      file cannot enforce itself; this can.
 *
 *   2. Web/Android parity for the constants that MUST match. DEPTH_BLUR_FRACTION
 *      is the load-bearing one: the 8-bit depth banding fix is that exact
 *      fraction, and if the two ports disagree the artifact comes back on one
 *      platform only. Nothing in the build enforced this.
 *
 * Exits non-zero on failure so CI goes red. No dependencies — plain Node, so it
 * runs in the web job with no install step.
 */
import { readFileSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const failures = [];

// ---------------------------------------------------------------- doc links

/**
 * Pull `backticked` spans out of AGENTS.md and keep the ones that are meant to
 * be paths. Requires a "/" so bare filenames in prose ("replace all three") are
 * not mistaken for paths.
 */
function checkDocLinks() {
  const doc = join(root, 'AGENTS.md');
  if (!existsSync(doc)) {
    failures.push('AGENTS.md is missing');
    return;
  }
  const spans = [...readFileSync(doc, 'utf8').matchAll(/`([^`\n]+)`/g)].map((m) => m[1]);
  const pathish = spans.filter((s) => s.includes('/'));
  const seen = new Set();
  let skipped = 0;

  for (const span of pathish) {
    // Not repo paths: absolute host/device locations (/tmp, /usr/bin/env),
    // ~ and $VAR expansions, and shebangs. Their absence here says nothing.
    if (/^[~$#/]/.test(span) || span.includes('$')) {
      skipped++;
      continue;
    }
    // `a/{b,c}.png` -> `a/`; a trailing `()` or comma is prose, not path.
    const probe = span.replace(/[{}]/g, '').split(/[,)]/)[0].trim();
    if (!probe || /\s/.test(probe) || seen.has(probe)) continue;
    seen.add(probe);
    // Prose writes Android paths relative to android/ (e.g. `res/raw/depth.png`,
    // `app/build.gradle`), so try that base too.
    const bases = [root, join(root, 'android')];
    if (!bases.some((b) => existsSync(join(b, probe)))) {
      failures.push(
        `AGENTS.md mentions \`${span}\` but ${probe} does not exist — ` +
          `the doc has drifted from the tree`
      );
    }
  }
  console.log(`doc links: ${seen.size} repo paths checked, ${skipped} host paths skipped`);
}

// --------------------------------------------------------- web/Android parity

/** Read a numeric constant out of a source file, e.g. DEPTH_BLUR_FRACTION. */
function readFraction(file, name) {
  const text = readFileSync(join(root, file), 'utf8');
  // matches: const X = 1 / 78;   |   float X = 1f / 78f;
  const m = text.match(new RegExp(`${name}\\s*=\\s*([0-9f.]+)\\s*/\\s*([0-9f.]+)`));
  if (!m) return null;
  // Strip the Java `f` suffix from BOTH sides. Leaving it on the numerator gives
  // Number('1f') === NaN, and every NaN comparison is false — which silently
  // turns this whole check into a no-op that can never fail.
  const num = Number(m[1].replace(/f$/, ''));
  const den = Number(m[2].replace(/f$/, ''));
  if (!Number.isFinite(num) || !Number.isFinite(den) || den === 0) return null;
  return num / den;
}

function checkParity() {
  const pairs = [
    {
      name: 'DEPTH_BLUR_FRACTION',
      why: 'the 8-bit depth banding fix is this exact fraction',
      web: 'src/scene.js',
      android: 'android/core/src/main/java/com/amoshydra/basrelief/core/render/ReliefMesh.java',
    },
  ];

  for (const p of pairs) {
    const w = readFraction(p.web, p.name);
    const a = readFraction(p.android, p.name);
    if (w === null) {
      failures.push(`could not read ${p.name} from ${p.web} — update this script`);
      continue;
    }
    if (a === null) {
      failures.push(`could not read ${p.name} from ${p.android} — update this script`);
      continue;
    }
    if (Math.abs(w - a) > 1e-12) {
      failures.push(
        `${p.name} differs: web 1/${Math.round(1 / w)} vs android 1/${Math.round(1 / a)} ` +
          `(${p.why})`
      );
    } else {
      console.log(`parity: ${p.name} = 1/${Math.round(1 / w)} in both ports`);
    }
  }
}

checkDocLinks();
checkParity();

if (failures.length) {
  console.error(`\ncheck-parity: ${failures.length} problem(s)\n`);
  for (const f of failures) console.error(`  - ${f}`);
  process.exit(1);
}
console.log('\ncheck-parity: OK');
