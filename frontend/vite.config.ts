import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const target = process.env.TRACEFLOW_API_TARGET || env.TRACEFLOW_API_TARGET || 'http://127.0.0.1:8080';
  return { plugins: [react()], server: { proxy: { '/api': { target, changeOrigin: false } } }, preview: { proxy: { '/api': { target, changeOrigin: false } } } };
});
