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

  // Artefacts the build makes and `.gitignore` excludes. A fresh clone does not
  // have them — `podman-build.sh build-image` generates the debug keystore on the
  // host — so requiring them here fails CI while passing on the machine that
  // built the image. That is the worst shape a guard can take: green where it is
  // developed, red where it matters.
  const GENERATED = new Set(['android/debug.keystore', 'debug.keystore']);
  let generated = 0;

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
    if (GENERATED.has(span)) {
      generated++;
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
      `${hostPaths} host paths skipped, ${platform} platform references skipped, ` +
      `${generated} generated artefacts tolerated`
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

// ------------------------------------------------------ library contract

/**
 * The gallery index, held across the fixture, the app's own writers and the
 * gallery's copy.
 *
 * This is the guard `contract/library/cases.json` exists for, and it is the same
 * shape as the settings body's: the fixture is the shared statement, and two
 * copies that cannot share code are held to it. What this script can see is the
 * *shape* — the schema number, and the key set each side writes — because no JVM
 * test reads this fixture and nothing here can run TypeScript. The bodies
 * themselves are round-tripped by `web/src/library.test.ts`, and the byte-for-byte
 * claim between the two languages is the fixture's job on the next Java test.
 *
 * The failure this prevents is not a crash. A reader that dropped `video` is a
 * mirror that copies no files for a clip; a writer that emitted `focal: 0` is
 * every stored row changing on save; a schema number that drifted is an older
 * build deciding a newer blob is its own and overwriting somebody's gallery.
 */
function checkLibraryParity() {
  const fixturePath = 'contract/library/cases.json';
  const cases = JSON.parse(readFileSync(join(root, fixturePath), 'utf8'));
  const LIB = 'android/core/src/main/java/com/amoshydra/basrelief/core/store/Library.java';
  const ITEM = 'android/core/src/main/java/com/amoshydra/basrelief/core/store/LibraryItem.java';
  const SORT = 'android/core/src/main/java/com/amoshydra/basrelief/core/store/SortOrder.java';
  const SURFACE = 'android/core/src/main/java/com/amoshydra/basrelief/core/store/Surface.java';
  const WEB = 'web/src/library.ts';

  const read = (file) => readFileSync(join(root, file), 'utf8');
  const libText = read(LIB);
  const itemText = read(ITEM);
  const sortText = read(SORT);
  const surfaceText = read(SURFACE);
  const webText = read(WEB);
  const before = failures.length;

  const libraryVersion = readNumber(LIB, 'VERSION');
  const webSchema = readNumber(WEB, 'LIBRARY_SCHEMA');
  if (libraryVersion === null || webSchema === null) {
    failures.push('could not read Library.VERSION or LIBRARY_SCHEMA — update this script');
  } else if (cases.schema !== libraryVersion || cases.schema !== webSchema) {
    failures.push(
      `the library fixture says schema ${cases.schema}, Library.VERSION is ${libraryVersion} and ` +
        `LIBRARY_SCHEMA is ${webSchema} — the index and the reader that mirrors it are one version`,
    );
  }

  const checkKeys = (keys, javaText, javaFile, what) => {
    const seen = new Set();
    for (const key of keys) {
      if (typeof key !== 'string' || key.length === 0) {
        failures.push(`${fixturePath}: ${what} has a key that is not a name`);
        continue;
      }
      if (seen.has(key)) failures.push(`${fixturePath}: ${what} lists "${key}" twice`);
      seen.add(key);
      if (!javaText.includes(`put("${key}"`)) {
        failures.push(
          `${javaFile} does not write "${key}" — ${fixturePath} says the ${what} has it, and a ` +
            'key one side writes and the other drops is a value that disappears on a round trip',
        );
      }
      // A presence check, not a structural one: this script cannot parse
      // TypeScript. The writer's actual key placement is pinned by the round trip
      // in web/src/library.test.ts, and the lists it looks for here are the
      // exported BLOB_KEYS / ITEM_KEYS that same test holds to this fixture.
      if (!webText.includes(`"${key}"`) && !webText.includes(`'${key}'`)) {
        failures.push(
          `${WEB} never mentions "${key}" — the gallery’s copy of the index has no such field`,
        );
      }
    }
  };
  checkKeys(cases.blobKeys, libText, LIB, 'blob');
  checkKeys(cases.itemKeys, itemText, ITEM, 'item');

  const javaSorts = [...sortText.matchAll(/[A-Z_]+\(\s*"([a-z_]+)"\s*\)/g)].map((m) => m[1]);
  const fixtureSorts = [...cases.sortKeys].sort();
  if (javaSorts.length === 0) {
    failures.push(`could not read SortOrder’s stored keys from ${SORT} — update this script`);
  } else if (JSON.stringify([...javaSorts].sort()) !== JSON.stringify(fixtureSorts)) {
    failures.push(
      `SortOrder's stored keys are ${javaSorts.join(', ')} but ${fixturePath} says ` +
        `${fixtureSorts.join(', ')} — a sort the phone writes and the page reads as "newest" is a ` +
        'list order that changes on a restore',
    );
  }
  for (const key of fixtureSorts) {
    if (!webText.includes(`'${key}'`)) {
      failures.push(`${WEB} has no sort key '${key}' — the reader would fall back to the default`);
    }
  }

  const javaSurfaces = [...surfaceText.matchAll(/[A-Z_]+\(\s*"([a-z_]+)"\s*\)/g)].map((m) => m[1]);
  const fixtureSurfaces = [...cases.surfaceKeys].sort();
  if (JSON.stringify([...javaSurfaces].sort()) !== JSON.stringify(fixtureSurfaces)) {
    failures.push(
      `Surface's stored keys are ${javaSurfaces.join(', ') || '(none)'} but ${fixturePath} says ` +
        `${fixtureSurfaces.join(', ')}`,
    );
  }
  for (const key of fixtureSurfaces) {
    if (!webText.includes(`'${key}'`)) {
      failures.push(`${WEB} has no surface key '${key}'`);
    }
  }

  if (failures.length === before) {
    console.log(
      `parity: library schema ${cases.schema}, ${cases.blobKeys.length} blob keys and ` +
        `${cases.itemKeys.length} item keys across Java and web`,
    );
  }
}

// ------------------------------------------------------ ComfyUI prefs parity

/**
 * The ComfyUI prefs, held across the app's `SharedPreferences` keys and the
 * gallery's `prefs.json`.
 *
 * The page stores what the phone stores, so a file copied between them has to
 * mean the same thing: one key spelled differently is a value that silently
 * disappears, and a history capped at a different depth is a chooser that orders
 * the two sides differently. Like the library's key-set claim, this checks the
 * shape and the one constant, because nothing here can run TypeScript or Java —
 * the values themselves are `web/src/prefs.test.ts`'s.
 */
function checkComfyPrefsParity() {
  const JAVA = 'android/app/src/main/java/com/amoshydra/basrelief/BuildDialog.java';
  const WEB = 'web/src/prefs.ts';
  const RANKING =
    'android/core/src/main/java/com/amoshydra/basrelief/core/depth/ModelRanking.java';
  const javaText = readFileSync(join(root, JAVA), 'utf8');
  const webText = readFileSync(join(root, WEB), 'utf8');
  const before = failures.length;

  const javaKey = (constant) => {
    const match = javaText.match(new RegExp(`${constant}\\s*=\\s*"([^"]+)"`));
    return match ? match[1] : null;
  };
  const javaKeys = [
    javaKey('KEY_HOST'),
    javaKey('KEY_MODEL'),
    javaKey('KEY_MODEL_HISTORY'),
  ];
  if (javaKeys.some((key) => key === null)) {
    failures.push(
      `could not read KEY_HOST/KEY_MODEL/KEY_MODEL_HISTORY from ${JAVA} — update this script`,
    );
  } else {
    const list = webText.match(/PREF_KEYS\s*=\s*\[([^\]]*)\]/);
    const webKeys = list
      ? [...list[1].matchAll(/'([^']+)'|"([^"]+)"/g)].map((m) => m[1] ?? m[2])
      : [];
    const java = [...javaKeys].sort();
    const web = [...webKeys].sort();
    if (JSON.stringify(java) !== JSON.stringify(web)) {
      failures.push(
        `the ComfyUI prefs keys are ${java.join(', ')} in ${JAVA} but ` +
          `${web.join(', ') || '(none)'} in ${WEB} — the page stores what the phone ` +
          'stores, or a copied prefs file means something different on each side',
      );
    }
    for (const key of javaKeys) {
      if (!webText.includes(`'${key}'`)) {
        failures.push(`${WEB} never names the pref key '${key}'`);
      }
    }
  }

  const javaCap = readNumber(RANKING, 'HISTORY_CAP');
  const webCap = readNumber(WEB, 'MODEL_HISTORY_CAP');
  if (javaCap === null || webCap === null) {
    failures.push('could not read ModelRanking.HISTORY_CAP or MODEL_HISTORY_CAP — update this script');
  } else if (javaCap !== webCap) {
    failures.push(
      `ModelRanking.HISTORY_CAP is ${javaCap} but web/src/prefs.ts caps the history at ` +
        `${webCap} — the chooser would order the same server differently on each side`,
    );
  }

  if (failures.length === before) {
    console.log(
      `parity: ComfyUI prefs keys match BuildDialog and the history cap is ${webCap}`,
    );
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

// ------------------------------------------------------- the sheet's numbers

/**
 * Read `NAME = <literal>` out of a source file and resolve it to a number.
 *
 * `readNumber` above refuses a derived value, and half of the sheet's constants
 * are derived on purpose (`SHAPE_BAND_START` IS the collapse threshold on both
 * sides; `PILL_RADIUS` is half the strip). This follows ONE level of indirection
 * and reads hex, decimal and a-b-over-c, so a number is compared as a number and
 * a derivation is still compared — a copy that hard-coded 0.95 where the app
 * derives it would agree today and drift the day the app's policy moved.
 */
function readConstant(file, name, depth = 0) {
  const text = readFileSync(join(root, file), 'utf8');
  const m = text.match(new RegExp(`\\b${name}\\s*=\\s*([^;\\n]+)`));
  if (!m) return null;
  const token = m[1].replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/, '').trim();
  if (/^-?0x[0-9a-f]+$/i.test(token)) return parseInt(token, 16);
  if (/^-?\d+(\.\d+)?[fLd]?$/.test(token)) return Number(token.replace(/[fLd]$/, ''));
  const fraction = token.match(/^(\d+(?:\.\d+)?)[fLd]?\s*\/\s*(\d+(?:\.\d+)?)[fLd]?$/);
  if (fraction) return Number(fraction[1]) / Number(fraction[2]);
  // A derived constant: follow it once (or twice) rather than give up.
  if (/^[A-Za-z_][A-Za-z0-9_]*$/.test(token) && depth < 2) {
    return readConstant(file, token, depth + 1);
  }
  return null;
}

/** A stored string constant, e.g. `KEY_SHEET = "sheet_pos"`. */
function readStringConstant(file, name) {
  const text = readFileSync(join(root, file), 'utf8');
  const m = text.match(new RegExp(`\\b${name}\\s*=\\s*["']([^"']+)["']`));
  return m ? m[1] : null;
}

/** `new Policy(a, b)` / `new Profile(a, b, c, d, e, f)`: its numbers, in order. */
function readConstructed(file, name, className) {
  const text = readFileSync(join(root, file), 'utf8');
  const m = text.match(new RegExp(`\\b${name}\\s*=\\s*new\\s+${className}\\s*\\(([^)]*)\\)`));
  if (!m) return null;
  return m[1]
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .split(',')
    .map((part) => part.replace(/[fLd]/g, '').trim())
    .filter((part) => part.length > 0)
    .map(Number);
}

/** One `prop: Npx` out of a CSS rule, so a layout constant is not "compared by eye". */
function readCssPx(selector, property) {
  const text = readFileSync(join(root, 'web/src/styles.css'), 'utf8');
  const block = text.match(new RegExp(`(?:^|\\n)${selector.replace('.', '\\.')}\\s*\\{([^}]*)\\}`));
  if (!block) return null;
  const m = block[1].match(new RegExp(`${property}\\s*:\\s*(-?[\\d.]+)px`));
  return m ? Number(m[1]) : null;
}

/**
 * The studio sheet, held across the Java that owns it and the page that mirrors it.
 *
 * This is the guard the audit of the web sheet exists for: every one of these
 * numbers was either missing from the page or read from the wrong Java constant
 * (the plate was `ModalSheet.CARD`'s opaque `#171b21`, two states instead of a
 * free rest, a 30px strip, a 180ms `cubic-bezier` where the app derives its
 * duration). None of those failures crashes anything — each one is a sheet that
 * looks plausible and feels wrong — which is exactly the class of drift
 * `contract/` and this script exist to refuse.
 *
 * The two SIDES cannot share code: this checks the numbers, not the pixels. The
 * behaviours those numbers compose are `web/src/sheetmath.test.ts`'s.
 */
function checkSheetParity() {
  const INTENT = 'android/core/src/main/java/com/amoshydra/basrelief/core/gesture/SheetIntent.java';
  const MORPH = 'android/core/src/main/java/com/amoshydra/basrelief/core/gesture/SheetMorph.java';
  const SPRING = 'android/app/src/main/java/com/amoshydra/basrelief/SpringSettle.java';
  const MAIN = 'android/app/src/main/java/com/amoshydra/basrelief/MainActivity.java';
  const GALLERY = 'android/app/src/main/java/com/amoshydra/basrelief/Gallery.java';
  const SLIDER = 'android/app/src/main/java/com/amoshydra/basrelief/BasreliefSlider.java';
  const WEB = 'web/src/sheetmath.ts';
  const before = failures.length;

  /** name -> [javaFile, javaName, webName]. */
  const numbers = [
    ['PROJECTION_SECONDS', INTENT, 'PROJECTION_SECONDS', 'PROJECTION_SECONDS'],
    ['FLING_TRAVEL_PER_SECOND', INTENT, 'FLING_TRAVEL_PER_SECOND', 'FLING_TRAVEL_PER_SECOND'],
    ['EDGE_BAND_START', MORPH, 'EDGE_BAND_START', 'EDGE_BAND_START'],
    ['stride height', MAIN, 'STRIP_HEIGHT_DP', 'STRIP_HEIGHT'],
    ['grip band', MAIN, 'STRIP_GRIP_DP', 'STRIP_GRIP'],
    ['pill max inset', MAIN, 'PILL_MAX_INSET_DP', 'PILL_MAX_INSET'],
    ['pill min width', MAIN, 'PILL_MIN_WIDTH_DP', 'PILL_MIN_WIDTH'],
    ['pill bottom inset', MAIN, 'PILL_BOTTOM_INSET_DP', 'PILL_BOTTOM_INSET'],
    ['peek fraction', MAIN, 'SHEET_PEEK_FRACTION', 'SHEET_PEEK_FRACTION'],
    ['sheet glass', MAIN, 'SHEET_GLASS', 'SHEET_GLASS'],
    ['pill plate', MAIN, 'PILL_PLATE', 'PILL_PLATE'],
    ['pill dot', MAIN, 'STRIP_DIM', 'PILL_DOT'],
    ['pill outline', MORPH, null, 'PILL_STROKE'],
    ['warn colour', MAIN, 'WARN_COLOUR', 'WARN_COLOUR'],
    ['tether', SPRING, 'RESISTANCE_FRACTION', 'RESISTANCE_FRACTION'],
    ['strip tile', GALLERY, 'THUMB_DP', null],
    ['strip tile radius', GALLERY, 'TILE_RADIUS_DP', null],
  ];

  for (const [what, javaFile, javaName, webName] of numbers) {
    if (javaName === null) continue; // a web-only literal, listed for the record
    const java = readConstant(javaFile, javaName);
    if (java === null) {
      failures.push(`could not read ${javaName} from ${javaFile} — update this script`);
      continue;
    }
    if (webName === null) continue; // checked against the CSS below
    const web = readConstant(WEB, webName);
    if (web === null) {
      failures.push(`could not read ${webName} from ${WEB} — update this script`);
      continue;
    }
    if (Math.abs(web - java) > 1e-9) {
      failures.push(
        `the sheet's ${what} differs: ${javaFile} has ${java}, ${WEB} has ${web} — ` +
          'a sheet whose numbers are not the studio’s is a different sheet that looks plausible',
      );
    }
  }

  // The pill's outline is a Java literal in `MainActivity` and a web constant. It
  // is the stroke that makes the collapsed glass read as a surface, so a drift is
  // a pill that stops floating.
  const javaStroke = /setStripStroke\(0x33FFFFFF\)/.test(readFileSync(join(root, MAIN), 'utf8'))
    || readConstant(MAIN, 'PILL_STROKE') !== null;
  const webStroke = readConstant(WEB, 'PILL_STROKE');
  if (!javaStroke || webStroke !== 0x33ffffff) {
    failures.push(
      'the pill outline is not 0x33FFFFFF on both sides — the stroke is what keeps a ' +
        '55%-transparent pill readable over a busy render',
    );
  }

  // The two implemented rules that ARE a derivation rather than a literal.
  const morphText = readFileSync(join(root, MORPH), 'utf8');
  const webText = readFileSync(join(root, WEB), 'utf8');
  if (!/SHAPE_BAND_START\s*=\s*SheetIntent\.SHEET\.collapseThreshold/.test(morphText)) {
    failures.push('SheetMorph.SHAPE_BAND_START is no longer derived from SheetIntent.SHEET');
  }
  if (!/SHAPE_BAND_START\s*=\s*COLLAPSE_THRESHOLD/.test(webText)) {
    failures.push(
      `${WEB} hard-codes SHAPE_BAND_START instead of deriving it from COLLAPSE_THRESHOLD — ` +
        'the shape must start exactly where a release commits to collapsing',
    );
  }
  if (!/PILL_RADIUS\s*=\s*STRIP_HEIGHT\s*\/\s*2/.test(webText)) {
    failures.push(`${WEB} does not derive PILL_RADIUS from half the strip`);
  }
  if (!/dp\(STRIP_HEIGHT_DP\)\s*\*\s*0\.5f/.test(readFileSync(join(root, MAIN), 'utf8'))) {
    failures.push('MainActivity no longer targets half the strip as the pill’s radius');
  }

  // The release policy: `SheetIntent.SHEET` / `DIALOG` against the web's two.
  const sheetPolicy = readConstructed(INTENT, 'SHEET', 'Policy');
  const dialogPolicy = readConstructed(INTENT, 'DIALOG', 'Policy');
  for (const [what, java] of [
    ['SHEET', sheetPolicy],
    ['DIALOG', dialogPolicy],
  ]) {
    if (java === null || java.length !== 2 || java.some((n) => !Number.isFinite(n))) {
      failures.push(`could not read SheetIntent.${what} — update this script`);
      continue;
    }
    const collapse = readConstant(WEB, what === 'SHEET' ? 'COLLAPSE_THRESHOLD' : 'DIALOG_COLLAPSE_THRESHOLD');
    const expand = readConstant(WEB, what === 'SHEET' ? 'EXPAND_THRESHOLD' : 'DIALOG_EXPAND_THRESHOLD');
    if (collapse !== java[0] || expand !== java[1]) {
      failures.push(
        `SheetIntent.${what} is ${java[0]}/${java[1]} but ${WEB} says ${collapse}/${expand} — ` +
          'the release rule is the number a hand feels, and it is the app’s',
      );
    }
  }

  // The spring: damping, omega, duration factor, settle-speed floor, min and max.
  const spring = readConstructed(SPRING, 'SHEET', 'Profile');
  const springWeb = readFileSync(join(root, WEB), 'utf8').match(/SPRING\s*=\s*\{([^}]*)\}/);
  if (spring === null || spring.length !== 6 || springWeb === null) {
    failures.push('could not read SpringSettle.SHEET / the web SPRING block — update this script');
  } else {
    const values = [...springWeb[1].matchAll(/:\s*(-?[\d.]+)/g)].map((m) => Number(m[1]));
    if (values.length !== spring.length || values.some((v, i) => Math.abs(v - spring[i]) > 1e-9)) {
      failures.push(
        `SpringSettle.SHEET is [${spring.join(', ')}] but ${WEB}'s SPRING is ` +
          `[${values.join(', ')}] — the curve and the duration are one profile, and ` +
          'damping below 1 overshoots by construction',
      );
    }
  }

  // The two prefs keys the position is stored under.
  const keys = [
    ['KEY_SHEET', 'SHEET_POS_KEY', 'sheet_pos'],
    ['KEY_SHEET_OPEN', 'SHEET_OPEN_KEY', 'sheet_open_pos'],
  ];
  for (const [javaName, webName, expected] of keys) {
    const java = readStringConstant(MAIN, javaName);
    const web = readStringConstant('web/src/prefs.ts', webName);
    if (java !== expected || web !== expected) {
      failures.push(
        `the sheet's stored position is "${expected}" on the phone but ${java} / ${web} — ` +
          'a copied prefs file would forget where the sheet was',
      );
    }
  }

  // The filmstrip's tile, from Gallery's dp() into the CSS that draws it.
  const thumb = readConstant(GALLERY, 'THUMB_DP');
  const thumbCss = readCssPx('.sheet-film-tile', 'width');
  if (thumb !== thumbCss) {
    failures.push(
      `the filmstrip's tile is ${thumb}dp in Gallery.java and ${thumbCss}px in styles.css — ` +
        'the strip is sheet content on both sides and its size is the library’s',
    );
  }
  const radius = readConstant(GALLERY, 'TILE_RADIUS_DP');
  const radiusCss = readCssPx('.sheet-film-tile', 'border-radius');
  if (radius !== radiusCss) {
    failures.push(
      `the filmstrip's tile radius is ${radius}dp in Gallery.java and ${radiusCss}px in styles.css`,
    );
  }

  // The row inset and the segment's margin: `dp(14)` and `dp(10)` in MainActivity.
  const mainText = readFileSync(join(root, MAIN), 'utf8');
  if (!/sheetPadLeft\s*=\s*dp\(14\)/.test(mainText)) {
    failures.push('MainActivity no longer insets the scrolling content by dp(14)');
  }
  const rowInset = readCssPx('.sheet-body-inner', 'padding');
  if (rowInset !== null && Math.abs(rowInset - 14) > 1e-9) {
    failures.push(`the web's row inset is ${rowInset}px, the app's is 14dp`);
  }
  if (!/outer\.bottomMargin\s*=\s*dp\(10\)/.test(mainText)) {
    failures.push('MainActivity no longer gives the segment a dp(10) bottom margin');
  }

  // The slider's name column. The app's is 66dp; the web's is `LABEL_COLUMN_PX`
  // in styles.css, and it may differ ONLY as the recorded departure below.
  const javaLabel = readConstant(SLIDER, 'LABEL_DP');
  const webLabel = readCssPx('.bslider-label', 'width');
  const webSliderComment = readFileSync(join(root, 'web/src/styles.css'), 'utf8');
  const departure = /deliberate departure[^]*?72px/.test(webSliderComment);
  if (javaLabel !== webLabel && !departure) {
    failures.push(
      `the slider's name column is ${javaLabel}dp in BasreliefSlider and ${webLabel}px here — ` +
        'either match it or record the departure in styles.css beside the number',
    );
  }

  if (failures.length === before) {
    console.log(
      `parity: the studio sheet — ${numbers.length} constants, both release policies, the ` +
        'spring profile, the two stored keys and the filmstrip tile agree with Android',
    );
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
    'contract/library/cases.json',
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
    'web/src/library.ts',
    'web/src/library.test.ts',
    'web/src/prefs.ts',
    'web/src/prefs.test.ts',
    'web/src/store.ts',
    'web/src/mirror.ts',
    'web/src/picture.ts',
    'web/src/picture.test.ts',
    'web/src/comfy.ts',
    'web/src/comfy.test.ts',
    'web/src/camera.ts',
    'web/src/camera.test.ts',
    'web/vite.config.ts',
    'web/src/sheet.ts',
    'web/src/sheetmath.ts',
    'web/src/sheetmath.test.ts',
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

  /**
   * The gallery's copy is the tag rule's third implementation, because the cloud
   * build derives `basrelief_<tag>.splat` / `.splat.json` without ever having seen
   * a response — the same filenames the node writes. It is a copy that cannot share
   * code, so it must read the same fixture the other two do; a copy tested against
   * its own examples agrees with itself and proves nothing.
   */
  const comfyTagTest = readFileSync(join(root, 'web/src/comfy.test.ts'), 'utf8');
  if (!/join\(contractDir,\s*'tag',\s*'cases\.json'\)/.test(comfyTagTest)) {
    failures.push(
      'web/src/comfy.test.ts does not read contract/tag/cases.json — the gallery’s ' +
        'tag copy (comfy.ts sanitiseTag) is a third implementation and must be held ' +
        'to the shared bytes');
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
checkLibraryParity();
checkComfyPrefsParity();
checkSheetParity();
checkModalInvariants();
checkTiltSmoothing();
checkSharedRules();

if (failures.length) {
  console.error(`\ncheck-parity: ${failures.length} problem(s)\n`);
  for (const f of failures) console.error(`  - ${f}`);
  process.exit(1);
}
console.log('\ncheck-parity: OK');
