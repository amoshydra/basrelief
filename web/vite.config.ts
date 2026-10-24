import { defineConfig, loadEnv, type HttpProxy } from 'vite';
import basicSsl from '@vitejs/plugin-basic-ssl';
import license from 'rollup-plugin-license';

// HTTPS is not decoration here. Browsers expose `deviceorientation` only in a
// secure context, and `pnpm dev` on localhost is one — but a phone opening the
// LAN address is not. The self-signed certificate is what lets the gyro be tried
// on a real device; accept the warning once.
//
// The `/comfy` proxy is the CORS answer for the path this gallery is actually run
// through: a browser cannot POST to ComfyUI on another origin unless that server
// allows it, and the dev server is a same-origin hop. Point it elsewhere with
// `COMFY_URL=http://host:8188 pnpm dev`. A static build has no proxy, so it needs
// ComfyUI's own `--enable-cors-header` — the README says so.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const target = env.COMFY_URL || 'http://127.0.0.1:8188';
  const proxy = {
    '/comfy': {
      target,
      changeOrigin: true,
      rewrite: (path: string) => path.replace(/^\/comfy/, ''),
      // ComfyUI's CSRF check compares the Origin header with the Host it was
      // reached on, and a proxied request otherwise carries the page's origin
      // (`http://localhost:5173`) against the proxy target's host — a 403 on
      // every POST. The dev server is the same-origin hop, so its Origin is not
      // a claim ComfyUI can check; strip it rather than pretend it matches.
      configure: (proxyServer: HttpProxy.ProxyServer) => {
        proxyServer.on('proxyReq', (request) => {
          request.removeHeader('origin');
          request.removeHeader('referer');
        });
      },
    },
  };

  return {
    // Served from `/` locally and from `/basrelief/` on GitHub Pages. A wrong
    // base does not fail the build -- it 404s every script, which reads like a
    // broken deploy rather than a wrong path.
    base: process.env.PAGES_BASE || '/',
    plugins: [basicSsl()],
    server: { host: true, proxy },
    preview: { host: true, proxy },
    build: {
      target: 'es2022',
      rollupOptions: {
        // What the page ships decides what it owes. A bundle is a distribution,
        // so every dependency inside it must carry its notice with it — and the
        // project's own licence rides along for the same reason. Listing the
        // bundle's graph rather than package.json means this file cannot go
        // stale as dependencies are added or dropped; a dev-only package is
        // absent because it never reached the output.
        plugins: [
          license({
            thirdParty: {
              output: 'dist/THIRD-PARTY.txt',
              includePrivate: true,
            },
          }),
        ],
      },
    },
  };
});
