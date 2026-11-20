import { createHash } from "node:crypto";
import path from "node:path";
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig(({ mode }) => {
  const mobile = mode === 'mobile';
  const devBackend = process.env.FLUX_DEV_PROXY_TARGET || 'http://127.0.0.1:6365';
  return {
    plugins: [
      react(),
      ...(mobile ? [{
        name: 'flux-mobile-html',
        transformIndexHtml: {
          order: 'post' as const,
          handler(html: string) {
            // WKWebView loads a local file. Keep its classic script separate
            // from the browser build's split ES modules.
            const classic = html.replace('<script type="module" crossorigin ', '<script defer ');
            const hashes = [...classic.matchAll(/<script>([\s\S]*?)<\/script>/g)]
              .map(([, content]) => `'sha256-${createHash('sha256').update(content).digest('base64')}'`);
            const local = "'self' file:";
            const policy = `default-src 'none'; script-src ${local} ${hashes.join(' ')}; style-src ${local} 'unsafe-inline'; img-src ${local} data: blob: https:; font-src ${local} data:; connect-src https: wss:; base-uri 'none'; form-action 'none'`;
            return classic.replace('<head>', `<head>\n    <meta http-equiv="Content-Security-Policy" content="${policy}">`);
          },
        },
      }] : []),
    ],
    base: mobile ? './' : '/',
    resolve: { alias: { "@": path.resolve(__dirname, "./src") } },
    server: {
      port: 3000,
      host: '0.0.0.0',
      proxy: {
        // Preserve the browser's Host and Origin so Spring sees a same-origin request.
        '/api/v1': { target: devBackend, changeOrigin: false },
        '/system-info': { target: devBackend, changeOrigin: false, ws: true },
      },
    },
    build: {
      outDir: mobile ? 'dist-mobile' : 'dist',
      ...(mobile ? { target: ['chrome89', 'safari15.6'], modulePreload: false, cssCodeSplit: false } : {}),
      sourcemap: false,
      minify: 'esbuild',
      rollupOptions: {
        treeshake: false,
        ...(mobile ? { output: { format: 'iife' as const, inlineDynamicImports: true } } : {}),
      },
    },
  };
});
