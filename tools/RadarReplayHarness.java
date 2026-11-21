// c:\Users\Acer\Downloads\gsm\roadpulse_lite\tools\RadarReplayHarness.java
// Java 11 / Host-side verification harness running actual SpatialRadarEngine against compiled roadpulse_vn.rpoi
package vn.roadpulse.lite;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class RadarReplayHarness {
    private static final double[][] WAYPOINTS = {
        // 1. Inner Hanoi: Dien Bien Phu -> Cua Nam -> Cau Chuong Duong
        {21.0340, 105.8347, 50f, 115f}, // approaching Dien Bien Phu
        {21.0369, 105.8347, 46f, 95f},  // right at DBP cam
        {21.0315, 105.8406, 44f, 110f}, // Cua Nam redlight AI cam
        {21.0448, 105.8548, 48f, 65f},  // Cau Chuong Duong head
        // 2. Long Bien: Nguyen Van Cu -> Cau Chui
        {21.0493, 105.8825, 45f, 54f},  // 600m before Cau Chui
        {21.0510, 105.8850, 56f, 54f},  // 350m before Cau Chui - OVERSPEED (>50)
        {21.0527, 105.8876, 48f, 54f},  // right at Cau Chui
        {21.0540, 105.8898, 48f, 54f},  // 250m past Cau Chui -> MUST BE PASSED!
        // 3. Bac Giang: Near CT01 Expressway prohibition gate
        {21.2545, 106.1945, 55f, 40f},  // 480m before CT01 entrance
        // 4. Lang Son -> Dong Dang -> QL4A -> Na Sam (Lewe2k6)
        {21.8915, 106.7395, 52f, 325f}, // approaching Hoang Dong CSGT
        {21.9430, 106.6990, 48f, 330f}, // approaching Dong Dang junction -> turn QL4A
        {21.9740, 106.6805, 38f, 318f}, // approaching QL4A curve/slope
        {22.0355, 106.6285, 46f, 315f}, // approaching Na Sam gate CSGT
        {22.0554, 106.6136, 20f, 310f}, // Arrived Lewe2k6 (22.0554663, 106.6136236)
    };

    public static void main(String[] args) throws Exception {
        System.setOut(new java.io.PrintStream(System.out, true, "UTF-8"));
        File rpoi = new File(args[0]);
        SpatialRadarEngine engine = new SpatialRadarEngine();
        engine.loadFromFile(rpoi);
        engine.setMotoMode(true);
        System.out.println("Loaded RPOI records = " + engine.getOfflinePoiCount());
        if (engine.getOfflinePoiCount() < 100) {
            throw new AssertionError("Too few POIs loaded!");
        }

        // Test 1: User Pin integration
        List<UserPinStore.UserPin> pins = new ArrayList<>();
        pins.add(new UserPinStore.UserPin(999999, 21048500, 105880500, 54,
                SpatialRadarEngine.CAT_POLICE, 50, "Test Custom CSGT", 0L));
        engine.updateUserPins(pins);

        long now = 1_000_000L;
        int alertCount = 0;
        boolean sawOverspeed = false;
        boolean sawMotoProhibited = false;
        boolean sawNaSamGate = false;
        boolean cauChuiClearedAfterPass = false;

        List<SpatialRadarEngine.AudioTrigger> triggers = new ArrayList<>();
        for (int i = 0; i < WAYPOINTS.length; i++) {
            double lat = WAYPOINTS[i][0];
            double lon = WAYPOINTS[i][1];
            float speedKmh = (float) WAYPOINTS[i][2];
            float hdg = (float) WAYPOINTS[i][3];
            triggers.clear();
            SpatialRadarEngine.RadarSnapshot snap = engine.evaluate(
                    lat, lon, speedKmh / 3.6f, hdg, true, 5.0f, now + i * 5000L, triggers
            );
            String pStr = snap.primary != null
                    ? (snap.primary.distanceMeters + "m cat=" + snap.primary.category + " [" + snap.primary.label + "]")
                    : "CLEAR";
            System.out.printf("WP#%02d spd=%d lim=%d over=%s -> %s (triggers=%d)%n",
                    i, snap.speedKmh, snap.speedLimitKmh, snap.overspeed, pStr, triggers.size());

            if (snap.primary != null) {
                alertCount++;
                if ((snap.primary.flags & SpatialRadarEngine.FLAG_MOTO_PROHIBITED) != 0) sawMotoProhibited = true;
                if (snap.primary.label.contains("Na S")) sawNaSamGate = true;
            }
            if (snap.overspeed) sawOverspeed = true;
            if (i == 7 && (snap.primary == null || !snap.primary.label.contains("Cầu Chui"))) {
                cauChuiClearedAfterPass = true;
            }
        }

        // Test 2: Opposite direction heading test at Cau Chui -> must NOT alert for southbound approaching from north? Wait: at Cau Chui heading = 234 away from northbound? Let's test passing away!
        engine.resetDynamicState();
        triggers.clear();
        SpatialRadarEngine.RadarSnapshot wrongDir = engine.evaluate(
                21.0493, 105.8825, 12f, 234f, true, 5f, now + 90_000L, triggers
        );
        String wrongLabel = wrongDir.primary != null ? wrongDir.primary.label : "NONE";
        System.out.println("Reverse-heading at (21.0493, 105.8825) hdg=234 -> primary=" + wrongLabel);
        if (wrongDir.primary != null && wrongDir.primary.label.contains("Cầu Chui")) {
            throw new AssertionError("Should NOT alert on Cầu Chui when driving away from it!");
        }

        System.out.printf("ASSERT SUMMARY: alerts=%d overspeed=%s motoProhibit=%s naSam=%s clearedAfterPass=%s%n",
                alertCount, sawOverspeed, sawMotoProhibited, sawNaSamGate, cauChuiClearedAfterPass);
        if (alertCount < 5 || !sawOverspeed || !sawMotoProhibited || !sawNaSamGate || !cauChuiClearedAfterPass) {
            throw new AssertionError("Simulation verification failed one or more assertions!");
        }
        System.out.println("ALL RADAR REPLAY ASSERTIONS PASSED!");
    }
}
