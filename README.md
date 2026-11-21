# RoadPulse Lite (v1.1)

Ứng dụng HUD & Radar giao thông siêu nhẹ (**256.5 KB** tính cả dữ liệu Offline toàn quốc) chạy song song với **Google Maps** trên Android (`Android 8.0+ / API 26..34`), tối ưu riêng cho **Xe máy** và **Ô tô** tại Việt Nam.

## Điểm nổi bật

* **Siêu nhẹ & Thuần Android SDK (`Zero Dependencies`):**
  * Dung lượng mã máy + giao diện chỉ **56.6 KB** (`classes.dex` nén `44.9 KB`), không dùng thư viện ngoài, vẽ trực tiếp bằng `Canvas` không cấp phát bộ nhớ thừa (`0 allocation` trong `onDraw`).
  * Cơ sở dữ liệu Offline nén nhị phân phẳng `app/assets/roadpulse_vn.rpoi` (**200 KB**, lưu `ZIP_STORED` để ánh xạ thẳng bằng `MappedByteBuffer` 0-copy, kiểm chứng toàn vẹn `CRC32`).
* **Chế độ Kép (Dual-Mode) đi cùng Google Maps:**
  * **Floating HUD Pill (`TYPE_APPLICATION_OVERLAY`):** Nằm gọn trên Google Maps (cả khi Google Maps mở toàn màn hình lẫn khi thu về PiP mà không tranh chấp cửa sổ PiP của hệ điều hành). Kéo thả tự do, chạm đúp thu gọn thành viên thuốc tốc độ.
  * **Native Android PiP (`239:100`):** Cửa sổ Picture-in-Picture gốc hệ điều hành khi không muốn cấp quyền Overlay.
* **Radar Hình Nón & Cảnh Báo Thực Chiến (`6,606` điểm Offline):**
  * Đo tốc độ GPS thời gian thực + đối chiếu biển giới hạn tốc độ chuẩn QCVN (đổi màu Xanh / Cam / Đỏ nhấp nháy khi quá tốc độ).
  * Cảnh báo sớm theo góc nhìn mũi xe (`42°`) + lọc lệch ngang cự ly gần chống báo nhầm đường gom/chiều ngược lại: **Camera phạt nguội AI 24/7, Camera vượt đèn đỏ, Camera bắn tốc độ, Đèn tín hiệu ngã tư, Chốt CSGT, Khu đông dân cư, Giao cắt đường sắt, Nhánh đường cấm xe máy (CT01, Vành Đai 3 trên cao, Cầu Thanh Trì, ĐL Thăng Long...)**.
  * Chạm dấu `+` ngay trên HUD để ghim nhanh chốt CSGT/camera mới trên đường (lưu nguyên tử kèm `fsync`).
* **Hệ thống Tự Chữa Lành (`Self-Healing Watchdog v1.1`):**
  * **Watchdog 5s:** Tự bắt lại vệ tinh khi bật GPS muộn, tự hạ tốc độ về `0 km/h` sau `6.2s` khi chui vào hầm mất sóng, tự kéo HUD vào vùng an toàn khi xoay Ngang/Dọc màn hình.
  * **Audio Ducking 4.5s Watchdog:** Đọc cảnh báo Tiếng Việt ngắn gọn + âm Bíp PCM tổng hợp (0 KB file âm thanh), tự hạ nhỏ nhạc Spotify/YouTube tạm thời và đảm bảo trả lại 100% âm lượng ngay cả khi bộ đọc TTS của máy bị treo.
  * **Chẩn đoán 7 bước (`SystemDoctor`):** Tích hợp công cụ tự kiểm tra & mở khóa chống giết ngầm pin đường dài trên Xiaomi / Samsung / Oppo / Vivo.

## Tải cài đặt ngay

* Tải file APK đã ký sẵn tại: [`dist/RoadPulseLite-v1.1.apk`](dist/RoadPulseLite-v1.1.apk)

## Cấu trúc thư mục

```text
├── app/
│   ├── AndroidManifest.xml
│   ├── assets/roadpulse_vn.rpoi           # Kho dữ liệu nhị phân v2 (6,606 điểm, CRC32)
│   ├── res/                               # Vector icon & theme tối OLED
│   └── src/main/java/vn/roadpulse/lite/
│       ├── RoadPulseApp.java              # Khởi chạy trình bảo vệ sự cố toàn cục
│       ├── CrashSelfHealer.java           # Bắt ngoại lệ & tự khôi phục trạng thái HUD
│       ├── MainActivity.java              # Bảng điều khiển 1 chạm + Native PiP
│       ├── RadarForegroundService.java    # GNSS 1Hz + Watchdog 5s + HUD Nổi
│       ├── HudOverlayView.java            # Giao diện Canvas OLED 60fps
│       ├── SpatialRadarEngine.java        # Truy vấn lưới mmap O(log C) + lọc góc nón
│       ├── VoiceAlertManager.java         # TTS Tiếng Việt + Bíp PCM + Audio Ducking
│       ├── UserPinStore.java              # Lưu chốt cá nhân chống lỗi dòng & atomic save
│       └── SystemDoctor.java              # Trình tự chẩn đoán & sửa lỗi 7 bước
├── tools/
│   ├── compile_poi_pack.py                # Trình biên dịch dữ liệu sang định dạng .rpoi v2
│   ├── build_apk.py                       # Đóng gói APK thuần aapt2 + javac + d8 + apksigner
│   ├── RadarFuzzAndFaultHarness.java      # Bộ test 100,000 ca fuzzing & tiêm lỗi nhị phân
│   └── auto_doctor.py                     # Pipeline tự kiểm tra 4 cổng (API 26..34 + Fuzz + APK)
└── dist/
    └── RoadPulseLite-v1.1.apk             # Bản cài đặt phát hành (256.5 KB)
```

## Tự Build & Chạy Kiểm Thử Tự Động (`Auto-Doctor`)

Yêu cầu Python 3.10+, JDK 17 và Android SDK (`platforms/android-26`, `platforms/android-34`, `build-tools/34.0.0`):

```bash
python tools/auto_doctor.py
```
