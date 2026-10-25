#!/usr/bin/env node
/**
 * The TypeScript half of `contract/library/`, run under plain Node.
 *
 *   node --experimental-strip-types src/library.test.ts
 *
 * `Library` is the app's gallery index, and this page has a second reader and
 * writer for it (`web/src/library.ts`) because a copy's whole point is that a
 * folder written here opens on the phone and a folder the phone wrote opens here.
 * Nothing in either build can see the other copy, so the shared statement is
 * `contract/library/cases.json`, and `scripts/check-parity.mjs` holds the schema
 * number and the field set against the Java sources.
 *
 * What a wrong answer looks like, case by case:
 *
 *   - a row whose `image` is dropped is a photograph the phone loses; a row whose
 *     `video` is dropped is a video a copy would carry no files for.
 *   - a reader that throws on a corrupt or newer blob is a dead page, and one that
 *     treats a newer blob as writable erases a future version's gallery.
 *   - a writer that emits `focal: 0` or the five default-valued optional settings
 *     keys changes every stored item on upgrade for no rendered difference.
 *   - a reader that keeps a path instead of a basename makes a backup
 *     un-restorable on another device (`PrefFiles`' live data-loss bug).
 *   - a reader that loses the flat v1/v2 shape silently un-frames a photograph the
 *     phone wrote before per-item settings existed.
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  BLOB_KEYS,
  ITEM_KEYS,
  LIBRARY_SCHEMA,
  SORT_KEYS,
  SURFACE_KEYS,
  displayName,
  emptyLibrary,
  fileNamesOf,
  imageNameFor,
  isValidId,
  markUsed,
  normaliseName,
  readLibrary,
  sortedRows,
  splatNameFor,
  writeLibrary,
  type WebLibrary,
} from './library.ts';
import {
  REQUIRED_KEYS,
  defaultSettings,
  fromJson as settingsFromJson,
  toJson as settingsToJson,
  type SurfaceSettings,
} from './settings.ts';

const here = dirname(fileURLToPath(import.meta.url));
const fixtureDir = join(here, '..', '..', 'contract');

const fixture = JSON.parse(
  readFileSync(join(fixtureDir, 'library', 'cases.json'), 'utf8'),
) as {
  schema: number;
  why: string;
  blobKeys: string[];
  itemKeys: string[];
  sortKeys: string[];
  surfaceKeys: string[];
  populated: {
    why: string;
    blob: Record<string, unknown>;
    expected: {
      ids: string[];
      active: string | null;
      sort: string;
      applied: string[];
      render: string[];
      archived: boolean[];
      labels: string[];
      files: string[];
    };
  };
  defaults: {
    why: string;
    blob: Record<string, unknown>;
    expected: {
      id: string;
      image: string;
      depth: null;
      normal: null;
      video: null;
      focal: number;
      splat: null;
      splatLens: number;
      render: string;
      label: string;
      addedAt: number;
      lastUsedAt: number;
      archived: boolean;
      lock: null;
    };
  };
  flat: {
    why: string;
    blob: Record<string, unknown>;
    expectedHome: Record<string, number>;
  };
  junk: Array<{
    why: string;
    raw: unknown;
    status: string;
    newerVersion?: number;
    readOnly?: boolean;
    expectedIds?: string[];
    expectedActive?: string | null;
    expectedSort?: string;
    expectedApplied?: string[];
    expectedItem?: {
      image: string;
      depth: number;
      zoom: number;
      panX: number;
      addedAt: number;
      lastUsedAt: number;
      archived: boolean;
      render: string;
      label: string | null;
    };
  }>;
};

const settingsFixture = JSON.parse(
  readFileSync(join(fixtureDir, 'settings', 'cases.json'), 'utf8'),
) as { populated: { body: Record<string, number> } };

/** A blob as the reader takes it: an object serialised, or a raw string. */
function rawOf(value: unknown): string {
  return typeof value === 'string' ? value : JSON.stringify(value);
}

function parseBlob(value: unknown): ReturnType<typeof readLibrary> {
  return readLibrary(rawOf(value));
}

function itemAt(library: WebLibrary, index: number) {
  const item = library.items[index];
  assert.ok(item, `the library has no item ${index}`);
  return item;
}

// ---------------------------------------------------------------- the schema

assert.equal(fixture.schema, LIBRARY_SCHEMA, 'fixture schema versus LIBRARY_SCHEMA');
assert.ok(fixture.why.length > 0, 'the fixture must say why it exists');

assert.deepEqual(
  [...fixture.sortKeys].sort(),
  [...SORT_KEYS].sort(),
  'SortOrder keys versus library.ts SORT_KEYS',
);
assert.deepEqual(
  [...fixture.surfaceKeys].sort(),
  [...SURFACE_KEYS].sort(),
  'Surface keys versus library.ts SURFACE_KEYS',
);
for (const key of fixture.blobKeys) {
  assert.ok(key.length > 0, 'a blank blob key is not a key');
}
for (const key of fixture.itemKeys) {
  assert.ok(key.length > 0, 'a blank item key is not a key');
}
// The reader and writer's declared key sets are the fixture's: `check-parity`
// holds both to the Java sources, so this closes the loop rather than adding a
// third opinion.
assert.deepEqual([...fixture.blobKeys].sort(), [...BLOB_KEYS].sort());
assert.deepEqual([...fixture.itemKeys].sort(), [...ITEM_KEYS].sort());
// The required keys are the settings writer's own list, held together so a body
// cannot gain a key on one side and lose it on the other.
assert.deepEqual(
  [...REQUIRED_KEYS].sort(),
  Object.keys(
    ((fixture.defaults.blob.items as Array<Record<string, unknown>>)[0]
      .surfaces as Record<string, Record<string, number>>).home,
  ).sort(),
  'the defaults body is not the settings writer’s required-key set',
);

// -------------------------------------------------------------- the populated

{
  const read = parseBlob(fixture.populated.blob);
  assert.equal(read.status, 'ok', read.message ?? '');
  assert.equal(read.readOnly, false);
  assert.equal(read.library.items.length, fixture.populated.expected.ids.length);
  assert.deepEqual(read.library.items.map((item) => item.id), fixture.populated.expected.ids);
  assert.equal(read.library.active, fixture.populated.expected.active);
  assert.equal(read.library.sort, fixture.populated.expected.sort);
  assert.deepEqual(read.library.applied, fixture.populated.expected.applied);
  assert.deepEqual(
    read.library.items.map((item) => item.render),
    fixture.populated.expected.render,
  );
  assert.deepEqual(
    read.library.items.map((item) => item.archived),
    fixture.populated.expected.archived,
  );
  assert.deepEqual(
    read.library.items.map((item) => displayName(item)),
    fixture.populated.expected.labels,
  );

  // Every file the rows name, and nothing else: a copy carries exactly this
  // list, so a row that named a file the reader dropped would be a photograph the
  // backup silently leaves behind.
  const files = new Set<string>();
  for (const item of read.library.items) {
    for (const name of fileNamesOf(item)) files.add(name);
  }
  assert.deepEqual([...files].sort(), [...fixture.populated.expected.files].sort());

  // Row one's home surface is the settings contract's populated body — the two
  // fixtures cannot disagree about a render value.
  const first = itemAt(read.library, 0);
  assert.deepEqual(
    first.home,
    settingsFromJson(settingsFixture.populated.body),
    'the library fixture’s home surface and the settings fixture’s body disagree',
  );
  assert.deepEqual(
    settingsToJson(first.home),
    settingsFixture.populated.body,
    'the home surface does not write the settings contract’s own body back',
  );
  // ...and the lock override survives whole, which is the surface this page does
  // not draw: losing it would change a phone's lock screen.
  const lockBody = (
    (fixture.populated.blob.items as Array<Record<string, unknown>>)[0].surfaces as Record<
      string,
      Record<string, number>
    >
  ).lock;
  assert.deepEqual(settingsToJson(first.lock as SurfaceSettings), lockBody);
  assert.equal(first.focal, 1234.5);
  assert.equal(first.splatLens, 0.8125);
  assert.equal(first.splat, 'item-5d1c7a2e-7f2b-4a3c-8f2a-1c2b3d4e5f60.splat');

  const second = itemAt(read.library, 1);
  assert.equal(second.focal, 0, 'an absent focal is 0 (unknown), not a number');
  assert.equal(second.splat, null);
  assert.equal(second.splatLens, 0);
  assert.equal(second.depth, 'item-b7e2c4d6-1a3f-4b5c-9d7e-2f4a6b8c0d1e-depth.png');
  assert.equal(second.normal, 'item-b7e2c4d6-1a3f-4b5c-9d7e-2f4a6b8c0d1e-normal.png');
  assert.equal(second.home.panX, -135, 'pan is in PIXELS and is not clamped');

  const third = itemAt(read.library, 2);
  assert.deepEqual(third.video, {
    color: 'item-c9a3e5f7-2b4d-4c6e-8f0a-3b5c7d9e1f20.mp4',
    depth: 'item-c9a3e5f7-2b4d-4c6e-8f0a-3b5c7d9e1f20-depth.mp4',
    normal: 'item-c9a3e5f7-2b4d-4c6e-8f0a-3b5c7d9e1f20-normal.mp4',
  });

  // The writer must give the blob back: a dropped key, a stored 0 or a rewritten
  // surface body is a stored picture that changes when it is re-saved.
  assert.deepEqual(
    JSON.parse(writeLibrary(read.library)),
    fixture.populated.blob,
    'the populated blob does not survive parse → write',
  );
  // And the round trip is a fixed point, not merely one pass.
  const again = parseBlob(writeLibrary(read.library));
  assert.deepEqual(again.library, read.library, 'parse → write → parse moved a value');
  assert.equal(writeLibrary(again.library), writeLibrary(read.library), 'the writer is not stable');
}

// ----------------------------------------------------------------- the defaults

{
  const read = parseBlob(fixture.defaults.blob);
  assert.equal(read.status, 'ok');
  const item = itemAt(read.library, 0);
  const expected = fixture.defaults.expected;
  assert.equal(item.id, expected.id);
  assert.equal(item.image, expected.image);
  assert.equal(item.depth, expected.depth);
  assert.equal(item.normal, expected.normal);
  assert.equal(item.video, expected.video);
  assert.equal(item.focal, expected.focal);
  assert.equal(item.splat, expected.splat);
  assert.equal(item.splatLens, expected.splatLens);
  assert.equal(item.render, expected.render);
  assert.equal(item.label, expected.label);
  assert.equal(item.addedAt, expected.addedAt);
  assert.equal(item.lastUsedAt, expected.lastUsedAt);
  assert.equal(item.archived, expected.archived);
  assert.equal(item.lock, expected.lock);
  assert.deepEqual(item.home, defaultSettings(), 'an unadjusted row must default to the app’s values');

  // The five optional keys are absent, so an untouched item stays byte-identical.
  const written = JSON.parse(writeLibrary(read.library)) as {
    items: Array<{ surfaces: { home: Record<string, number> } }>;
  };
  assert.deepEqual(Object.keys(written.items[0].surfaces.home).sort(), [...REQUIRED_KEYS].sort());
  assert.deepEqual(JSON.parse(writeLibrary(read.library)), fixture.defaults.blob);
}

// ------------------------------------------------------------ the flat v1 shape

{
  const read = parseBlob(fixture.flat.blob);
  assert.equal(read.status, 'ok');
  const item = itemAt(read.library, 0);
  assert.deepEqual(
    item.home,
    settingsFromJson(fixture.flat.expectedHome),
    'a flat row lost its framing on the way into the nested shape',
  );
  // The v2 version number is older than this schema, and the page rewrites it as
  // the current shape on the next save — a migration by parse, not a second reader.
  assert.equal(JSON.parse(writeLibrary(read.library)).version, LIBRARY_SCHEMA);
}

// ----------------------------------------------------------------- the junk

for (const junk of fixture.junk) {
  assert.ok(junk.why, 'a junk case with no "why" is not a case');
  const read = parseBlob(junk.raw);
  assert.equal(read.status, junk.status, `junk status for "${junk.why}"`);
  if (junk.status === 'corrupt') {
    assert.ok(read.message !== null, 'a corrupt blob must produce a sentence');
    assert.equal(read.library.items.length, 0);
    assert.equal(read.readOnly, false, 'a corrupt blob is this build’s problem to rewrite');
  }
  if (junk.status === 'newer') {
    assert.equal(read.newerVersion, junk.newerVersion);
    assert.equal(read.readOnly, junk.readOnly);
    assert.equal(read.library.items.length, 0, 'a newer blob must not be interpreted');
    assert.ok(read.message !== null);
  }
  if (junk.expectedIds) {
    assert.deepEqual(read.library.items.map((item) => item.id), junk.expectedIds);
  }
  if ('expectedActive' in junk) assert.equal(read.library.active, junk.expectedActive);
  if (junk.expectedSort) assert.equal(read.library.sort, junk.expectedSort);
  if (junk.expectedApplied) assert.deepEqual(read.library.applied, junk.expectedApplied);
  if (junk.expectedItem) {
    const item = itemAt(read.library, 0);
    const want = junk.expectedItem;
    assert.equal(item.image, want.image, 'a traversal name must collapse to a basename');
    assert.equal(item.home.depth, want.depth, 'an out-of-range depth is clamped, never carried');
    assert.equal(item.home.zoom, want.zoom);
    assert.equal(item.home.panX, want.panX);
    assert.equal(item.addedAt, want.addedAt, 'a negative stamp would sort ahead of everything');
    assert.equal(item.lastUsedAt, want.lastUsedAt);
    assert.equal(item.archived, want.archived, 'a string is not a boolean');
    assert.equal(item.render, want.render, 'an unknown render mode is the relief');
    assert.equal(item.label, want.label, 'a blank label is no label');
  }
}

// ------------------------------------------------------------------- helpers

assert.equal(imageNameFor('abc-123', 'Beach.JPG'), 'item-abc-123.jpg');
assert.equal(imageNameFor('abc-123', 'photo'), 'item-abc-123.png');
assert.equal(imageNameFor('abc-123', 'x.weird-extension'), 'item-abc-123.png');
assert.equal(splatNameFor('abc-123'), 'item-abc-123.splat');
assert.equal(normaliseName('../evil.png'), 'evil.png');
assert.equal(normaliseName('/etc/passwd'), 'passwd');
assert.equal(normaliseName('null'), null);
assert.equal(normaliseName('a\u0000b.png'), null);
assert.equal(normaliseName(42), null);
assert.equal(isValidId('a/b'), false);
assert.equal(isValidId(' '), false);
assert.equal(isValidId('ok-id_1'), true);
assert.equal(isValidId('x'.repeat(65)), false);

// --------------------------------------------------------------- read order

{
  const library: WebLibrary = {
    ...emptyLibrary(),
    sort: 'added_desc',
    items: [
      { ...itemAt(parseBlob(fixture.defaults.blob).library, 0), id: 'a', label: 'B', addedAt: 10 },
      { ...itemAt(parseBlob(fixture.defaults.blob).library, 0), id: 'b', label: 'a', addedAt: 20 },
      { ...itemAt(parseBlob(fixture.defaults.blob).library, 0), id: 'c', label: 'C', addedAt: 20 },
    ],
  };
  assert.deepEqual(sortedRows(library).map((row) => row.id), ['c', 'b', 'a']);
  assert.deepEqual(
    sortedRows({ ...library, sort: 'name_asc' }).map((row) => row.id),
    ['b', 'a', 'c'],
  );
  const used = markUsed(library, 'a', 99);
  assert.equal(used.active, 'a');
  assert.equal(sortedRows({ ...used, sort: 'recently_used' })[0].id, 'a');
  // An id that is not in the list changes nothing: a selection that points at
  // nothing is the dangling reference `Library.of` refuses to store.
  assert.equal(markUsed(library, 'missing', 99), library);
}

console.log(
  `library contract: ${fixture.itemKeys.length} item keys, ${fixture.blobKeys.length} blob keys, ` +
    `${fixture.junk.length} junk bodies and one populated round trip agree with web/src/library.ts`,
);
