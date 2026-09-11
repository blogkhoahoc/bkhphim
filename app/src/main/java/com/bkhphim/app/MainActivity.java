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
    }

    /**
     * Khi trình phát đang mở, chặn phím remote TẠI ĐÂY thay vì để WebView đưa
     * xuống iframe (trình phát nhúng bên thứ ba sẽ nuốt mất phím và app không
     * còn cách nào điều khiển):
     *   OK            = phát/dừng  (giả lập cú chạm giữa màn hình video)
     *   DPAD_UP       = bật/tắt toàn màn hình
     *   DPAD_LEFT/RIGHT = tập trước / tập sau
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (playerOpen && event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    tapCenter();
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    jsKey("up");
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    jsKey("left");
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    jsKey("right");
                    return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void jsKey(String key) {
        webView.evaluateJavascript("window.tvKey && window.tvKey('" + key + "')", null);
    }

    /** Giả lập cú chạm giữa màn hình — chủ yếu để phát/dừng video trong iframe */
    private void tapCenter() {
        View decor = getWindow().getDecorView();
        long now = SystemClock.uptimeMillis();
        float x = decor.getWidth() / 2f;
        float y = decor.getHeight() / 2f;
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        decor.dispatchTouchEvent(down);
        MotionEvent up = MotionEvent.obtain(now, now + 80, MotionEvent.ACTION_UP, x, y, 0);
        decor.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
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
            if (customViewCallback != null) {
                customViewCallback.onCustomViewHidden();
                customViewCallback = null;
            }

            // Trả lại xoay tự do
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    // Phím Back: thoát fullscreen -> quay lại trong WebView -> thoát app
    @Override
    public void onBackPressed() {
        if (customView != null) {               // đang fullscreen video -> thoát fullscreen trước
            // Thoát chuẩn qua Fullscreen API của trang; nếu không phải element-fullscreen thì ẩn thủ công
            webView.evaluateJavascript(
                "(function(){try{if(document.fullscreenElement||document.webkitFullscreenElement){(document.exitFullscreen||document.webkitExitFullscreen).call(document);return '1';}}catch(e){}return '0';})()",
                value -> { if (value == null || value.indexOf("1") == -1) chromeClient.onHideCustomView(); });
            return;
        }
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
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
