// c:\Users\Acer\Downloads\gsm\roadpulse_lite\app\src\main\java\vn\roadpulse\lite\VoiceAlertManager.java
// Java 8 / Android SDK 26..34 / Self-healing Vietnamese TTS + bounded PCM tone executor + 4.5s ducking watchdog
package vn.roadpulse.lite;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Speaks concise Vietnamese traffic warnings with guaranteed audio un-ducking watchdog and PCM tone fallback.
 */
public final class VoiceAlertManager {
    public static final int MODE_VOICE_AND_BEEP = 0;
    public static final int MODE_BEEP_ONLY = 1;
    public static final int MODE_MUTE = 2;

    private static final int SAMPLE_RATE = 22050;
    private static final long FOCUS_WATCHDOG_MS = 4500L;

    private final Context appContext;
    private final AudioManager audioManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor beepExecutor;

    private TextToSpeech tts;
    private volatile boolean ttsReady = false;
    private volatile boolean viVoiceAvailable = false;
    private volatile int audioMode = MODE_VOICE_AND_BEEP;
    private AudioFocusRequest activeFocusRequest;
    private long lastSpokenMs = 0L;
    private long lastOverspeedBeepMs = 0L;
    private long lastReinitAttemptMs = 0L;

    private final short[] alertChimePcm;
    private final short[] speedBeepPcm;
    private final short[] confirmChimePcm;

    private final Runnable focusWatchdog = new Runnable() {
        @Override
        public void run() {
            releaseFocus();
        }
    };

    public VoiceAlertManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        this.alertChimePcm = synthDualTone(880, 1175, 70, 85, 0.45);
        this.speedBeepPcm = synthDualTone(1320, 1320, 65, 65, 0.50);
        this.confirmChimePcm = synthDualTone(660, 990, 50, 70, 0.40);

        this.beepExecutor = new ThreadPoolExecutor(
                1, 1, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(2),
                new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "rp-audio");
                        t.setDaemon(true);
                        return t;
                    }
                },
                new ThreadPoolExecutor.DiscardOldestPolicy()
        );
        this.beepExecutor.allowCoreThreadTimeOut(true);

        initTts();
    }

    private synchronized void initTts() {
        try {
            tts = new TextToSpeech(appContext, status -> {
                if (status == TextToSpeech.SUCCESS && tts != null) {
                    Locale vi = Locale.forLanguageTag("vi-VN");
                    int res = tts.setLanguage(vi);
                    if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                        viVoiceAvailable = false;
                        tts.setLanguage(Locale.US);
                    } else {
                        viVoiceAvailable = true;
                    }
                    tts.setSpeechRate(1.12f);
                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override public void onStart(String utteranceId) {}
                        @Override public void onDone(String utteranceId) { releaseFocusDelayed(140L); }
                        @Override public void onError(String utteranceId) { releaseFocusDelayed(0L); }
                    });
                    ttsReady = true;
                } else {
                    ttsReady = false;
                    viVoiceAvailable = false;
                }
            });
        } catch (Throwable ignored) {
            ttsReady = false;
            viVoiceAvailable = false;
        }
    }

    private void trySelfHealTts() {
        long now = System.currentTimeMillis();
        if (now - lastReinitAttemptMs < 15_000L) return;
        lastReinitAttemptMs = now;
        mainHandler.post(() -> {
            synchronized (VoiceAlertManager.this) {
                if (tts != null) {
                    try { tts.shutdown(); } catch (Exception ignored) {}
                    tts = null;
                }
                ttsReady = false;
                initTts();
            }
        });
    }

    public boolean isTtsReady() {
        return ttsReady;
    }

    public boolean isVietnameseVoiceAvailable() {
        return viVoiceAvailable;
    }

    public int getAudioMode() {
        return audioMode;
    }

    public void setAudioMode(int mode) {
        this.audioMode = mode;
        if (mode == MODE_MUTE && tts != null) {
            try { tts.stop(); } catch (Exception ignored) {}
            releaseFocus();
        }
    }

    public int cycleAudioMode() {
        audioMode = (audioMode + 1) % 3;
        setAudioMode(audioMode);
        return audioMode;
    }

    public String getAudioModeLabel() {
        switch (audioMode) {
            case MODE_BEEP_ONLY: return "Chỉ bíp";
            case MODE_MUTE: return "Im lặng";
            default: return "Giọng + Bíp";
        }
    }

    public void playConfirmPinChime() {
        if (audioMode == MODE_MUTE) return;
        playPcmAsync(confirmChimePcm, false);
    }

    public void playOverspeedWarning(int speedKmh, int limitKmh) {
        if (audioMode == MODE_MUTE) return;
        long now = System.currentTimeMillis();
        if (now - lastOverspeedBeepMs < 3800L) return;
        lastOverspeedBeepMs = now;

        if (audioMode == MODE_VOICE_AND_BEEP && ttsReady && viVoiceAvailable && (now - lastSpokenMs >= 4500L)) {
            if (!speakText("Quá tốc độ " + limitKmh)) {
                playPcmAsync(speedBeepPcm, true);
            }
        } else {
            playPcmAsync(speedBeepPcm, true);
        }
    }

    public void announceAlert(SpatialRadarEngine.RadarAlert alert, boolean urgent) {
        if (audioMode == MODE_MUTE || alert == null) return;
        long now = System.currentTimeMillis();

        if (audioMode == MODE_BEEP_ONLY || !ttsReady || !viVoiceAvailable) {
            if (!ttsReady && audioMode == MODE_VOICE_AND_BEEP) {
                trySelfHealTts();
            }
            if (now - lastSpokenMs >= 1800L) {
                lastSpokenMs = now;
                playPcmAsync(alertChimePcm, true);
            }
            return;
        }

        if (now - lastSpokenMs < 2400L) {
            return;
        }
        final String text = buildSpeechPhrase(alert, urgent);
        playPcmAsync(alertChimePcm, false);
        mainHandler.postDelayed(() -> {
            if (!speakText(text)) {
                playPcmAsync(speedBeepPcm, true);
            }
        }, 190L);
    }

    public void testVoiceSample() {
        if (audioMode == MODE_MUTE) audioMode = MODE_VOICE_AND_BEEP;
        playPcmAsync(alertChimePcm, false);
        mainHandler.postDelayed(() -> {
            if (!ttsReady || !viVoiceAvailable || !speakText("Phía trước 300 mét, camera phạt nguội đèn đỏ, tốc độ 50")) {
                playPcmAsync(confirmChimePcm, true);
            }
        }, 200L);
    }

    private String buildSpeechPhrase(SpatialRadarEngine.RadarAlert a, boolean urgent) {
        int roundedDist = Math.max(50, ((a.distanceMeters + 25) / 50) * 50);
        String prefix = urgent ? ("Gần " + roundedDist + " mét, ") : ("Phía trước " + roundedDist + " mét, ");
        String body;
        if ((a.flags & SpatialRadarEngine.FLAG_MOTO_PROHIBITED) != 0) {
            return prefix + "đoạn đường cấm xe máy, chú ý đi đường gom";
        }
        switch (a.category) {
            case SpatialRadarEngine.CAT_CAM_RED_LIGHT:
                body = "camera phạt nguội đèn đỏ";
                break;
            case SpatialRadarEngine.CAT_CAM_SPEED:
                body = "camera bắn tốc độ";
                break;
            case SpatialRadarEngine.CAT_CAM_PENALTY:
                body = "camera phạt nguội";
                break;
            case SpatialRadarEngine.CAT_TRAFFIC_LIGHT:
                body = "đèn đỏ phía trước";
                break;
            case SpatialRadarEngine.CAT_POLICE:
                body = "chú ý chốt kiểm tra giao thông";
                break;
            case SpatialRadarEngine.CAT_URBAN_SIGN:
                body = "khu đông dân cư";
                break;
            case SpatialRadarEngine.CAT_WARNING_SIGN:
                body = (a.label != null && a.label.toLowerCase(Locale.ROOT).contains("đường sắt"))
                        ? "giao cắt đường sắt" : "biển cảnh báo nguy hiểm";
                break;
            default:
                body = "cảnh báo giao thông";
                break;
        }
        if (a.speedLimitKmh > 0 && (a.category <= SpatialRadarEngine.CAT_POLICE || a.category == SpatialRadarEngine.CAT_URBAN_SIGN)) {
            return prefix + body + ", giới hạn " + a.speedLimitKmh;
        }
        return prefix + body;
    }

    private boolean speakText(String text) {
        TextToSpeech engine = this.tts;
        if (engine == null || !ttsReady || audioMode != MODE_VOICE_AND_BEEP) {
            return false;
        }
        lastSpokenMs = System.currentTimeMillis();
        requestDuckFocus();
        try {
            int code = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "rp_" + lastSpokenMs);
            if (code != TextToSpeech.SUCCESS) {
                releaseFocus();
                trySelfHealTts();
                return false;
            }
            return true;
        } catch (Exception ignored) {
            releaseFocus();
            trySelfHealTts();
            return false;
        }
    }

    private void requestDuckFocus() {
        if (audioManager == null) return;
        try {
            if (activeFocusRequest == null) {
                AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build();
                activeFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(attrs)
                        .setAcceptsDelayedFocusGain(false)
                        .setOnAudioFocusChangeListener(focusChange -> {})
                        .build();
            }
            audioManager.requestAudioFocus(activeFocusRequest);
            // Hard watchdog ensures music volume always restores even if TTS service hangs
            mainHandler.removeCallbacks(focusWatchdog);
            mainHandler.postDelayed(focusWatchdog, FOCUS_WATCHDOG_MS);
        } catch (Exception ignored) {}
    }

    private void releaseFocusDelayed(long delayMs) {
        mainHandler.postDelayed(this::releaseFocus, delayMs);
    }

    private void releaseFocus() {
        mainHandler.removeCallbacks(focusWatchdog);
        if (audioManager != null && activeFocusRequest != null) {
            try {
                audioManager.abandonAudioFocusRequest(activeFocusRequest);
            } catch (Exception ignored) {}
        }
    }

    private void playPcmAsync(final short[] samples, final boolean releaseFocusAfter) {
        if (beepExecutor.isShutdown() || samples == null) return;
        beepExecutor.execute(() -> {
            if (releaseFocusAfter) requestDuckFocus();
            AudioTrack track = null;
            try {
                AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build();
                AudioFormat fmt = new AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build();
                track = new AudioTrack.Builder()
                        .setAudioAttributes(attrs)
                        .setAudioFormat(fmt)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .setBufferSizeInBytes(samples.length * 2)
                        .build();
                track.write(samples, 0, samples.length);
                track.play();
                long durMs = (samples.length * 1000L) / SAMPLE_RATE + 40L;
                Thread.sleep(durMs);
            } catch (Exception ignored) {
            } finally {
                if (track != null) {
                    try { track.stop(); } catch (Exception ignored) {}
                    try { track.release(); } catch (Exception ignored) {}
                }
                if (releaseFocusAfter) releaseFocus();
            }
        });
    }

    private static short[] synthDualTone(int hz1, int hz2, int ms1, int ms2, double amp) {
        int n1 = (SAMPLE_RATE * ms1) / 1000;
        int gap = (SAMPLE_RATE * 25) / 1000;
        int n2 = (SAMPLE_RATE * ms2) / 1000;
        short[] out = new short[n1 + gap + n2];
        fillTone(out, 0, n1, hz1, amp);
        fillTone(out, n1 + gap, n2, hz2, amp);
        return out;
    }

    private static void fillTone(short[] buf, int offset, int count, int hz, double amp) {
        int fade = Math.min(count / 5, 180);
        for (int i = 0; i < count; i++) {
            double env = 1.0;
            if (i < fade) env = (double) i / fade;
            else if (i > count - fade) env = (double) (count - i) / fade;
            double v = Math.sin(2.0 * Math.PI * hz * i / SAMPLE_RATE) * amp * env;
            buf[offset + i] = (short) (v * 32767.0);
        }
    }

    public synchronized void release() {
        mainHandler.removeCallbacksAndMessages(null);
        releaseFocus();
        beepExecutor.shutdownNow();
        if (tts != null) {
            try { tts.stop(); } catch (Exception ignored) {}
            try { tts.shutdown(); } catch (Exception ignored) {}
            tts = null;
        }
        ttsReady = false;
    }
}
