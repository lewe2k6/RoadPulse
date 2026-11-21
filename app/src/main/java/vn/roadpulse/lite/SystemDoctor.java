// c:\Users\Acer\Downloads\gsm\roadpulse_lite\app\src\main\java\vn\roadpulse\lite\SystemDoctor.java
// Java 8 / Android SDK 26..34 / 7-Step In-App Self-Diagnostic & Auto-Repair Engine
package vn.roadpulse.lite;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.util.DisplayMetrics;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs 7 live health checks on permissions, GNSS hardware, battery killers, TTS, CRC32 database,
 * user pins, and HUD screen bounds — repairing issues automatically or offering 1-tap OS actions.
 */
public final class SystemDoctor {
    public static final class CheckItem {
        public final String title;
        public final String detail;
        public final boolean healthy;
        public final Runnable quickFix;

        public CheckItem(String title, String detail, boolean healthy, Runnable quickFix) {
            this.title = title;
            this.detail = detail;
            this.healthy = healthy;
            this.quickFix = quickFix;
        }
    }

    private SystemDoctor() {}

    public static List<CheckItem> runDiagnostics(final Activity activity, final UserPinStore pinStore,
                                                 final VoiceAlertManager voiceProbe, final Runnable onFixedCallback) {
        List<CheckItem> out = new ArrayList<>(8);

        // 1. Fine Location Permission
        boolean fineLoc = activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        out.add(new CheckItem(
                "1. Quyền Vị trí GPS chính xác",
                fineLoc ? "OK • Đã cấp quyền GNSS phần cứng" : "LỖI • Chưa cấp quyền (Chạm để cấp ngay)",
                fineLoc,
                fineLoc ? null : () -> activity.requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 101)
        ));

        // 2. Hardware GPS Switch
        LocationManager lm = (LocationManager) activity.getSystemService(Context.LOCATION_SERVICE);
        boolean gpsHwOn = false;
        try {
            gpsHwOn = lm != null && lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch (Exception ignored) {}
        out.add(new CheckItem(
                "2. Công tắc Định vị phần cứng",
                gpsHwOn ? "OK • Vệ tinh GPS đang bật" : "CẢNH BÁO • Đang tắt GPS trên máy (Chạm để bật)",
                gpsHwOn,
                gpsHwOn ? null : () -> {
                    try {
                        activity.startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                    } catch (Exception ignored) {}
                }
        ));

        // 3. Floating Overlay Permission
        boolean overlayOk = Settings.canDrawOverlays(activity);
        out.add(new CheckItem(
                "3. Quyền Nổi trên Google Maps",
                overlayOk ? "OK • Cho phép vẽ HUD đè lên bản đồ" : "CẦN BẬT • Chưa cấp quyền Overlay (Chạm để mở)",
                overlayOk,
                overlayOk ? null : () -> {
                    try {
                        activity.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + activity.getPackageName())));
                    } catch (Exception ignored) {}
                }
        ));

        // 4. Long-ride Battery Optimization Guard (Xiaomi / Samsung / Oppo / Vivo)
        PowerManager pm = (PowerManager) activity.getSystemService(Context.POWER_SERVICE);
        boolean ignoringDoze = pm == null || pm.isIgnoringBatteryOptimizations(activity.getPackageName());
        String mfr = Build.MANUFACTURER != null ? Build.MANUFACTURER : "Android";
        out.add(new CheckItem(
                "4. Chống giết ngầm đường dài (" + mfr + ")",
                ignoringDoze ? "OK • Không bị giới hạn pin chạy nền" : "NÊN BẬT • Tránh tự tắt HUD khi đi tỉnh (Chạm để mở khóa)",
                ignoringDoze,
                ignoringDoze ? null : () -> {
                    try {
                        Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + activity.getPackageName()));
                        activity.startActivity(i);
                    } catch (Exception e) {
                        try {
                            activity.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                        } catch (Exception ignored) {}
                    }
                }
        ));

        // 5. Vietnamese TTS Engine & Fallback
        boolean ttsOk = voiceProbe != null && voiceProbe.isTtsReady() && voiceProbe.isVietnameseVoiceAvailable();
        String ttsDetail = ttsOk
                ? "OK • Giọng đọc Tiếng Việt (vi-VN) sẵn sàng"
                : "DỰ PHÒNG • Máy thiếu giọng Tiếng Việt (Đã tự bật Bíp PCM • Chạm tải giọng)";
        out.add(new CheckItem(
                "5. Âm thanh & Giọng đọc Tiếng Việt",
                ttsDetail,
                ttsOk,
                ttsOk ? null : () -> {
                    try {
                        Intent installTts = new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA);
                        activity.startActivity(installTts);
                    } catch (Exception e) {
                        Toast.makeText(activity, "App đã tự động dùng chế độ Bíp cảnh báo an toàn", Toast.LENGTH_LONG).show();
                    }
                }
        ));

        // 6. Offline Database CRC32 & UserPinStore Self-Heal
        SpatialRadarEngine verifyEngine = new SpatialRadarEngine();
        boolean dbLoaded = verifyEngine.loadFromAndroidAssets(activity, "roadpulse_vn.rpoi");
        boolean deepOk = dbLoaded && verifyEngine.verifyDeepIntegrity();
        int healedPins = pinStore != null ? pinStore.reload() : 0;
        String dbDetail = deepOk
                ? ("OK • CRC32 chuẩn (" + verifyEngine.getOfflinePoiCount() + " điểm"
                    + (healedPins > 0 ? ", đã tự dọn " + healedPins + " dòng chốt lỗi" : "") + ")")
                : "LỖI • Dữ liệu offline cần khôi phục (Chạm để tự nạp lại)";
        out.add(new CheckItem(
                "6. Toàn vẹn dữ liệu Offline (.rpoi CRC32)",
                dbDetail,
                deepOk,
                deepOk ? null : () -> {
                    java.io.File cached = new java.io.File(activity.getFilesDir(), "roadpulse_vn.rpoi");
                    if (cached.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        cached.delete();
                    }
                    SpatialRadarEngine re = new SpatialRadarEngine();
                    boolean fixed = re.loadFromAndroidAssets(activity, "roadpulse_vn.rpoi") && re.verifyDeepIntegrity();
                    Toast.makeText(activity, fixed ? "Đã khôi phục kho dữ liệu chuẩn CRC32!" : "Không thể nạp lại dữ liệu", Toast.LENGTH_SHORT).show();
                    if (onFixedCallback != null) onFixedCallback.run();
                }
        ));

        // 7. HUD Coordinates & Previous Crash Self-Heal
        SharedPreferences prefs = activity.getSharedPreferences("rp_prefs", Context.MODE_PRIVATE);
        DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        int ox = prefs.getInt("overlay_x", 24);
        int oy = prefs.getInt("overlay_y", 180);
        boolean boundsValid = ox >= 0 && ox <= Math.max(200, dm.widthPixels - 80)
                && oy >= 0 && oy <= Math.max(200, dm.heightPixels - 60);
        if (!boundsValid) {
            prefs.edit().putInt("overlay_x", 24).putInt("overlay_y", 180).apply();
            if (RadarForegroundService.isRunning()) {
                RadarForegroundService.startSafely(activity,
                        new Intent(activity, RadarForegroundService.class).setAction(RadarForegroundService.ACTION_RESET_HUD_POS));
            }
        }
        String crashLog = CrashSelfHealer.readLastCrashSummary(activity);
        boolean step7Healthy = boundsValid && crashLog == null;
        String step7Detail;
        if (crashLog != null) {
            step7Detail = "CÓ NHẬT KÝ SỰ CỐ CŨ • Đã tự reset HUD an toàn (Chạm xem & xóa log)";
        } else if (!boundsValid) {
            step7Detail = "ĐÃ TỰ SỬA • Tọa độ HUD lệch màn hình đã được kéo về (24, 180)";
        } else {
            step7Detail = "OK • Tọa độ HUD (" + ox + ", " + oy + ") nằm gọn trong màn hình";
        }
        out.add(new CheckItem(
                "7. Vị trí HUD & Nhật ký tự hồi phục",
                step7Detail,
                step7Healthy,
                () -> {
                    prefs.edit().putInt("overlay_x", 24).putInt("overlay_y", 180).apply();
                    if (RadarForegroundService.isRunning()) {
                        RadarForegroundService.startSafely(activity,
                                new Intent(activity, RadarForegroundService.class).setAction(RadarForegroundService.ACTION_RESET_HUD_POS));
                    }
                    if (crashLog != null) {
                        new AlertDialog.Builder(activity)
                                .setTitle("Nhật ký tự hồi phục sự cố")
                                .setMessage(crashLog)
                                .setPositiveButton("Xóa nhật ký", (d, w) -> {
                                    CrashSelfHealer.clearCrashLog(activity);
                                    if (onFixedCallback != null) onFixedCallback.run();
                                })
                                .setNegativeButton("Đóng", null)
                                .show();
                    } else {
                        Toast.makeText(activity, "Đã đưa HUD về góc mặc định (24, 180)", Toast.LENGTH_SHORT).show();
                        if (onFixedCallback != null) onFixedCallback.run();
                    }
                }
        ));

        return out;
    }

    public static void showDoctorDialog(final Activity activity, final UserPinStore pinStore,
                                        final VoiceAlertManager voiceProbe, final Runnable onFixedCallback) {
        final List<CheckItem> items = runDiagnostics(activity, pinStore, voiceProbe, onFixedCallback);
        String[] rows = new String[items.size()];
        int okCount = 0;
        for (int i = 0; i < items.size(); i++) {
            CheckItem c = items.get(i);
            if (c.healthy) okCount++;
            rows[i] = (c.healthy ? "[OK] " : "[SỬA] ") + c.title + "\n" + c.detail;
        }
        new AlertDialog.Builder(activity)
                .setTitle("Chẩn đoán & Tự sửa (" + okCount + "/" + items.size() + " đạt)")
                .setItems(rows, (dialog, which) -> {
                    CheckItem chosen = items.get(which);
                    if (chosen.quickFix != null) {
                        chosen.quickFix.run();
                    } else {
                        Toast.makeText(activity, chosen.title + ": Đang hoạt động tốt!", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton("Đặt lại HUD", (d, w) -> {
                    activity.getSharedPreferences("rp_prefs", Context.MODE_PRIVATE)
                            .edit().putInt("overlay_x", 24).putInt("overlay_y", 180).apply();
                    if (RadarForegroundService.isRunning()) {
                        RadarForegroundService.startSafely(activity,
                                new Intent(activity, RadarForegroundService.class).setAction(RadarForegroundService.ACTION_RESET_HUD_POS));
                    }
                    Toast.makeText(activity, "Đã kéo HUD về vị trí gốc (24, 180)", Toast.LENGTH_SHORT).show();
                })
                .setPositiveButton("Đóng", null)
                .show();
    }
}
