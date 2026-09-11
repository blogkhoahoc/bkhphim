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

    /**
     * Khi trình phát đang mở, chặn phím remote TẠI ĐÂY thay vì để WebView đưa
     * xuống iframe (trình phát nhúng bên thứ ba sẽ nuốt mất phím và app không
     * còn cách nào điều khiển).
     *
     * Ngoại lệ: khi video đang chạy toàn màn hình HỆ THỐNG (custom view là
     * SurfaceView/TextureView của video), ta nhường phím mũi tên cho thanh điều
     * khiển của Android (MediaController) tự xử lý — D-pad focus nút, ◀ ▶ tua.
     *   OK            = phát/dừng
     *   DPAD_UP       = bật/tắt toàn màn hình
     *   DPAD_LEFT/RIGHT = tập trước / tập sau
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (playerOpen && event.getAction() == KeyEvent.ACTION_DOWN) {
            boolean nativeVideoFs = customView != null && isNativeVideoFullscreen(customView);
            if (nativeVideoFs) {
                // Video hệ thống: OK = bấm nút play/pause trên MediaController,
                // các phím còn lại nhường nguyên viện cho Android (tua/focus)
                if ((event.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER
                        || event.getKeyCode() == KeyEvent.KEYCODE_ENTER)
                        && event.getRepeatCount() == 0) {
                    toggleNativeVideoPlayPause();
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
            if (event.getRepeatCount() == 0 || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT
                    || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT) {
                switch (event.getKeyCode()) {
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                    case KeyEvent.KEYCODE_ENTER:
                    case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    case KeyEvent.KEYCODE_MEDIA_PLAY:
                    case KeyEvent.KEYCODE_MEDIA_PAUSE:
                        if (event.getRepeatCount() == 0) {
                            if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER || event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
								forwardKeyToWebView(event.getKeyCode());
							}
							
							// OK = chạm video + gửi phím Space -> player nhúng tự phát/dừng
                            jsKey("ok");
                        }
                        return true;
                    case KeyEvent.KEYCODE_DPAD_UP:
                        if (event.getRepeatCount() == 0) {
                            upGestureDone = false;
                            jsKey("up");
                        } else if (!upGestureDone && event.getRepeatCount() >= 2) {
                            upGestureDone = true;
                            jsKey("fs"); // giữ ▲ ~0.7s = bật/tắt toàn màn hình
                        }
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        if (event.getRepeatCount() == 0) jsKey("down");
                        return true;
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        if (event.getRepeatCount() == 0) {
                            episodeGestureDone = false;
                            forwardKeyToWebView(event.getKeyCode());   // 1 nhát = tua video
                        } else if (!episodeGestureDone && event.getRepeatCount() >= 2) {
                            episodeGestureDone = true;                  // giữ ~0.7s = đổi tập
                            jsKey(event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? "epprev" : "epnext");
                        }
                        return true; // nuốt repeat trung gian để không tua 2 lần
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    /** Custom view này có phải là surface video toàn màn hình của HTML5 không */
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

    /**
     * Phát/dừng khi video đang ở toàn màn hình hệ thống: MediaController của
     * Android có nút play/pause ở góc trái dưới. Nếu thanh điều khiển đang ẩn,
     * cú chạm đầu sẽ làm nó hiện lên, cú chạm sau (350ms) bấm vào nút.
     */
    private void toggleNativeVideoPlayPause() {
        View decor = getWindow().getDecorView();
        float bx = dp(34);
        float by = decor.getHeight() - dp(26);
        if (!mediaControllerVisible) {
            tap(decor, bx, by);                                    // hiện thanh điều khiển
            decor.postDelayed(() -> tap(decor, bx, by), 350);      // bấm nút play/pause
            mediaControllerVisible = true;
        } else {
            tap(decor, bx, by);                                    // bấm thẳng nút
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

    /** Đưa phím mũi tên thật vào WebView — player nhúng đang giữ focus sẽ tự tua */
    private void forwardKeyToWebView(int code) {
        long now = SystemClock.uptimeMillis();
        webView.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
        webView.dispatchKeyEvent(new KeyEvent(now, now + 40, KeyEvent.ACTION_UP, code, 0));
    }

    /** Giả lập cú chạm giữa màn hình — chủ yếu để phát/dừng video trong iframe */
    private void tapCenter() {
        View decor = getWindow().getDecorView();
        tap(decor, decor.getWidth() / 2f, decor.getHeight() / 2f);
    }

    /**
     * WebChromeClient xử lý nút fullscreen của trình phát video trong iframe.
     * Khi video/iframe yêu cầu fullscreen, Android hiển thị custom view toàn màn hình
     * và khóa xoay ngang; thoát fullscreen thì trả lại giao diện app như cũ.
     */
    private class FullscreenChromeClient extends WebChromeClient {

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (customView != null) {           // đang fullscreen rồi -> bỏ qua
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

            // Khóa ngang khi xem fullscreen (tivi đã ngang sẵn nên không đổi gì)
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

            // Trả lại xoay tự do
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    // Phím Back: thoát fullscreen -> đóng trình phát -> về trang chủ -> thoát app.
    // Trang web tự quyết định qua window.tvBack(): trả về true nghĩa là "đã xử lý",
    // false nghĩa là đang ở trang chủ -> thoát app thật.
    @Override
    public void onBackPressed() {
        if (customView != null) {               // đang fullscreen video -> thoát fullscreen trước
            // Thoát chuẩn qua Fullscreen API của trang; nếu không phải element-fullscreen thì ẩn thủ công
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
        // Một số remote/TV Box gửi phím Back qua onKeyDown thay vì onBackPressed
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
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
