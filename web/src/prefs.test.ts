#!/usr/bin/env node
/**
 * The app's prefs blob, and the history rule `ModelRanking` owns.
 *
 *   node --experimental-strip-types src/prefs.test.ts
 *
 * The page stores `comfy_url`, `comfy_model` and `comfy_model_history` in
 * `prefs.json` beside `library.json`, because the phone stores exactly those
 * three under those names. Nothing in either build can see the other copy, so
 * what this test pins is the *value*: the newline form of the history (not a
 * JSON array), the defaults a missing key means, and the one ordering rule.
 *
 * What a wrong answer looks like, case by case:
 *
 *   - a history written as a JSON array is a value the phone's `ModelRanking`
 *     does not parse, so the choice silently disappears on a copy;
 *   - a writer that emits the defaults changes an untouched file on upgrade, and
 *     a reader that treats a wrong type as corrupt turns one bad key into a page
 *     that will not open;
 *   - a ranker that inserts a model the server no longer offers (or drops one it
 *     does offer) is a chooser that names a prompt the server cannot run;
 *   - a history recorded on a finished run rather than on the selection forgets
 *     the checkpoint a user looked at and rejected — which is still the one they
 *     reach for;
 *   - a `comfy_model` written on the tap is a *different* fact from the model the
 *     last run was sent to, and comparing two maps would then mean nothing.
 */
import assert from 'node:assert/strict';

import {
  MODEL_HISTORY_CAP,
  PREF_KEYS,
  bindPrefsSink,
  decodeHistory,
  defaultPrefs,
  encodeHistory,
  getComfyUrl,
  getModelChoice,
  getModelHistory,
  hydratePrefs,
  prefsNow,
  rankModels,
  readPrefs,
  rememberSelected,
  selectModel,
  setComfyUrl,
  setModelChoice,
  writePrefs,
} from './prefs.ts';

let count = 0;
function test(name: string, fn: () => void): void {
  try {
    fn();
  } catch (err) {
    console.error(`FAIL ${name}`);
    throw err;
  }
  count++;
}

// ------------------------------------------------------------------- the blob

test('an absent or empty blob is the app’s defaults, and says nothing', () => {
  for (const raw of [null, '', '   ']) {
    const read = readPrefs(raw);
    assert.deepEqual(read.prefs, defaultPrefs());
    assert.deepEqual(read.extra, {});
    assert.equal(read.status, 'empty');
    assert.equal(read.message, null);
  }
});

test('the three keys are read under the app’s own names', () => {
  assert.deepEqual([...PREF_KEYS], ['comfy_url', 'comfy_model', 'comfy_model_history']);
  const read = readPrefs(
    JSON.stringify({
      comfy_url: 'http://box:8188',
      comfy_model: 'moge_3_vitg_fp16.safetensors',
      comfy_model_history: 'moge_3_vitg_fp16.safetensors\nDepthAnythingV2',
    }),
  );
  assert.equal(read.status, 'ok');
  assert.equal(read.prefs.comfyUrl, 'http://box:8188');
  assert.equal(read.prefs.comfyModel, 'moge_3_vitg_fp16.safetensors');
  assert.deepEqual(read.prefs.comfyModelHistory, [
    'moge_3_vitg_fp16.safetensors',
    'DepthAnythingV2',
  ]);
});

test('the history is stored in the app’s newline form, not as a JSON array', () => {
  const text = writePrefs({
    comfyUrl: 'http://box:8188',
    comfyModel: '',
    comfyModelHistory: ['b', 'a'],
  });
  const body = JSON.parse(text) as Record<string, unknown>;
  assert.equal(body.comfy_model_history, 'b\na');
  // And the app's own string is what the reader accepts back.
  assert.deepEqual(decodeHistory(body.comfy_model_history), ['b', 'a']);
  assert.deepEqual(decodeHistory('b\na'), decodeHistory(body.comfy_model_history));
});

test('a default value is written nowhere, so an untouched file keeps its bytes', () => {
  assert.equal(writePrefs(defaultPrefs()), '{}');
  const read = readPrefs(JSON.stringify({ comfy_url: '', comfy_model: '', comfy_model_history: '' }));
  assert.deepEqual(read.prefs, defaultPrefs());
  assert.equal(writePrefs(read.prefs, read.extra), '{}');
});

test('a write is what the reader reads back, history and all', () => {
  const prefs = {
    comfyUrl: 'http://box:8188',
    comfyModel: 'm.pt',
    comfyModelHistory: ['m.pt', 'n.pt'],
  };
  const read = readPrefs(writePrefs(prefs));
  assert.equal(read.status, 'ok');
  assert.deepEqual(read.prefs, prefs);
});

test('a key this build does not know is carried through a rewrite', () => {
  const read = readPrefs(
    JSON.stringify({ comfy_url: 'http://box:8188', comfy_resolution: 1024, future: { a: 1 } }),
  );
  assert.deepEqual(read.extra, { comfy_resolution: 1024, future: { a: 1 } });
  const body = JSON.parse(writePrefs(read.prefs, read.extra)) as Record<string, unknown>;
  assert.equal(body.comfy_url, 'http://box:8188');
  assert.equal(body.comfy_resolution, 1024);
  assert.deepEqual(body.future, { a: 1 });
});

test('a known key parked in the extra map cannot overwrite the real one', () => {
  const text = writePrefs(
    { comfyUrl: 'http://real:8188', comfyModel: '', comfyModelHistory: [] },
    { comfy_url: 'http://stale:1' },
  );
  const body = JSON.parse(text) as Record<string, unknown>;
  assert.equal(body.comfy_url, 'http://real:8188');
  assert.equal(JSON.stringify(body).includes('stale'), false);
});

test('a corrupt blob is the defaults plus a sentence, and never a throw', () => {
  for (const raw of ['{oops', '[]', '"a string"', '42', 'null']) {
    const read = readPrefs(raw);
    assert.equal(read.status, 'corrupt');
    assert.deepEqual(read.prefs, defaultPrefs());
    assert.ok(read.message !== null);
    assert.match(read.message ?? '', /prefs\.corrupt\.json/);
  }
});

test('one wrong-typed key is that key’s default, not a dead page', () => {
  const read = readPrefs(
    JSON.stringify({
      comfy_url: 5,
      comfy_model: { name: 'x' },
      comfy_model_history: ['a', 'b'],
    }),
  );
  assert.equal(read.status, 'ok');
  assert.deepEqual(read.prefs, defaultPrefs());
});

test('the address is trimmed and the model name is taken verbatim', () => {
  const read = readPrefs(
    JSON.stringify({ comfy_url: '  http://box:8188  ', comfy_model: ' model.pt ' }),
  );
  assert.equal(read.prefs.comfyUrl, 'http://box:8188');
  assert.equal(read.prefs.comfyModel, ' model.pt ');
});

test('a stored history collapses empty lines and duplicates and is capped', () => {
  assert.deepEqual(decodeHistory('\na\na\nb\nc\nd\ne\n'), ['a', 'b', 'c', 'd']);
  assert.equal(decodeHistory('').length, 0);
  assert.equal(decodeHistory(undefined).length, 0);
  assert.equal(encodeHistory(['a', '', 'b']), 'a\nb');
  // A newline cannot be part of a checkpoint filename, so it is not encoded as
  // one; Java's ModelRanking.encode does the same.
  assert.equal(encodeHistory(['a\nb']), '');
});

// ---------------------------------------------------------------- the ranking

test('the history comes first, most recent first, then discovery order', () => {
  assert.deepEqual(rankModels(['a', 'b', 'c', 'd'], ['c', 'a']), ['c', 'a', 'b', 'd']);
  assert.deepEqual(rankModels(['a', 'b', 'c'], []), ['a', 'b', 'c']);
});

test('a model the server no longer offers is skipped, never invented', () => {
  // A permutation of what was discovered: nothing added, nothing dropped.
  const ranked = rankModels(['a', 'b'], ['gone', 'b']);
  assert.deepEqual(ranked, ['b', 'a']);
  assert.deepEqual([...ranked].sort(), ['a', 'b']);
});

test('duplicates on either side collapse to one row', () => {
  assert.deepEqual(rankModels(['a', 'a', 'b'], ['b', 'b']), ['b', 'a']);
});

test('a selection moves to the front, dedupes and is capped', () => {
  assert.deepEqual(rememberSelected(['b', 'c'], 'a'), ['a', 'b', 'c']);
  assert.deepEqual(rememberSelected(['a', 'b', 'c'], 'b'), ['b', 'a', 'c']);
  assert.deepEqual(rememberSelected(['a', 'b', 'c', 'd', 'e'], 'x'), ['x', 'a', 'b', 'c']);
  assert.equal(rememberSelected(['a', 'b', 'c', 'd'], 'x').length, MODEL_HISTORY_CAP);
  assert.deepEqual(rememberSelected(['a'], ''), ['a']);
});

// -------------------------------------------------------------- the live copy

test('hydrating writes nothing, because a load must not rewrite the file', () => {
  const saved: string[] = [];
  bindPrefsSink((prefs) => saved.push(JSON.stringify(prefs)));
  hydratePrefs({
    comfyUrl: 'http://box:8188',
    comfyModel: 'moge',
    comfyModelHistory: ['moge'],
  });
  assert.deepEqual(saved, []);
  assert.equal(getComfyUrl(), 'http://box:8188');
  assert.equal(getModelChoice(), 'moge');
  assert.deepEqual(getModelHistory(), ['moge']);
  bindPrefsSink(null);
});

test('a selection is the history’s signal and not the run’s', () => {
  const saved: Array<ReturnType<typeof prefsNow>> = [];
  bindPrefsSink((prefs) => saved.push(prefs));
  hydratePrefs({
    comfyUrl: '',
    comfyModel: 'already-run.pt',
    comfyModelHistory: ['already-run.pt'],
  });
  selectModel('tapped.pt');
  assert.equal(saved.length, 1);
  assert.deepEqual(saved[0].comfyModelHistory, ['tapped.pt', 'already-run.pt']);
  // The saved model is the different fact: it moves at submission, not at the tap.
  assert.equal(saved[0].comfyModel, 'already-run.pt');
  assert.equal(getModelChoice(), 'already-run.pt');

  // Selecting the same model again has nothing new to say and writes nothing.
  selectModel('tapped.pt');
  assert.equal(saved.length, 1);

  setModelChoice('tapped.pt');
  assert.equal(saved.length, 2);
  assert.equal(saved[1].comfyModel, 'tapped.pt');
  bindPrefsSink(null);
});

test('the proxy is the empty address, in one spelling', () => {
  const saved: Array<ReturnType<typeof prefsNow>> = [];
  bindPrefsSink((prefs) => saved.push(prefs));
  hydratePrefs(defaultPrefs());
  assert.equal(getComfyUrl(), '/comfy');
  setComfyUrl('/comfy');
  assert.equal(saved.length, 0); // the default, so nothing changed and nothing wrote
  setComfyUrl('  ');
  assert.equal(saved.length, 0);
  setComfyUrl('box:8188');
  assert.equal(saved.length, 1);
  assert.equal(saved[0].comfyUrl, 'box:8188');
  assert.equal(writePrefs(saved[0]), '{"comfy_url":"box:8188"}');
  bindPrefsSink(null);
});

test('prefsNow is a copy, so a writer cannot mutate the live values', () => {
  hydratePrefs({ comfyUrl: '', comfyModel: '', comfyModelHistory: ['a'] });
  const snapshot = prefsNow();
  snapshot.comfyModelHistory.push('b');
  snapshot.comfyUrl = 'http://wrong';
  assert.deepEqual(getModelHistory(), ['a']);
  assert.equal(getComfyUrl(), '/comfy');
});

console.log(`\nprefs: ${count} checks passed`);
