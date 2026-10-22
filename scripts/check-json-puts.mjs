/**
 * Fail if any org.json `JSONObject.put` is handed a `float`.
 *
 * WHY THIS EXISTS. `:core` compiles against Maven's org.json (`compileOnly
 * 'org.json:json:20231013'`) because on a device org.json comes from the
 * platform. The two are NOT the same API: Maven's has `put(String, float)`,
 * Android's does not. A bare float argument therefore links against the JVM
 * build, passes `:core:test` and `build-apk`, and throws NoSuchMethodError at
 * runtime -- which is what shipped: `LibraryItem.toJson` wrote `focal` and
 * `splat_lens` as floats, and because the throw is on the `index-writer` thread
 * every library write took the app down, so any gallery tap crashed it.
 *
 * This is the one class of defect that neither existing gate can see. `:core:test`
 * cannot: it runs on the JVM, against the very org.json that has the method.
 * `build-apk` cannot: it compiles, which is the whole problem. It is the shader
 * checker's argument again, one language over -- anything that can invalidate a
 * device test has to be checkable without one.
 *
 * The rule: a float value reaching `put` must be widened explicitly, `(double)`,
 * which resolves to `put(String, double)` on both platforms. A `(float)` cast is
 * itself a float and is refused.
 *
 * It is deliberately conservative. It only judges the SECOND argument of a
 * two-argument `put` whose first argument is a string literal, in files that use
 * org.json at all, and it only accuses a bare name it can see declared `float`
 * and nothing else in that file. An expression it cannot type is passed over
 * rather than guessed at -- a false accusation costs someone an afternoon, and a
 * missed one costs the user their app.
 *
 * Usage: node scripts/check-json-puts.mjs [files...]
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

const ROOT = new URL('..', import.meta.url).pathname;
const DEFAULT_DIRS = [
  join(ROOT, 'android/core/src/main/java'),
  join(ROOT, 'android/app/src/main/java'),
];

const TYPES = ['float', 'double', 'int', 'long', 'boolean', 'char', 'short', 'byte',
  'String', 'JSONObject', 'JSONArray'];

function javaFiles(dir, out = []) {
  for (const entry of readdirSync(dir)) {
    const p = join(dir, entry);
    if (statSync(p).isDirectory()) javaFiles(p, out);
    else if (entry.endsWith('.java')) out.push(p);
  }
  return out;
}

/** Every name this file declares, mapped to the set of types it is declared as. */
function declaredTypes(src) {
  const map = new Map();
  const re = new RegExp(
    `\\b(?:${TYPES.join('|')})\\b(?:\\s*\\[\\s*\\])?\\s+(\\w+)\\s*(?=[=;,)\\[\\s])`, 'g');
  let m;
  while ((m = re.exec(src)) !== null) {
    const name = m[1];
    const type = m[0].match(new RegExp(`\\b(${TYPES.join('|')})\\b`))[1];
    if (!map.has(name)) map.set(name, new Set());
    map.get(name).add(type);
  }
  return map;
}

/** Drop string literals so a name inside one is not mistaken for an identifier. */
function withoutStrings(expr) {
  return expr.replace(/"(?:[^"\\]|\\.)*"/g, '""');
}

/** The 2-argument `put("literal", <expr>)` calls in a source, as {line, key, value}. */
function puts(src) {
  const out = [];
  const re = /\.put\s*\(/g;
  let m;
  while ((m = re.exec(src)) !== null) {
    // Balanced scan from the opening paren, so `new JSONObject(x.toJson())` is
    // one argument and not three.
    let i = re.lastIndex;
    let depth = 1;
    const args = [];
    let cur = '';
    while (i < src.length && depth > 0) {
      const ch = src[i];
      if (ch === '"') {
        cur += ch;
        i++;
        while (i < src.length && src[i] !== '"') {
          if (src[i] === '\\') cur += src[i++];
          cur += src[i++];
        }
        cur += src[i++];
        continue;
      }
      if (ch === '(') depth++;
      else if (ch === ')') {
        depth--;
        if (depth === 0) break;
      }
      if (ch === ',' && depth === 1) {
        args.push(cur);
        cur = '';
      } else {
        cur += ch;
      }
      i++;
    }
    args.push(cur);
    if (args.length === 2 && /^\s*"/.test(args[0])) {
      out.push({
        line: src.slice(0, m.index).split('\n').length,
        key: args[0].trim(),
        value: args[1].trim(),
      });
    }
    re.lastIndex = i;
  }
  return out;
}

function offenceIn(expr, types) {
  const e = withoutStrings(expr).trim();
  if (e === '') return null;
  // No \b here: the cast is often the first token, and there is no word boundary
  // between the start of the expression and `(`.
  if (/\(double\)/.test(e)) return null;            // the explicit widening: safe
  if (/\(float\)/.test(e)) return 'a (float) cast is still a float';
  if (/^[\d.]+[fF]$/.test(e)) return 'a float literal';
  for (const id of e.match(/[A-Za-z_]\w*/g) || []) {
    const t = types.get(id);
    if (t && t.size === 1 && t.has('float')) return `\`${id}\` is declared float`;
  }
  return null;
}

const files = process.argv.slice(2).length
  ? process.argv.slice(2)
  : DEFAULT_DIRS.flatMap((d) => javaFiles(d));

let bad = 0;
let scanned = 0;
for (const file of files) {
  const src = readFileSync(file, 'utf8');
  if (!src.includes('org.json') && !/new JSONObject\s*\(/.test(src)) continue;
  scanned++;
  const types = declaredTypes(src);
  for (const p of puts(src)) {
    const why = offenceIn(p.value, types);
    if (why) {
      console.error(`${file.replace(ROOT, '')}:${p.line}: put(${p.key}, ${p.value})`
        + ` -- ${why}, and Android's org.json has no put(String, float).`
        + ' Write (double) explicitly.');
      bad++;
    }
  }
}

if (bad) {
  console.error(`\n${bad} float JSONObject.put call(s). Android throws NoSuchMethodError`
    + ' for these at runtime, off-device they look fine.');
  process.exit(1);
}
console.log(`OK: no float JSONObject.put in ${scanned} org.json source file(s).`);
