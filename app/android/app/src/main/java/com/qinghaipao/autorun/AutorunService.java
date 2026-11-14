package com.qinghaipao.autorun;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.io.File;

import com.qinghaipao.autorun.core.HttpServer;
import com.qinghaipao.autorun.core.MapRepo;
import com.qinghaipao.autorun.core.Router;
import com.qinghaipao.autorun.core.RunEngine;
import com.qinghaipao.autorun.core.SignEngine;
import com.qinghaipao.autorun.core.Const;
import com.qinghaipao.autorun.core.Store;

/**
 * Foreground service hosting the local API and both engines.
 *
 * <p>Why a service at all: the WebView is only alive while the user looks at it,
 * but a sign-in due at 08:00 has to fire whether or not the app is on screen. The
 * service keeps the process at foreground priority (much less likely to be frozen
 * or killed) and re-arms an exact alarm after every pass.
 *
 * <p>The alarm is the actual scheduler. The in-process loop below is only a
 * convenience: it gives sub-second reaction while the process happens to be
 * running, and it is expected to be suspended when Android freezes the app. That
 * is fine — the alarm thaws the process, this loop resumes, and the engines scan
 * for anything due. Because tasks are persisted, nothing depends on the loop
 * having been alive the whole time.
 */
public final class AutorunService extends Service {

    public static final String ACTION_TICK = "com.qinghaipao.autorun.TICK";

    private static final String TAG = "Autorun/Service";
    private static final String CHANNEL_ID = "hh_run";
    private static final int NOTIF_ID = 1;
    private static final int RC_MAIN_ALARM = 1001;
    private static final int RC_HEARTBEAT = 1002;

    /** Fallback cadence. Doze stretches this to ~9 min, which is the platform limit. */
    private static final long HEARTBEAT_MS = 3L * 60 * 1000;
    /** Within this horizon the target is armed as a user-visible alarm (doze-proof). */
    private static final long ALARM_CLOCK_HORIZON_MS = 30L * 60 * 1000;
    private static final long MAX_ALARM_DELAY_MS = 60L * 60 * 1000;

    private Store store;
    private MapRepo maps;
    private SignEngine signEngine;
    private RunEngine runEngine;
    private HttpServer httpServer;
    private PowerManager.WakeLock wakeLock;

    private volatile boolean running;
    private Thread worker;

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundCompat();
        acquireWakeLock();

        installLogSink();

        store = new Store(new File(getFilesDir(), "state"));
        AndroidAssets assets = new AndroidAssets(getAssets());
        maps = new MapRepo(assets);
        signEngine = new SignEngine(store);
        runEngine = new RunEngine(store, maps);

        Router router = new Router(assets, signEngine, runEngine);
        httpServer = new HttpServer(Const.PORT, router);
        try {
            httpServer.start();
        } catch (Exception e) {
            Log.e(TAG, "local server failed to bind port " + Const.PORT, e);
        }

        signEngine.logStartupForWatches();
        Log.i(TAG, "service up: port=" + Const.PORT);

        running = true;
        worker = new Thread(this::loop, "autorun-tick");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Every wake path lands here. Kick a pass immediately rather than waiting
        // for the loop: an alarm fired precisely because something is due.
        if (worker != null && worker.isAlive()) {
            new Thread(() -> {
                tickOnce();
                arm();
            }, "autorun-wake-tick").start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (worker != null) worker.interrupt();
        if (httpServer != null) httpServer.stop();
        releaseWakeLock();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------- tick loop

    private void loop() {
        while (running) {
            long delay;
            try {
                delay = tickOnce();
            } catch (Throwable t) {
                Log.e(TAG, "tick failed", t);
                delay = 30_000;
            }
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** Runs one pass; returns how long to sleep before the next one. */
    private long tickOnce() {
        int signs = 0;
        int runs = 0;
        try {
            signs = signEngine.tick();
        } catch (Throwable t) {
            Log.e(TAG, "sign tick failed", t);
        }
        try {
            runs = runEngine.tick();
        } catch (Throwable t) {
            Log.e(TAG, "run tick failed", t);
        }
        if (signs + runs > 0) {
            notify("已执行 " + (signs + runs) + " 个任务");
        }
        arm();

        // Tight cadence only while something is about to become due.
        long next = signEngine.nextDueInMs();
        long nextRun = runEngine.nextWakeInMs();
        if (nextRun >= 0 && (next < 0 || nextRun < next)) next = nextRun;
        if (next >= 0 && next <= Const.FAST_LEAD_MS) return Const.FAST_TICK_MS;
        if (next >= 0 && next < 60_000) return 10_000;
        return Const.IDLE_TICK_MS;
    }

    // ---------------------------------------------------------------- alarms

    /**
     * Arms the next wake-up. Two alarms, on purpose:
     * <ul>
     *   <li>a target alarm at the next due time — an {@code setAlarmClock} when the
     *       target is near, because that is the only alarm class Android honours
     *       inside Doze without a 9-minute quota;</li>
     *   <li>a heartbeat as a safety net for the case where the exact alarm is
     *       swallowed by an OEM battery manager.</li>
     * </ul>
     */
    private void arm() {
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        long next = signEngine.nextDueInMs();
        long nextRun = runEngine.nextWakeInMs();
        if (nextRun >= 0 && (next < 0 || nextRun < next)) next = nextRun;

        long now = System.currentTimeMillis();
        if (next >= 0) {
            long triggerAt = now + Math.max(1000, Math.min(next, MAX_ALARM_DELAY_MS));
            PendingIntent pi = wakeIntent(RC_MAIN_ALARM);
            if (next <= ALARM_CLOCK_HORIZON_MS) {
                PendingIntent show = PendingIntent.getActivity(
                        this, RC_MAIN_ALARM + 100, new Intent(this, MainActivity.class),
                        pendingFlags());
                try {
                    am.setAlarmClock(new AlarmManager.AlarmClockInfo(triggerAt, show), pi);
                } catch (Exception e) {
                    setExact(am, triggerAt, pi);
                }
            } else {
                setExact(am, triggerAt, pi);
            }
        } else {
            setExact(am, now + MAX_ALARM_DELAY_MS, wakeIntent(RC_MAIN_ALARM));
        }

        setExact(am, now + HEARTBEAT_MS, wakeIntent(RC_HEARTBEAT));
    }

    private PendingIntent wakeIntent(int requestCode) {
        Intent i = new Intent(this, WakeReceiver.class);
        i.setAction(ACTION_TICK);
        return PendingIntent.getBroadcast(this, requestCode, i, pendingFlags());
    }

    private static int pendingFlags() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
    }

    private static void setExact(AlarmManager am, long triggerAt, PendingIntent pi) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        } catch (Exception e) {
            am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        }
    }

    // ---------------------------------------------------------- foreground

    private void startForegroundCompat() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "定时任务", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("保持签到与跑步定时任务运行");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        startForeground(NOTIF_ID, buildNotification("定时任务运行中"));
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, pendingFlags());

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("氢氦跑")
                .setContentText(text + " · 到点自动执行")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    private void notify(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
        } catch (Exception ignored) {
        }
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hh:autorun");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        } catch (Exception e) {
            Log.w(TAG, "wakelock unavailable: " + e.getMessage());
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {
        }
        wakeLock = null;
    }

    /** Routes core logging to logcat; the default sink only prints to stdout. */
    private void installLogSink() {
        com.qinghaipao.autorun.core.L.setSink((level, tag, msg, t) -> {
            switch (level) {
                case com.qinghaipao.autorun.core.L.WARN:
                    Log.w(tag, msg);
                    break;
                case com.qinghaipao.autorun.core.L.ERROR:
                    Log.e(tag, msg, t);
                    break;
                default:
                    Log.i(tag, msg);
            }
        });
    }

    // ------------------------------------------------------------- lifecycle

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, AutorunService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    public static void tick(Context ctx) {
        Intent i = new Intent(ctx, AutorunService.class);
        i.setAction(ACTION_TICK);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }
}
