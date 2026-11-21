// c:\Users\Acer\Downloads\gsm\roadpulse_lite\app\src\main\java\vn\roadpulse\lite\CrashSelfHealer.java
// Java 8 / Android SDK 26..34 / RoadPulse Lite uncaught crash recorder & state sanitizer
package vn.roadpulse.lite;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Captures unexpected runtime crashes, resets potentially off-screen/corrupt HUD prefs, and persists diagnostic logs.
 */
public final class CrashSelfHealer implements Thread.UncaughtExceptionHandler {
    private static final String CRASH_FILE = "last_crash.log";
    private final Context appContext;
    private final Thread.UncaughtExceptionHandler previousHandler;

    private CrashSelfHealer(Context appContext, Thread.UncaughtExceptionHandler previousHandler) {
        this.appContext = appContext.getApplicationContext();
        this.previousHandler = previousHandler;
    }

    public static void install(Context context) {
        Thread.UncaughtExceptionHandler current = Thread.getDefaultUncaughtExceptionHandler();
        if (current instanceof CrashSelfHealer) {
            return;
        }
        Thread.setDefaultUncaughtExceptionHandler(new CrashSelfHealer(context, current));
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        try {
            // Self-heal persisted HUD coordinates in case crash was caused by corrupt layout bounds
            SharedPreferences prefs = appContext.getSharedPreferences("rp_prefs", Context.MODE_PRIVATE);
            prefs.edit()
                    .putInt("overlay_x", 24)
                    .putInt("overlay_y", 180)
                    .putLong("last_healed_crash_ms", System.currentTimeMillis())
                    .commit();

            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            pw.println("Time: " + ts);
            pw.println("Thread: " + (t != null ? t.getName() : "unknown"));
            if (e != null) {
                e.printStackTrace(pw);
            }
            pw.flush();
            byte[] data = sw.toString().getBytes(StandardCharsets.UTF_8);

            File f = new File(appContext.getFilesDir(), CRASH_FILE);
            try (FileOutputStream fos = new FileOutputStream(f, false)) {
                fos.write(data, 0, Math.min(data.length, 8192));
                fos.getFD().sync();
            }
        } catch (Throwable ignored) {
        } finally {
            if (previousHandler != null) {
                previousHandler.uncaughtException(t, e);
            }
        }
    }

    public static String readLastCrashSummary(Context context) {
        File f = new File(context.getFilesDir(), CRASH_FILE);
        if (!f.exists() || f.length() == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            int lines = 0;
            while ((line = br.readLine()) != null && lines < 14) {
                sb.append(line).append('\n');
                lines++;
            }
        } catch (Exception ignored) {
            return null;
        }
        return sb.toString().trim();
    }

    public static void clearCrashLog(Context context) {
        File f = new File(context.getFilesDir(), CRASH_FILE);
        if (f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }
}
