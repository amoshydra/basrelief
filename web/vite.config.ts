import { defineConfig } from 'vite';
import basicSsl from '@vitejs/plugin-basic-ssl';

// HTTPS is not decoration here. Browsers expose `deviceorientation` only in a
// secure context, and `pnpm dev` on localhost is one — but a phone opening the
// LAN address is not. The self-signed certificate is what lets the gyro be tried
// on a real device; accept the warning once.
export default defineConfig({
  plugins: [basicSsl()],
  server: { host: true },
  build: { target: 'es2022' },
});
