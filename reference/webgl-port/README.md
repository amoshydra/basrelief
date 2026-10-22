# Basrelief WebGL port — reference only

**This is not the product.** It is an older browser port of Basrelief, kept as a
readable reference for two things:

- the original **camera model** (`src/scene.js`) — displaced relief mesh,
  per-vertex normals, orbit centre, pan/zoom, and the float depth blur;
- the **embedded-depth reader** (`src/png-depth.js`) — the `dePH`/`dEPt`
  ancillary-chunk parser for a PNG that carries its own depth map.

It is **not maintained in sync with the Android app** and is not expected to
match it. See *Known drift* in the repository's `AGENTS.md` (default Depth,
web tilt range, and so on). Do not start a new web feature here by assumption:
the Android app under `android/` is the source of truth for behaviour, and a
new web gallery should be its own project rather than an edit to this port.

## Run it

This directory is a self-contained Vite + three.js app. Package manager is
**pnpm** (the repository convention); `package-lock.json` is gone and
`pnpm-lock.yaml` is the lockfile.

```sh
cd reference/webgl-port
pnpm install

pnpm dev            # HTTPS on :5173 — required: browsers expose
                    # deviceorientation only in a secure context
pnpm run build      # static bundle in ./dist
pnpm preview        # serve the built bundle locally
pnpm run gen:assets # regenerate public/assets/{image,depth,photo-with-depth}.png
```

`pnpm dev` binds to the LAN (`vite.config.js` sets `server.host`), so a phone can
open `https://<your-lan-ip>:5173` after accepting the self-signed certificate
once. To *serve* it rather than build it, run `pnpm dev` (or `pnpm preview` after
a build); the app fetches its demo images from `/assets/…` at runtime, which is
why `public/` must be served by Vite and not opened as a file.

Controls (touch-first): tap = set orbit centre, two-finger drag = pan, two-finger
pinch / slider = zoom, sliders = depth intensity / motion / edge smoothing,
single-finger drag = tilt fallback.

## Demo assets

`public/assets/` holds the **old** demo trio (`image.png`, `depth.png`, and
`photo-with-depth.png`, which carries its depth in a `dePH` chunk). It is
generated only by `pnpm run gen:assets`, and it is unrelated to
`android/app/src/main/res/raw/`, which is the app's own bundled demo.

## Layout

```
index.html          Vite entry
vite.config.js      HTTPS plugin + LAN host
src/main.js         UI wiring, demo image loading
src/scene.js        camera model, relief geometry, pan/zoom/orbit, depth blur
src/tilt.js         deviceorientation handling
src/png-depth.js    embedded-depth chunk parser (dePH/dEPt)
scripts/            asset generator for public/assets
```
