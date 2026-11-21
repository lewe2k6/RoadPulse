// c:\Users\Acer\Downloads\gsm\roadpulse_lite\app\src\main\java\vn\roadpulse\lite\RoadPulseApp.java
// Java 8 / Android SDK 26..34 / Application entry point installing global CrashSelfHealer
package vn.roadpulse.lite;

import android.app.Application;

public final class RoadPulseApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        CrashSelfHealer.install(this);
    }
}
