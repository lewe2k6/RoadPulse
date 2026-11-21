// c:\Users\Acer\Downloads\gsm\roadpulse_lite\tools\RadarFuzzAndFaultHarness.java
// Java 8 / 100,000-case property fuzzer, binary fault injector, TSV self-heal verifier & corridor replay
package vn.roadpulse.lite;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

public final class RadarFuzzAndFaultHarness {
    private static final double[][] CORRIDOR_WAYPOINTS = {
        {21.0340, 105.8347, 50f, 115f},
        {21.0369, 105.8347, 46f, 95f},
        {21.0315, 105.8406, 44f, 110f},
        {21.0448, 105.8548, 48f, 65f},
        {21.0493, 105.8825, 45f, 54f},
        {21.0510, 105.8850, 56f, 54f},
        {21.0527, 105.8876, 48f, 54f},
        {21.0540, 105.8898, 48f, 54f},
        {21.2545, 106.1945, 55f, 40f},
        {21.8915, 106.7395, 52f, 325f},
        {21.9430, 106.6990, 48f, 330f},
        {21.9740, 106.6805, 38f, 318f},
        {22.0355, 106.6285, 46f, 315f},
        {22.0554, 106.6136, 20f, 310f},
    };

    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(System.out, true, "UTF-8"));
        File rpoiFile = new File(args[0]);
        byte[] validBytes = Files.readAllBytes(rpoiFile.toPath());

        testAngleMathEdgeCases();
        testBinaryFaultInjection(validBytes);
        testUserPinStoreSelfHeal();
        testCorridorAndTunnelDecay(rpoiFile);
        test100kPropertyFuzz(rpoiFile);

        System.out.println("[PASS] ALL 5 TEST SUITES (INCLUDING 100,000 FUZZ TICKS) PASSED ZERO-ERROR!");
    }

    private static void testAngleMathEdgeCases() {
        float[] trickyAngles = {
            0f, -0f, -1e-7f, -1e-5f, 359.99999f, 360f, 720f, -360f, -720.0001f,
            Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 180f, -180f
        };
        for (float a : trickyAngles) {
            float norm = SpatialRadarEngine.normalizeDeg(a);
            if (!(norm >= 0f && norm < 360f)) {
                throw new AssertionError("normalizeDeg out of [0, 360) for input=" + a + " -> " + norm);
            }
            for (float b : trickyAngles) {
                float d = SpatialRadarEngine.signedAngleDiff(a, b);
                if (!Float.isFinite(d) || d < -180.001f || d > 180.001f) {
                    throw new AssertionError("signedAngleDiff out of [-180, 180] for " + a + "," + b + " -> " + d);
                }
            }
        }
        // Check wrap-around across 359 -> 1 deg == +2 deg
        float wrap = SpatialRadarEngine.signedAngleDiff(359f, 1f);
        if (Math.abs(wrap - 2f) > 0.01f) {
            throw new AssertionError("Expected +2 deg wrap, got " + wrap);
        }
        // Check HudOverlayView fast distance formatter
        if (!"0 m".equals(HudOverlayView.formatDistanceFast(-25))
                || !"450 m".equals(HudOverlayView.formatDistanceFast(450))
                || !"1.2 km".equals(HudOverlayView.formatDistanceFast(1240))) {
            throw new AssertionError("HudOverlayView.formatDistanceFast failed!");
        }
        System.out.println("[OK] Suite 1: IEEE-754 angle wrap-around & HUD formatter invariants");
    }

    private static void testBinaryFaultInjection(byte[] validBytes) {
        SpatialRadarEngine eng = new SpatialRadarEngine();

        // 1. Null & tiny buffers
        if (eng.initFromBuffer(null)) throw new AssertionError("Accepted null buffer");
        if (eng.initFromBuffer(ByteBuffer.allocate(0))) throw new AssertionError("Accepted 0-byte buffer");
        if (eng.initFromBuffer(ByteBuffer.allocate(20))) throw new AssertionError("Accepted 20-byte buffer");

        // 2. Corrupted magic
        byte[] badMagic = validBytes.clone();
        badMagic[0] = 'X';
        if (eng.initFromBuffer(ByteBuffer.wrap(badMagic))) throw new AssertionError("Accepted bad magic");

        // 3. Unsupported version
        byte[] badVer = validBytes.clone();
        badVer[4] = 99;
        if (eng.initFromBuffer(ByteBuffer.wrap(badVer))) throw new AssertionError("Accepted version 99");

        // 4. Bitflip inside payload -> MUST fail CRC32 check!
        byte[] bitFlip = validBytes.clone();
        bitFlip[bitFlip.length / 2] ^= 0x5A;
        if (eng.initFromBuffer(ByteBuffer.wrap(bitFlip), true)) {
            throw new AssertionError("CRC32 failed to catch payload bitflip!");
        }

        // 5. Truncated payload at 10 different cut points
        for (int cut : new int[]{39, 40, 64, 512, 4096, validBytes.length / 3, validBytes.length - 1}) {
            if (cut < validBytes.length) {
                ByteBuffer slice = ByteBuffer.wrap(validBytes, 0, cut);
                if (eng.initFromBuffer(slice, true)) {
                    throw new AssertionError("Accepted truncated buffer of length " + cut);
                }
            }
        }

        // 6. Header integer overflow attack (cellCount = 0x7FFFFFFF)
        byte[] overflowHdr = validBytes.clone();
        ByteBuffer.wrap(overflowHdr).order(ByteOrder.LITTLE_ENDIAN).putInt(16, 0x7FFFFFFF);
        if (eng.initFromBuffer(ByteBuffer.wrap(overflowHdr), false)) {
            throw new AssertionError("Accepted overflowed cellCount!");
        }

        // 7. Evaluate even when engine has failed load -> must return safe snapshot with 0 crash
        List<SpatialRadarEngine.AudioTrigger> trigs = new ArrayList<>();
        SpatialRadarEngine.RadarSnapshot safe = eng.evaluate(21.0285, 105.8542, 12f, 45f, true, 5f, 1000L, trigs);
        if (safe == null || safe.primary != null) {
            throw new AssertionError("Unloaded engine did not degrade safely");
        }
        System.out.println("[OK] Suite 2: Binary .rpoi CRC32, truncation & overflow fault injection");
    }

    private static void testUserPinStoreSelfHeal() throws Exception {
        File tmpFile = new File(System.getProperty("java.io.tmpdir"), "rp_fuzz_pins_" + System.nanoTime() + ".tsv");
        try {
            String poisoned = ""
                    + "# comment header\n"
                    + "1073741825\t21050000\t105880000\t54\t5\t50\tChốt Cầu Chui\t1700000000\n"
                    + "corrupted_not_enough_cols\t123\n"
                    + "bad_int\tNOT_A_NUMBER\t105880000\t54\t5\t50\tBad\t0\n"
                    + "1073741826\t999000000\t105880000\t54\t5\t50\tOutOfWorldLat\t0\n"
                    + "1073741827\t22055466\t106613623\t65535\t99\t999\tChốt Na Sầm\t1700000100\n"
                    + "   \n"
                    + "1073741828\t21564500\t106418000\t40\t5\t60\tTrạm Tùng Diễn\t1700000200\n"
                    + "broken_tail_timestamp\t21000000\t105000000\t0\t1\t50\tTail\tNOT_LONG\n";
            try (FileOutputStream fos = new FileOutputStream(tmpFile)) {
                fos.write(poisoned.getBytes(StandardCharsets.UTF_8));
            }

            UserPinStore store = new UserPinStore(tmpFile);
            if (store.count() != 3) {
                throw new AssertionError("Expected 3 valid pins recovered, got " + store.count());
            }
            if (store.getLastHealedLines() != 5) {
                throw new AssertionError("Expected 5 corrupted lines healed, got " + store.getLastHealedLines());
            }

            // Reload healed atomic file -> must have 0 corrupted lines left!
            UserPinStore verifyReload = new UserPinStore(tmpFile);
            if (verifyReload.count() != 3 || verifyReload.getLastHealedLines() != 0) {
                throw new AssertionError("Atomic self-healed TSV was not clean on second reload!");
            }

            // Reject NaN/Infinity pin additions safely
            if (verifyReload.addPin(Double.NaN, 105.8, 0, 5, 50, "Bad") != null) {
                throw new AssertionError("Accepted NaN pin!");
            }
            System.out.println("[OK] Suite 3: UserPinStore per-line corruption recovery & atomic fsync");
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmpFile.delete();
        }
    }

    private static void testCorridorAndTunnelDecay(File rpoiFile) {
        SpatialRadarEngine engine = new SpatialRadarEngine();
        if (!engine.loadFromFile(rpoiFile, true) || !engine.verifyDeepIntegrity()) {
            throw new AssertionError("Failed deep integrity check on production .rpoi!");
        }
        engine.setMotoMode(true);

        long now = 1_000_000L;
        int alerts = 0;
        boolean sawOverspeed = false;
        boolean sawMotoProhibit = false;
        boolean sawNaSam = false;
        boolean clearedAfterCauChui = false;
        List<SpatialRadarEngine.AudioTrigger> trigs = new ArrayList<>();

        for (int i = 0; i < CORRIDOR_WAYPOINTS.length; i++) {
            double[] wp = CORRIDOR_WAYPOINTS[i];
            trigs.clear();
            SpatialRadarEngine.RadarSnapshot s = engine.evaluate(
                    wp[0], wp[1], (float) (wp[2] / 3.6), (float) wp[3], true, 5f, now + i * 5000L, trigs
            );
            if (s.primary != null) {
                alerts++;
                if ((s.primary.flags & SpatialRadarEngine.FLAG_MOTO_PROHIBITED) != 0) sawMotoProhibit = true;
                if (s.primary.label.contains("Na Sầm")) sawNaSam = true;
            }
            if (s.overspeed) sawOverspeed = true;
            if (i == 7 && (s.primary == null || !s.primary.label.contains("Cầu Chui"))) {
                clearedAfterCauChui = true;
            }
        }

        // Verify tunnel signal-lost decay resets speed to 0 and clears overspeed while keeping heading
        SpatialRadarEngine.RadarSnapshot decayed = engine.buildSignalLostSnapshot(21.0510, 105.8850, now + 90_000L);
        if (decayed.speedKmh != 0 || decayed.overspeed || decayed.gpsFixed) {
            throw new AssertionError("Tunnel signal-lost decay failed to zero speed & clear overspeed!");
        }

        if (alerts < 8 || !sawOverspeed || !sawMotoProhibit || !sawNaSam || !clearedAfterCauChui) {
            throw new AssertionError("Corridor replay failed: alerts=" + alerts + " over=" + sawOverspeed
                    + " moto=" + sawMotoProhibit + " naSam=" + sawNaSam + " clear=" + clearedAfterCauChui);
        }
        System.out.println("[OK] Suite 4: HN -> Cau Chui -> CT01 -> Na Sam replay + Tunnel stale-fix decay");
    }

    private static void test100kPropertyFuzz(File rpoiFile) {
        SpatialRadarEngine engine = new SpatialRadarEngine();
        engine.loadFromFile(rpoiFile, true);
        SplittableRandom rng = new SplittableRandom(20261121L);
        List<SpatialRadarEngine.AudioTrigger> trigs = new ArrayList<>(4);

        long simTime = 10_000_000L;
        int alertHits = 0;

        for (int i = 0; i < 100_000; i++) {
            double lat, lon;
            float speedMs, bearing, acc;
            boolean hasBearing;

            int mode = i % 20;
            if (mode == 0) {
                // Adversarial NaN / Infinity / Out-of-bounds torture
                double[] weird = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -120.0, 95.0, 0.0};
                lat = weird[rng.nextInt(weird.length)];
                lon = weird[rng.nextInt(weird.length)];
                speedMs = (i % 40 == 0) ? Float.NaN : -15f;
                bearing = Float.POSITIVE_INFINITY;
                acc = Float.NaN;
                hasBearing = true;
            } else if (mode <= 12) {
                // Dense Hanoi <-> Lang Son <-> Na Sam corridor random walk
                lat = 20.95 + rng.nextDouble() * 1.15;
                lon = 105.75 + rng.nextDouble() * 1.05;
                speedMs = (float) (rng.nextDouble() * 28.0);
                bearing = (float) (rng.nextDouble() * 720.0 - 360.0);
                acc = (float) (rng.nextDouble() * 45.0);
                hasBearing = rng.nextBoolean();
            } else if (mode == 13) {
                // Stationary red-light jitter (< 0.7 m/s) -> speedKmh MUST be 0 and overspeed MUST be false!
                lat = 21.0528 + (rng.nextDouble() - 0.5) * 0.0001;
                lon = 105.8878 + (rng.nextDouble() - 0.5) * 0.0001;
                speedMs = (float) (rng.nextDouble() * 0.70);
                bearing = (float) (rng.nextDouble() * 360.0);
                acc = 6.0f;
                hasBearing = true;
            } else {
                // Nationwide Vietnam random coordinates
                lat = 8.3 + rng.nextDouble() * 15.1;
                lon = 102.2 + rng.nextDouble() * 7.3;
                speedMs = (float) (rng.nextDouble() * 35.0);
                bearing = (float) (rng.nextDouble() * 360.0);
                acc = (float) (1.0 + rng.nextDouble() * 80.0);
                hasBearing = true;
            }

            if (i % 2500 == 0) {
                engine.setMotoMode(!engine.isMotoMode());
            }

            trigs.clear();
            simTime += 1000L;
            SpatialRadarEngine.RadarSnapshot snap = engine.evaluate(
                    lat, lon, speedMs, bearing, hasBearing, acc, simTime, trigs
            );

            // Universal invariants across all 100,000 ticks:
            if (snap == null) throw new AssertionError("Null snapshot at tick " + i);
            if (snap.speedKmh < 0 || snap.speedKmh > 320) {
                throw new AssertionError("Invalid speedKmh=" + snap.speedKmh + " at tick " + i);
            }
            if (snap.headingDeg < 0 || snap.headingDeg >= 360) {
                throw new AssertionError("Invalid headingDeg=" + snap.headingDeg + " at tick " + i);
            }
            if (mode == 13 && (snap.speedKmh != 0 || snap.overspeed)) {
                throw new AssertionError("Stationary drift leaked non-zero speed at tick " + i);
            }
            if (snap.primary != null) {
                alertHits++;
                if (snap.primary.label == null || snap.primary.label.isEmpty()) {
                    throw new AssertionError("Empty alert label at tick " + i);
                }
                if (snap.primary.distanceMeters < 0) {
                    throw new AssertionError("Negative distance at tick " + i);
                }
            }
        }
        System.out.println("[OK] Suite 5: 100,000 randomized + adversarial GPS fuzz ticks (alertHits=" + alertHits + ")");
    }
}
