#!/usr/bin/env node
/**
 * 把 app/dist 同步进 android assets。
 *
 * 为什么不用 `rm -rf + cp -r`：
 *   1. assets/web 可能正被运行中的引擎（桌面服务或 adb 拉起的进程）读取，
 *      Windows 下会直接 Permission denied；
 *   2. 沙箱/安全策略会把 rm 拦下来，脚本中途断掉。
 *
 * Vite 产物已经改成固定文件名（见 vite.config.js），所以这里只需要覆盖同名文件，
 * 完全不需要删除。唯一要过滤的是 remixicon 的四种冗余字体格式——现代 WebView
 * 只用 woff2，其余 4.4MB 留着纯属浪费。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// 以脚本自身位置定位项目根，避免调用方 cwd 不同（build.sh 在根目录跑，
// package.sh 在 app/ 下跑）时解析错路径。
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const SRC = path.join(ROOT, 'app/dist');
const DST = path.join(ROOT, 'app/android/app/src/main/assets/web');

// 只保留 woff2，其余格式不复制。
// 注意两种命名都要覆盖：带 hash 时叫 remixicon-abc123.svg，
// 固定文件名后叫 remixicon.svg。
const SKIP = /^remixicon(-[^.]+)?\.(svg|eot|ttf|woff)$/i;

if (!fs.existsSync(path.join(SRC, 'index.html'))) {
  console.error('[sync-web] 找不到 app/dist/index.html，先执行 npm run build');
  process.exit(1);
}

let copied = 0;
let skipped = 0;

function walk(srcDir, dstDir) {
  fs.mkdirSync(dstDir, { recursive: true });
  for (const entry of fs.readdirSync(srcDir, { withFileTypes: true })) {
    const sp = path.join(srcDir, entry.name);
    const dp = path.join(dstDir, entry.name);
    if (entry.isDirectory()) {
      walk(sp, dp);
    } else if (SKIP.test(entry.name)) {
      skipped++;
    } else {
      fs.copyFileSync(sp, dp);
      copied++;
    }
  }
}

walk(SRC, DST);
console.log(`[sync-web] 复制 ${copied} 个文件，跳过 ${skipped} 个冗余字体`);
