import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwind from '@tailwindcss/vite';
const backend = process.env.APP_BACKEND || 'http://127.0.0.1:7701';
export default defineConfig({ plugins: [react(), tailwind()], server: {proxy: {'/api': backend, '/actuator': backend, '/oauth2': backend, '/login/oauth2': backend, '/ws': {target: backend, ws: true, changeOrigin: true, rewriteWsOrigin: true}}} });
