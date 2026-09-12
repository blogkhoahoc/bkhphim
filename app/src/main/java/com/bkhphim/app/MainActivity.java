package com.bkhphim.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

public class MainActivity extends Activity {

    private WebView webView;
    private FrameLayout rootLayout;
    private FullscreenChromeClient chromeClient;

    private volatile boolean playerOpen = false;
    private boolean mediaControllerVisible = false;
    private boolean episodeGestureDone = false;

    // Biến hỗ trợ bấm giữ (Long-press) phím OK
    private boolean isOkPressed = false;
    private long okPressTime = 0;
    private boolean okLongPressExecuted = false;

    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private FrameLayout fullscreenContainer;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        rootLayout = new FrameLayout(this);
        webView = new WebView(this);
        rootLayout.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(rootLayout);
        
        webView.setFocusableInTouchMode(true);
        webView.requestFocus();

        applyImmersive();

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);

        webView.setWebViewClient(new WebViewClient());
        chromeClient = new FullscreenChromeClient();
        webView.setWebChromeClient(chromeClient);
        webView.addJavascriptInterface(new TvBridge(), "TvBridge");
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void applyImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private class TvBridge {
        @JavascriptInterface
        public void setPlayerOpen(boolean open) {
            playerOpen = open;
        }

        @JavascriptInterface
        public void tapVideo() {
            runOnUiThread(() -> {
                mediaControllerVisible = false;
                tapCenter();
            });
        }

        @JavascriptInterface
        public void sendKey(final String code) {
            runOnUiThread(() -> {
                int kc;
                if (" ".equals(code)) kc = KeyEvent.KEYCODE_SPACE;
                else if ("ArrowLeft".equals(code)) kc = KeyEvent.KEYCODE_DPAD_LEFT;
                else if ("ArrowRight".equals(code)) kc = KeyEvent.KEYCODE_DPAD_RIGHT;
                else if ("ArrowUp".equals(code)) kc = KeyEvent.KEYCODE_DPAD_UP;
                else if ("ArrowDown".equals(code)) kc = KeyEvent.KEYCODE_DPAD_DOWN;
                else return;
                forwardKeyToPlayer(kc);
            });
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (playerOpen) {
            // Phím OK / Enter (Xử lý Play/Pause hoặc Bấm giữ xóa Quảng cáo)
            if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER || 
                event.getKeyCode() == KeyEvent.KEYCODE_ENTER || 
                event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (event.getRepeatCount() == 0) {
                        isOkPressed = true;
                        okPressTime = SystemClock.uptimeMillis();
                        okLongPressExecuted = false;
                    } else if (isOkPressed && !okLongPressExecuted) {
                        if (SystemClock.uptimeMillis() - okPressTime > 600) { 
                            okLongPressExecuted = true;
                            autoClickAds(); 
                        }
                    }
                } else if (event.getAction() == KeyEvent.ACTION_UP) {
                    if (isOkPressed && !okLongPressExecuted) {
                        jsKey("ok"); // Gọi qua JS để nó tự tìm view hiện tại Play/Pause
                    }
                    isOkPressed = false;
                    okLongPressExecuted = false;
                }
                return true;
            }

            // Các phím điều hướng (Tua, Đổi màn hình)
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                switch (event.getKeyCode()) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                        if (event.getRepeatCount() == 0) jsKey("up");
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        if (event.getRepeatCount() == 0) jsKey("down");
                        return true;
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        if (event.getRepeatCount() == 0) {
                            episodeGestureDone = false;
                            forwardKeyToPlayer(event.getKeyCode()); // Tua video
                        } else if (!episodeGestureDone && event.getRepeatCount() >= 2) {
                            episodeGestureDone = true;
                            jsKey(event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? "epprev" : "epnext");
                        }
                        return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void autoClickAds() {
        // Tự động tìm View đang hiển thị (Web nhỏ hoặc Cửa sổ toàn màn hình)
        View target = customView != null ? customView : getWindow().getDecorView();
        int w = target.getWidth();
        int h = target.getHeight();

        tap(target, w * 0.66f, h * 0.65f); // Đóng thông báo
        target.postDelayed(() -> tap(target, w * 0.85f, h * 0.85f), 100); // Skip Ad
        target.postDelayed(() -> tap(target, w * 0.40f, h * 0.65f), 200); // Trình phát dự phòng

        // Khôi phục Focus
        target.postDelayed(() -> {
            target.requestFocus();
            if (customView == null) {
                webView.evaluateJavascript("if(window.tvFocusOurPage) window.tvFocusOurPage();", null);
            }
        }, 300);
    }

    private void tapCenter() {
        View target = customView != null ? customView : webView;
        tap(target, target.getWidth() / 2f, target.getHeight() / 2f);
    }

    private void tap(View view, float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        view.dispatchTouchEvent(down);
        MotionEvent up = MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, x, y, 0);
        view.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
    }

    private void jsKey(String key) {
        webView.evaluateJavascript("window.tvKey && window.tvKey('" + key + "')", null);
    }

    private void forwardKeyToPlayer(int code) {
        long now = SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0);
        KeyEvent up = new KeyEvent(now, now + 40, KeyEvent.ACTION_UP, code, 0);
        
        // Bắn phím điều khiển vào trực tiếp trình phát Video đang nổi trên cùng
        if (customView != null) {
            customView.dispatchKeyEvent(down);
            customView.dispatchKeyEvent(up);
        } else {
            webView.dispatchKeyEvent(down);
            webView.dispatchKeyEvent(up);
        }
    }

    private class FullscreenChromeClient extends WebChromeClient {

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (customView != null) {
                callback.onCustomViewHidden();
                return;
            }
            customView = view;
            customViewCallback = callback;

            fullscreenContainer = new FrameLayout(MainActivity.this);
            fullscreenContainer.setBackgroundColor(0xFF000000);
            fullscreenContainer.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER));

            setContentView(fullscreenContainer);
            applyImmersive();
            
            view.requestFocus(); // Ép hệ thống nhắm Focus vào Video Fullscreen

            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        @Override
        public void onHideCustomView() {
            if (customView == null) return;

            fullscreenContainer.removeAllViews();
            rootLayout.removeAllViews();
            rootLayout.addView(webView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            setContentView(rootLayout);
            applyImmersive();
            
            webView.requestFocus(); // Trả Focus lại cho Web

            customView = null;
            mediaControllerVisible = false;
            if (customViewCallback != null) {
                customViewCallback.onCustomViewHidden();
                customViewCallback = null;
            }

            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            webView.evaluateJavascript(
                "(function(){try{if(document.fullscreenElement||document.webkitFullscreenElement){(document.exitFullscreen||document.webkitExitFullscreen).call(document);return '1';}}catch(e){}return '0';})()",
                value -> { if (value == null || value.indexOf("1") == -1) chromeClient.onHideCustomView(); });
            return;
        }
        webView.evaluateJavascript(
            "(function(){try{if(window.tvBack&&window.tvBack())return '1';}catch(e){}return '0';})()",
            value -> { if (value == null || value.indexOf("1") == -1) finish(); });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && customView != null) {
            onBackPressed();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersive();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            rootLayout.removeView(webView);
            webView.clearHistory();
            webView.clearCache(true);
            webView.loadUrl("about:blank");
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}