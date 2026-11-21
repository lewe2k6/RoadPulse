// c:\Users\Acer\Downloads\gsm\roadpulse_lite\app\src\main\java\vn\roadpulse\lite\RadarForegroundService.java
// Java 8 / Android SDK 26..34 / Foreground GNSS Radar with 5s Self-Healing Watchdog, Tunnel Decay & Viewport Clamp
package vn.roadpulse.lite;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Self-healing Foreground GNSS Service: recovers late-enabled GPS, decays frozen speed in tunnels,
 * clamps HUD pill inside screen across Landscape/Portrait rotations, and handles Android 12-15 FGS rules.
 */
public final class RadarForegroundService extends Service implements LocationListener {
    public static final String ACTION_START_OVERLAY = "vn.roadpulse.lite.START_OVERLAY";
    public static final String ACTION_START_RADAR_NO_OVERLAY = "vn.roadpulse.lite.START_RADAR_NO_OVERLAY";
    public static final String ACTION_STOP = "vn.roadpulse.lite.STOP";
    public static final String ACTION_SET_VEHICLE = "vn.roadpulse.lite.SET_VEHICLE";
    public static final String ACTION_CYCLE_AUDIO = "vn.roadpulse.lite.CYCLE_AUDIO";
    public static final String ACTION_TEST_SIM = "vn.roadpulse.lite.TEST_SIM";
    public static final String ACTION_TEST_VOICE = "vn.roadpulse.lite.TEST_VOICE";
    public static final String ACTION_RELOAD_PINS = "vn.roadpulse.lite.RELOAD_PINS";
    public static final String ACTION_RESET_HUD_POS = "vn.roadpulse.lite.RESET_HUD_POS";
    public static final String EXTRA_MOTO_MODE = "moto_mode";
    public static final String EXTRA_SHOW_OVERLAY = "show_overlay";

    private static final String CHANNEL_ID = "roadpulse_radar";
    private static final int NOTIF_ID = 1042;
    private static final long WATCHDOG_INTERVAL_MS = 5000L;
    private static final long STALE_GPS_DECAY_MS = 6200L;
    private static final long FROZEN_GNSS_REBIND_MS = 22_000L;

    public interface SnapshotObserver {
        void onRadarUpdated(SpatialRadarEngine.RadarSnapshot snap, boolean serviceRunning, boolean overlayActive);
    }

    private static volatile SnapshotObserver observer;
    private static volatile SpatialRadarEngine.RadarSnapshot latestSnapshot;
    private static volatile boolean running = false;
    private static volatile boolean overlayActive = false;
    private static volatile int loadedPoiCount = 0;
    private static volatile int selfHealEvents = 0;

    public static void setObserver(SnapshotObserver obs) {
        observer = obs;
        if (obs != null && latestSnapshot != null) {
            obs.onRadarUpdated(latestSnapshot, running, overlayActive);
        }
    }

    public static SpatialRadarEngine.RadarSnapshot getLatestSnapshot() {
        return latestSnapshot;
    }

    public static boolean isRunning() {
        return running;
    }

    public static boolean isOverlayActive() {
        return overlayActive;
    }

    public static int getLoadedPoiCount() {
        return loadedPoiCount;
    }

    public static int getSelfHealEvents() {
        return selfHealEvents;
    }

    public static boolean startSafely(Context context, Intent intent) {
        try {
            context.startForegroundService(intent);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SpatialRadarEngine radarEngine;
    private UserPinStore pinStore;
    private VoiceAlertManager voiceManager;
    private LocationManager locationManager;
    private WindowManager windowManager;
    private HudOverlayView overlayView;
    private WindowManager.LayoutParams overlayLp;
    private SharedPreferences prefs;

    private final List<SpatialRadarEngine.AudioTrigger> tmpTriggers = new ArrayList<>(4);
    private boolean gpsSubscribed = false;
    private boolean netSubscribed = false;
    private boolean wantOverlay = false;
    private long lastGpsFixMs = 0L;
    private long lastAnyFixMs = 0L;
    private long lastRebindAttemptMs = 0L;
    private double lastLat = 21.0285;
    private double lastLon = 105.8542;
    private int lastHeading = 45;
    private int simStep = -1;

    private final Runnable simTick = new Runnable() {
        @Override
        public void run() {
            if (simStep < 0 || simStep > 16) {
                simStep = -1;
                if (radarEngine != null) radarEngine.resetDynamicState();
                return;
            }
            double frac = simStep / 16.0;
            double sLat = 21.0493 + (21.0533 - 21.0493) * frac;
            double sLon = 105.8825 + (105.8886 - 105.8825) * frac;
            float speedMs = simStep < 5 ? 11.8f : (simStep < 11 ? 15.8f : 12.2f);
            processFix(sLat, sLon, speedMs, 54f, true, 5.0f);
            simStep++;
            handler.postDelayed(this, 900L);
        }
    };

    private final Runnable watchdogTick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            runSelfHealingChecks();
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("rp_prefs", MODE_PRIVATE);
        pinStore = new UserPinStore(this);
        radarEngine = new SpatialRadarEngine();
        radarEngine.loadFromAndroidAssets(this, "roadpulse_vn.rpoi");
        radarEngine.setMotoMode(prefs.getBoolean("moto_mode", true));
        radarEngine.updateUserPins(pinStore.snapshot());
        loadedPoiCount = radarEngine.getOfflinePoiCount();

        voiceManager = new VoiceAlertManager(this);
        voiceManager.setAudioMode(prefs.getInt("audio_mode", VoiceAlertManager.MODE_VOICE_AND_BEEP));

        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        running = true;

        handler.postDelayed(watchdogTick, WATCHDOG_INTERVAL_MS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!ensureForegroundNotification("Đang chạy radar cảnh báo giao thông")) {
            return START_NOT_STICKY;
        }
        ensureLocationSubscriptions(false);

        String action = intent != null ? intent.getAction() : ACTION_START_OVERLAY;
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_SET_VEHICLE.equals(action) && intent != null) {
            boolean moto = intent.getBooleanExtra(EXTRA_MOTO_MODE, true);
            prefs.edit().putBoolean("moto_mode", moto).apply();
            if (radarEngine != null) radarEngine.setMotoMode(moto);
            reEvaluateLastPoint();
            return START_STICKY;
        }
        if (ACTION_CYCLE_AUDIO.equals(action)) {
            int mode = voiceManager.cycleAudioMode();
            prefs.edit().putInt("audio_mode", mode).apply();
            if (overlayView != null) overlayView.setAudioMode(mode);
            ensureForegroundNotification("Âm thanh: " + voiceManager.getAudioModeLabel());
            return START_STICKY;
        }
        if (ACTION_TEST_VOICE.equals(action)) {
            voiceManager.testVoiceSample();
            return START_STICKY;
        }
        if (ACTION_RELOAD_PINS.equals(action)) {
            if (pinStore != null && radarEngine != null) {
                pinStore.reload();
                radarEngine.updateUserPins(pinStore.snapshot());
            }
            return START_STICKY;
        }
        if (ACTION_RESET_HUD_POS.equals(action)) {
            prefs.edit().putInt("overlay_x", 24).putInt("overlay_y", 180).apply();
            if (overlayView != null && overlayLp != null) {
                overlayLp.x = 24;
                overlayLp.y = 180;
                clampOverlayToScreen(true);
                overlayView.showFlashToast("Đã đặt lại vị trí HUD", 1500L);
            }
            return START_STICKY;
        }
        if (ACTION_TEST_SIM.equals(action)) {
            boolean showOvl = intent == null || intent.getBooleanExtra(EXTRA_SHOW_OVERLAY, true);
            wantOverlay = showOvl;
            if (showOvl && canDrawOverlay()) {
                attachOverlay();
            }
            simStep = 0;
            radarEngine.resetDynamicState();
            handler.removeCallbacks(simTick);
            handler.post(simTick);
            return START_STICKY;
        }
        if (ACTION_START_RADAR_NO_OVERLAY.equals(action)) {
            wantOverlay = false;
            detachOverlay();
            reEvaluateLastPoint();
            return START_STICKY;
        }

        // Default: ACTION_START_OVERLAY
        wantOverlay = true;
        if (canDrawOverlay()) {
            attachOverlay();
        }
        reEvaluateLastPoint();
        return START_STICKY;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Self-heal HUD coordinates when phone rotates between Landscape & Portrait
        clampOverlayToScreen(true);
    }

    private void runSelfHealingChecks() {
        long now = System.currentTimeMillis();

        // 1. Self-heal overlay permission revocation or late grant + screen bounds
        if (wantOverlay) {
            if (!canDrawOverlay() && overlayView != null) {
                detachOverlay();
                selfHealEvents++;
            } else if (canDrawOverlay() && overlayView == null) {
                attachOverlay();
                selfHealEvents++;
            } else if (overlayView != null) {
                clampOverlayToScreen(false);
            }
        }

        if (simStep >= 0) {
            return; // Demo simulation in progress
        }

        // 2. Self-heal tunnel / underpass stale GPS speed freeze (> 6.2s without fix)
        if (lastAnyFixMs > 0L && (now - lastAnyFixMs > STALE_GPS_DECAY_MS)) {
            SpatialRadarEngine.RadarSnapshot prev = latestSnapshot;
            if (prev != null && (prev.speedKmh > 0 || prev.gpsFixed || prev.overspeed)) {
                SpatialRadarEngine.RadarSnapshot decayed = radarEngine.buildSignalLostSnapshot(lastLat, lastLon, now);
                latestSnapshot = decayed;
                if (overlayView != null) overlayView.updateSnapshot(decayed);
                SnapshotObserver obs = observer;
                if (obs != null) obs.onRadarUpdated(decayed, running, overlayActive);
                selfHealEvents++;
            }
        }

        // 3. Self-heal late-enabled GPS or frozen OEM GNSS HAL (> 22s silent while provider enabled)
        if (hasLocationPerm() && locationManager != null) {
            boolean gpsEnabled = false;
            try { gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER); } catch (Exception ignored) {}
            if (!gpsSubscribed) {
                ensureLocationSubscriptions(false);
                selfHealEvents++;
            } else if (gpsEnabled && (now - lastGpsFixMs > FROZEN_GNSS_REBIND_MS) && (now - lastRebindAttemptMs > FROZEN_GNSS_REBIND_MS)) {
                lastRebindAttemptMs = now;
                ensureLocationSubscriptions(true);
                selfHealEvents++;
            }
        }
    }

    private boolean hasLocationPerm() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean canDrawOverlay() {
        return Settings.canDrawOverlays(this);
    }

    private void attachOverlay() {
        if (overlayView != null) {
            overlayView.setAudioMode(voiceManager.getAudioMode());
            clampOverlayToScreen(false);
            overlayActive = true;
            return;
        }
        overlayView = new HudOverlayView(this, false);
        overlayView.setAudioMode(voiceManager.getAudioMode());

        overlayLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
        );
        overlayLp.gravity = Gravity.TOP | Gravity.START;
        overlayLp.x = prefs.getInt("overlay_x", 24);
        overlayLp.y = prefs.getInt("overlay_y", 180);
        clampOverlayParamsInPlace();

        overlayView.setInteractionListener(new HudOverlayView.InteractionListener() {
            @Override
            public void onDragDelta(int dxPx, int dyPx) {
                if (overlayView == null || overlayLp == null) return;
                overlayLp.x += dxPx;
                overlayLp.y += dyPx;
                clampOverlayParamsInPlace();
                try {
                    windowManager.updateViewLayout(overlayView, overlayLp);
                } catch (Exception ignored) {}
            }

            @Override
            public void onDragFinished() {
                clampOverlayToScreen(true);
            }

            @Override
            public void onToggleCompact() {
                if (overlayView == null) return;
                overlayView.setCompactMode(!overlayView.isCompactMode());
                overlayView.requestLayout();
                clampOverlayToScreen(true);
            }

            @Override
            public void onCycleAudio() {
                int mode = voiceManager.cycleAudioMode();
                prefs.edit().putInt("audio_mode", mode).apply();
                if (overlayView != null) {
                    overlayView.setAudioMode(mode);
                    overlayView.showFlashToast("Loa: " + voiceManager.getAudioModeLabel(), 1400L);
                }
            }

            @Override
            public void onQuickPin() {
                SpatialRadarEngine.RadarSnapshot s = latestSnapshot;
                double pLat = s != null ? s.lat : lastLat;
                double pLon = s != null ? s.lon : lastLon;
                int pHdg = s != null ? s.headingDeg : SpatialRadarEngine.HEADING_OMNI;
                int pLim = s != null ? s.speedLimitKmh : 50;
                pinStore.addPin(pLat, pLon, pHdg, SpatialRadarEngine.CAT_POLICE, pLim, "Chốt CSGT tự ghim");
                radarEngine.updateUserPins(pinStore.snapshot());
                voiceManager.playConfirmPinChime();
                if (overlayView != null) {
                    overlayView.showFlashToast("Đã ghim chốt CSGT!", 2000L);
                }
            }

            @Override
            public void onCloseOverlay() {
                wantOverlay = false;
                stopSelf();
            }
        });

        try {
            windowManager.addView(overlayView, overlayLp);
            overlayActive = true;
            if (latestSnapshot != null) {
                overlayView.updateSnapshot(latestSnapshot);
            }
        } catch (Exception e) {
            overlayView = null;
            overlayActive = false;
        }
    }

    private boolean clampOverlayParamsInPlace() {
        if (overlayLp == null || overlayView == null) return false;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int sw = Math.max(320, dm.widthPixels);
        int sh = Math.max(320, dm.heightPixels);
        int vw = overlayView.getDesiredWidthPx();
        int vh = overlayView.getDesiredHeightPx();
        int margin = Math.round(6 * dm.density);
        int minY = Math.round(28 * dm.density);
        int maxX = Math.max(margin, sw - vw - margin);
        int maxY = Math.max(minY, sh - vh - margin);

        int clampedX = Math.max(margin, Math.min(maxX, overlayLp.x));
        int clampedY = Math.max(minY, Math.min(maxY, overlayLp.y));
        boolean changed = (clampedX != overlayLp.x) || (clampedY != overlayLp.y);
        overlayLp.x = clampedX;
        overlayLp.y = clampedY;
        return changed;
    }

    private void clampOverlayToScreen(boolean forceSave) {
        if (overlayView == null || overlayLp == null || windowManager == null) return;
        boolean changed = clampOverlayParamsInPlace();
        if (changed || forceSave) {
            try {
                windowManager.updateViewLayout(overlayView, overlayLp);
            } catch (Exception ignored) {}
            prefs.edit().putInt("overlay_x", overlayLp.x).putInt("overlay_y", overlayLp.y).apply();
        }
    }

    private void detachOverlay() {
        if (overlayView != null && windowManager != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception ignored) {}
        }
        overlayView = null;
        overlayActive = false;
    }

    private void ensureLocationSubscriptions(boolean forceReset) {
        if (locationManager == null || !hasLocationPerm()) return;
        if (forceReset) {
            try { locationManager.removeUpdates(this); } catch (Exception ignored) {}
            gpsSubscribed = false;
            netSubscribed = false;
        }
        try {
            if (!gpsSubscribed) {
                Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (last != null) {
                    lastLat = last.getLatitude();
                    lastLon = last.getLongitude();
                }
                // Subscribe directly even if GPS toggle is currently off; OS delivers fixes immediately once toggled on
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 850L, 0f, this, Looper.getMainLooper());
                gpsSubscribed = true;
            }
        } catch (Exception ignored) {
            gpsSubscribed = false;
        }
        try {
            if (!netSubscribed && locationManager.getAllProviders().contains(LocationManager.NETWORK_PROVIDER)) {
                Location lastNet = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (lastNet != null && lastAnyFixMs == 0L) {
                    lastLat = lastNet.getLatitude();
                    lastLon = lastNet.getLongitude();
                }
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3500L, 15f, this, Looper.getMainLooper());
                netSubscribed = true;
            }
        } catch (Exception ignored) {
            netSubscribed = false;
        }
    }

    @Override
    public void onLocationChanged(Location loc) {
        if (loc == null) return;
        boolean isGps = LocationManager.GPS_PROVIDER.equals(loc.getProvider());
        long now = System.currentTimeMillis();
        if (!isGps && (now - lastGpsFixMs < 8000L)) {
            return;
        }
        lastAnyFixMs = now;
        if (isGps) lastGpsFixMs = now;

        if (simStep >= 0 && isGps && loc.hasSpeed() && loc.getSpeed() > 1.5f) {
            simStep = -1;
            handler.removeCallbacks(simTick);
            radarEngine.resetDynamicState();
        } else if (simStep >= 0) {
            return;
        }

        float speedMs = loc.hasSpeed() ? loc.getSpeed() : 0f;
        boolean hasBearing = loc.hasBearing();
        float bearing = hasBearing ? loc.getBearing() : lastHeading;
        float acc = loc.hasAccuracy() ? loc.getAccuracy() : 25f;
        processFix(loc.getLatitude(), loc.getLongitude(), speedMs, bearing, hasBearing, acc);
    }

    private void reEvaluateLastPoint() {
        if (simStep >= 0) return;
        processFix(lastLat, lastLon, 0f, lastHeading, false, latestSnapshot != null ? latestSnapshot.accuracyMeters : 99f);
    }

    private void processFix(double lat, double lon, float speedMs, float bearing, boolean hasBearing, float accuracy) {
        if (radarEngine == null) return;
        if (Double.isFinite(lat) && Double.isFinite(lon)) {
            lastLat = lat;
            lastLon = lon;
        }
        long now = System.currentTimeMillis();
        tmpTriggers.clear();
        SpatialRadarEngine.RadarSnapshot snap = radarEngine.evaluate(
                lat, lon, speedMs, bearing, hasBearing, accuracy, now, tmpTriggers
        );
        lastHeading = snap.headingDeg;
        latestSnapshot = snap;

        if (!tmpTriggers.isEmpty()) {
            SpatialRadarEngine.AudioTrigger trig = tmpTriggers.get(0);
            voiceManager.announceAlert(trig.alert, trig.urgent);
        } else if (snap.overspeed) {
            voiceManager.playOverspeedWarning(snap.speedKmh, snap.speedLimitKmh);
        }

        if (overlayView != null) {
            overlayView.updateSnapshot(snap);
        }
        SnapshotObserver obs = observer;
        if (obs != null) {
            obs.onRadarUpdated(snap, running, overlayActive);
        }
    }

    private boolean ensureForegroundNotification(String statusLine) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW
            );
            ch.setShowBadge(false);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        }

        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent contentPi = PendingIntent.getActivity(this, 1, openIntent, PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, RadarForegroundService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 2, stopIntent, PendingIntent.FLAG_IMMUTABLE);

        Intent audioIntent = new Intent(this, RadarForegroundService.class).setAction(ACTION_CYCLE_AUDIO);
        PendingIntent audioPi = PendingIntent.getService(this, 3, audioIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notif = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("RoadPulse Lite • Radar Giao Thông")
                .setContentText(statusLine)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(contentPi)
                .addAction(new Notification.Action.Builder((android.graphics.drawable.Icon) null, "Đổi Loa", audioPi).build())
                .addAction(new Notification.Action.Builder((android.graphics.drawable.Icon) null, "Tắt Radar", stopPi).build())
                .build();

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIF_ID, notif);
            }
            return true;
        } catch (Throwable se) {
            stopSelf();
            return false;
        }
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}

    @Override
    public void onProviderEnabled(String provider) {
        ensureLocationSubscriptions(false);
    }

    @Override
    public void onProviderDisabled(String provider) {
        if (LocationManager.GPS_PROVIDER.equals(provider)) {
            lastGpsFixMs = 0L;
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        overlayActive = false;
        wantOverlay = false;
        handler.removeCallbacksAndMessages(null);
        detachOverlay();
        if (locationManager != null && (gpsSubscribed || netSubscribed)) {
            try { locationManager.removeUpdates(this); } catch (Exception ignored) {}
            gpsSubscribed = false;
            netSubscribed = false;
        }
        if (voiceManager != null) {
            voiceManager.release();
        }
        SnapshotObserver obs = observer;
        if (obs != null) {
            obs.onRadarUpdated(latestSnapshot, false, false);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
