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
import { readFileSync, existsSync, readdirSync, statSync } from 'node:fs';
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
  let hostPaths = 0;
  let platform = 0;

  // Directories at the top of an Android/AOSP tree. AGENTS.md cites platform
  // sources when it explains a platform limitation, and those paths are not
  // supposed to exist in this repository — checking them would be checking that
  // someone cloned AOSP.
  const AOSP_ROOTS = /^(core|frameworks|services|packages|libs|cmds|apex|art|base|tests)\//;

  for (const span of pathish) {
    // Not repo paths: absolute host/device locations (/tmp, /usr/bin/env),
    // ~ and $VAR expansions, and shebangs. Their absence here says nothing.
    if (/^[~$#/]/.test(span) || span.includes('$')) {
      hostPaths++;
      continue;
    }
    if (AOSP_ROOTS.test(span)) {
      platform++;
      continue;
    }
    // API family shorthand: `setTranslationX/Y`, `width/height`. Two bare
    // identifiers, and the first is not a directory in this tree, so it is a
    // method pair and not a path.
    const asPair = span.split('/');
    if (asPair.length === 2 && asPair.every((p) => /^[A-Za-z_][A-Za-z0-9_]*$/.test(p))) {
      const bases = [root, join(root, 'android')];
      const firstIsDir = bases.some((b) => {
        const s = join(b, asPair[0]);
        return existsSync(s) && statSync(s).isDirectory();
      });
      if (!firstIsDir) {
        platform++;
        continue;
      }
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
  console.log(
    `doc links: ${seen.size} repo paths checked, ` +
      `${hostPaths} host paths skipped, ${platform} platform references skipped`
  );
}

// --------------------------------------------------------------- test counts

/**
 * AGENTS.md states how many tests there are and lists the classes. That went
 * stale the moment a class was added — the file claimed "20 tests across four
 * classes" while the tree had 48 across six — because a hand-maintained count
 * is exactly the kind of claim nothing checks. Derive it instead.
 */
function checkTestCounts() {
  const doc = readFileSync(join(root, 'AGENTS.md'), 'utf8');
  const claimed = doc.match(/(\d+)\s+tests across (\w+)\s+classes/);
  if (!claimed) {
    failures.push(
      'AGENTS.md no longer states "N tests across M classes" — update this script'
    );
    return;
  }

  const testDir = join(root, 'android/core/src/test/java/com/amoshydra/basrelief/core');
  const classes = [];
  let methods = 0;
  for (const dir of readdirSync(testDir, { withFileTypes: true })) {
    if (!dir.isDirectory()) continue;
    for (const f of readdirSync(join(testDir, dir.name))) {
      if (!f.endsWith('Test.java')) continue;
      classes.push(f.replace('.java', ''));
      methods += (readFileSync(join(testDir, dir.name, f), 'utf8').match(/@Test/g) || []).length;
    }
  }

  // AGENTS.md spells small numbers out ("six classes"), so compare words for the
  // class count and digits for the test count.
  // Spelled out, because that is how the sentence reads. Past ten the list has
  // to keep going or the check breaks on the eleventh class -- and a guard that
  // breaks when the thing it guards grows is a guard that gets disabled, which is
  // how a hand-maintained count comes back.
  const words = ['zero', 'one', 'two', 'three', 'four', 'five', 'six', 'seven',
    'eight', 'nine', 'ten', 'eleven', 'twelve', 'thirteen', 'fourteen', 'fifteen',
    'sixteen', 'seventeen', 'eighteen', 'nineteen', 'twenty'];
  if (+claimed[1] !== methods) {
    failures.push(
      `AGENTS.md says ${claimed[1]} tests but the tree has ${methods} @Test methods`
    );
  }
  if (words[classes.length] !== claimed[2]) {
    failures.push(
      `AGENTS.md says ${claimed[2]} test classes but the tree has ${classes.length}: ` +
        classes.sort().join(', ')
    );
  } else {
    console.log(`test counts: ${methods} tests across ${classes.length} classes — matches AGENTS.md`);
  }
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

// --------------------------------------------------------- modal architecture

/**
 * Strip comments before matching, so prose ABOUT the old shape ("was a `new
 * Dialog(`") is not mistaken for the shape itself. Crude on string literals
 * containing `//`, which is acceptable: the only thing this is used for is
 * structural keywords, and a stripped rest-of-line cannot hide a real one.
 */
function stripJavaComments(text) {
  return text.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');
}

/**
 * The four defects this guards are one defect: the same visual built more than
 * once, so a fix lands in one copy and not the others. Habits cannot enforce
 * that; the two exact claims can be checked, so they are:
 *
 *   1. `new Dialog(` exists once, in ModalSheet.java. A second one means a
 *      second scrim alpha, a second nav-bar inset listener and a second drag
 *      implementation, which is how the four modals drifted apart.
 *   2. `plainRow` does not exist. Its optional tap is what let Reset (a
 *      destructive button) and "Basrelief 0.1.0" (a fact) be the same picture;
 *      the three factories that replace it each carry exactly one affordance.
 *
 * The third claim — "every row is built by one of the three factories" — is
 * deliberately NOT checked, and this says so rather than shipping a plausible
 * guard: a row is not a type, so a mechanical count would either fail on
 * correct code (the playlists screen's photo rows, the fix line's buttons) or
 * pass on the next hand-rolled row someone writes. A guard that can only look
 * like it works is worse than none.
 */
function checkModalInvariants() {
  const appDir = join(root, 'android/app/src/main/java/com/amoshydra/basrelief');
  const files = readdirSync(appDir).filter((f) => f.endsWith('.java'));
  const extraDialogs = [];
  const plainRows = [];

  for (const f of files) {
    const text = stripJavaComments(readFileSync(join(appDir, f), 'utf8'));
    if (f !== 'ModalSheet.java' && /new\s+(?:android\.app\.)?Dialog\s*\(/.test(text)) {
      extraDialogs.push(f);
    }
    if (/\bplainRow\b/.test(text)) plainRows.push(f);
  }

  if (extraDialogs.length) {
    failures.push(
      `new Dialog( outside ModalSheet.java in: ${extraDialogs.join(', ')} — ` +
        `every modal is ModalSheet, or the scrim, inset and drag code comes ` +
        `back a third time`
    );
  } else {
    console.log('modals: one Dialog, in ModalSheet.java');
  }

  if (plainRows.length) {
    failures.push(
      `plainRow in: ${plainRows.join(', ')} — it drew tappable and reading rows ` +
        `identically; use switchRow, actionRow or readingRow`
    );
  } else {
    console.log('row roles: no plainRow, and the three factories exist');
  }

  for (const factory of ['switchRow', 'actionRow', 'readingRow']) {
    if (!new RegExp(`\\b${factory}\\s*\\(`).test(
      readFileSync(join(appDir, 'ModalSheet.java'), 'utf8'))) {
      failures.push(
        `ModalSheet.${factory} is missing — the row-role rule needs all three factories`
      );
    }
  }
  console.log(
    'row roles: rows built outside the three factories are not counted — a row ' +
      'is not a type, and a guessed count would be a guard that only looks plausible'
  );
}

checkDocLinks();
checkTestCounts();
checkParity();
checkModalInvariants();

if (failures.length) {
  console.error(`\ncheck-parity: ${failures.length} problem(s)\n`);
  for (const f of failures) console.error(`  - ${f}`);
  process.exit(1);
}
console.log('\ncheck-parity: OK');
