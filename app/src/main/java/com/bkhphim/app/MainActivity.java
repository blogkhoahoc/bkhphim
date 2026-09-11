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

    // true khi màn trình phát đang mở (do trang web báo qua TvBridge)
    private volatile boolean playerOpen = false;
    // thanh điều khiển video hệ thống (MediaController) đang hiển thị hay không
    private boolean mediaControllerVisible = false;
    // chống lặp khi giữ phím ◀ ▶ để đổi tập
    private boolean episodeGestureDone = false;
    // chống lặp khi giữ phím ▲ để bật/tắt toàn màn hình
    private boolean upGestureDone = false;

    // Biến hỗ trợ bấm giữ (Long-press) phím OK
    private boolean isOkPressed = false;
    private long okPressTime = 0;
    private boolean okLongPressExecuted = false;

    // Fullscreen video (custom view) state
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
        
        // Đảm bảo WebView nhận focus ngay khi mở app trên TV
        webView.setFocusableInTouchMode(true);
        webView.requestFocus();

        applyImmersive();

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // cần cho localStorage (yêu thích, lịch sử)
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        
        // Tối ưu hóa cho Tivi: Tắt tính năng thu phóng (zoom) không cần thiết
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

    /**
     * Cầu nối JS -> Android. Trang web gọi TvBridge.setPlayerOpen(true/false)
     * khi mở/đóng màn trình phát để Activity biết lúc nào cần điều khiển remote.
     */
    private class TvBridge {
        @JavascriptInterface
        public void setPlayerOpen(boolean open) {
            playerOpen = open;
        }

        /** Chạm vào giữa vùng video — để iframe nhúng chiếm keyboard focus */
        @JavascriptInterface
        public void tapVideo() {
            runOnUiThread(() -> {
                mediaControllerVisible = false;
                tapCenter();
            });
        }

        /**
         * Bơm một phím thật vào WebView. Khi iframe (player nhúng) đang giữ
         * focus, phím sẽ tới trực tiếp player: Space = phát/dừng,
         * ArrowLeft/Right = tua — các player web đều hỗ trợ sẵn.
         */
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
                long now = SystemClock.uptimeMillis();
                webView.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, kc, 0));
                webView.dispatchKeyEvent(new KeyEvent(now, now + 40, KeyEvent.ACTION_UP, kc, 0));
            });
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (playerOpen) {
            boolean nativeVideoFs = customView != null && isNativeVideoFullscreen(customView);
            if (nativeVideoFs && event.getAction() == KeyEvent.ACTION_DOWN) {
                // Video hệ thống: OK = bấm nút play/pause trên MediaController
                if ((event.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER
                        || event.getKeyCode() == KeyEvent.KEYCODE_ENTER)
                        && event.getRepeatCount() == 0) {
                    toggleNativeVideoPlayPause();
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }

            // Xử lý riêng cho phím OK/Enter để hỗ trợ BẤM GIỮ (Long Press) click nút ẩn
            if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER || 
                event.getKeyCode() == KeyEvent.KEYCODE_ENTER || 
                event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (event.getRepeatCount() == 0) {
                        isOkPressed = true;
                        okPressTime = SystemClock.uptimeMillis();
                        okLongPressExecuted = false;
                    } else if (isOkPressed && !okLongPressExecuted) {
                        // Bấm giữ khoảng 0.6 giây sẽ kích hoạt click tự động các nút thông báo/quảng cáo
                        if (SystemClock.uptimeMillis() - okPressTime > 600) { 
                            okLongPressExecuted = true;
                            autoClickAds(); 
                        }
                    }
                } else if (event.getAction() == KeyEvent.ACTION_UP) {
                    isOkPressed = false;
                    if (!okLongPressExecuted) {
                        // Bấm nhả nhanh: Ưu tiên truyền lệnh phím Enter vào Web để click các nút đang được Focus
                        forwardKeyToWebView(KeyEvent.KEYCODE_ENTER);
                        // Kế tiếp gửi lệnh Space để Player nhúng xử lý Play/Pause
                        jsKey("ok"); 
                    }
                }
                return true;
            }

            // Xử lý các phím điều hướng D-pad
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                switch (event.getKeyCode()) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                        if (event.getRepeatCount() == 0) {
                            upGestureDone = false;
                            // Truyền phím Lên vào Web để người dùng điều hướng được tới các nút thông báo
                            forwardKeyToWebView(event.getKeyCode()); 
                        } else if (!upGestureDone && event.getRepeatCount() >= 2) {
                            upGestureDone = true;
                            jsKey("fs"); // Giữ phím Lên = Bật/Tắt Toàn màn hình
                        }
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        if (event.getRepeatCount() == 0) {
                            // Truyền phím Xuống vào Web để di chuyển focus
                            forwardKeyToWebView(event.getKeyCode()); 
                            jsKey("down");
                        }
                        return true;
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        if (event.getRepeatCount() == 0) {
                            episodeGestureDone = false;
                            forwardKeyToWebView(event.getKeyCode()); // 1 nhát = tua video
                        } else if (!episodeGestureDone && event.getRepeatCount() >= 2) {
                            episodeGestureDone = true; // Giữ phím = Đổi tập
                            jsKey(event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? "epprev" : "epnext");
                        }
                        return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    /** 
     * Tự động giả lập cú chạm vào tọa độ thường xuất hiện của các nút trên Tivi.
     */
    private void autoClickAds() {
        View decor = getWindow().getDecorView();
        int w = decor.getWidth();
        int h = decor.getHeight();

        // 1. Tọa độ nút "Đóng thông báo" (66% chiều ngang, 65% chiều dọc)
        tap(decor, w * 0.66f, h * 0.65f);
        
        // 2. Tọa độ nút "Bỏ qua quảng cáo >|" (85% ngang, 85% dọc)
        decor.postDelayed(() -> tap(decor, w * 0.85f, h * 0.85f), 100);

        // 3. Tọa độ nút "Kiểm tra lại" (40% ngang, 65% dọc)
        decor.postDelayed(() -> tap(decor, w * 0.40f, h * 0.65f), 200);
    }

    private boolean isNativeVideoFullscreen(View v) {
        if (v instanceof android.view.SurfaceView || v instanceof android.view.TextureView) return true;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (isNativeVideoFullscreen(g.getChildAt(i))) return true;
            }
        }
        return false;
    }

    private void toggleNativeVideoPlayPause() {
        View decor = getWindow().getDecorView();
        float bx = dp(34);
        float by = decor.getHeight() - dp(26);
        if (!mediaControllerVisible) {
            tap(decor, bx, by);
            decor.postDelayed(() -> tap(decor, bx, by), 350);
            mediaControllerVisible = true;
        } else {
            tap(decor, bx, by);
        }
    }

    private void tap(View decor, float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        decor.dispatchTouchEvent(down);
        MotionEvent up = MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, x, y, 0);
        decor.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
    }

    private float dp(int v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private void jsKey(String key) {
        webView.evaluateJavascript("window.tvKey && window.tvKey('" + key + "')", null);
    }

    private void forwardKeyToWebView(int code) {
        long now = SystemClock.uptimeMillis();
        webView.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
        webView.dispatchKeyEvent(new KeyEvent(now, now + 40, KeyEvent.ACTION_UP, code, 0));
    }

    private void tapCenter() {
        View decor = getWindow().getDecorView();
        tap(decor, decor.getWidth() / 2f, decor.getHeight() / 2f);
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
        // Tối ưu giải phóng bộ nhớ để tránh treo Tivi khi thoát ứng dụng
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