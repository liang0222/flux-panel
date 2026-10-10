import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import path from "path";

/**
 * 客户端（iOS / Android WebView 壳）构建模式。
 *
 * 背景：App 通过 file:// 加载本地 index.html，而面板 Web 版部署在 nginx 根路径下。
 * 两者对资源路径的要求不同：
 *   - 面板：base '/'，资源放 assets/ 子目录，由 nginx 托管；
 *   - 客户端：必须用相对路径 base './'，否则 file:// 下会去请求
 *     file:///assets/xxx.js 而 404，表现为白屏。
 *
 * 通过环境变量 BUILD_TARGET=client 触发，默认行为（面板）保持不变。
 */
const isClientBuild = process.env.BUILD_TARGET === "client";

export default defineConfig({
  plugins: [
    react(),
  ],
  base: isClientBuild ? './' : '/',
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
  server: {
    port: 3000,
    host: '0.0.0.0'
  },
  build: {
    // 客户端产物输出到独立目录，避免覆盖面板构建结果
    outDir: isClientBuild ? 'dist-client' : 'dist',
    // 客户端要求资源平铺，不使用 assets/ 子目录
    assetsDir: isClientBuild ? '' : 'assets',
    sourcemap: false,
    // 面板构建产物经 nginx 对外分发，必须压缩并做 tree-shaking：
    // 此前对两种构建都设置 minify:false / treeshake:false，导致单个 JS 达 7.4MB。
    // 客户端（WebView）产物仍保留不压缩，便于真机调试与崩溃定位。
    minify: isClientBuild ? false : 'esbuild',
    rollupOptions: {
      treeshake: isClientBuild ? false : true,
      // 仅面板构建做 vendor 拆分，避免单个 chunk 过大影响首屏与缓存命中
      output: isClientBuild
        ? {}
        : {
            manualChunks: {
              react: ['react', 'react-dom', 'react-router-dom'],
              charts: ['recharts'],
              motion: ['framer-motion'],
            },
          },
    }
  }
});
