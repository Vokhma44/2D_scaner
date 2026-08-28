import { defineConfig } from 'vite';

/**
 * Сборка кладётся прямо в ресурсы агента: ПК раздаёт мобильный клиент сам,
 * поэтому телефону не нужен ни интернет, ни магазин приложений.
 */
export default defineConfig({
  base: './',
  build: {
    outDir: '../agent/src/main/resources/web',
    emptyOutDir: true,
    target: 'es2020',
    sourcemap: false,
    chunkSizeWarningLimit: 900,
  },
  server: {
    port: 5173,
  },
});
