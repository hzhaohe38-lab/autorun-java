#!/usr/bin/env node
/**
 * 一键开发环境：编译并启动 Java 引擎（3000），再启动 Vite（5173）。
 *
 * 这就是 `npm run dev` 实际跑的东西。终端最后会打印 http://localhost:5173/，
 * 点开就是完整可用的界面——登录、签到、定时任务都通，因为 5173 会把接口
 * 转发到本地 3000 的 Java 引擎。
 *
 * 如果 3000 已经有服务在跑（比如另开终端执行了 tools/desktop.sh），就跳过启动，
 * 直接复用，避免端口冲突。
 */
import { spawn, spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, readdirSync, writeFileSync } from 'node:fs';
import http from 'node:http';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const SEP = process.platform === 'win32' ? ';' : ':';
const OUT = path.join(ROOT, 'build', 'desktop');
const JSON_JAR = path.join(ROOT, 'libs', 'json.jar');
const API_PORT = 3000;

// Android 专属类不进桌面编译（它们引用 android.*）
const SKIP = new Set([
  'MainActivity.java',
  'AutorunService.java',
  'WakeReceiver.java',
  'BootReceiver.java',
  'AndroidAssets.java',
]);

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function collectJava(dir, acc = []) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name);
    if (entry.isDirectory()) collectJava(p, acc);
    else if (entry.name.endsWith('.java') && !SKIP.has(entry.name)) acc.push(p);
  }
  return acc;
}

function tcpOpen(port) {
  return new Promise((resolve) => {
    const socket = net.connect({ port, host: '127.0.0.1' });
    socket.setTimeout(600);
    socket.once('connect', () => { socket.destroy(); resolve(true); });
    socket.once('timeout', () => { socket.destroy(); resolve(false); });
    socket.once('error', () => resolve(false));
  });
}

function httpReady(port) {
  return new Promise((resolve) => {
    const req = http.get({ host: '127.0.0.1', port, path: '/', timeout: 900 }, (res) => {
      res.resume();
      resolve(res.statusCode < 500);
    });
    req.on('error', () => resolve(false));
    req.on('timeout', () => { req.destroy(); resolve(false); });
  });
}

async function ensureJsonJar() {
  if (existsSync(JSON_JAR)) return;
  console.log('[dev] 下载 org.json（桌面端没有 Android 自带的那个）…');
  mkdirSync(path.dirname(JSON_JAR), { recursive: true });
  const res = await fetch('https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar');
  if (!res.ok) throw new Error('下载失败: HTTP ' + res.status);
  writeFileSync(JSON_JAR, Buffer.from(await res.arrayBuffer()));
}

function compile() {
  mkdirSync(OUT, { recursive: true });
  const files = [
    ...collectJava(path.join(ROOT, 'app/android/app/src/main/java')),
    ...collectJava(path.join(ROOT, 'tools/desktop')),
  ];
  const argfile = path.join(OUT, 'sources.txt');
  writeFileSync(argfile, files.join('\n'), 'utf8');

  const r = spawnSync('javac', ['-encoding', 'UTF-8', '-d', OUT, '-cp', JSON_JAR, '@' + argfile], {
    stdio: 'inherit',
  });
  if (r.error) throw new Error('找不到 javac，请确认 JDK 已安装并在 PATH 里');
  if (r.status !== 0) throw new Error('引擎编译失败');
}

async function main() {
  const viteBin = path.join(ROOT, 'app/node_modules/vite/bin/vite.js');
  if (!existsSync(viteBin)) {
    console.error('[dev] 前端依赖没装。先执行: cd app && npm install');
    process.exit(1);
  }

  await ensureJsonJar();

  let engine = null;
  if (await tcpOpen(API_PORT)) {
    console.log(`[dev] 端口 ${API_PORT} 已有服务，直接复用`);
  } else {
    console.log('[dev] 编译 Java 引擎…');
    compile();

    console.log(`[dev] 启动引擎（端口 ${API_PORT}）…`);
    engine = spawn('java', ['-cp', OUT + SEP + JSON_JAR,
      'com.qinghaipao.autorun.desktop.DesktopMain', ROOT], { stdio: 'inherit' });

    let ready = false;
    for (let i = 0; i < 60; i++) {
      if (await httpReady(API_PORT)) { ready = true; break; }
      if (engine.exitCode !== null) break;
      await sleep(250);
    }
    if (!ready) {
      console.error('[dev] 引擎没起来，检查上面的报错');
      engine?.kill();
      process.exit(1);
    }
    console.log(`[dev] 引擎就绪 http://127.0.0.1:${API_PORT}`);
  }

  console.log('[dev] 启动 Vite…\n');
  const vite = spawn(process.execPath, [viteBin], {
    cwd: path.join(ROOT, 'app'),
    stdio: 'inherit',
  });

  const shutdown = () => {
    try { vite.kill(); } catch {}
    try { engine?.kill(); } catch {}
    process.exit(0);
  };
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
  vite.on('exit', (code) => {
    try { engine?.kill(); } catch {}
    process.exit(code ?? 0);
  });
}

main().catch((e) => {
  console.error('[dev] ' + e.message);
  process.exit(1);
});
