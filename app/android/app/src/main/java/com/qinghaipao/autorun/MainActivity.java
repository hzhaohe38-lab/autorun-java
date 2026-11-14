package com.qinghaipao.autorun;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Shell activity: a WebView pointed at the local server, plus a startup overlay.
 *
 * <p>The overlay is not decoration. Starting the service, loading both engines from
 * disk and binding the port takes a moment, and loading the URL before the server
 * answers produces a WebView error page that users read as "it's broken". So the
 * app shows what is happening and polls readiness instead.
 */
public final class MainActivity extends Activity {

    private static final String LOCAL_URL = "http://127.0.0.1:3000";
    private static final long READY_TIMEOUT_MS = 30_000;
    private static final long PROBE_INTERVAL_MS = 250;

    private WebView webView;
    private LinearLayout statusView;
    private TextView statusText;
    private TextView statusHint;

    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean loaded;
    private long startedAt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView.setWebContentsDebuggingEnabled(true);

        FrameLayout root = new FrameLayout(this);
        webView = buildWebView();
        root.addView(webView, matchParent());
        statusView = buildStatusView();
        root.addView(statusView, matchParent());
        setContentView(root);

        startedAt = System.currentTimeMillis();
        showStatus("正在启动本地服务…", "首次启动会初始化定时任务，请稍候");
        AutorunService.start(this);
        pollUntilReady();
    }

    // ---------------------------------------------------------------- views

    private WebView buildWebView() {
        WebView v = new WebView(this);
        WebSettings s = v.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);        // localStorage holds the session
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);

        v.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                loaded = true;
                hideStatus();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                // Only a main-frame failure is worth surfacing; a failed favicon is not.
                if (request != null && request.isForMainFrame()) {
                    showStatus("页面加载失败", String.valueOf(error == null ? "" : error.getDescription()));
                }
            }
        });
        return v;
    }

    private LinearLayout buildStatusView() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(Color.parseColor("#0B1220"));
        box.setPadding(dp(28), dp(28), dp(28), dp(28));

        TextView title = new TextView(this);
        title.setText("氢氦跑");
        title.setTextColor(Color.parseColor("#E1F5EE"));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setGravity(Gravity.CENTER);

        statusText = new TextView(this);
        statusText.setTextColor(Color.parseColor("#9FE1CB"));
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, dp(14), 0, dp(6));

        statusHint = new TextView(this);
        statusHint.setTextColor(Color.parseColor("#5DCAA5"));
        statusHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        statusHint.setGravity(Gravity.CENTER);

        box.addView(title);
        box.addView(statusText);
        box.addView(statusHint);
        return box;
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private static FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
    }

    private void showStatus(String line, String hint) {
        if (statusView == null) return;
        statusView.setVisibility(View.VISIBLE);
        statusText.setText(line);
        statusHint.setText(hint == null ? "" : hint);
    }

    private void hideStatus() {
        if (statusView != null) statusView.setVisibility(View.GONE);
    }

    // ------------------------------------------------------------ readiness

    private void pollUntilReady() {
        new Thread(() -> {
            while (System.currentTimeMillis() - startedAt < READY_TIMEOUT_MS) {
                if (probe()) {
                    main.post(() -> {
                        showStatus("已就绪，正在载入界面…", "");
                        webView.loadUrl(LOCAL_URL);
                    });
                    return;
                }
                try {
                    Thread.sleep(PROBE_INTERVAL_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
            main.post(() -> showStatus("服务启动超时",
                    "下拉通知栏确认「定时任务」服务是否在运行；若在运行，点此重试"));
        }, "autorun-probe").start();
    }

    private boolean probe() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(LOCAL_URL + "/").openConnection();
            c.setConnectTimeout(800);
            c.setReadTimeout(1200);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            return code >= 200 && code < 500;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ------------------------------------------------------------ lifecycle

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            // Home, not finish: the service keeps running either way, and keeping
            // the activity alive makes returning instant.
            moveTaskToBack(true);
        }
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        if (loaded) hideStatus();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
