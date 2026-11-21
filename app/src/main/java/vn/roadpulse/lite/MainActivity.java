// c:\Users\Acer\Downloads\gsm\roadpulse_lite\app\src\main\java\vn\roadpulse\lite\MainActivity.java
// Java 8 / Android SDK 26..34 / Control Panel, Native PiP & 1-Tap System Self-Doctor
package vn.roadpulse.lite;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Rational;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Locale;

/**
 * Ultra-lightweight main dashboard with 1-tap Ride Mode (HUD + Google Maps), Lewe2k6 Na Sam shortcut,
 * Native Android PiP mode, 7-step System Self-Doctor, simulation test, and custom checkpoint manager.
 */
public final class MainActivity extends Activity implements RadarForegroundService.SnapshotObserver {
    private static final int REQ_LOCATION_PERM = 101;
    private static final int REQ_OVERLAY_PERM = 102;
    private static final double NASAM_LAT = 22.0554663;
    private static final double NASAM_LON = 106.6136236;

    private SharedPreferences prefs;
    private UserPinStore pinStore;
    private float density;
    private boolean motoMode = true;
    private int pendingLaunchType = 0; // 0=none, 1=gmaps_home, 2=gmaps_nasam, 3=hud_only, 4=sim

    private ScrollView dashboardScroll;
    private HudOverlayView previewHudView;
    private HudOverlayView pipHudView;
    private VoiceAlertManager localVoiceTester;

    private TextView statusBadge;
    private TextView statsLine;
    private TextView btnMoto;
    private TextView btnCar;
    private TextView btnPermBanner;
    private TextView btnStartRide;
    private TextView btnToggleHudOnly;
    private TextView btnManagePins;
    private TextView btnAudioMode;
    private TextView btnSystemDoctor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("rp_prefs", MODE_PRIVATE);
        motoMode = prefs.getBoolean("moto_mode", true);
        pinStore = new UserPinStore(this);
        localVoiceTester = new VoiceAlertManager(this);

        pipHudView = new HudOverlayView(this, true);
        buildDashboardUi();
        setContentView(dashboardScroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        pinStore.reload();
        RadarForegroundService.setObserver(this);
        refreshUiState();

        if (pendingLaunchType != 0 && hasLocationPerm() && (pendingLaunchType == 4 || hasOverlayPerm())) {
            int p = pendingLaunchType;
            pendingLaunchType = 0;
            executeRideAction(p);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!isInPictureInPictureMode()) {
            RadarForegroundService.setObserver(null);
        }
    }

    @Override
    protected void onDestroy() {
        if (localVoiceTester != null) {
            localVoiceTester.release();
            localVoiceTester = null;
        }
        super.onDestroy();
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        if (isInPictureInPictureMode) {
            RadarForegroundService.setObserver(this);
            setContentView(pipHudView);
            SpatialRadarEngine.RadarSnapshot latest = RadarForegroundService.getLatestSnapshot();
            if (latest != null) {
                pipHudView.updateSnapshot(latest);
            }
        } else {
            setContentView(dashboardScroll);
            refreshUiState();
        }
    }

    @Override
    public void onRadarUpdated(SpatialRadarEngine.RadarSnapshot snap, boolean serviceRunning, boolean overlayActive) {
        runOnUiThread(() -> {
            if (snap != null) {
                previewHudView.updateSnapshot(snap);
                pipHudView.updateSnapshot(snap);
            }
            updateButtonsAndStatus(serviceRunning, overlayActive);
        });
    }

    private int dp(int v) {
        return Math.round(v * density);
    }

    private void buildDashboardUi() {
        dashboardScroll = new ScrollView(this);
        dashboardScroll.setBackgroundColor(0xFF0B0F14);
        dashboardScroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(24));
        dashboardScroll.addView(root);

        // Header
        LinearLayout hdrRow = new LinearLayout(this);
        hdrRow.setOrientation(LinearLayout.HORIZONTAL);
        hdrRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("RoadPulse Lite v1.1");
        title.setTextColor(0xFFF8FAFC);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        hdrRow.addView(title, tp);

        statusBadge = new TextView(this);
        statusBadge.setText("Sẵn sàng");
        statusBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        statusBadge.setTypeface(Typeface.DEFAULT_BOLD);
        statusBadge.setPadding(dp(10), dp(4), dp(10), dp(4));
        statusBadge.setBackground(roundRect(0xFF14291E, 0xFF22C55E, 12));
        statusBadge.setTextColor(0xFF4ADE80);
        hdrRow.addView(statusBadge);
        root.addView(hdrRow);

        statsLine = new TextView(this);
        statsLine.setTextColor(0xFF94A3B8);
        statsLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.2f);
        statsLine.setPadding(0, dp(6), 0, dp(14));
        root.addView(statsLine);

        // Live HUD Preview Card
        previewHudView = new HudOverlayView(this, true);
        LinearLayout.LayoutParams prevLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(90));
        prevLp.bottomMargin = dp(14);
        root.addView(previewHudView, prevLp);

        // Missing Permission Banner
        btnPermBanner = makeButton("", 0xFF3A1D12, 0xFFFB923C, 0xFFFDBA74, 13);
        btnPermBanner.setVisibility(View.GONE);
        btnPermBanner.setOnClickListener(v -> requestMissingPermissions());
        LinearLayout.LayoutParams permLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        permLp.bottomMargin = dp(12);
        root.addView(btnPermBanner, permLp);

        // Vehicle Selector Row
        LinearLayout vehRow = new LinearLayout(this);
        vehRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams vehLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        vehLp.bottomMargin = dp(12);

        btnMoto = makeButton("Xe máy (Ưu tiên)", 0xFF132A1F, 0xFF22C55E, 0xFF4ADE80, 14);
        btnCar = makeButton("Ô tô", 0xFF161E29, 0xFF2C3A4F, 0xFF94A3B8, 14);
        LinearLayout.LayoutParams halfLp1 = new LinearLayout.LayoutParams(0, dp(46), 1f);
        halfLp1.rightMargin = dp(6);
        LinearLayout.LayoutParams halfLp2 = new LinearLayout.LayoutParams(0, dp(46), 1f);
        halfLp2.leftMargin = dp(6);
        btnMoto.setOnClickListener(v -> selectVehicle(true));
        btnCar.setOnClickListener(v -> selectVehicle(false));
        vehRow.addView(btnMoto, halfLp1);
        vehRow.addView(btnCar, halfLp2);
        root.addView(vehRow, vehLp);

        // Primary CTA
        btnStartRide = makeButton("BẮT ĐẦU ĐI ĐƯỜNG (Bật HUD + Google Maps)", 0xFF16A34A, 0xFF22C55E, 0xFFFFFFFF, 15.2f);
        btnStartRide.setOnClickListener(v -> handleRideClick(1));
        LinearLayout.LayoutParams ctaLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        ctaLp.bottomMargin = dp(10);
        root.addView(btnStartRide, ctaLp);

        // Shortcut CTA: Direct route to Lewe2k6 / Na Sam
        TextView btnNaSam = makeButton("Dẫn thẳng về Na Sầm / Lewe2k6 (Xe máy)", 0xFF172554, 0xFF3B82F6, 0xFF93C5FD, 14);
        btnNaSam.setOnClickListener(v -> handleRideClick(2));
        LinearLayout.LayoutParams nsLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        nsLp.bottomMargin = dp(12);
        root.addView(btnNaSam, nsLp);

        // 7-Step Self-Doctor Button
        btnSystemDoctor = makeButton("Chẩn đoán & Tự sửa lỗi hệ thống (7 bước)", 0xFF1E1B4B, 0xFF6366F1, 0xFFC7D2FE, 13.5f);
        btnSystemDoctor.setOnClickListener(v -> SystemDoctor.showDoctorDialog(this, pinStore, localVoiceTester, this::refreshUiState));
        LinearLayout.LayoutParams docLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        docLp.bottomMargin = dp(12);
        root.addView(btnSystemDoctor, docLp);

        // Row 2: Overlay Toggle + Native Android PiP
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams mrLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mrLp.bottomMargin = dp(10);

        btnToggleHudOnly = makeButton("Chỉ bật HUD nổi", 0xFF161E29, 0xFF334155, 0xFFE2E8F0, 13.5f);
        btnToggleHudOnly.setOnClickListener(v -> {
            if (RadarForegroundService.isRunning()) {
                stopService(new Intent(this, RadarForegroundService.class));
            } else {
                handleRideClick(3);
            }
        });
        TextView btnPipMode = makeButton("Chế độ PiP Android", 0xFF161E29, 0xFF334155, 0xFFE2E8F0, 13.5f);
        btnPipMode.setOnClickListener(v -> launchNativePip());

        LinearLayout.LayoutParams m1 = new LinearLayout.LayoutParams(0, dp(46), 1f);
        m1.rightMargin = dp(6);
        LinearLayout.LayoutParams m2 = new LinearLayout.LayoutParams(0, dp(46), 1f);
        m2.leftMargin = dp(6);
        modeRow.addView(btnToggleHudOnly, m1);
        modeRow.addView(btnPipMode, m2);
        root.addView(modeRow, mrLp);

        // Row 3: Test Simulation & Audio Mode
        LinearLayout testRow = new LinearLayout(this);
        testRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams trLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        trLp.bottomMargin = dp(10);

        TextView btnSim = makeButton("Test Mô Phỏng 15s", 0xFF281C0E, 0xFFD97706, 0xFFFBBF24, 13.5f);
        btnSim.setOnClickListener(v -> runSimulationTest());
        btnAudioMode = makeButton("Loa: Giọng + Bíp", 0xFF161E29, 0xFF334155, 0xFFE2E8F0, 13.5f);
        btnAudioMode.setOnClickListener(v -> cycleAudioPref());

        testRow.addView(btnSim, m1);
        testRow.addView(btnAudioMode, m2);
        root.addView(testRow, trLp);

        // Row 4: Voice Test & Custom Pins Manager
        LinearLayout utilRow = new LinearLayout(this);
        utilRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams urLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        urLp.bottomMargin = dp(14);

        TextView btnVoice = makeButton("Thử giọng cảnh báo", 0xFF161E29, 0xFF334155, 0xFFCBD5E1, 13);
        btnVoice.setOnClickListener(v -> testVoiceSample());
        btnManagePins = makeButton("Chốt tự ghim (0)", 0xFF161E29, 0xFF334155, 0xFFCBD5E1, 13);
        btnManagePins.setOnClickListener(v -> showPinsDialog());

        utilRow.addView(btnVoice, m1);
        utilRow.addView(btnManagePins, m2);
        root.addView(utilRow, urLp);

        // Compact Tip Card
        TextView tipCard = new TextView(this);
        tipCard.setBackground(roundRect(0xFF111824, 0xFF1E293B, 12));
        tipCard.setPadding(dp(12), dp(12), dp(12), dp(12));
        tipCard.setTextColor(0xFF94A3B8);
        tipCard.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tipCard.setText("Cơ chế Tự Hồi Phục (Self-Healing v1.1) đang hoạt động:\n"
                + "• Watchdog 5s: Tự bắt lại GPS khi bật định vị muộn, tự về 0 km/h sau 6s vào hầm mất sóng.\n"
                + "• Xoay màn hình Ngang/Dọc: Tự kéo HUD lọt vào mép an toàn, không bao giờ văng khỏi màn.\n"
                + "• Bảo vệ nhạc nền: Watchdog 4.5s đảm bảo trả âm lượng Spotify/YouTube 100%, tự chuyển sang Bíp nếu máy thiếu giọng Tiếng Việt.\n"
                + "• Bấm 'Chẩn đoán & Tự sửa lỗi' để mở khóa tiết kiệm pin đường dài trên Xiaomi / Samsung / Oppo.");
        root.addView(tipCard);
    }

    private TextView makeButton(String text, int fillColor, int strokeColor, int textColor, float textSp) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(textColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(12), dp(10), dp(12), dp(10));
        tv.setBackground(roundRect(fillColor, strokeColor, 12));
        return tv;
    }

    private GradientDrawable roundRect(int fill, int stroke, int radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fill);
        gd.setStroke(dp(1), stroke);
        gd.setCornerRadius(dp(radiusDp));
        return gd;
    }

    private void selectVehicle(boolean moto) {
        motoMode = moto;
        prefs.edit().putBoolean("moto_mode", moto).apply();
        if (RadarForegroundService.isRunning()) {
            Intent i = new Intent(this, RadarForegroundService.class)
                    .setAction(RadarForegroundService.ACTION_SET_VEHICLE)
                    .putExtra(RadarForegroundService.EXTRA_MOTO_MODE, moto);
            RadarForegroundService.startSafely(this, i);
        }
        refreshUiState();
    }

    private void handleRideClick(int launchType) {
        if (!hasLocationPerm() || (launchType != 0 && !hasOverlayPerm())) {
            pendingLaunchType = launchType;
            requestMissingPermissions();
            return;
        }
        executeRideAction(launchType);
    }

    private void executeRideAction(int launchType) {
        if (launchType == 4) {
            runSimulationTest();
            return;
        }
        Intent svc = new Intent(this, RadarForegroundService.class)
                .setAction(RadarForegroundService.ACTION_START_OVERLAY)
                .putExtra(RadarForegroundService.EXTRA_MOTO_MODE, motoMode);
        RadarForegroundService.startSafely(this, svc);

        if (launchType == 1) {
            openGoogleMapsHome();
        } else if (launchType == 2) {
            openGoogleMapsToNaSam();
        }
    }

    private void launchNativePip() {
        if (!hasLocationPerm()) {
            requestMissingPermissions();
            return;
        }
        Intent svc = new Intent(this, RadarForegroundService.class)
                .setAction(RadarForegroundService.ACTION_START_RADAR_NO_OVERLAY)
                .putExtra(RadarForegroundService.EXTRA_MOTO_MODE, motoMode);
        RadarForegroundService.startSafely(this, svc);

        if (getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            PictureInPictureParams params = new PictureInPictureParams.Builder()
                    .setAspectRatio(new Rational(239, 100))
                    .build();
            try {
                enterPictureInPictureMode(params);
            } catch (Exception e) {
                Toast.makeText(this, "Không thể bật PiP lúc này", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "Thiết bị không hỗ trợ PiP chuẩn", Toast.LENGTH_SHORT).show();
        }
    }

    private void runSimulationTest() {
        if (!hasLocationPerm()) {
            pendingLaunchType = 4;
            requestMissingPermissions();
            return;
        }
        Intent svc = new Intent(this, RadarForegroundService.class)
                .setAction(RadarForegroundService.ACTION_TEST_SIM)
                .putExtra(RadarForegroundService.EXTRA_SHOW_OVERLAY, hasOverlayPerm());
        RadarForegroundService.startSafely(this, svc);
        Toast.makeText(this, "Đang chạy mô phỏng 15s (Ngã tư Cầu Chui 42 -> 57 km/h)...", Toast.LENGTH_SHORT).show();
    }

    private void testVoiceSample() {
        if (RadarForegroundService.isRunning()) {
            RadarForegroundService.startSafely(this,
                    new Intent(this, RadarForegroundService.class).setAction(RadarForegroundService.ACTION_TEST_VOICE));
            return;
        }
        if (localVoiceTester == null) {
            localVoiceTester = new VoiceAlertManager(this);
        }
        localVoiceTester.testVoiceSample();
    }

    private void cycleAudioPref() {
        if (RadarForegroundService.isRunning()) {
            RadarForegroundService.startSafely(this,
                    new Intent(this, RadarForegroundService.class).setAction(RadarForegroundService.ACTION_CYCLE_AUDIO));
            btnAudioMode.postDelayed(this::refreshUiState, 120L);
        } else {
            int cur = prefs.getInt("audio_mode", VoiceAlertManager.MODE_VOICE_AND_BEEP);
            prefs.edit().putInt("audio_mode", (cur + 1) % 3).apply();
            refreshUiState();
        }
    }

    private void openGoogleMapsHome() {
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage("com.google.android.apps.maps");
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launch);
                return;
            }
            Intent fallback = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:21.0285,105.8542?q=Hanoi"));
            startActivity(fallback);
        } catch (Exception ignored) {}
    }

    private void openGoogleMapsToNaSam() {
        try {
            String mode = motoMode ? "l" : "d";
            Uri navUri = Uri.parse("google.navigation:q=" + NASAM_LAT + "," + NASAM_LON + "&mode=" + mode);
            Intent navIntent = new Intent(Intent.ACTION_VIEW, navUri);
            navIntent.setPackage("com.google.android.apps.maps");
            navIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (navIntent.resolveActivity(getPackageManager()) != null) {
                startActivity(navIntent);
                return;
            }
            Uri webUri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=" + NASAM_LAT + "," + NASAM_LON + "&travelmode=two-wheeler");
            startActivity(new Intent(Intent.ACTION_VIEW, webUri));
        } catch (Exception ignored) {}
    }

    private void showPinsDialog() {
        pinStore.reload();
        List<UserPinStore.UserPin> list = pinStore.snapshot();
        if (list.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Chốt CSGT / Cam tự ghim")
                    .setMessage("Chưa có điểm nào tự ghim. Khi đi đường, chỉ cần chạm dấu + ngay trên HUD để ghim tọa độ & hướng chạy hiện tại!")
                    .setPositiveButton("Đóng", null)
                    .show();
            return;
        }
        String[] items = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            UserPinStore.UserPin p = list.get(i);
            items[i] = p.label + String.format(Locale.US, "\n(%.5f, %.5f • Hướng %s)",
                    p.latE6 / 1_000_000.0, p.lonE6 / 1_000_000.0,
                    p.headingDeg == SpatialRadarEngine.HEADING_OMNI ? "2 chiều" : (p.headingDeg + "°"));
        }
        new AlertDialog.Builder(this)
                .setTitle("Chốt tự ghim (" + list.size() + ") - Chạm để xóa")
                .setItems(items, (dialog, which) -> {
                    pinStore.removePinById(list.get(which).id);
                    notifyPinStoreChanged();
                    refreshUiState();
                })
                .setNegativeButton("Xóa hết", (d, w) -> {
                    pinStore.clearAll();
                    notifyPinStoreChanged();
                    refreshUiState();
                })
                .setPositiveButton("Đóng", null)
                .show();
    }

    private void notifyPinStoreChanged() {
        if (RadarForegroundService.isRunning()) {
            RadarForegroundService.startSafely(this,
                    new Intent(this, RadarForegroundService.class).setAction(RadarForegroundService.ACTION_RELOAD_PINS));
        }
    }

    private boolean hasLocationPerm() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasOverlayPerm() {
        return Settings.canDrawOverlays(this);
    }

    private void requestMissingPermissions() {
        if (!hasLocationPerm()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS}, REQ_LOCATION_PERM);
            } else {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION_PERM);
            }
            return;
        }
        if (!hasOverlayPerm()) {
            Toast.makeText(this, "Hãy bật 'Hiển thị trên ứng dụng khác' cho RoadPulse Lite", Toast.LENGTH_LONG).show();
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, REQ_OVERLAY_PERM);
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION_PERM) {
            if (hasLocationPerm() && pendingLaunchType == 4) {
                pendingLaunchType = 0;
                runSimulationTest();
            } else if (hasLocationPerm() && pendingLaunchType != 0 && !hasOverlayPerm()) {
                requestMissingPermissions();
            } else if (hasLocationPerm() && pendingLaunchType != 0) {
                int p = pendingLaunchType;
                pendingLaunchType = 0;
                executeRideAction(p);
            }
            refreshUiState();
        }
    }

    private void refreshUiState() {
        int offlineCount = RadarForegroundService.getLoadedPoiCount();
        if (offlineCount == 0) {
            offlineCount = peekAssetPoiCount();
        }
        int heals = RadarForegroundService.getSelfHealEvents();
        String healSuffix = heals > 0 ? (" • Tự sửa: " + heals) : " • CRC32 OK";
        statsLine.setText("Offline: " + offlineCount + " điểm (HN ⇄ Lạng Sơn / Na Sầm)" + healSuffix);
        btnManagePins.setText("Chốt tự ghim (" + pinStore.count() + ")");

        String crashLog = CrashSelfHealer.readLastCrashSummary(this);
        if (crashLog != null) {
            btnSystemDoctor.setText("Chẩn đoán hệ thống (Có nhật ký tự hồi phục!)");
            btnSystemDoctor.setBackground(roundRect(0xFF3B1518, 0xFFEF4444, 12));
            btnSystemDoctor.setTextColor(0xFFFCA5A5);
        } else {
            btnSystemDoctor.setText("Chẩn đoán & Tự sửa lỗi hệ thống (7 bước)");
            btnSystemDoctor.setBackground(roundRect(0xFF1E1B4B, 0xFF6366F1, 12));
            btnSystemDoctor.setTextColor(0xFFC7D2FE);
        }

        if (motoMode) {
            btnMoto.setBackground(roundRect(0xFF132A1F, 0xFF22C55E, 12));
            btnMoto.setTextColor(0xFF4ADE80);
            btnCar.setBackground(roundRect(0xFF161E29, 0xFF2C3A4F, 12));
            btnCar.setTextColor(0xFF94A3B8);
        } else {
            btnMoto.setBackground(roundRect(0xFF161E29, 0xFF2C3A4F, 12));
            btnMoto.setTextColor(0xFF94A3B8);
            btnCar.setBackground(roundRect(0xFF172554, 0xFF3B82F6, 12));
            btnCar.setTextColor(0xFF93C5FD);
        }

        int amode = prefs.getInt("audio_mode", VoiceAlertManager.MODE_VOICE_AND_BEEP);
        String alabel = amode == VoiceAlertManager.MODE_MUTE ? "Im lặng"
                : (amode == VoiceAlertManager.MODE_BEEP_ONLY ? "Chỉ bíp" : "Giọng + Bíp");
        btnAudioMode.setText("Loa: " + alabel);

        boolean locOk = hasLocationPerm();
        boolean ovlOk = hasOverlayPerm();
        if (!locOk || !ovlOk) {
            btnPermBanner.setVisibility(View.VISIBLE);
            if (!locOk) {
                btnPermBanner.setText("Chạm để cấp quyền Vị trí GPS (Bắt buộc)");
            } else {
                btnPermBanner.setText("Chạm để cấp quyền Nổi trên Google Maps (Overlay)");
            }
        } else {
            btnPermBanner.setVisibility(View.GONE);
        }

        updateButtonsAndStatus(RadarForegroundService.isRunning(), RadarForegroundService.isOverlayActive());
    }

    private void updateButtonsAndStatus(boolean running, boolean overlayActive) {
        if (running && overlayActive) {
            statusBadge.setText("HUD đang nổi");
            statusBadge.setBackground(roundRect(0xFF14291E, 0xFF22C55E, 12));
            statusBadge.setTextColor(0xFF4ADE80);
            btnToggleHudOnly.setText("Tắt HUD & Radar");
            btnToggleHudOnly.setBackground(roundRect(0xFF3B1518, 0xFFEF4444, 12));
            btnToggleHudOnly.setTextColor(0xFFFCA5A5);
        } else if (running) {
            statusBadge.setText("Radar nền");
            statusBadge.setBackground(roundRect(0xFF172554, 0xFF3B82F6, 12));
            statusBadge.setTextColor(0xFF93C5FD);
            btnToggleHudOnly.setText("Tắt Radar nền");
            btnToggleHudOnly.setBackground(roundRect(0xFF3B1518, 0xFFEF4444, 12));
            btnToggleHudOnly.setTextColor(0xFFFCA5A5);
        } else {
            statusBadge.setText("Sẵn sàng");
            statusBadge.setBackground(roundRect(0xFF1E293B, 0xFF475569, 12));
            statusBadge.setTextColor(0xFFCBD5E1);
            btnToggleHudOnly.setText("Chỉ bật HUD nổi");
            btnToggleHudOnly.setBackground(roundRect(0xFF161E29, 0xFF334155, 12));
            btnToggleHudOnly.setTextColor(0xFFE2E8F0);
        }
    }

    private int peekAssetPoiCount() {
        try (InputStream is = getAssets().open("roadpulse_vn.rpoi")) {
            byte[] hdr = new byte[24];
            if (is.read(hdr) == 24 && hdr[0] == 'R' && hdr[1] == 'P' && hdr[2] == 'O' && hdr[3] == 'I') {
                return ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN).getInt(20);
            }
        } catch (Exception ignored) {}
        return 0;
    }
}
