/**
 * The ComfyUI client and the still depth graph, in TypeScript.
 *
 * **The graph is `ComfyWorkflow.java`'s still shape, not a second invention.** A
 * classical depth node is `LoadImage → <node> → SaveImage`; MoGe is a pipeline
 * (`LoadMoGeModel → MoGeInference → MoGeRender(depth) → SaveImage`, plus a second
 * render/save for the normal). Every scalar the node declares is discovered from
 * `/object_info` and sent at its own default; only the inputs the flow owns are
 * overridden — the uploaded image name, the depth/normal output selection, the
 * saver prefixes — exactly as the Java builder does. A node the app needs but the
 * server does not have is refused with the node's name, never substituted.
 *
 * **The poll is keyed by the prompt id `/prompt` returned, and an absent history
 * entry is not a verdict.** A run queued behind another has no history entry yet,
 * and a completed entry is the only thing that says the run is over. The node's
 * fixed-name sidecar (`basrelief_<tag>.progress.json`) is deliberately never read
 * here: `AGENTS.md` records that it answers for the WRONG run, and every build
 * then finished instantly with the previous run's output.
 *
 * **The address and the model are the app's prefs, not this module's.** They live
 * in `prefs.ts` and are written to `prefs.json` beside `library.json`; this module
 * only reads them at the request and reports the model a run was submitted with.
 *
 * The transport is plain `fetch`. It needs CORS when the page and ComfyUI are on
 * different origins; `vite.config.ts` proxies `/comfy` in development, which is
 * the path this gallery is actually run through. A failed preflight throws a
 * `TypeError` that reads as an unreachable server, so `describeComfyError` says
 * the CORS sentence explicitly rather than leaving a silent nothing.
 */

import { isPngSignature } from './deph.ts';
import {
  DEFAULT_BASE_URL,
  getComfyUrl,
  getModelChoice,
  setModelChoice,
} from './prefs.ts';
import { parseSidecar, type SplatSidecar } from './splat.ts';

// --------------------------------------------------------------------- types

export type ObjectInfo = Record<string, unknown>;

interface RequiredSpec {
  [name: string]: unknown;
}

interface NodeInfo {
  input?: { required?: RequiredSpec } | unknown;
  required?: RequiredSpec;
  output?: unknown;
}

export interface UploadedImage {
  /** The name in ComfyUI's input directory, including its subfolder. */
  uploadedName: string;
  name: string;
  subfolder: string;
}

export interface DepthImageRef {
  filename: string;
  subfolder: string;
  type: string;
}

export type ComfyPhase =
  | 'connecting'
  | 'uploading'
  | 'queued'
  | 'generating'
  | 'fetching';

export type ComfyFailure =
  | 'network'
  | 'http'
  | 'protocol'
  | 'server'
  | 'timeout'
  | 'no-node'
  | 'no-output';

/** A failure with the server's own words where it gave any. */
export class ComfyError extends Error {
  readonly kind: ComfyFailure;
  readonly detail: string;
  readonly status: number;

  constructor(kind: ComfyFailure, message: string, detail = '', status = 0) {
    super(message);
    this.name = 'ComfyError';
    this.kind = kind;
    this.detail = detail;
    this.status = status;
  }
}

// ------------------------------------------------------------------ the address

/**
 * The ComfyUI base URL, formatted for a request.
 *
 * The stored value comes from `prefs.ts`; `/comfy` is what an empty one means,
 * because `pnpm dev` proxies it and that is the path this gallery is run through.
 * A typed address is kept exactly as typed (minus a trailing slash); a bare
 * `host:port` gets `http://`, because `fetch` would otherwise read it as a
 * relative path.
 */
export function normaliseBaseUrl(value: string): string {
  const trimmed = value.trim();
  if (!trimmed) return DEFAULT_BASE_URL;
  if (trimmed.startsWith('/')) return trimmed.replace(/\/+$/, '') || DEFAULT_BASE_URL;
  if (/^https?:\/\//i.test(trimmed)) return trimmed.replace(/\/+$/, '');
  return `http://${trimmed}`.replace(/\/+$/, '');
}

// ------------------------------------------------------------------ the graph

const LOAD_ID = '1';
const DEPTH_ID = '2';
const SAVE_ID = '3';

const MOGE_LOAD_ID = '2';
const MOGE_INFER_ID = '3';
const MOGE_RENDER_ID = '4';
const MOGE_SAVE_ID = '5';
const MOGE_NORMAL_RENDER_ID = '6';
const MOGE_NORMAL_SAVE_ID = '7';

export const MOGE_LOADER = 'LoadMoGeModel';
export const MOGE_INFERENCE = 'MoGeInference';
export const MOGE_RENDER = 'MoGeRender';

const MOGE_STILL_OUTPUT = 'depth';
export const MOGE_NORMAL_OUTPUT = 'normal_opengl';

/** The saver prefixes the fetch path uses to tell the two outputs apart. */
export const DEPTH_FILE_PREFIX = 'depthmap_';
export const NORMAL_FILE_PREFIX = 'normal_';

/** The app's default resolution for a node that declares a `resolution` input. */
export const DEFAULT_RESOLUTION = 1024;

function nodeInfo(info: ObjectInfo, name: string): NodeInfo | null {
  const node = info[name];
  return typeof node === 'object' && node !== null ? (node as NodeInfo) : null;
}

function requiredInputs(node: NodeInfo | null): RequiredSpec {
  if (!node) return {};
  const input = node.input;
  if (typeof input === 'object' && input !== null) {
    const required = (input as { required?: unknown }).required;
    if (typeof required === 'object' && required !== null) return required as RequiredSpec;
  }
  // Some builds put them under a bare `required`; both are accepted, as in Java.
  if (typeof node.required === 'object' && node.required !== null) {
    return node.required as RequiredSpec;
  }
  return {};
}

function requiredSpec(node: NodeInfo | null, name: string): unknown[] | null {
  const spec = requiredInputs(node)[name];
  return Array.isArray(spec) ? spec : null;
}

function defaultForType(type: string): unknown {
  if (type === 'INT') return 0;
  if (type === 'FLOAT') return 0.0;
  if (type === 'BOOLEAN') return true;
  return '';
}

function fallbackFor(typeObj: unknown): unknown {
  if (Array.isArray(typeObj)) return typeObj.length > 0 ? typeObj[0] : '';
  return defaultForType(typeof typeObj === 'string' ? typeObj : '');
}

/**
 * The scalar values a node's required inputs need, from `/object_info`.
 * Inputs named in `wired` are skipped: the caller supplies their links.
 *
 * Two combo spellings are accepted, exactly as the Java copy does: the older one
 * holds the option array in the type slot, the newer one names `COMBO` and nests
 * `options` in the options object.
 */
export function discoveredInputs(node: NodeInfo | null, ...wired: string[]): Record<string, unknown> {
  const required = requiredInputs(node);
  const out: Record<string, unknown> = {};
  for (const [name, raw] of Object.entries(required)) {
    if (wired.includes(name)) continue;
    if (!Array.isArray(raw) || raw.length === 0) continue;
    const typeObj = raw[0];
    const def = raw.length > 1 ? raw[1] : null;
    let value: unknown;
    if (typeof def === 'object' && def !== null && !Array.isArray(def)) {
      const opts = def as { default?: unknown; options?: unknown };
      if ('default' in opts) value = opts.default;
      else if (Array.isArray(opts.options) && opts.options.length > 0) value = opts.options[0];
      else value = fallbackFor(typeObj);
    } else if (def === null) {
      value = fallbackFor(typeObj);
    } else {
      value = def;
    }
    out[name] = value;
  }
  return out;
}

/** `["<node id>", <output index>]` — the graph's one link spelling. */
function link(nodeId: string, outputIndex = 0): [string, number] {
  return [nodeId, outputIndex];
}

function loadImageNode(uploadedName: string): Record<string, unknown> {
  return { class_type: 'LoadImage', inputs: { image: uploadedName } };
}

function saveImageNode(
  sourceId: string,
  outputIndex: number,
  prefix: string,
  timestampMs: number,
): Record<string, unknown> {
  return {
    class_type: 'SaveImage',
    inputs: {
      images: link(sourceId, outputIndex),
      filename_prefix: `${prefix}${timestampMs}`,
    },
  };
}

/** One `MoGeRender`, with its output and the inference it reads forced. */
function moGeRenderNode(
  renderInfo: NodeInfo | null,
  output: string,
  inferenceId: string,
): Record<string, unknown> {
  const inputs = discoveredInputs(renderInfo, 'moge_geometry');
  inputs.moge_geometry = link(inferenceId);
  inputs.output = output;
  return { class_type: MOGE_RENDER, inputs };
}

/** Whether the server offers MoGe's whole three-node pipeline. */
function hasMoGePipeline(info: ObjectInfo): boolean {
  return (
    nodeInfo(info, MOGE_LOADER) !== null &&
    nodeInfo(info, MOGE_INFERENCE) !== null &&
    nodeInfo(info, MOGE_RENDER) !== null
  );
}

/** The MoGe checkpoints `LoadMoGeModel.model_name` offers. */
function moGeCheckpoints(info: ObjectInfo): string[] {
  if (!hasMoGePipeline(info)) return [];
  const spec = requiredSpec(nodeInfo(info, MOGE_LOADER), 'model_name');
  if (!spec) return [];
  let options: unknown = spec[0];
  if (!Array.isArray(options)) {
    const def = spec.length > 1 ? spec[1] : null;
    if (typeof def === 'object' && def !== null) {
      options = (def as { options?: unknown }).options;
    }
  }
  if (!Array.isArray(options)) return [];
  return options.filter((option): option is string => typeof option === 'string');
}

/**
 * Whether a model argument names the MoGe pipeline rather than an ordinary node.
 * Public because it is also the answer to "does this run emit a normal".
 */
export function isMoGeModel(info: ObjectInfo, model: string | null): boolean {
  if (!model || !hasMoGePipeline(info)) return false;
  if (model === MOGE_INFERENCE) return true;
  if (model in info) return false; // a real top-level node is never a checkpoint
  return moGeCheckpoints(info).includes(model);
}

function buildMoGeWorkflow(
  info: ObjectInfo,
  model: string,
  uploadedName: string,
  timestampMs: number,
): Record<string, unknown> {
  const loaderInfo = nodeInfo(info, MOGE_LOADER);
  const inferInfo = nodeInfo(info, MOGE_INFERENCE);
  const renderInfo = nodeInfo(info, MOGE_RENDER);
  if (!loaderInfo || !inferInfo || !renderInfo) {
    throw new ComfyError(
      'no-node',
      'The server does not offer the MoGe pipeline this page builds (LoadMoGeModel, MoGeInference and MoGeRender are all required).',
    );
  }

  const loaderInputs = discoveredInputs(loaderInfo);
  if (model !== MOGE_INFERENCE) loaderInputs.model_name = model;

  const inferInputs = discoveredInputs(inferInfo, 'moge_model', 'image');
  inferInputs.moge_model = link(MOGE_LOAD_ID);
  inferInputs.image = link(LOAD_ID);

  return {
    [LOAD_ID]: loadImageNode(uploadedName),
    [MOGE_LOAD_ID]: { class_type: MOGE_LOADER, inputs: loaderInputs },
    [MOGE_INFER_ID]: { class_type: MOGE_INFERENCE, inputs: inferInputs },
    [MOGE_RENDER_ID]: moGeRenderNode(renderInfo, MOGE_STILL_OUTPUT, MOGE_INFER_ID),
    [MOGE_SAVE_ID]: saveImageNode(MOGE_RENDER_ID, 0, DEPTH_FILE_PREFIX, timestampMs),
    [MOGE_NORMAL_RENDER_ID]: moGeRenderNode(renderInfo, MOGE_NORMAL_OUTPUT, MOGE_INFER_ID),
    [MOGE_NORMAL_SAVE_ID]: saveImageNode(MOGE_NORMAL_RENDER_ID, 0, NORMAL_FILE_PREFIX, timestampMs),
  };
}

/**
 * Assemble the still graph for the chosen model.
 *
 * `model` is whichever identifier `usableDepthModels` offered: an ordinary node
 * class name, one of the `LoadMoGeModel.model_name` filenames, or
 * `MoGeInference` for the server's own default checkpoint.
 */
export function buildWorkflow(
  info: ObjectInfo,
  model: string,
  uploadedName: string,
  resolution: number,
  timestampMs: number,
): Record<string, unknown> {
  if (isMoGeModel(info, model)) {
    return buildMoGeWorkflow(info, model, uploadedName, timestampMs);
  }

  const node = nodeInfo(info, model);
  if (!node) {
    throw new ComfyError('no-node', `The server has no node called ${model}.`);
  }
  const depthInputs = discoveredInputs(node, 'image');
  // The app's resolution slider only overrides a node that declares one: an
  // input a node does not have would be rejected by the server.
  if ('resolution' in depthInputs) depthInputs.resolution = resolution;
  depthInputs.image = link(LOAD_ID);

  return {
    [LOAD_ID]: loadImageNode(uploadedName),
    [DEPTH_ID]: { class_type: model, inputs: depthInputs },
    [SAVE_ID]: saveImageNode(DEPTH_ID, 0, DEPTH_FILE_PREFIX, timestampMs),
  };
}

// -------------------------------------------------------------- the SHARP graph

/**
 * The custom node that turns one still into a Gaussian splat cloud.
 *
 * `ComfyWorkflow.SHARP_STILL`, and the graph is that class's own still-splat
 * shape: **two nodes, one wire**, `LoadImage -> SharpStill`. The node does the
 * reconstruction and writes the cloud in the phone's 32-byte record format plus a
 * JSON sidecar, both under names derived from the `tag` it was given — which is
 * what lets a client fetch a finished run it never saw a response for. Every
 * scalar is discovered from `/object_info` at its own default; the five the flow
 * owns (the uploaded name, `tag`, `budget`, `internal_size`, `keep_loaded`) are
 * stated.
 *
 * **A server without the node is a sentence, never a substitute.** A depth node
 * is not a cloud generator, and a graph built against a missing class is a prompt
 * the server refuses with a node error nobody reads.
 */
export const SHARP_STILL = 'SharpStill';

/** `ComfyWorkflow.SHARP_INTERNAL_SIZE`: SHARP's reference inference size. */
export const SHARP_INTERNAL_SIZE = 1536;

/**
 * The cloud's default budget, in splats: a CEILING on the file, and the one
 * number this flow states rather than discovers.
 *
 * `ComfyWorkflow.SHARP_BUDGET`'s number and its reason: 150,000 is the node's own
 * conservative default and the count the phone's performance work measured
 * (~55 fps with culling). The node rounds it down to a whole 2048-splat block, so
 * the written count can never exceed it (measured: 150000 -> 149504).
 */
export const SHARP_BUDGET = 150_000;

/**
 * The budget range: the ladder a user compares by eye.
 *
 * `ComfyWorkflow.SHARP_BUDGET_MIN`/`_MAX`. The app's measured verdict is **150k
 * soft, 741k right, 1.05M indistinguishable from full** — and on the measured
 * phone 741k ≈ 18 fps while 1.05M ≈ 13, against ~5 fps at 1M on a 2018 phone.
 * Those are the app's own numbers, stated as what they are: a phone measurement,
 * not a promise about the machine drawing this page.
 */
export const SHARP_BUDGET_MIN = 150_000;
export const SHARP_BUDGET_MAX = 1_200_000;

/** The budget a stored value means. Non-positive and absurd values answer the default. */
export function clampSharpBudget(budget: number): number {
  if (!Number.isFinite(budget) || budget <= 0) return SHARP_BUDGET;
  if (budget < SHARP_BUDGET_MIN) return SHARP_BUDGET_MIN;
  if (budget > SHARP_BUDGET_MAX) return SHARP_BUDGET_MAX;
  return budget;
}

/** `ComfyWorkflow.TAG_MAX_CHARS`; see `contract/tag/`. */
export const TAG_MAX_CHARS = 48;

/**
 * The tag rule: keep the ASCII characters `[A-Za-z0-9_-]`, in order, then the
 * first 48. The contract's fourth copy (`contract/tag/cases.json`).
 *
 * It is ASCII so "48" is 48 bytes, 48 UTF-16 units and 48 code points at once,
 * which is why the unit is not a question; nothing is trimmed or normalised. The
 * filename this page must fetch is derived from the tag exactly as the app's is,
 * so a character dropped on one side is a 404 on a run that finished.
 */
export function sanitiseTag(id: string | null): string {
  if (!id) return '';
  let out = '';
  for (let i = 0; i < id.length && out.length < TAG_MAX_CHARS; i++) {
    const c = id.charCodeAt(i);
    const keep =
      (c >= 0x30 && c <= 0x39) || // 0-9
      (c >= 0x41 && c <= 0x5a) || // A-Z
      (c >= 0x61 && c <= 0x7a) || // a-z
      c === 0x2d || // -
      c === 0x5f; // _
    if (keep) out += id[i];
  }
  return out;
}

/** `ComfyWorkflow.STREAM_FILE_PREFIX` + a tag + the cloud's suffix. */
export function sharpSplatName(tag: string): string {
  return `basrelief_${tag}.splat`;
}

/** The sidecar's name: the cloud's path plus `.json`. */
export function sharpSidecarName(tag: string): string {
  return `basrelief_${tag}.splat.json`;
}

/** Whether the server offers the one node this flow submits. */
export function hasSharpStill(info: ObjectInfo): boolean {
  return nodeInfo(info, SHARP_STILL) !== null;
}

/** A tag for a run this page starts. Unique per submission, and ASCII by rule. */
export function newTag(): string {
  const stamp = Date.now().toString(36);
  const salt = Math.random().toString(36).slice(2, 8);
  return sanitiseTag(`w${stamp}-${salt}`);
}

/**
 * Assemble the cloud graph: `LoadImage -> SharpStill`.
 *
 * Everything the node declares is discovered and sent at its own default, then
 * overridden with the values this flow owns — `image` is a link, `tag` names the
 * two files, `budget` is the ceiling, `internal_size` is SHARP's reference size,
 * `focal_px` is 0 ("unknown", the node's 30 mm fallback; the page has no EXIF
 * lens to hand in), and `keep_loaded` is false so the predictor's ~2.8 GB goes
 * back to the shared card at the end. `checkpoint` is deliberately left at the
 * node's own discovered default: the page has no reason to name a model file.
 */
export function sharpWorkflow(
  info: ObjectInfo,
  uploadedName: string,
  tag: string,
  budget: number,
): Record<string, unknown> {
  const node = nodeInfo(info, SHARP_STILL);
  if (!node) {
    throw new ComfyError(
      'no-node',
      'The server has no SharpStill node, so it cannot build a splat cloud.',
      'A depth node is not a cloud generator; install server/comfy-node or point this page at a server that has it.',
    );
  }
  const inputs = discoveredInputs(node, 'image');
  inputs.image = link(LOAD_ID);
  inputs.internal_size = SHARP_INTERNAL_SIZE;
  inputs.budget = clampSharpBudget(budget);
  inputs.tag = sanitiseTag(tag);
  inputs.focal_px = 0;
  inputs.keep_loaded = false;
  return {
    [LOAD_ID]: loadImageNode(uploadedName),
    ['2']: { class_type: SHARP_STILL, inputs },
  };
}

/**
 * Read a SHARP run's history entry.
 *
 * **`completed` is the gate**, exactly as for depth: a running entry can already
 * carry outputs, and a prompt queued behind another has no entry at all. What it
 * does NOT do is read the output list: the two filenames are derived from the
 * tag this page submitted, which is the app's own addressing rule and the reason
 * a finished run can be fetched by a client that never saw a response. Reading a
 * filename out of `outputs` would be a second, weaker derivation of a name that
 * is already certain.
 */
export function classifySplatHistory(entry: HistoryEntry | null): HistoryOutcomeNoImage {
  if (!entry) return { kind: 'running' };
  const status = entry.status;
  if (status && status.status_str === 'error') {
    return { kind: 'failed', detail: extractExecutionError(status) };
  }
  if (!status || status.completed !== true) return { kind: 'running' };
  return { kind: 'done' };
}

export type HistoryOutcomeNoImage =
  | { kind: 'running' }
  | { kind: 'failed'; detail: string }
  | { kind: 'done' };

// --------------------------------------------------------------- model choice

function looksLikeDepthModel(name: string): boolean {
  const lower = name.toLowerCase();
  return (
    lower.includes('depth') ||
    lower.includes('midas') ||
    lower.includes('zoe') ||
    lower.includes('moge')
  );
}

function isPlainInputType(type: unknown): boolean {
  return (
    type === 'INT' ||
    type === 'FLOAT' ||
    type === 'BOOLEAN' ||
    type === 'STRING' ||
    type === 'COMBO'
  );
}

/**
 * Whether this page can drive a node from one uploaded image and its defaults.
 * Mirrors `ComfyWorkflow.canBuildWorkflow`: `image` must be an IMAGE input and
 * every other required input must be a plain scalar or a combo. A required
 * handle type cannot be supplied by a client that has no other node to wire.
 */
export function canBuildWorkflow(node: NodeInfo | null): boolean {
  if (!node) return false;
  const required = requiredInputs(node);
  const names = Object.keys(required);
  if (names.length === 0) return false;
  const output = node.output;
  if (Array.isArray(output) && output.length > 0 && output[0] !== 'IMAGE') return false;

  let hasImage = false;
  for (const name of names) {
    const spec = required[name];
    if (!Array.isArray(spec) || spec.length === 0) return false;
    const type = spec[0];
    if (name === 'image') {
      if (type !== 'IMAGE') return false;
      hasImage = true;
    } else if (!Array.isArray(type) && !isPlainInputType(type)) {
      return false;
    }
  }
  return hasImage;
}

/** Every model this page can build a still-depth graph for, sorted by name. */
export function usableDepthModels(info: ObjectInfo): string[] {
  const usable: string[] = [];
  for (const name of Object.keys(info)) {
    if (!looksLikeDepthModel(name)) continue;
    if (canBuildWorkflow(nodeInfo(info, name))) usable.push(name);
  }
  usable.push(...moGeCheckpoints(info));
  usable.sort();
  return usable;
}

/** Whether the server names any depth-like node at all, usable or not. */
export function hasDepthNode(info: ObjectInfo): boolean {
  return Object.keys(info).some(looksLikeDepthModel);
}

/** Human names for the chooser, matching `ComfyWorkflow.friendlyName`. */
export function friendlyName(name: string): string {
  if (name.includes('DepthAnythingV2')) return 'Depth Anything V2';
  if (name.includes('DepthAnything')) return 'Depth Anything V1';
  const lower = name.toLowerCase();
  if (lower.includes('midas')) return 'MiDaS';
  if (lower.includes('zoe')) return 'ZoeDepth';
  if (lower.includes('moge')) {
    if (name.includes(MOGE_LOADER)) return 'Load MoGe Model';
    if (name.includes(MOGE_RENDER)) return 'MoGe Render';
    if (name.includes(MOGE_INFERENCE)) return 'MoGe Inference';
    const base = name.split('.')[0];
    return base
      .split(/[_-]+/)
      .filter(Boolean)
      .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
      .join(' ');
  }
  return name
    .replace(/-/g, ' ')
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2');
}

// ---------------------------------------------------------------- the history

export interface HistoryMessage {
  status_str?: string;
  completed?: boolean;
  messages?: unknown[];
}

export interface HistoryEntry {
  status?: HistoryMessage;
  outputs?: Record<string, { images?: Array<Record<string, unknown>> }>;
}

export type HistoryOutcome =
  | { kind: 'running' }
  | { kind: 'failed'; detail: string }
  | { kind: 'done'; depth: DepthImageRef; normal: DepthImageRef | null };

/** `ComfyWorkflow.extractExecutionError`: the server's own words for a failure. */
export function extractExecutionError(status: HistoryMessage | undefined): string {
  if (status && Array.isArray(status.messages)) {
    for (const message of status.messages) {
      if (!Array.isArray(message) || message.length < 2) continue;
      if (message[0] !== 'execution_error') continue;
      const error = message[1];
      if (typeof error === 'object' && error !== null) {
        const typed = error as { exception_type?: unknown; exception_message?: unknown };
        const type = typeof typed.exception_type === 'string' ? typed.exception_type : '';
        const text = typeof typed.exception_message === 'string' ? typed.exception_message : '';
        if (type) return `${type}: ${text}`;
      }
    }
  }
  return 'execution error (see the ComfyUI console)';
}

function imageRef(raw: Record<string, unknown> | undefined): DepthImageRef | null {
  if (!raw || typeof raw.filename !== 'string' || raw.filename.length === 0) return null;
  return {
    filename: raw.filename,
    subfolder: typeof raw.subfolder === 'string' ? raw.subfolder : '',
    type: typeof raw.type === 'string' ? raw.type : 'output',
  };
}

/**
 * Read one history entry.
 *
 * `completed` is the gate, not `outputs.length > 0`: a running entry can already
 * carry outputs, and the old in-flight poll took that as finished, then failed
 * because the depth file was not there yet. An absent entry is `running`, because
 * a prompt queued behind another has none — that is not a verdict.
 */
export function classifyHistory(entry: HistoryEntry | null, normalExpected: boolean): HistoryOutcome {
  if (!entry) return { kind: 'running' };
  const status = entry.status;
  if (status && status.status_str === 'error') {
    return { kind: 'failed', detail: extractExecutionError(status) };
  }
  if (!status || status.completed !== true) return { kind: 'running' };

  const outputs = entry.outputs;
  if (!outputs) {
    return { kind: 'failed', detail: 'the run finished with no output in its history' };
  }

  // Two SaveImage nodes run in one prompt for a MoGe run, so the outputs are
  // classified by the filename prefix the builder wrote rather than by taking
  // the first node. A fetch path that read only the first image would silently
  // drop the normal, and a MoGe photo would render flat with no error anywhere.
  let depth: DepthImageRef | null = null;
  let normal: DepthImageRef | null = null;
  for (const node of Object.values(outputs)) {
    if (!node || !Array.isArray(node.images)) continue;
    for (const raw of node.images) {
      const ref = imageRef(raw);
      if (!ref) continue;
      if (!depth && ref.filename.startsWith(DEPTH_FILE_PREFIX)) depth = ref;
      else if (!normal && ref.filename.startsWith(NORMAL_FILE_PREFIX)) normal = ref;
    }
  }
  if (!depth) return { kind: 'failed', detail: 'the run produced no depth output' };
  if (normalExpected && !normal) {
    return { kind: 'failed', detail: 'the run produced no normal map' };
  }
  return { kind: 'done', depth, normal };
}

// ----------------------------------------------------------------- transport

function endpoint(path: string): string {
  return `${normaliseBaseUrl(getComfyUrl())}${path}`;
}

function detailOf(error: unknown): string {
  if (error instanceof Error) return error.message;
  return String(error);
}

async function readJson<T>(
  path: string,
  init: RequestInit,
  signal: AbortSignal | undefined,
): Promise<T> {
  let response: Response;
  try {
    response = await fetch(endpoint(path), { ...init, signal });
  } catch (err) {
    if (err instanceof DOMException && err.name === 'AbortError') throw err;
    throw new ComfyError('network', 'the request failed', detailOf(err));
  }
  const text = await response.text().catch(() => '');
  if (!response.ok) {
    throw new ComfyError(
      'http',
      `ComfyUI answered HTTP ${response.status}`,
      text.slice(0, 600),
      response.status,
    );
  }
  try {
    return JSON.parse(text) as T;
  } catch {
    throw new ComfyError(
      'protocol',
      'ComfyUI did not answer with JSON',
      text.slice(0, 240),
    );
  }
}

/** GET `/object_info` — the map of every installed node. */
export async function getObjectInfo(signal?: AbortSignal): Promise<ObjectInfo> {
  return readJson<ObjectInfo>('/object_info', { method: 'GET' }, signal);
}

/** POST `/upload/image` (multipart) — returns `{name, subfolder}`. */
export async function uploadImage(file: File, signal?: AbortSignal): Promise<UploadedImage> {
  const form = new FormData();
  form.append('overwrite', 'true');
  form.append('image', file, file.name || 'photo.png');
  let response: Response;
  try {
    response = await fetch(endpoint('/upload/image'), { method: 'POST', body: form, signal });
  } catch (err) {
    if (err instanceof DOMException && err.name === 'AbortError') throw err;
    throw new ComfyError('network', 'the upload failed', detailOf(err));
  }
  const text = await response.text().catch(() => '');
  if (!response.ok) {
    throw new ComfyError(
      'http',
      `ComfyUI refused the upload (HTTP ${response.status})`,
      text.slice(0, 600),
      response.status,
    );
  }
  let body: { name?: unknown; subfolder?: unknown };
  try {
    body = JSON.parse(text) as { name?: unknown; subfolder?: unknown };
  } catch {
    throw new ComfyError('protocol', 'the upload answer was not JSON', text.slice(0, 240));
  }
  if (typeof body.name !== 'string' || body.name.length === 0) {
    throw new ComfyError('protocol', 'the upload answer named no image', text.slice(0, 240));
  }
  const subfolder = typeof body.subfolder === 'string' ? body.subfolder : '';
  return {
    name: body.name,
    subfolder,
    uploadedName: subfolder ? `${subfolder}/${body.name}` : body.name,
  };
}

/** POST `/prompt` — returns the prompt id this page polls, and nothing else. */
export async function submitPrompt(
  prompt: Record<string, unknown>,
  clientId: string,
  signal?: AbortSignal,
): Promise<string> {
  const body = JSON.stringify({ prompt, client_id: clientId });
  const answer = await readJson<{
    prompt_id?: unknown;
    error?: unknown;
    node_errors?: unknown;
  }>('/prompt', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body,
  }, signal);

  if (answer.error) {
    const detail =
      typeof answer.error === 'object'
        ? JSON.stringify(answer.node_errors ? { error: answer.error, node_errors: answer.node_errors } : answer.error)
        : String(answer.error);
    throw new ComfyError('server', 'ComfyUI refused the prompt', detail.slice(0, 900));
  }
  if (typeof answer.prompt_id !== 'string' || answer.prompt_id.length === 0) {
    throw new ComfyError('protocol', 'ComfyUI accepted the prompt but named no prompt id');
  }
  return answer.prompt_id;
}

/** GET `/history/{id}` — the entry, or null while the prompt is still queued. */
export async function getHistory(
  promptId: string,
  signal?: AbortSignal,
): Promise<HistoryEntry | null> {
  const answer = await readJson<Record<string, HistoryEntry>>(
    `/history/${encodeURIComponent(promptId)}`,
    { method: 'GET' },
    signal,
  );
  const entry = answer[promptId];
  return entry && typeof entry === 'object' ? entry : null;
}

/** GET `/view` — one produced file, or null when the server does not have it. */
async function fetchView(
  file: DepthImageRef,
  signal: AbortSignal | undefined,
  what: string,
): Promise<Uint8Array | null> {
  const query =
    `?filename=${encodeURIComponent(file.filename)}` +
    `&subfolder=${encodeURIComponent(file.subfolder)}` +
    `&type=${encodeURIComponent(file.type)}`;
  let response: Response;
  try {
    response = await fetch(endpoint(`/view${query}`), { method: 'GET', signal });
  } catch (err) {
    if (err instanceof DOMException && err.name === 'AbortError') throw err;
    throw new ComfyError('network', `fetching the ${what} failed`, detailOf(err));
  }
  if (response.status === 404) return null;
  if (!response.ok) {
    throw new ComfyError(
      'http',
      `ComfyUI could not return the ${what} (HTTP ${response.status})`,
      '',
      response.status,
    );
  }
  return new Uint8Array(await response.arrayBuffer());
}

/** GET `/view` — a produced PNG that must be there. */
export async function viewImage(
  image: DepthImageRef,
  signal?: AbortSignal,
): Promise<Uint8Array> {
  const bytes = await fetchView(image, signal, 'depth map');
  if (bytes === null) {
    throw new ComfyError('no-output', 'ComfyUI has no depth map at the name the run stated');
  }
  if (bytes.length === 0) {
    throw new ComfyError('no-output', 'ComfyUI returned an empty depth image');
  }
  if (!isPngSignature(bytes)) {
    throw new ComfyError('no-output', 'the depth output is not a PNG', 'no PNG signature');
  }
  return bytes;
}

/** GET `/view` — any produced file (a `.splat`), by the name the tag derives. */
export async function viewBytes(
  file: DepthImageRef,
  signal: AbortSignal | undefined,
  what: string,
): Promise<Uint8Array> {
  const bytes = await fetchView(file, signal, what);
  if (bytes === null) {
    throw new ComfyError(
      'no-output',
      `ComfyUI has no ${what} at the name this run derived`,
      file.filename,
    );
  }
  if (bytes.length === 0) {
    throw new ComfyError('no-output', `ComfyUI returned an empty ${what}`);
  }
  return bytes;
}

/**
 * GET `/view` — a file that may legitimately be absent.
 *
 * The sidecar is the case: a run that wrote its cloud but not its sidecar still
 * has a cloud, and "no lens" is a real answer (the cloud draws at the app's
 * fallback look). A 404 is therefore null, not a failure; only a transport or
 * server error throws.
 */
export async function viewIfPresent(
  file: DepthImageRef,
  signal?: AbortSignal,
): Promise<Uint8Array | null> {
  return fetchView(file, signal, 'lens sidecar');
}

// ------------------------------------------------------------------- the run

export const POLL_INTERVAL_MS = 1000;
export const POLL_TIMEOUT_MS = 10 * 60 * 1000;

export interface DepthRunOptions {
  file: File;
  /** A model from `usableDepthModels`; absent means the remembered/first one. */
  model?: string;
  signal?: AbortSignal;
  onPhase?: (phase: ComfyPhase) => void;
}

export interface DepthRunResult {
  /** The depth image exactly as the server returned it (a PNG). */
  depthPng: Uint8Array;
  model: string;
  normalReturned: boolean;
}

function newClientId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `basrelief-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
}

function sleep(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const timer = window.setTimeout(() => {
      signal?.removeEventListener('abort', onAbort);
      resolve();
    }, ms);
    const onAbort = (): void => {
      window.clearTimeout(timer);
      reject(new DOMException('aborted', 'AbortError'));
    };
    if (signal?.aborted) onAbort();
    else signal?.addEventListener('abort', onAbort, { once: true });
  });
}

/**
 * Upload, submit, poll by the returned prompt id, and fetch the depth image.
 *
 * The poll's verdict is `classifyHistory`; this function owns only the loop and
 * the deadline. A run that never appears in history is a timeout — not a failure
 * inferred from the fixed-name sidecar, which any run can overwrite.
 */
export async function runDepth(options: DepthRunOptions): Promise<DepthRunResult> {
  const phase = options.onPhase ?? (() => undefined);
  const signal = options.signal;

  phase('connecting');
  const info = await getObjectInfo(signal);
  const models = usableDepthModels(info);
  if (models.length === 0) {
    throw new ComfyError(
      'no-node',
      hasDepthNode(info)
        ? 'This server has depth nodes, but none this page can drive from one image plus its defaults.'
        : 'This server has no depth node this page can use.',
      'Expected a single image-to-image depth node, or the MoGe pipeline (LoadMoGeModel, MoGeInference, MoGeRender).',
    );
  }
  const remembered = getModelChoice();
  const model =
    options.model && models.includes(options.model)
      ? options.model
      : remembered && models.includes(remembered)
        ? remembered
        : models[0];
  // `comfy_model`: the app writes this at submission, not at the tap. The
  // chooser's history is `selectModel`'s, and a selection with no run is only a
  // history entry.
  setModelChoice(model);
  const normalExpected = isMoGeModel(info, model);

  phase('uploading');
  const uploaded = await uploadImage(options.file, signal);

  phase('queued');
  const workflow = buildWorkflow(info, model, uploaded.uploadedName, DEFAULT_RESOLUTION, Date.now());
  const promptId = await submitPrompt(workflow, newClientId(), signal);

  phase('generating');
  const deadline = Date.now() + POLL_TIMEOUT_MS;
  let outcome: HistoryOutcome = { kind: 'running' };
  while (Date.now() < deadline) {
    await sleep(POLL_INTERVAL_MS, signal);
    const entry = await getHistory(promptId, signal);
    outcome = classifyHistory(entry, normalExpected);
    if (outcome.kind !== 'running') break;
  }

  if (outcome.kind === 'running') {
    throw new ComfyError(
      'timeout',
      'ComfyUI did not finish this run within 10 minutes',
      `prompt ${promptId} was still queued or running when the deadline passed`,
    );
  }
  if (outcome.kind === 'failed') {
    throw new ComfyError('no-output', 'ComfyUI could not produce a depth map', outcome.detail);
  }

  phase('fetching');
  const depthPng = await viewImage(outcome.depth, signal);
  return { depthPng, model, normalReturned: outcome.normal !== null };
}

export interface SplatRunOptions {
  /** The photograph to reconstruct. */
  file: File;
  /** The splat ceiling; `clampSharpBudget` is the gate. */
  budget?: number;
  signal?: AbortSignal;
  onPhase?: (phase: ComfyPhase) => void;
}

export interface SplatRunResult {
  /** The `.splat` bytes exactly as the server wrote them. */
  bytes: Uint8Array;
  /** The node's lens measurement, or null when no sidecar was written. */
  sidecar: SplatSidecar | null;
  /** The sidecar's server-side name when one was fetched, for the line that says which lens. */
  sidecarName: string | null;
  /** The cloud's derived name: `basrelief_<tag>.splat`. */
  name: string;
  tag: string;
  budget: number;
}

/**
 * Upload, submit, poll by the prompt id, and fetch the cloud AND its sidecar.
 *
 * The poll's verdict is `classifySplatHistory`; this function owns the loop and
 * the deadline. The filenames are derived from the tag, because that is what the
 * node names its outputs after — nothing here reads a fixed-name sidecar, and an
 * absent history entry is "running", never a failure.
 */
export async function runSplat(options: SplatRunOptions): Promise<SplatRunResult> {
  const phase = options.onPhase ?? (() => undefined);
  const signal = options.signal;
  const budget = clampSharpBudget(options.budget ?? SHARP_BUDGET);

  phase('connecting');
  const info = await getObjectInfo(signal);
  if (!hasSharpStill(info)) {
    throw new ComfyError(
      'no-node',
      'This server has no SharpStill node, so it cannot build a splat cloud.',
      'Install server/comfy-node/basrelief_depth, or point this page at a server that has it. A depth node is not a cloud generator.',
    );
  }

  phase('uploading');
  const uploaded = await uploadImage(options.file, signal);
  const tag = newTag();
  const name = sharpSplatName(tag);

  phase('queued');
  const workflow = sharpWorkflow(info, uploaded.uploadedName, tag, budget);
  const promptId = await submitPrompt(workflow, newClientId(), signal);

  phase('generating');
  const deadline = Date.now() + POLL_TIMEOUT_MS;
  let outcome: HistoryOutcomeNoImage = { kind: 'running' };
  while (Date.now() < deadline) {
    await sleep(POLL_INTERVAL_MS, signal);
    const entry = await getHistory(promptId, signal);
    outcome = classifySplatHistory(entry);
    if (outcome.kind !== 'running') break;
  }

  if (outcome.kind === 'running') {
    throw new ComfyError(
      'timeout',
      'ComfyUI did not finish this run within 10 minutes',
      `prompt ${promptId} was still queued or running when the deadline passed`,
    );
  }
  if (outcome.kind === 'failed') {
    throw new ComfyError('no-output', 'ComfyUI could not build the cloud', outcome.detail);
  }

  phase('fetching');
  const bytes = await viewBytes({ filename: name, subfolder: '', type: 'output' }, signal, 'cloud');
  // The lens is part of the same run, so it is fetched before the result is
  // reported: a cloud shown without its sidecar draws through the fallback camera
  // and looks perfectly fine while being the wrong lens.
  const sidecarName = sharpSidecarName(tag);
  let sidecar: SplatSidecar | null = null;
  let fetchedName: string | null = null;
  try {
    const sideBytes = await viewIfPresent(
      { filename: sidecarName, subfolder: '', type: 'output' },
      signal,
    );
    if (sideBytes) {
      sidecar = parseSidecar(new TextDecoder().decode(sideBytes));
      fetchedName = sidecarName;
    }
  } catch (err) {
    // A missing sidecar is "no lens" — the cloud still loads and draws at the
    // app's fallback look. A transport failure here must not throw the cloud away.
    if (err instanceof DOMException && err.name === 'AbortError') throw err;
  }

  return { bytes, sidecar, sidecarName: fetchedName, name, tag, budget };
}

/**
 * A server's own words, cut down to what fits in a sheet.
 *
 * A 404 or 500 body is often an HTML page; pasted whole into a status line it is
 * unreadable and unactionable. Tags go, runs of whitespace fold, and anything
 * past a short sentence is clipped — the status line is a diagnosis, not a log.
 */
function readableDetail(detail: string): string {
  const trimmed = detail.trim();
  // ComfyUI answers a refusal with a small JSON body. The words are the message;
  // the braces and quotes are not.
  if (trimmed.startsWith('{')) {
    try {
      const parsed: unknown = JSON.parse(trimmed);
      if (parsed && typeof parsed === 'object') {
        const record = parsed as Record<string, unknown>;
        const nested = record.error;
        const candidate =
          typeof record.message === 'string'
            ? record.message
            : typeof nested === 'string'
              ? nested
              : nested && typeof nested === 'object' && typeof (nested as Record<string, unknown>).message === 'string'
                ? ((nested as Record<string, unknown>).message as string)
                : '';
        if (candidate.trim() !== '') {
          return candidate.length > 160 ? `${candidate.slice(0, 157)}…` : candidate;
        }
      }
    } catch {
      // Not JSON after all; fall through to the plain text.
    }
  }
  const text = trimmed.replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ').trim();
  if (text === '') return '';
  return text.length > 160 ? `${text.slice(0, 157)}…` : text;
}

/**
 * One sentence for any failure, including the CORS case a browser cannot name.
 *
 * A `fetch` that a preflight blocked throws the same bare `TypeError` as an
 * unreachable host, so the sentence states both possibilities rather than
 * pretending to know which it was. When the page is pointed at `/comfy` the
 * request went to the dev server, and a 404 there means this is a static build
 * with no proxy — the other half of the same mistake.
 */
export function describeComfyError(error: unknown): string {
  const base = normaliseBaseUrl(getComfyUrl());
  if (error instanceof DOMException && error.name === 'AbortError') {
    return 'Stopped.';
  }
  if (error instanceof DOMException && error.name === 'TimeoutError') {
    return 'The server did not answer in time. Check that ComfyUI is running and reachable.';
  }
  if (error instanceof ComfyError) {
    switch (error.kind) {
      case 'network': {
        const direct = !base.startsWith('/');
        const cause = error.detail ? ` (${readableDetail(error.detail)})` : '';
        // A blocked preflight throws the same bare TypeError as an unreachable
        // host, so the sentence names both routes rather than pretending to know
        // which it was — the browser cannot tell them apart either.
        return (
          `Could not reach ${base}${cause}. ` +
          (direct
            ? 'Check the address, that ComfyUI is running, and that it allows CORS (--enable-cors-header) — or run pnpm dev and use /comfy.'
            : 'Check that the dev server’s /comfy proxy is running and points at ComfyUI.')
        );
      }
      case 'http':
        // A static host answers `/comfy` with its own 404 page, and the body is
        // five lines of HTML. Nobody needs to read that: the fact is that there is
        // no proxy here, and the fix is to type the address.
        if (error.status === 404 && base.startsWith('/')) {
          return 'No ComfyUI proxy on this page. Enter your server’s address above — for example http://192.168.0.9:8188.';
        }
        return `${error.message}${error.detail ? ` — ${readableDetail(error.detail)}` : ''}`;
      case 'protocol':
      case 'server':
        return `${error.message}${error.detail ? `: ${readableDetail(error.detail)}` : ''}`;
      case 'timeout':
      case 'no-node':
      case 'no-output':
        return `${error.message}${error.detail ? ` — ${readableDetail(error.detail)}` : ''}`;
    }
  }
  return error instanceof Error ? error.message : 'something went wrong';
}
