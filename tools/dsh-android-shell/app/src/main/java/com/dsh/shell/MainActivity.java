package com.dsh.shell;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int DEFAULT_PORT = 3080;
    private static final int BACKGROUND = Color.parseColor("#0E1116");
    private String homeUrl;
    private WebView webView;
    private View errorView;
    private ProgressBar progressBar;
    private Button retryButton;
    private TextView errorDetail;

    private boolean doubleBackToExitPressed = false;
    private boolean mainFrameError = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        int port = getIntent().getIntExtra(LauncherActivity.EXTRA_PORT, DEFAULT_PORT);
        if (port < 1 || port > 65535) port = DEFAULT_PORT;
        homeUrl = "http://127.0.0.1:" + port + "/";

        webView = findViewById(R.id.webview);
        errorView = findViewById(R.id.error_view);
        progressBar = findViewById(R.id.progress);
        retryButton = findViewById(R.id.retry_button);
        errorDetail = findViewById(R.id.error_detail);

        setupWindow();
        setupWebView();
        setupButtons();

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
            if (webView.getUrl() != null && !webView.getUrl().isEmpty()) {
                errorView.setVisibility(View.GONE);
            } else {
                loadHome();
            }
        } else {
            loadHome();
        }
    }

    private void setupWindow() {
        Window window = getWindow();
        window.setStatusBarColor(BACKGROUND);
        window.setNavigationBarColor(BACKGROUND);

        View content = findViewById(R.id.content);
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(),
                        insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(),
                        insets.getSystemWindowInsetBottom());
            }
            return WindowInsets.CONSUMED;
        });
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();

        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setTextZoom(100);

        if (Build.VERSION.SDK_INT >= 33) {
            settings.setAlgorithmicDarkeningAllowed(true);
        }

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setBackgroundColor(BACKGROUND);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setVerticalScrollBarEnabled(false);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (ActivityNotFoundException ignored) {
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                mainFrameError = false;
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setProgress(0);
                errorView.setVisibility(View.GONE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
                if (!mainFrameError) {
                    errorView.setVisibility(View.GONE);
                    injectMobileOptimizations();
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    mainFrameError = true;
                    String detail = error.getDescription() != null
                            ? error.getDescription().toString()
                            : getString(R.string.error_unknown);
                    showError(detail);
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                            WebResourceResponse response) {
                if (request.isForMainFrame() && response.getStatusCode() >= 400) {
                    mainFrameError = true;
                    showError(getString(R.string.error_http, response.getStatusCode()));
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage message) {
                Log.e("DshWebView", message.message() + " (" + message.sourceId() + ":" + message.lineNumber() + ")");
                return true;
            }

            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                if (newProgress >= 100) {
                    progressBar.setVisibility(View.GONE);
                }
            }
        });
    }

    private void setupButtons() {
        retryButton.setOnClickListener(v -> loadHome());
    }

    private void loadHome() {
        mainFrameError = false;
        errorView.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        webView.loadUrl(homeUrl);
    }

    private void showError(String detail) {
        errorDetail.setText(detail);
        errorView.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.GONE);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void injectMobileOptimizations() {
        String js = "(function(){"
                + "var v=document.querySelector('meta[name=viewport]');"
                + "if(!v){v=document.createElement('meta');v.name='viewport';document.head.appendChild(v);}"
                + "v.content='width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no, viewport-fit=cover';"
                + "var s=document.createElement('style');"
                + "s.textContent='html,body{-webkit-text-size-adjust:100%;text-size-adjust:100%;}*{-webkit-tap-highlight-color:transparent;}';"
                + "document.head.appendChild(s);"
                + "})();";
        webView.evaluateJavascript(js, null);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
            return;
        }
        if (doubleBackToExitPressed) {
            super.onBackPressed();
            return;
        }
        doubleBackToExitPressed = true;
        Toast.makeText(this, R.string.back_again_to_exit, Toast.LENGTH_SHORT).show();
        handler.postDelayed(() -> doubleBackToExitPressed = false, 2000);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (webView != null) {
            webView.destroy();
        }
    }
}
