#!/usr/bin/env node
/**
 * The ComfyUI still graph and history reader, without a server.
 *
 *   node --experimental-strip-types src/comfy.test.ts
 *
 * The graph is a port of `ComfyWorkflow.java`'s still shape, so the things worth
 * pinning are the things a working server would not complain about:
 *
 *   - an off-by-one wire (`SaveImage` reading the loader instead of the depth
 *     node) renders a plausible, wrong picture with no error;
 *   - dropping the normal saver is silent — the photo just renders flat;
 *   - treating a running history entry with outputs as finished is the
 *     "every build completes instantly" bug from the app, one client over;
 *   - an absent history entry is not a failure; a queued run has none.
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  SHARP_BUDGET,
  SHARP_BUDGET_MAX,
  SHARP_BUDGET_MIN,
  SHARP_INTERNAL_SIZE,
  buildWorkflow,
  canBuildWorkflow,
  clampSharpBudget,
  classifyHistory,
  classifySplatHistory,
  ComfyError,
  describeComfyError,
  extractExecutionError,
  friendlyName,
  hasDepthNode,
  hasSharpStill,
  isMoGeModel,
  normaliseBaseUrl,
  rankModels,
  sanitiseTag,
  setBaseUrl,
  sharpSidecarName,
  sharpSplatName,
  sharpWorkflow,
  usableDepthModels,
  type HistoryEntry,
  type ObjectInfo,
} from './comfy.ts';

const contractDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'contract');

let checks = 0;

function check(name: string, fn: () => void): void {
  try {
    fn();
    checks++;
    console.log(`ok   ${name}`);
  } catch (err) {
    console.error(`FAIL ${name}`);
    throw err;
  }
}

// --------------------------------------------------------------- fixtures

const singleNodeInfo: ObjectInfo = {
  DepthAnythingV2: {
    input: {
      required: {
        image: ['IMAGE'],
        resolution: ['INT', { default: 1024, min: 256, max: 2048 }],
        mode: [['relative', 'absolute']],
        new_combo: ['COMBO', { options: ['x', 'y'] }],
      },
    },
    output: ['IMAGE'],
  },
};

const moGeInfo: ObjectInfo = {
  LoadMoGeModel: {
    input: {
      required: {
        model_name: [['moge_3_vitg_fp16.safetensors', 'moge_2_vitb.safetensors'], {}],
      },
    },
    output: ['MOGE_MODEL'],
  },
  MoGeInference: {
    input: {
      required: {
        moge_model: ['MOGE_MODEL'],
        image: ['IMAGE'],
        resolution_level: ['INT', { default: 9 }],
        refine_steps: ['INT', { default: 0 }],
      },
    },
    output: ['MOGE_GEOMETRY'],
  },
  MoGeRender: {
    input: {
      required: {
        moge_geometry: ['MOGE_GEOMETRY'],
        output: [['depth', 'normal_opengl'], { default: 'depth' }],
      },
    },
    output: ['IMAGE'],
  },
};

// ------------------------------------------------------------------ graph

check('the single-node graph wires LoadImage -> node -> SaveImage', () => {
  const wf = buildWorkflow(singleNodeInfo, 'DepthAnythingV2', 'uploaded/photo.png', 512, 1234);
  assert.deepEqual(Object.keys(wf).sort(), ['1', '2', '3']);
  const load = wf['1'] as { class_type: string; inputs: { image: string } };
  const depth = wf['2'] as { class_type: string; inputs: Record<string, unknown> };
  const save = wf['3'] as { class_type: string; inputs: Record<string, unknown> };
  assert.equal(load.class_type, 'LoadImage');
  assert.equal(load.inputs.image, 'uploaded/photo.png');
  assert.equal(depth.class_type, 'DepthAnythingV2');
  assert.deepEqual(depth.inputs.image, ['1', 0]);
  assert.equal(depth.inputs.resolution, 512);
  assert.equal(save.class_type, 'SaveImage');
  assert.deepEqual(save.inputs.images, ['2', 0]);
  assert.equal(save.inputs.filename_prefix, 'depthmap_1234');
});

check('discovered combo defaults use the older array and the newer options form', () => {
  const wf = buildWorkflow(singleNodeInfo, 'DepthAnythingV2', 'photo.png', 1024, 1);
  const inputs = (wf['2'] as { inputs: Record<string, unknown> }).inputs;
  assert.equal(inputs.mode, 'relative');
  assert.equal(inputs.new_combo, 'x');
});

check('the MoGe graph is seven nodes with both savers wired to their own renders', () => {
  const wf = buildWorkflow(moGeInfo, 'moge_3_vitg_fp16.safetensors', 'photo.png', 1024, 99);
  assert.deepEqual(Object.keys(wf).sort(), ['1', '2', '3', '4', '5', '6', '7']);
  const infer = wf['3'] as { inputs: Record<string, unknown> };
  assert.deepEqual(infer.inputs.moge_model, ['2', 0]);
  assert.deepEqual(infer.inputs.image, ['1', 0]);
  const depthRender = wf['4'] as { inputs: Record<string, unknown> };
  const normalRender = wf['6'] as { inputs: Record<string, unknown> };
  assert.deepEqual(depthRender.inputs.moge_geometry, ['3', 0]);
  assert.deepEqual(normalRender.inputs.moge_geometry, ['3', 0]);
  assert.equal(depthRender.inputs.output, 'depth');
  assert.equal(normalRender.inputs.output, 'normal_opengl');
  const depthSave = wf['5'] as { inputs: Record<string, unknown> };
  const normalSave = wf['7'] as { inputs: Record<string, unknown> };
  assert.deepEqual(depthSave.inputs.images, ['4', 0]);
  assert.deepEqual(normalSave.inputs.images, ['6', 0]);
  assert.equal(depthSave.inputs.filename_prefix, 'depthmap_99');
  assert.equal(normalSave.inputs.filename_prefix, 'normal_99');
});

check('MoGeInference as the model keeps the discovered checkpoint default', () => {
  const wf = buildWorkflow(moGeInfo, 'MoGeInference', 'photo.png', 1024, 1);
  const loader = wf['2'] as { inputs: Record<string, unknown> };
  assert.equal(loader.inputs.model_name, 'moge_3_vitg_fp16.safetensors');
});

// -------------------------------------------------------------- discovery

check('usable models are depth-like and drivable, plus the MoGe checkpoints', () => {
  const info: ObjectInfo = { ...singleNodeInfo, ...moGeInfo };
  const models = usableDepthModels(info);
  assert.ok(models.includes('DepthAnythingV2'));
  assert.ok(models.includes('moge_3_vitg_fp16.safetensors'));
  assert.ok(models.includes('moge_2_vitb.safetensors'));
  assert.equal(hasDepthNode(info), true);
  assert.equal(isMoGeModel(info, 'moge_3_vitg_fp16.safetensors'), true);
  assert.equal(isMoGeModel(info, 'DepthAnythingV2'), false);
});

check('a depth node with a required handle type is not offered', () => {
  const info: ObjectInfo = {
    SomeDepthNode: {
      input: {
        required: {
          image: ['IMAGE'],
          mask: ['MASK'],
        },
      },
      output: ['IMAGE'],
    },
  };
  assert.equal(canBuildWorkflow(info.SomeDepthNode as never), false);
  assert.deepEqual(usableDepthModels(info), []);
  assert.equal(hasDepthNode(info), true);
});

check('a node whose first output is not an image is not offered', () => {
  const info: ObjectInfo = {
    DepthModel: {
      input: { required: { image: ['IMAGE'] } },
      output: ['MASK'],
    },
  };
  assert.deepEqual(usableDepthModels(info), []);
});

check('the remembered model is ranked first without inventing one', () => {
  assert.deepEqual(rankModels(['a', 'b', 'c'], 'b'), ['b', 'a', 'c']);
  assert.deepEqual(rankModels(['a', 'b'], 'gone'), ['a', 'b']);
  assert.deepEqual(rankModels(['a', 'b'], ''), ['a', 'b']);
});

// ---------------------------------------------------------------- history

check('an absent history entry is "still running", not a failure', () => {
  assert.deepEqual(classifyHistory(null, true), { kind: 'running' });
});

check('an entry with outputs but completed=false is still running', () => {
  const entry: HistoryEntry = {
    status: { completed: false },
    outputs: { 5: { images: [{ filename: 'depthmap_1.png' }] } },
  };
  assert.deepEqual(classifyHistory(entry, true), { kind: 'running' });
});

check('a completed entry finds depth and normal by prefix, not by node order', () => {
  const entry: HistoryEntry = {
    status: { completed: true, status_str: 'success' },
    outputs: {
      '7': { images: [{ filename: 'normal_123.png', subfolder: '', type: 'output' }] },
      '5': { images: [{ filename: 'depthmap_123.png', subfolder: 'out', type: 'output' }] },
    },
  };
  const outcome = classifyHistory(entry, true);
  assert.equal(outcome.kind, 'done');
  if (outcome.kind !== 'done') return;
  assert.equal(outcome.depth.filename, 'depthmap_123.png');
  assert.equal(outcome.depth.subfolder, 'out');
  assert.equal(outcome.normal?.filename, 'normal_123.png');
});

check('a MoGe run that lost its normal is a failure, not a flat render', () => {
  const entry: HistoryEntry = {
    status: { completed: true },
    outputs: { 5: { images: [{ filename: 'depthmap_1.png' }] } },
  };
  const outcome = classifyHistory(entry, true);
  assert.equal(outcome.kind, 'failed');
});

check('a completed entry with no depth output is a failure with a sentence', () => {
  const entry: HistoryEntry = { status: { completed: true }, outputs: {} };
  const outcome = classifyHistory(entry, false);
  assert.equal(outcome.kind, 'failed');
  if (outcome.kind === 'failed') assert.match(outcome.detail, /no depth output/);
});

check('the server’s execution_error words are extracted', () => {
  const detail = extractExecutionError({
    status_str: 'error',
    messages: [
      ['execution_start', {}],
      ['execution_error', { exception_type: 'RuntimeError', exception_message: 'CUDA out of memory' }],
    ],
  });
  assert.equal(detail, 'RuntimeError: CUDA out of memory');
  assert.match(extractExecutionError(undefined), /execution error/);
});

// -------------------------------------------------------------- the address

check('the base URL normalises without inventing a scheme on the proxy path', () => {
  assert.equal(normaliseBaseUrl(''), '/comfy');
  assert.equal(normaliseBaseUrl('/comfy/'), '/comfy');
  assert.equal(normaliseBaseUrl('http://box:8188/'), 'http://box:8188');
  assert.equal(normaliseBaseUrl('box:8188'), 'http://box:8188');
});

check('the CORS failure names both routes rather than saying nothing', () => {
  setBaseUrl('http://box:8188');
  const sentence = describeComfyError(new ComfyError('network', 'the request failed', 'Failed to fetch'));
  assert.match(sentence, /Failed to fetch/);
  assert.match(sentence, /CORS/);
  assert.match(sentence, /\/comfy/);
  setBaseUrl('/comfy');
});

check('friendly names read as models rather than class names', () => {
  assert.equal(friendlyName('DepthAnythingV2'), 'Depth Anything V2');
  assert.equal(friendlyName('moge_3_vitg_fp16.safetensors'), 'Moge 3 Vitg Fp16');
  assert.equal(friendlyName('MiDaS'), 'MiDaS');
});

// ------------------------------------------------------------ the SHARP graph

const sharpInfo: ObjectInfo = {
  SharpStill: {
    input: {
      required: {
        image: ['IMAGE'],
        internal_size: ['INT', { default: 1536, min: 384, max: 3072, step: 288 }],
        budget: ['INT', { default: 150000, min: 0, max: 1000000000 }],
        tag: ['STRING', { default: '' }],
        focal_px: ['FLOAT', { default: 0.0, min: 0.0, max: 100000.0, step: 1.0 }],
        checkpoint: ['STRING', { default: '' }],
        keep_loaded: ['BOOLEAN', { default: false }],
      },
    },
  },
};

check('the SHARP graph is two nodes: LoadImage -> SharpStill, one wire', () => {
  const wf = sharpWorkflow(sharpInfo, 'photo.png', 'run-1', 300_000);
  assert.deepEqual(Object.keys(wf).sort(), ['1', '2']);
  const load = wf['1'] as { class_type: string; inputs: Record<string, unknown> };
  const sharp = wf['2'] as { class_type: string; inputs: Record<string, unknown> };
  assert.equal(load.class_type, 'LoadImage');
  assert.equal(load.inputs.image, 'photo.png');
  assert.equal(sharp.class_type, 'SharpStill');
  assert.deepEqual(sharp.inputs.image, ['1', 0]);
  assert.equal(sharp.inputs.internal_size, SHARP_INTERNAL_SIZE);
  assert.equal(sharp.inputs.budget, 300_000);
  assert.equal(sharp.inputs.tag, 'run-1');
  assert.equal(sharp.inputs.focal_px, 0);
  assert.equal(sharp.inputs.keep_loaded, false);
  // The discovered scalar the flow does NOT own is still sent at its default:
  // a node that adds or retunes an input is honoured rather than stripped.
  assert.equal(sharp.inputs.checkpoint, '');
});

check('the SHARP graph refuses a server without the node, rather than substituting', () => {
  assert.equal(hasSharpStill(sharpInfo), true);
  assert.equal(hasSharpStill(singleNodeInfo), false);
  assert.throws(
    () => sharpWorkflow(singleNodeInfo, 'photo.png', 'run-1', SHARP_BUDGET),
    (err: unknown) => err instanceof ComfyError && err.kind === 'no-node' && /SharpStill/.test(err.message),
  );
});

check('the budget is the app’s own value and range', () => {
  assert.equal(SHARP_BUDGET, 150_000);
  assert.equal(SHARP_BUDGET_MIN, 150_000);
  assert.equal(SHARP_BUDGET_MAX, 1_200_000);
  assert.equal(clampSharpBudget(0), SHARP_BUDGET);
  assert.equal(clampSharpBudget(Number.NaN), SHARP_BUDGET);
  assert.equal(clampSharpBudget(1_000), SHARP_BUDGET_MIN);
  assert.equal(clampSharpBudget(741_000), 741_000);
  assert.equal(clampSharpBudget(9_000_000), SHARP_BUDGET_MAX);
  const wf = sharpWorkflow(sharpInfo, 'photo.png', 't', 9_000_000);
  assert.equal((wf['2'] as { inputs: Record<string, unknown> }).inputs.budget, SHARP_BUDGET_MAX);
});

check('the tag rule is contract/tag’s, character for character', () => {
  const fixture = JSON.parse(readFileSync(join(contractDir, 'tag', 'cases.json'), 'utf8')) as {
    maxChars: number;
    cases: Array<{ id: string; tag: string }>;
    names: { id: string; tag: string; splat: { cloud: string; sidecar: string } };
  };
  for (const row of fixture.cases) {
    assert.equal(sanitiseTag(row.id), row.tag, `tag for ${JSON.stringify(row.id)}`);
  }
  // The names both sides derive WITHOUT a response — the node writes them and
  // this page fetches them by exactly this string.
  assert.equal(sharpSplatName(fixture.names.tag), fixture.names.splat.cloud);
  assert.equal(sharpSidecarName(fixture.names.tag), fixture.names.splat.sidecar);
  assert.equal(fixture.maxChars, 48);
});

check('a splat run is finished only by completed, and an absent entry is running', () => {
  assert.deepEqual(classifySplatHistory(null), { kind: 'running' });
  assert.deepEqual(classifySplatHistory({ status: { completed: false }, outputs: {} }), {
    kind: 'running',
  });
  assert.deepEqual(classifySplatHistory({ status: { completed: true } }), { kind: 'done' });
  const failed = classifySplatHistory({
    status: {
      status_str: 'error',
      messages: [['execution_error', { exception_type: 'RuntimeError', exception_message: 'CUDA out of memory' }]],
    },
  });
  assert.equal(failed.kind, 'failed');
  if (failed.kind === 'failed') assert.match(failed.detail, /CUDA out of memory/);
});

check('the splat filenames are the tag’s, not a fixed name another run can answer', () => {
  assert.equal(sharpSplatName('abc'), 'basrelief_abc.splat');
  assert.equal(sharpSidecarName('abc'), 'basrelief_abc.splat.json');
  // An id with nothing taggable yields the empty tag, and the names say so
  // rather than silently falling back to a name another run may own.
  assert.equal(sanitiseTag(''), '');
  assert.equal(sharpSplatName(''), 'basrelief_.splat');
});

console.log(`\ncomfy: ${checks} checks passed`);
