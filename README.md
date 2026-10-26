# Basrelief

Turn a single photograph into something you can move around: a displaced relief
surface, or a Gaussian-splat cloud, that shifts with the phone in your hand.

It ships as an **Android live wallpaper** with a built-in studio, plus a **web
gallery** that shows the same pictures in a browser. Depth maps come from MoGe
and cloud reconstructions from SHARP, both through a ComfyUI node in this repo.

<video src="https://github.com/user-attachments/assets/79e3e067-6ecc-483d-bf73-1d344f5ba9dd" loop="true"></video>

## What it does

- **Relief** — the photo becomes a mesh, each vertex displaced by the depth map.
  Tilt the phone and the camera swings around a pivot you choose.
- **Splat** — a `.splat` cloud is drawn as one card per Gaussian, sorted
  back-to-front, with its own lens read from a sidecar file.
- **Depth and clouds on demand** — the studio and the gallery can both ask a
  ComfyUI server for a depth map (MoGe) or an entirely new cloud (SHARP).
- **A library** — photos, their per-surface settings, the selection and the
  playlists live in one versioned blob, so a tuned look survives a restart.

## The three surfaces

| | |
|---|---|
| **`android/`** | The product. Java + OpenGL ES, no AndroidX and no Maven dependencies. A live wallpaper and a studio screen that share one camera model and one settings index. |
| **`web/`** | A Vite + TypeScript gallery. Drop a packed PNG or a `.splat`, and it opens. The library lives in the browser (OPFS), with a picked folder as an optional backup — no account, no server, no upload. |
| **`reference/webgl-port/`** | An older three.js implementation, kept only for the original camera model and the embedded-depth reader. **Not maintained, not kept in sync.** |

A file travels between them because the format is the same one the app writes:
a PNG carrying its depth in a `dePH` chunk. The web gallery can read it, write
it, and the phone can open it.

## How the relief works

The photo is mapped onto a grid: each vertex is pushed along Z by
`depth/255 - 0.5`, scaled by an intensity value, and **per-vertex normals are
averaged from the surrounding faces** so a directional light shades it properly.
This is real geometry, not a parallax shader.

Because depth is 8-bit, its terraces are smoothed by a **separable float box
blur** (radius `width/78`) before tessellation — without it, grazing light turns
quantisation into visible contour lines.

The camera is a turntable about the surface under the orbit centre, with a fixed
rest eye: at rest the picture is exactly the photograph, and Origin names only
the axis the tilt rotates about. Depth is a per-photo value, so the same
photograph can stand further out of the plane on the lock screen than on home.

## How the splat renderer works

One card per sample, generated from `gl_InstanceID` — no vertex buffer, so the
whole grid resolution is a uniform. The full 3D covariance is projected to a 2D
conic, floored so a sub-pixel splat still covers a pixel, and clipped to the
radius its own alpha survives.

The colour path is **integer**: the 32-byte record goes up as two `RGBA32UI`
texels and is unpacked with shifts. It must not be a float texture — a bright
opaque colour is a NaN as float32, and a driver that canonicalises NaNs renders
those splats flat white.

Sorting is a counting sort into 262,144 depth buckets, re-derived only when the
tilt moves more than a sub-pixel bound, because alpha blending is not
commutative.

## Building

Android is built with Gradle inside a Podman container; the host needs only
`podman` (and `adb` to install).

```bash
cd android
./podman-build.sh build-image    # once: SDK image (Ubuntu + JDK + SDK 36)
./podman-build.sh test           # JVM unit tests in :core — no device
./podman-build.sh build-apk      # debug APK
./podman-build.sh install        # release build + adb install + AOT compile
```

`install` builds and **AOT-compiles** the release APK: this ROM disables JIT, so
a debug build runs interpreter-only and is roughly 40× slower. The debug APK is
for behaviour testing only.

Release signing reads `android/keystore.properties` (gitignored). Building a
release with the committed debug key is **refused** — distribution is F-Droid
and Obtainium, which ship the APK this project signs, so the release key is the
whole of the update trust chain.

Web:

```bash
cd web
pnpm install
pnpm dev        # HTTPS on :5173 — the gyro needs a secure context
pnpm build      # typecheck + static bundle
pnpm test       # contract fixtures, media states, renderers
```

ComfyUI generation needs a server; `pnpm dev` proxies `/comfy` to
`http://127.0.0.1:8188`, and a static build needs the server's own CORS flag.

## Verifying a release

Releases are tagged (`v0.1.0`, `v0.1.1`, …) and built by the `release` workflow,
which attaches the APK to a GitHub Release — that is what
[Obtainium](https://github.com/ImranR98/Obtainium) tracks for updates.

The APK is signed with this certificate:

```
SHA-256  b8b42c4024a7f7e1a7b09f15765207413135194c02b0c5425d310977119bea7d
CN       amoshydra, O=Basrelief, C=MY
```

Check it before trusting an update:

```bash
apksigner verify --print-certs app-release.apk | grep SHA-256
```

A different digest means it is not this project's build. The signing key is not
in this repository, and it is the only thing standing between a user and an APK
that Android would accept as an update.

## Shared contracts

Some rules are implemented more than once because the copies cannot share code:
the depth chunk, the splat record and sidecar, the tag rule, the settings body,
the library index. They share **bytes** instead — one golden fixture per rule in
`contract/`, each implementation tested against it in Java, TypeScript and
Python. `scripts/check-parity.mjs` holds the constants and the fixture paths
together so a drift is a build failure rather than a review note.

## Status

Working: the wallpaper and studio, relief and splat rendering, the web gallery
with its library and backup folder, packed-PNG read and write, depth and cloud
generation against ComfyUI, playlists, archive and clip playback.

Not yet: accounts, a server, cloud sync, LoRA or model training.

The web gallery's folder picker needs a Chromium desktop; OPFS works everywhere.

## Licence

GPL-3.0 — see [LICENSE](LICENSE).

This is a copyleft licence: you may use, study, modify and redistribute this
work, and a fork must stay under the same terms and publish its source. What it
does **not** permit is turning any part of it into a closed product, which is
the point. `server/comfy-node/` imports ComfyUI, which is itself GPL-3.0, so the
node was never able to be permissively licensed anyway.

Relicensed from MIT before any distribution; no copy was ever published under
the earlier terms.

## Credits

- [three.js](https://threejs.org) (MIT) renders the gallery's relief surface and
  the reference port's scene.
- Depth and cloud inference run in [ComfyUI](https://github.com/comfyanonymous/ComfyUI)
  via the node in `server/comfy-node/`; MoGe and SHARP are separate upstream
  projects and are not bundled here.
- The bundled demo imagery is AI-generated and carries no third-party rights.
