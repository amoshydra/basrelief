/**
 * Compile every GLSL shader the app builds in Java, off-device.
 *
 * WHY THIS EXISTS. The Android build cannot see a shader error: a renderer keeps
 * its GLSL in Java strings, compiles at RUNTIME on the GL thread, and a failed
 * compile throws there. That is how a reserved word reached a phone -- `sample`
 * is reserved in GLSL ES 3.00, the shader failed, and because the live wallpaper
 * shares the renderer the crash-loop cost the user their wallpaper, which the
 * system then reverted to a static image and only the picker can restore.
 *
 * It scans the app sources for `private static final String NAME = "..." + "..."`
 * fields whose value starts a shader, joins the literals and runs each through
 * glslangValidator. Two traps, both of which this has already fallen into once: a
 * Java `//` comment between the literals is not part of the string, and a Java
 * comment containing a double quote must not be mistaken for one.
 *
 * Usage: node scripts/check-shaders.mjs
 */
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const ROOT = new URL('..', import.meta.url).pathname;

/** The concatenated string literals of a Java expression, comments skipped. */
function literalsOf(body) {
  const out = [];
  let i = 0;
  while (i < body.length) {
    const ch = body[i];
    if (ch === '"') {
      i++;
      const buf = [];
      while (i < body.length && body[i] !== '"') {
        if (body[i] === '\\' && i + 1 < body.length) {
          const esc = body.slice(i, i + 2);
          const map = { '\\n': '\n', '\\t': '\t', '\\\\': '\\', '\\"': '"', '\\r': '\r' };
          buf.push(map[esc] ?? body[i + 1]);
          i += 2;
        } else {
          buf.push(body[i++]);
        }
      }
      i++;
      out.push(buf.join(''));
    } else if (body.slice(i, i + 2) === '//') {
      const nl = body.indexOf('\n', i);
      if (nl < 0) break;
      i = nl;
      out.push('\n');
    } else {
      i++;
    }
  }
  return out.join('');
}

function* shadersIn(src) {
  const re = /private static final String (\w+) =\s*([\s\S]*?);\n/g;
  let m;
  while ((m = re.exec(src)) !== null) {
    const [, name, body] = m;
    if (!body.includes('"')) continue;
    const text = literalsOf(body);
    // GLSL ES 1.00 sources carry no `#version` (100 is the default), so the
    // test for "is this a shader" cannot be the directive -- and validating
    // them without it would have glslang parse desktop 1.10 and reject every
    // attribute and varying in the file.
    if (!text.includes('void main')) continue;
    const kind = name.includes('VERT') ? 'vert' : name.includes('FRAG') ? 'frag' : null;
    if (kind) yield { name, kind, text: text.includes('#version') ? text : `#version 100\n${text}` };
  }
}

function javaFiles(dir, out = []) {
  for (const entry of readdirSync(dir)) {
    const p = join(dir, entry);
    if (statSync(p).isDirectory()) javaFiles(p, out);
    else if (entry.endsWith('.java')) out.push(p);
  }
  return out;
}

function ensureGlslang() {
  try {
    execFileSync('glslangValidator', ['--version'], { stdio: 'ignore' });
  } catch {
    console.error('check-shaders: glslangValidator is not on PATH.');
    console.error('  Debian/Ubuntu: sudo apt-get install -y glslang-tools');
    console.error('  macOS:         brew install glslang');
    // A gate that cannot run must not report success.
    process.exit(2);
  }
}

let checked = 0;
let failed = 0;
ensureGlslang();

for (const file of javaFiles(join(ROOT, 'android/app/src/main/java'))) {
  const src = readFileSync(file, 'utf8');
  for (const { name, kind, text } of shadersIn(src)) {
    checked++;
    const dir = mkdtempSync(join(tmpdir(), 'shader-'));
    const path = join(dir, `${name}.${kind}`);
    writeFileSync(path, text);
    let ok = true;
    let out = '';
    try {
      out = execFileSync('glslangValidator', ['-S', kind, path], { encoding: 'utf8' });
    } catch (e) {
      ok = false;
      out = `${e.stdout ?? ''}${e.stderr ?? ''}${e.message ?? ''}`;
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
    const rel = file.slice(ROOT.length);
    console.log(`${ok ? 'OK  ' : 'FAIL'} ${name} (${kind}) — ${rel}`);
    if (!ok) {
      failed++;
      console.log(out.split('\n').slice(0, 12).join('\n'));
    }
  }
}

console.log(`check-shaders: ${checked} shader(s) checked, ${failed} failed`);
process.exit(failed ? 1 : 0);
