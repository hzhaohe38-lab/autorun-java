import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { resolve } from 'path';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig(() => {
  return {
    plugins: [tailwindcss(), vue()],
    resolve: {
      alias: {
        '@': resolve(__dirname, 'src'),
      },
    },
    optimizeDeps: {
      include: ['leaflet'],
    },
    // 产物文件名不带 hash。
    //
    // 这份 dist 会被复制进 android/app/src/main/assets/web 随 APK 打包，而 assets
    // 在替换时需要先删旧目录——一旦手机上/桌面上的引擎正在读这些文件，删除就会
    // 失败（Permission denied），打包流程直接断掉。
    //
    // 固定文件名之后，同步退化成"覆盖同名文件"，不需要删除，任何时刻都能打包。
    // 代价是失去浏览器缓存失效能力，本地应用无所谓。
    build: {
      rollupOptions: {
        output: {
          entryFileNames: 'assets/[name].js',
          chunkFileNames: 'assets/[name].js',
          assetFileNames: 'assets/[name].[ext]',
        },
      },
    },
    server: {
      hot: true,
      host: '0.0.0.0',
      port: 5173,
      strictPort: true,
      allowedHosts: 'all',
      cors: true,
      proxy: {
        '/auth': {
          target: 'http://localhost:3000',
          changeOrigin: true,
        },
        '/clubactivity': {
          target: 'http://localhost:3000',
          changeOrigin: true,
        },
        '/unirun': {
          target: 'http://localhost:3000',
          changeOrigin: true,
        },
        '/api/auto-sign': {
          target: 'http://localhost:3000',
          changeOrigin: true,
        },
        '/api/auto-run': {
          target: 'http://localhost:3000',
          changeOrigin: true,
        },
      },
    },
  };
});
