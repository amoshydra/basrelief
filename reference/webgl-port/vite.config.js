import { defineConfig } from 'vite';
import basicSsl from '@vitejs/plugin-basic-ssl';

// HTTPS is required: browsers only expose deviceorientation (gyroscope)
// in secure contexts. The self-signed cert lets you test on your phone
// over LAN (accept the cert warning once).
export default defineConfig({
  plugins: [basicSsl()],
  server: {
    host: true, // expose on LAN so a phone can reach it
  },
});
