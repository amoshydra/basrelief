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
 *      fraction, and if the copies disagree the artifact comes back on one
 *      platform only. There are now THREE copies — the Android renderer, the
 *      reference port and the gallery in `web/` — and nothing in any build
 *      enforced this before.
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
  const claimed = doc.match(/(\d+)\s+tests across ([\w-]+)\s+classes/);
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
  //
  // DERIVED, not a list. This was a hardcoded array ending at "twenty", so the
  // twenty-first class made the regex fail to match at all and the guard reported
  // "AGENTS.md no longer states N tests across M classes" -- a guard that breaks
  // when the thing it guards grows is a guard that gets disabled, which is how a
  // hand-maintained count comes back. Spelling it out keeps the sentence readable
  // and removes the ceiling; digits are accepted too, so the file may switch.
  const ONES = ['zero', 'one', 'two', 'three', 'four', 'five', 'six', 'seven',
    'eight', 'nine', 'ten', 'eleven', 'twelve', 'thirteen', 'fourteen', 'fifteen',
    'sixteen', 'seventeen', 'eighteen', 'nineteen'];
  const TENS = ['', '', 'twenty', 'thirty', 'forty', 'fifty', 'sixty', 'seventy',
    'eighty', 'ninety'];
  const spell = (n) => {
    if (n < ONES.length) return ONES[n];
    if (n < 100) {
      const tens = TENS[Math.floor(n / 10)];
      const ones = n % 10;
      return ones ? `${tens}-${ONES[ones]}` : tens;
    }
    return String(n);
  };
  const before = failures.length;
  if (+claimed[1] !== methods) {
    failures.push(
      `AGENTS.md says ${claimed[1]} tests but the tree has ${methods} @Test methods`
    );
  }
  if (claimed[2] !== String(classes.length) && claimed[2] !== spell(classes.length)) {
    failures.push(
      `AGENTS.md says ${claimed[2]} test classes but the tree has ${classes.length}: ` +
        classes.sort().join(', ')
    );
  }
  // "matches AGENTS.md" only when BOTH counts agree, and never from inside one
  // check's else-branch. It was: the line was printed by the CLASS check's happy
  // path while the TEST check had already pushed a failure, so a stale count printed
  // "matches AGENTS.md" and exited 1. That is a guard congratulating itself while
  // failing, and it is how a guard gets ignored -- which is the whole reason this
  // check exists. It was read twice as green, by me, grepping for "OK".
  if (failures.length === before) {
    console.log(`test counts: ${methods} tests across ${classes.length} classes — matches AGENTS.md`);
  } else {
    console.log(`test counts: tree has ${methods} tests across ${classes.length} classes — AGENTS.md disagrees`);
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
      // Every copy of the effect. The reference port and the gallery are
      // separate apps on purpose; this constant is not allowed to drift.
      web: ['reference/webgl-port/src/scene.js', 'web/src/relief.ts'],
      android:
        'android/core/src/main/java/com/amoshydra/basrelief/core/render/ReliefMesh.java',
    },
  ];

  for (const p of pairs) {
    const a = readFraction(p.android, p.name);
    if (a === null) {
      failures.push(`could not read ${p.name} from ${p.android} — update this script`);
      continue;
    }
    for (const file of p.web) {
      const w = readFraction(file, p.name);
      if (w === null) {
        failures.push(`could not read ${p.name} from ${file} — update this script`);
        continue;
      }
      if (Math.abs(w - a) > 1e-12) {
        failures.push(
          `${p.name} differs: ${file} has 1/${Math.round(1 / w)} vs android ` +
            `1/${Math.round(1 / a)} (${p.why})`,
        );
      } else {
        console.log(
          `parity: ${p.name} = 1/${Math.round(1 / w)} in ${file} and the Android renderer`,
        );
      }
    }
  }
}

// ------------------------------------------------------ settings contract

/** Read a numeric constant, allowing a leading `-` for a symmetric bound. */
function readNumber(file, expression) {
  if (expression === null) return null;
  const negate = expression.startsWith('-');
  const name = negate ? expression.slice(1) : expression;
  const text = readFileSync(join(root, file), 'utf8');
  const m = text.match(new RegExp(`\\b${name}\\s*=\\s*(-?[0-9]+(?:\\.[0-9]+)?)f?\\s*[,;]`));
  if (!m) return null;
  const value = Number(m[1]);
  return negate ? -value : value;
}

/**
 * The settings body's RANGES, held across the fixture and both readers.
 *
 * This is the guard `contract/settings/cases.json` exists for. The fixture is
 * the shared statement; `SurfaceSettings`/`Splat`/`LightDirection` own the app's
 * copies, and `web/src/settings.ts` owns the sheet's. The failure this prevents
 * is not a crash: a Size slider that reaches 0.9 where the app stops at 0.8 lets
 * a visitor tune a picture the phone cannot produce, and each side's own tests
 * pass because each side agrees with itself.
 *
 * The two light DEFAULTS are deliberately absent: they are derived from the
 * historical vector `(-0.35, 0.5, 0.75)` in both languages, so there is no
 * literal to compare. `web/src/picture.test.ts` holds the derivation to the
 * vector, which is the invariant that stops an upgrade relighting every photo.
 */
function checkSettingsParity() {
  const fixturePath = 'contract/settings/cases.json';
  const cases = JSON.parse(readFileSync(join(root, fixturePath), 'utf8'));
  const SS = 'android/core/src/main/java/com/amoshydra/basrelief/core/store/SurfaceSettings.java';
  const SPLAT = 'android/core/src/main/java/com/amoshydra/basrelief/core/render/Splat.java';
  const LIGHT = 'android/core/src/main/java/com/amoshydra/basrelief/core/render/LightDirection.java';
  const WEB = 'web/src/settings.ts';

  // fixture range -> the three Java constants and the three TypeScript ones.
  // `null` is a bound one side states structurally rather than as a constant:
  // Splat has no BLUR_MIN (its clamp answers the identity below 0), and the
  // light's defaults are derived angles rather than literals.
  const shared = [
    { range: 'depth', java: [SS, 'DEPTH_MIN', 'DEPTH_MAX', 'DEFAULT_DEPTH'], web: ['DEPTH_MIN', 'DEPTH_MAX', 'DEPTH_DEFAULT'] },
    { range: 'zoom', java: [SS, 'ZOOM_MIN', 'ZOOM_MAX', 'DEFAULT_ZOOM'], web: ['ZOOM_MIN', 'ZOOM_MAX', 'ZOOM_DEFAULT'] },
    { range: 'motion', java: [SS, 'MOTION_MIN', 'MOTION_MAX', 'DEFAULT_MOTION'], web: ['MOTION_MIN', 'MOTION_MAX', 'MOTION_DEFAULT'] },
    { range: 'adjust', java: [SS, 'ADJUST_MIN', 'ADJUST_MAX', 'DEFAULT_ADJUST'], web: ['ADJUST_MIN', 'ADJUST_MAX', 'ADJUST_DEFAULT'] },
    { range: 'origin', java: [SS, '-ORIGIN_LIMIT', 'ORIGIN_LIMIT', 'DEFAULT_ORIGIN'], web: ['-ORIGIN_LIMIT', 'ORIGIN_LIMIT', 'ORIGIN_DEFAULT'] },
    { range: 'panZ', java: [SS, 'PAN_Z_MIN', 'PAN_Z_MAX', 'DEFAULT_PAN_Z'], web: ['PAN_Z_MIN', 'PAN_Z_MAX', 'PAN_Z_DEFAULT'] },
    { range: 'speed', java: [SS, 'SPEED_MIN', 'SPEED_MAX', 'DEFAULT_SPEED'], web: ['SPEED_MIN', 'SPEED_MAX', 'SPEED_DEFAULT'] },
    { range: 'splatDepth', java: [SPLAT, 'DEPTH_MUL_MIN', 'DEPTH_MUL_MAX', 'DEPTH_MUL_DEFAULT'], web: ['SPLAT_DEPTH_MIN', 'SPLAT_DEPTH_MAX', 'SPLAT_DEPTH_DEFAULT'] },
    { range: 'splatEps2d', java: [SPLAT, 'EPS_2D_MIN', 'EPS_2D_MAX', 'EPS_2D_DEFAULT'], web: ['SPLAT_EPS2D_MIN', 'SPLAT_EPS2D_MAX', 'SPLAT_EPS2D_DEFAULT'] },
    { range: 'splatBlur', java: [SPLAT, null, 'BLUR_MAX', 'BLUR_DEFAULT'], web: ['SPLAT_BLUR_MIN', 'SPLAT_BLUR_MAX', 'SPLAT_BLUR_DEFAULT'] },
    { range: 'splatScale', java: [SPLAT, 'RENDER_SCALE_MIN', 'RENDER_SCALE_MAX', 'RENDER_SCALE_DEFAULT'], web: ['SPLAT_SCALE_MIN', 'SPLAT_SCALE_MAX', 'SPLAT_SCALE_DEFAULT'] },
    { range: 'azimuth', java: [LIGHT, 'AZIMUTH_MIN', 'AZIMUTH_MAX', null], web: ['AZIMUTH_MIN', 'AZIMUTH_MAX', null] },
    { range: 'elevation', java: [LIGHT, 'ELEVATION_MIN', 'ELEVATION_MAX', null], web: ['ELEVATION_MIN', 'ELEVATION_MAX', null] },
  ];

  let checked = 0;
  for (const entry of shared) {
    const range = cases.ranges[entry.range];
    if (!range) {
      failures.push(`${fixturePath} lost its "${entry.range}" range — the settings schema needs it`);
      continue;
    }
    const [javaFile, javaMin, javaMax, javaDef] = entry.java;
    const [webMin, webMax, webDef] = entry.web;
    const sides = [
      ['min', range.min, javaMin, webMin],
      ['max', range.max, javaMax, webMax],
      ['default', range.default, javaDef, webDef],
    ];
    for (const [which, expected, javaExpr, webExpr] of sides) {
      if (expected === undefined && javaExpr === null && webExpr === null) continue;
      if (expected === undefined) {
        failures.push(`range ${entry.range}: the fixture has no ${which}`);
        continue;
      }
      // A null expression is a bound that side states structurally rather than
      // as a named constant (Splat's blur has no minimum constant because its
      // clamp answers the identity below 0), so there is nothing to compare.
      if (javaExpr !== null) {
        const javaValue = readNumber(javaFile, javaExpr);
        if (javaValue === null) {
          failures.push(`could not read ${entry.range} ${which} from ${javaFile} — update this script`);
        } else if (javaValue !== expected) {
          failures.push(
            `range ${entry.range} ${which}: android has ${javaValue}, the fixture says ${expected} ` +
              `(${range.why ?? 'the settings contract needs the same number on both sides'})`,
          );
        } else {
          checked++;
        }
      }
      if (webExpr !== null) {
        const webValue = readNumber(WEB, webExpr);
        if (webValue === null) {
          failures.push(`could not read ${entry.range} ${which} (${webExpr}) from ${WEB}`);
        } else if (webValue !== expected) {
          failures.push(
            `range ${entry.range} ${which}: web/src/settings.ts has ${webValue}, the fixture says ` +
              `${expected} — a slider whose bound differs from the app's tunes a picture the phone cannot make`,
          );
        }
        checked++;
      }
    }
  }

  // The body belongs to a versioned blob. A schema bump that adds a settings
  // field must reach the fixture and the web reader in the same commit.
  const libraryVersion = readNumber(
    'android/core/src/main/java/com/amoshydra/basrelief/core/store/Library.java',
    'VERSION',
  );
  const webSchema = readNumber(WEB, 'SETTINGS_SCHEMA');
  if (libraryVersion === null || webSchema === null) {
    failures.push('could not read Library.VERSION or SETTINGS_SCHEMA — update this script');
  } else if (cases.schema !== libraryVersion || cases.schema !== webSchema) {
    failures.push(
      `the settings fixture says schema ${cases.schema}, Library.VERSION is ${libraryVersion} and ` +
        `SETTINGS_SCHEMA is ${webSchema} — the settings body and the blob it lives in are one version`,
    );
  } else {
    console.log(`parity: settings schema ${cases.schema}, ${checked} range bounds across Java and web`);
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
 * The tilt smoother has ONE home: {@code TiltFilter}, in :core.
 *
 * <p>Two callers step it — {@code ReliefRenderer}, which smooths the tilt it
 * draws with, and {@code TiltController}, whose doze gate must measure the DRAWN
 * value rather than the raw reading. A second copy of the step or of its
 * constants in either caller would be a second answer to one question, and it
 * would fail silently: the gate would keep working, just around the wrong
 * threshold, with the picture visibly stepping when it woke. No :core test can
 * see :app and no JVM gate runs the shader-free GL path, so the claim is checked
 * where it can be — on the text of the two files that are allowed to step it.
 *
 * <p>The ban is on the step's ARITHMETIC ({@code Math.exp}), not on the word:
 * both callers must step {@code TiltFilter}, and no :app file may declare a copy
 * of the two constants. {@code SpringSettle}'s own {@code Math.exp} is a different
 * thing (the sheet's spring), so only these two files are checked for it.
 */
function checkTiltSmoothing() {
  const appDir = join(root, 'android/app/src/main/java/com/amoshydra/basrelief');
  const before = failures.length;
  for (const f of ['ReliefRenderer.java', 'TiltController.java']) {
    const text = stripJavaComments(readFileSync(join(appDir, f), 'utf8'));
    if (text.includes('Math.exp(')) {
      failures.push(`${f} derives a smoothing step of its own (Math.exp) — ` +
        `the tilt filter lives in :core/TiltFilter, and two copies drift`);
    }
    for (const call of ['TiltFilter.approach(', 'TiltFilter.alphaFor(']) {
      if (!text.includes(call)) {
        failures.push(`${f} does not call ${call} — the doze gate and the draw ` +
          `must step the SAME filter`);
      }
    }
  }
  for (const f of readdirSync(appDir).filter((n) => n.endsWith('.java'))) {
    const text = stripJavaComments(readFileSync(join(appDir, f), 'utf8'));
    if (/(SMOOTHING_TAU|ATTACK_ERROR)\s*=/.test(text)) {
      failures.push(`${f} declares a copy of SMOOTHING_TAU or ATTACK_ERROR — ` +
        `the tilt filter's constants have one home, in :core/TiltFilter`);
    }
  }
  if (failures.length === before) {
    console.log('tilt filter: one step and one set of constants, stepped by both callers');
  }
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

/**
 * A rule implemented more than once is held together by `contract/` ONLY while
 * both copies exist, both are tested against the fixture, and neither grows a
 * second implementation. The fixtures are run by four different jobs in four
 * languages; nothing in this script can run them, so what it can check is the
 * shape that makes them meaningful:
 *
 *   1. the fixture files exist (a test that silently skips is a green build);
 *   2. the node has ONE tag rule, imported from contract.py — it used to hold two
 *      (the `_safe_tag` function and a second inline copy in MoGeGeometryNPZ);
 *   3. the Java copy uses the ASCII predicate, not `Character.isLetterOrDigit`,
 *      which is the half of the pair that disagreed with Python's `isalnum()`;
 *   4. the app's sidecar reader is the one :core tests — an inline `new JSONObject`
 *      in SplatImport would leave `SplatSidecar` tested but unused, which is a test
 *      of dead code;
 *   5. the 32-byte record has BOTH readers tested against `contract/splat/` —
 *      Java's `SplatCloud` and the gallery's `web/src/splat.ts`. The record used to
 *      be deliberately unfixtured because there was one implementation; there are
 *      two now, and a fixture only one side reads is not a contract.
 */
function checkSharedRules() {
  const before = failures.length;
  for (const f of [
    'contract/README.md',
    'contract/tag/cases.json',
    'contract/deph/cases.json',
    'contract/deph/png-depth.test.mjs',
    'contract/sidecar/cases.json',
    'contract/splat/cases.json',
    'contract/splat/records.bin',
    'contract/splat/generate.mjs',
    'contract/settings/cases.json',
    'server/comfy-node/tests/test_contract.py',
    'android/core/src/test/java/com/amoshydra/basrelief/core/contract/TagContractTest.java',
    'android/core/src/test/java/com/amoshydra/basrelief/core/contract/SplatRecordContractTest.java',
    'android/core/src/test/java/com/amoshydra/basrelief/core/contract/SidecarContractTest.java',
    'web/src/deph.ts',
    'web/src/deph.test.ts',
    'web/src/pack.ts',
    'web/src/pack.test.ts',
    'contract/deph/pack-colour.png',
    'contract/deph/pack-raw.png',
    'contract/deph/generate.mjs',
    'web/src/splat.ts',
    'web/src/splat.test.ts',
    'web/src/settings.ts',
    'web/src/settings.test.ts',
    'web/src/picture.ts',
    'web/src/picture.test.ts',
    'web/src/sheet.ts',
    'web/README.md',
    'web/package.json',
    'web/pnpm-lock.yaml',
  ]) {
    if (!existsSync(join(root, f))) {
      failures.push(`${f} is missing — the shared contract fixtures need every half`);
    }
  }

  const nodes = readFileSync(
    join(root, 'server/comfy-node/basrelief_depth/nodes.py'), 'utf8');
  if (nodes.includes('isalnum(')) {
    failures.push(
      'nodes.py runs its own tag rule again (isalnum) — the rule lives in ' +
        'basrelief_depth/contract.py, tested against contract/tag/cases.json, and a ' +
        'second copy is a second answer to one question');
  }
  if (!nodes.includes('from .contract import')) {
    failures.push('nodes.py does not import the shared rules from .contract');
  }

  const comfy = stripJavaComments(readFileSync(
    join(root, 'android/core/src/main/java/com/amoshydra/basrelief/core/depth/ComfyWorkflow.java'),
    'utf8'));
  if (/Character\s*\.\s*isLetterOrDigit/.test(comfy)) {
    failures.push(
      'ComfyWorkflow.streamTag uses Character.isLetterOrDigit again — that is ' +
        'Unicode L*+Nd, and the Python copy it must match accepted N* too; the tag ' +
        'alphabet is ASCII (contract/tag/cases.json says why)');
  }

  const splatImport = readFileSync(
    join(root, 'android/app/src/main/java/com/amoshydra/basrelief/SplatImport.java'), 'utf8');
  if (!splatImport.includes('SplatSidecar.parse(')) {
    failures.push(
      'SplatImport does not read the sidecar through SplatSidecar — the :core ' +
        'contract test would then be testing a reader nothing uses');
  }

  /**
   * The splat fixture's hardest cases, held the way `TagContractTest` holds the
   * tag table's: a fixture that quietly lost the record it exists for is a rule
   * with no guard, and the loss is silent on both sides.
   */
  const splatCases = JSON.parse(readFileSync(join(root, 'contract/splat/cases.json'), 'utf8'));
  const records = Array.isArray(splatCases.recordsList) ? splatCases.recordsList : [];
  for (const required of ['first', 'bright', 'opaque', 'padding']) {
    if (!records.some((r) => r.name === required)) {
      failures.push(
        `contract/splat/cases.json lost its "${required}" record — every one of the ` +
          'four states the fixture pins is load-bearing (see contract/README.md)');
    }
  }
  const bright = records.find((r) => r.name === 'bright');
  if (!bright || bright.colourWordIsNaNAsFloat !== true
      || !Array.isArray(bright.color) || bright.color[3] < 128 || bright.color[2] < 128) {
    failures.push(
      'contract/splat\'s bright record no longer claims a float-NaN colour word — that ' +
        'record is the whole reason the integer texture path is not the float one');
  }

  if (failures.length === before) {
    console.log('shared rules: contract/ fixtures present, one tag rule per side');
  }
}

checkDocLinks();
checkTestCounts();
checkParity();
checkSettingsParity();
checkModalInvariants();
checkTiltSmoothing();
checkSharedRules();

if (failures.length) {
  console.error(`\ncheck-parity: ${failures.length} problem(s)\n`);
  for (const f of failures) console.error(`  - ${f}`);
  process.exit(1);
}
console.log('\ncheck-parity: OK');
