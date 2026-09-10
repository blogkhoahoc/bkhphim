# BKHPhim — APK cho Điện thoại & Android TV

Ứng dụng WebView bọc trang web xem phim (giao diện đã tối ưu cho cả điện thoại và Android TV).
APK được build tự động bởi GitHub Actions — **máy bạn không cần cài Android Studio hay SDK**.

## Cách build APK (chỉ 1 lần setup)

1. Tạo tài khoản GitHub (nếu chưa có) → bấm **New repository** → đặt tên ví dụ `bkhphim-apk` → tạo repo **Private** cũng được.
2. Đẩy thư mục này lên GitHub:

   ```bash
   cd BKHPhimAPK
   git init
   git add .
   git commit -m "BKHPhim app"
   git branch -M main
   git remote add origin https://github.com/<tên-bạn>/bkhphim-apk.git
   git push -u origin main
   ```

3. Trên GitHub: mở tab **Actions** → đợi workflow "Build APK" chạy xong (~3–5 phút).
4. Vào đúng lần chạy đó, mục **Artifacts** → tải `BKHPhim-APK` → giải nén → được file `app-debug.apk`.

## Cách cài

**Điện thoại:** copy APK vào máy → mở file → cho phép "Cài từ nguồn không xác định".

**Tivi Xiaomi (Android TV):**
- Cách 1: dùng **Browser / File Manager trên TV** tải APK, hoặc
- Cách 2: đẩy APK vào USB → cắm vào tivi → mở bằng trình quản lý tệp (cài "File Commander" hoặc "Send Files to TV" từ Google Play trên TV nếu chưa có).
- Lưu ý: vào **Cài đặt → Quyền riêng tư/Bảo mật → cho phép cài ứng dụng không rõ nguồn**.
- App sẽ hiện ngay trên màn hình chính tivi (có banner riêng) nhờ cấu hình LEANBACK.

## Cập nhật giao diện web

Chỉnh sửa file `app/src/main/assets/index.html` → commit + push → Actions tự build lại APK mới.

## Cấu hình quan trọng đã bật

- `LEANBACK_LAUNCHER` + banner → hiện trên launcher Android TV
- `touchscreen required=false` → cài được trên TV
- `usesCleartextTraffic` → phát được cả link stream http
- DOM Storage → lưu Yêu thích / Lịch sử / Xem tiếp trên thiết bị
