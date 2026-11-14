package com.qinghaipao.autorun.desktop;

import com.qinghaipao.autorun.core.Const;
import com.qinghaipao.autorun.core.HttpServer;
import com.qinghaipao.autorun.core.L;
import com.qinghaipao.autorun.core.MapRepo;
import com.qinghaipao.autorun.core.Router;
import com.qinghaipao.autorun.core.RunEngine;
import com.qinghaipao.autorun.core.SignEngine;
import com.qinghaipao.autorun.core.Store;

import java.io.File;

/**
 * Runs the engine on a desktop JVM so the frontend can be exercised in a browser
 * without building an APK.
 *
 * <p>What is missing compared to the phone: no AlarmManager, so the tick loop
 * below is the only scheduler. It polls every 5 seconds, which is plenty for a
 * 15-minute tolerance window. Everything else — signing, scheduling, track
 * generation, persistence — is the same code that ships in the APK.
 *
 * <p>Visiting {@code http://127.0.0.1:3000} serves the built frontend exactly as
 * the WebView sees it. For hot reload, run {@code npm run dev} in {@code app/}
 * and open port 5173 instead; its proxy forwards to this same server.
 */
public final class DesktopMain {

    private static final long TICK_MS = 5000;

    public static void main(String[] args) throws Exception {
        File root = resolveRoot(args);

        File assetsDir = new File(root, "app/android/app/src/main/assets");
        if (!new File(assetsDir, "web/index.html").isFile()) {
            System.err.println("找不到前端产物: " + new File(assetsDir, "web/index.html"));
            System.err.println("先执行: ./tools/build.sh --web   （或确认项目根目录正确）");
            System.exit(1);
        }

        File stateDir = new File(root, "runtime");
        L.i("Autorun/Desktop", "state dir : " + stateDir);
        L.i("Autorun/Desktop", "assets dir: " + assetsDir);

        Store store = new Store(stateDir);
        FileAssets assets = new FileAssets(assetsDir);
        MapRepo maps = new MapRepo(assets);
        SignEngine signEngine = new SignEngine(store);
        RunEngine runEngine = new RunEngine(store, maps);

        Router router = new Router(assets, signEngine, runEngine);
        HttpServer server = new HttpServer(Const.PORT, router);

        try {
            server.start();
        } catch (Exception e) {
            System.err.println("端口 " + Const.PORT + " 绑定失败：" + e.getMessage());
            System.err.println("可能已有实例在跑。先关掉它，或改 Const.PORT。");
            System.exit(1);
        }

        signEngine.logStartupForWatches();

        L.i("Autorun/Desktop", "");
        L.i("Autorun/Desktop", "  http://127.0.0.1:" + Const.PORT + "     ← 直接打开这个");
        L.i("Autorun/Desktop", "  前端热更新: cd app && npm run dev  (5173)");
        L.i("Autorun/Desktop", "  Ctrl+C 退出");
        L.i("Autorun/Desktop", "");

        startTickLoop(signEngine, runEngine);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            L.i("Autorun/Desktop", "shutting down");
            server.stop();
        }));

        Thread.currentThread().join();
    }

    private static void startTickLoop(SignEngine signEngine, RunEngine runEngine) {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    int signs = signEngine.tick();
                    int runs = runEngine.tick();
                    if (signs + runs > 0) {
                        L.i("Autorun/Desktop", "tick: " + signs + " sign / " + runs + " run");
                    }
                } catch (Throwable e) {
                    L.e("Autorun/Desktop", "tick failed: " + e.getMessage(), e);
                }
                try {
                    Thread.sleep(TICK_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "desktop-tick");
        t.setDaemon(true);
        t.start();
    }

    /** Accepts an explicit project root, otherwise walks up from the working dir. */
    private static File resolveRoot(String[] args) {
        if (args.length > 0) return new File(args[0]).getAbsoluteFile();

        File dir = new File(".").getAbsoluteFile();
        for (int i = 0; i < 4 && dir != null; i++) {
            if (new File(dir, "app/android/app/src/main/assets").isDirectory()) return dir;
            dir = dir.getParentFile();
        }
        return new File(".").getAbsoluteFile();
    }
}
