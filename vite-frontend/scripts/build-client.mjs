#!/usr/bin/env node
/**
 * 客户端（iOS / Android WebView 壳）构建脚本。
 *
 * 为什么需要单独一个脚本：
 *   Windows 下 `BUILD_TARGET=client vite build` 这种写法不生效，
 *   而 cross-env 又不是本项目的依赖（不想为打包客户端新增依赖）。
 *   这里用 Node 直接设置环境变量后调用 vite，跨平台且零新增依赖。
 *
 * 产物：vite-frontend/dist-client/
 *   - base 为相对路径 './'，资源平铺（无 assets/ 子目录）
 *   - 与 .ipa 中官方客户端的资源布局一致，可在 file:// 下正常加载
 *
 * 用法：npm run build:client
 */
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const projectRoot = path.resolve(here, '..');

// 先做类型检查，与 build 保持一致的质量门槛
function run(command, args, extraEnv = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, {
      cwd: projectRoot,
      stdio: 'inherit',
      shell: process.platform === 'win32',
      env: { ...process.env, ...extraEnv },
    });
    child.on('error', reject);
    child.on('close', (code) => {
      if (code === 0) resolve();
      else reject(new Error(`${command} ${args.join(' ')} 退出码 ${code}`));
    });
  });
}

try {
  console.log('▶ 类型检查 (tsc --noEmit)...');
  await run('npx', ['tsc', '--noEmit']);

  console.log('▶ 构建客户端产物 (BUILD_TARGET=client)...');
  await run('npx', ['vite', 'build'], { BUILD_TARGET: 'client' });

  console.log('✅ 客户端产物已生成：vite-frontend/dist-client');
  console.log('   下一步执行： pwsh -ExecutionPolicy Bypass -File ./scripts/inject-client-assets.ps1');
} catch (error) {
  console.error('❌ 客户端构建失败:', error.message);
  process.exit(1);
}
