// c:\Users\Acer\Downloads\gsm\roadpulse_lite\app\src\main\java\vn\roadpulse\lite\SpatialRadarEngine.java
// Java 8 / Android SDK 26..34 / Zero-copy mmap spatial radar with CRC32 self-heal & NaN/bounds guards
package vn.roadpulse.lite;

import android.content.Context;
import android.content.res.AssetFileDescriptor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Spatial radar engine querying flat .rpoi v1/v2 binary grid via MappedByteBuffer + custom user pins.
 */
public final class SpatialRadarEngine {
    public static final int CAT_CAM_PENALTY = 1;
    public static final int CAT_CAM_RED_LIGHT = 2;
    public static final int CAT_CAM_SPEED = 3;
    public static final int CAT_TRAFFIC_LIGHT = 4;
    public static final int CAT_POLICE = 5;
    public static final int CAT_URBAN_SIGN = 6;
    public static final int CAT_WARNING_SIGN = 7;
    public static final int CAT_ROAD_HAZARD = 8;

    public static final int FLAG_MOTO = 1 << 0;
    public static final int FLAG_CAR = 1 << 1;
    public static final int FLAG_REDLIGHT = 1 << 2;
    public static final int FLAG_MOTO_PROHIBITED = 1 << 3;
    public static final int FLAG_TWO_WAY = 1 << 4;

    public static final int HEADING_OMNI = 65535;

    private static final int V1_HEADER_SIZE = 36;
    private static final int V2_HEADER_SIZE = 40;
    private static final int CELL_ENTRY_SIZE = 12;
    private static final int RECORD_SIZE = 24;
    private static final double METERS_PER_DEG_LAT = 111194.9266;

    public static final class RadarAlert {
        public final int poiId;
        public final int category;
        public final int speedLimitKmh;
        public final int flags;
        public final int distanceMeters;
        public final int lookaheadMeters;
        public final float relativeAngleDeg;
        public final String label;
        public final boolean isUserPin;

        public RadarAlert(int poiId, int category, int speedLimitKmh, int flags,
                          int distanceMeters, int lookaheadMeters, float relativeAngleDeg,
                          String label, boolean isUserPin) {
            this.poiId = poiId;
            this.category = category;
            this.speedLimitKmh = speedLimitKmh;
            this.flags = flags;
            this.distanceMeters = distanceMeters;
            this.lookaheadMeters = lookaheadMeters;
            this.relativeAngleDeg = relativeAngleDeg;
            this.label = label;
            this.isUserPin = isUserPin;
        }
    }

    public static final class RadarSnapshot {
        public final double lat;
        public final double lon;
        public final int speedKmh;
        public final int speedLimitKmh;
        public final int headingDeg;
        public final float accuracyMeters;
        public final boolean overspeed;
        public final boolean gpsFixed;
        public final boolean motoMode;
        public final RadarAlert primary;
        public final RadarAlert secondary;
        public final long timestampMs;

        public RadarSnapshot(double lat, double lon, int speedKmh, int speedLimitKmh, int headingDeg,
                             float accuracyMeters, boolean overspeed, boolean gpsFixed, boolean motoMode,
                             RadarAlert primary, RadarAlert secondary, long timestampMs) {
            this.lat = lat;
            this.lon = lon;
            this.speedKmh = speedKmh;
            this.speedLimitKmh = speedLimitKmh;
            this.headingDeg = headingDeg;
            this.accuracyMeters = accuracyMeters;
            this.overspeed = overspeed;
            this.gpsFixed = gpsFixed;
            this.motoMode = motoMode;
            this.primary = primary;
            this.secondary = secondary;
            this.timestampMs = timestampMs;
        }
    }

    public static final class AudioTrigger {
        public final RadarAlert alert;
        public final boolean urgent;

        public AudioTrigger(RadarAlert alert, boolean urgent) {
            this.alert = alert;
            this.urgent = urgent;
        }
    }

    private static final class TrackState {
        int minDistMeters;
        boolean farAnnounced;
        boolean nearAnnounced;
        long cooldownUntilMs;
        long lastSeenMs;
    }

    private ByteBuffer buf;
    private int formatVersion;
    private int headerSize;
    private int cellSizeE6;
    private int minLatE6;
    private int minLonE6;
    private int cellCount;
    private int recordCount;
    private int recordsOffset;
    private int stringPoolOffset;
    private long buildTimestampSec;
    private long expectedCrc32;
    private boolean crcVerified;

    private final Map<Integer, TrackState> tracks = new HashMap<>();
    private List<UserPinStore.UserPin> userPins = Collections.emptyList();

    private float smoothHeadingDeg = 0f;
    private boolean hasValidHeading = false;
    private int currentZoneLimitKmh = 50;
    private double zoneLatchLat = 0.0;
    private double zoneLatchLon = 0.0;
    private long zoneLatchTimeMs = 0L;
    private boolean motoMode = true;

    public synchronized boolean loadFromAndroidAssets(Context context, String assetName) {
        // Primary path: direct zero-copy mmap from ZIP_STORED APK entry
        try {
            AssetFileDescriptor afd = context.getAssets().openFd(assetName);
            try (FileInputStream fis = afd.createInputStream()) {
                FileChannel ch = fis.getChannel();
                ByteBuffer mapped = ch.map(FileChannel.MapMode.READ_ONLY, afd.getStartOffset(), afd.getLength());
                if (initFromBuffer(mapped, true)) {
                    return true;
                }
            } finally {
                try { afd.close(); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {
            // Split APK or compressed stream: fallback to extracted cache file with version/CRC self-heal
        }

        long assetBuildTs = -1L;
        try (InputStream is = context.getAssets().open(assetName)) {
            byte[] hdr = new byte[V2_HEADER_SIZE];
            int n = is.read(hdr);
            if (n >= V1_HEADER_SIZE && hdr[0] == 'R' && hdr[1] == 'P' && hdr[2] == 'O' && hdr[3] == 'I') {
                assetBuildTs = ByteBuffer.wrap(hdr, 0, n).order(ByteOrder.LITTLE_ENDIAN).getInt(32) & 0xFFFFFFFFL;
            }
        } catch (Exception ignored) {
        }

        File out = new File(context.getFilesDir(), assetName);
        boolean needExtract = !out.exists() || out.length() < V1_HEADER_SIZE;
        if (!needExtract) {
            if (!loadFromFile(out, true) || (assetBuildTs > 0 && this.buildTimestampSec != assetBuildTs)) {
                needExtract = true;
            }
        }
        if (needExtract) {
            File tmp = new File(context.getFilesDir(), assetName + ".tmp");
            try (InputStream is = context.getAssets().open(assetName);
                 FileOutputStream fos = new FileOutputStream(tmp, false)) {
                byte[] chunk = new byte[16384];
                int n;
                while ((n = is.read(chunk)) > 0) {
                    fos.write(chunk, 0, n);
                }
                fos.getFD().sync();
            } catch (Exception e) {
                return false;
            }
            if (!tmp.renameTo(out)) {
                //noinspection ResultOfMethodCallIgnored
                out.delete();
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(out);
            }
            return loadFromFile(out, true);
        }
        return this.buf != null;
    }

    public synchronized boolean loadFromFile(File file) {
        return loadFromFile(file, true);
    }

    public synchronized boolean loadFromFile(File file, boolean checkCrc) {
        if (file == null || !file.exists() || file.length() < V1_HEADER_SIZE) {
            resetBufferFields();
            return false;
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            FileChannel ch = fis.getChannel();
            ByteBuffer mapped = ch.map(FileChannel.MapMode.READ_ONLY, 0, file.length());
            return initFromBuffer(mapped, checkCrc);
        } catch (Exception ignored) {
            resetBufferFields();
            return false;
        }
    }

    public synchronized boolean initFromBuffer(ByteBuffer bb) {
        return initFromBuffer(bb, true);
    }

    public synchronized boolean initFromBuffer(ByteBuffer bb, boolean verifyCrc) {
        resetBufferFields();
        if (bb == null || bb.limit() < V1_HEADER_SIZE) {
            return false;
        }
        bb.order(ByteOrder.LITTLE_ENDIAN);
        if (bb.get(0) != 'R' || bb.get(1) != 'P' || bb.get(2) != 'O' || bb.get(3) != 'I') {
            return false;
        }
        int ver = bb.getShort(4) & 0xFFFF;
        if (ver != 1 && ver != 2) {
            return false;
        }
        int hdrSz = (ver == 2) ? V2_HEADER_SIZE : V1_HEADER_SIZE;
        int limit = bb.limit();
        if (limit < hdrSz) {
            return false;
        }

        int cellSizeE4 = bb.getShort(6) & 0xFFFF;
        if (cellSizeE4 == 0 || cellSizeE4 > 50000) {
            return false;
        }
        int mLat = bb.getInt(8);
        int mLon = bb.getInt(12);
        long cCount = bb.getInt(16) & 0xFFFFFFFFL;
        long rCount = bb.getInt(20) & 0xFFFFFFFFL;
        long recOff = bb.getInt(24) & 0xFFFFFFFFL;
        long strOff = bb.getInt(28) & 0xFFFFFFFFL;
        long bTs = bb.getInt(32) & 0xFFFFFFFFL;
        long pCrc = (ver == 2) ? (bb.getInt(36) & 0xFFFFFFFFL) : 0L;

        // Structural bounds invariants (prevents integer overflow or out-of-bounds buffer reads)
        long expectedRecOff = hdrSz + cCount * CELL_ENTRY_SIZE;
        long expectedStrOff = expectedRecOff + rCount * RECORD_SIZE;
        if (cCount > 500_000L || rCount > 2_000_000L
                || recOff != expectedRecOff
                || strOff != expectedStrOff
                || strOff > limit) {
            return false;
        }

        if (ver == 2 && verifyCrc) {
            long actualCrc = computeCrc32(bb, hdrSz, limit);
            if (actualCrc != pCrc) {
                return false;
            }
            this.crcVerified = true;
        } else {
            this.crcVerified = (ver == 1);
        }

        this.buf = bb;
        this.formatVersion = ver;
        this.headerSize = hdrSz;
        this.cellSizeE6 = cellSizeE4 * 100;
        this.minLatE6 = mLat;
        this.minLonE6 = mLon;
        this.cellCount = (int) cCount;
        this.recordCount = (int) rCount;
        this.recordsOffset = (int) recOff;
        this.stringPoolOffset = (int) strOff;
        this.buildTimestampSec = bTs;
        this.expectedCrc32 = pCrc;
        return true;
    }

    private void resetBufferFields() {
        this.buf = null;
        this.formatVersion = 0;
        this.headerSize = 0;
        this.cellSizeE6 = 50_000;
        this.cellCount = 0;
        this.recordCount = 0;
        this.recordsOffset = 0;
        this.stringPoolOffset = 0;
        this.buildTimestampSec = 0L;
        this.expectedCrc32 = 0L;
        this.crcVerified = false;
    }

    private static long computeCrc32(ByteBuffer bb, int start, int end) {
        CRC32 crc = new CRC32();
        byte[] chunk = new byte[8192];
        int pos = start;
        while (pos < end) {
            int n = Math.min(chunk.length, end - pos);
            for (int i = 0; i < n; i++) {
                chunk[i] = bb.get(pos + i);
            }
            crc.update(chunk, 0, n);
            pos += n;
        }
        return crc.getValue();
    }

    public synchronized boolean verifyDeepIntegrity() {
        if (buf == null || cellCount <= 0 || recordCount <= 0) {
            return false;
        }
        int lim = buf.limit();
        if (formatVersion == 2) {
            long actual = computeCrc32(buf, headerSize, lim);
            if (actual != expectedCrc32) return false;
        }
        long prevCellKey = -1L;
        for (int i = 0; i < cellCount; i++) {
            int off = headerSize + i * CELL_ENTRY_SIZE;
            if (off + CELL_ENTRY_SIZE > lim) return false;
            long key = buf.getInt(off) & 0xFFFFFFFFL;
            long st = buf.getInt(off + 4) & 0xFFFFFFFFL;
            long cnt = buf.getInt(off + 8) & 0xFFFFFFFFL;
            if (key <= prevCellKey || st + cnt > recordCount) {
                return false;
            }
            prevCellKey = key;
        }
        return true;
    }

    public synchronized int getOfflinePoiCount() {
        return recordCount;
    }

    public synchronized boolean isCrcVerified() {
        return crcVerified;
    }

    public synchronized void setMotoMode(boolean motoMode) {
        this.motoMode = motoMode;
    }

    public synchronized boolean isMotoMode() {
        return motoMode;
    }

    public synchronized void updateUserPins(List<UserPinStore.UserPin> pins) {
        this.userPins = pins != null ? pins : Collections.emptyList();
    }

    public synchronized void resetDynamicState() {
        tracks.clear();
        hasValidHeading = false;
        currentZoneLimitKmh = motoMode ? 50 : 60;
    }

    /**
     * Produces a safe decayed snapshot when hardware GPS signal is lost (e.g. inside tunnels/underpasses).
     */
    public synchronized RadarSnapshot buildSignalLostSnapshot(double lastLat, double lastLon, long nowMs) {
        double safeLat = Double.isFinite(lastLat) ? Math.max(-85.0, Math.min(85.0, lastLat)) : 21.0285;
        double safeLon = Double.isFinite(lastLon) ? Math.max(-180.0, Math.min(180.0, lastLon)) : 105.8542;
        int headingInt = Math.round(normalizeDeg(smoothHeadingDeg)) % 360;
        return new RadarSnapshot(
                safeLat, safeLon, 0, currentZoneLimitKmh, headingInt, 999f,
                false, false, motoMode, null, null, nowMs
        );
    }

    /**
     * Evaluates a GNSS fix with full NaN/Infinity/bounds protection.
     */
    public synchronized RadarSnapshot evaluate(
            double lat, double lon, float rawSpeedMs, float rawBearingDeg,
            boolean hasBearing, float accuracyMeters, long nowMs,
            List<AudioTrigger> outAudioTriggers) {

        if (!Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat) > 85.0 || Math.abs(lon) > 180.0) {
            return buildSignalLostSnapshot(21.0285, 105.8542, nowMs);
        }
        float safeSpeedMs = (Float.isFinite(rawSpeedMs) && rawSpeedMs >= 0f) ? Math.min(rawSpeedMs, 85f) : 0f;
        float safeAcc = (Float.isFinite(accuracyMeters) && accuracyMeters >= 0f) ? accuracyMeters : 99f;
        boolean bearingOk = hasBearing && Float.isFinite(rawBearingDeg);

        int speedKmh = Math.round(safeSpeedMs * 3.6f);
        if (safeSpeedMs < 0.75f) {
            speedKmh = 0; // Suppress GPS stationary drift (< 2.7 km/h)
        }

        if (bearingOk && safeSpeedMs >= 1.1f && safeAcc <= 40f) {
            if (!hasValidHeading) {
                smoothHeadingDeg = normalizeDeg(rawBearingDeg);
                hasValidHeading = true;
            } else {
                float delta = signedAngleDiff(smoothHeadingDeg, rawBearingDeg);
                float alpha = Math.abs(delta) > 22f ? 1.0f : 0.78f;
                smoothHeadingDeg = normalizeDeg(smoothHeadingDeg + delta * alpha);
            }
        }
        int headingInt = Math.round(normalizeDeg(smoothHeadingDeg)) % 360;

        // Expire latched speed limit if user rode > 1800m or > 2.5 minutes since last speed sign
        if (zoneLatchTimeMs > 0 && (nowMs - zoneLatchTimeMs > 150_000L
                || approxDistMeters(lat, lon, zoneLatchLat, zoneLatchLon) > 1800.0)) {
            currentZoneLimitKmh = motoMode ? 50 : 60;
            zoneLatchTimeMs = 0L;
        }

        int baseLookahead = speedKmh < 40 ? 360 : (speedKmh <= 68 ? 600 : 1050);
        int queryRadius = baseLookahead + 450;

        List<RadarAlert> candidates = new ArrayList<>(8);

        double cosLat = Math.cos(Math.toRadians(lat));
        int latE6 = (int) Math.round(lat * 1_000_000.0);
        int lonE6 = (int) Math.round(lon * 1_000_000.0);

        // 1. Scan 3x3 spatial cells in mmap buffer
        if (buf != null && cellCount > 0 && cellSizeE6 > 0) {
            int centerRow = floorDiv(latE6 - minLatE6, cellSizeE6);
            int centerCol = floorDiv(lonE6 - minLonE6, cellSizeE6);
            int bufLimit = buf.limit();
            for (int dr = -1; dr <= 1; dr++) {
                int r = centerRow + dr;
                if (r < 0 || r > 65535) continue;
                for (int dc = -1; dc <= 1; dc++) {
                    int c = centerCol + dc;
                    if (c < 0 || c > 65535) continue;
                    int cellKey = (r << 16) | c;
                    int idx = binarySearchCell(cellKey);
                    if (idx < 0) continue;
                    int entryOff = headerSize + idx * CELL_ENTRY_SIZE;
                    if (entryOff + CELL_ENTRY_SIZE > bufLimit) continue;
                    int startIdx = buf.getInt(entryOff + 4);
                    int cnt = buf.getInt(entryOff + 8);
                    if (startIdx < 0 || cnt <= 0 || (long) startIdx + cnt > recordCount) continue;
                    for (int k = 0; k < cnt; k++) {
                        int recOff = recordsOffset + (startIdx + k) * RECORD_SIZE;
                        if (recOff < headerSize || recOff + RECORD_SIZE > bufLimit) break;
                        evaluateRecordFromBuffer(recOff, lat, lon, cosLat, speedKmh, queryRadius,
                                nowMs, candidates);
                    }
                }
            }
        }

        // 2. Scan custom driver-pinned checkpoints
        for (int i = 0; i < userPins.size(); i++) {
            UserPinStore.UserPin p = userPins.get(i);
            if (p == null) continue;
            evaluateCandidate(p.id, p.latE6 / 1_000_000.0, p.lonE6 / 1_000_000.0,
                    p.headingDeg, 42, p.category, p.speedLimitKmh,
                    FLAG_MOTO | FLAG_CAR | FLAG_TWO_WAY, p.label, 0, 0, true,
                    lat, lon, cosLat, speedKmh, queryRadius, nowMs, candidates);
        }

        // Rank candidates by composite threat priority + distance
        Collections.sort(candidates, new Comparator<RadarAlert>() {
            @Override
            public int compare(RadarAlert a, RadarAlert b) {
                int pa = priorityRank(a);
                int pb = priorityRank(b);
                if (pa != pb) return Integer.compare(pa, pb);
                return Integer.compare(a.distanceMeters, b.distanceMeters);
            }
        });

        RadarAlert primary = candidates.size() >= 1 ? candidates.get(0) : null;
        RadarAlert secondary = null;
        for (int i = 1; i < candidates.size(); i++) {
            RadarAlert cand = candidates.get(i);
            if (cand.poiId != primary.poiId) {
                secondary = cand;
                break;
            }
        }

        // Update active zone speed limit from speed zones and fixed speed/AI cameras
        for (int i = 0; i < candidates.size(); i++) {
            RadarAlert a = candidates.get(i);
            boolean isSpeedZonePoi = a.category == CAT_URBAN_SIGN
                    || a.category == CAT_CAM_SPEED
                    || a.category == CAT_CAM_PENALTY
                    || a.category == CAT_CAM_RED_LIGHT;
            if (isSpeedZonePoi && a.speedLimitKmh >= 30 && a.distanceMeters <= 260) {
                currentZoneLimitKmh = a.speedLimitKmh;
                zoneLatchLat = lat;
                zoneLatchLon = lon;
                zoneLatchTimeMs = nowMs;
                break;
            }
        }

        // Handle lifecycle audio announcements
        if (outAudioTriggers != null && primary != null) {
            checkAudioTrigger(primary, speedKmh, nowMs, outAudioTriggers);
        }

        // Cleanup stale track entries occasionally
        if (tracks.size() > 64) {
            Iterator<Map.Entry<Integer, TrackState>> it = tracks.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Integer, TrackState> e = it.next();
                if (nowMs - e.getValue().lastSeenMs > 240_000L && nowMs > e.getValue().cooldownUntilMs) {
                    it.remove();
                }
            }
        }

        boolean gpsFixed = safeAcc > 0f && safeAcc <= 60f;
        boolean overspeed = gpsFixed && (currentZoneLimitKmh > 0) && (speedKmh > currentZoneLimitKmh + 2);

        return new RadarSnapshot(
                lat, lon, speedKmh, currentZoneLimitKmh, headingInt, safeAcc,
                overspeed, gpsFixed, motoMode, primary, secondary, nowMs
        );
    }

    private void evaluateRecordFromBuffer(int recOff, double userLat, double userLon, double cosLat,
                                          int speedKmh, int queryRadius, long nowMs,
                                          List<RadarAlert> out) {
        int flags = buf.get(recOff + 13) & 0xFF;
        if (motoMode && (flags & FLAG_MOTO) == 0 && (flags & FLAG_MOTO_PROHIBITED) == 0) {
            return;
        }
        int pLatE6 = buf.getInt(recOff);
        int pLonE6 = buf.getInt(recOff + 4);
        int poiHeading = buf.getShort(recOff + 8) & 0xFFFF;
        int tol = buf.get(recOff + 10) & 0xFF;
        int cat = buf.get(recOff + 11) & 0xFF;
        int spdLimit = buf.get(recOff + 12) & 0xFF;
        int labelLen = buf.getShort(recOff + 14) & 0xFFFF;
        int labelOff = buf.getInt(recOff + 16);
        int poiId = buf.getInt(recOff + 20);

        evaluateCandidate(poiId, pLatE6 / 1_000_000.0, pLonE6 / 1_000_000.0,
                poiHeading, tol, cat, spdLimit, flags, null, labelOff, labelLen, false,
                userLat, userLon, cosLat, speedKmh, queryRadius, nowMs, out);
    }

    private void evaluateCandidate(int poiId, double pLat, double pLon,
                                   int poiHeading, int headingTol, int cat, int spdLimit, int flags,
                                   String directLabel, int labelOff, int labelLen, boolean isUserPin,
                                   double userLat, double userLon, double cosLat,
                                   int speedKmh, int queryRadius, long nowMs,
                                   List<RadarAlert> out) {
        double dy = (pLat - userLat) * METERS_PER_DEG_LAT;
        double dx = (pLon - userLon) * METERS_PER_DEG_LAT * cosLat;
        double distSq = dx * dx + dy * dy;
        int lookahead = effectiveLookahead(cat, flags, speedKmh);
        int maxScan = Math.max(queryRadius, lookahead);
        if (distSq > (double) maxScan * maxScan) {
            return;
        }
        int distM = (int) Math.round(Math.sqrt(distSq));
        if (distM > lookahead) {
            return;
        }

        TrackState ts = tracks.get(poiId);
        if (ts != null) {
            ts.lastSeenMs = nowMs;
            if (nowMs < ts.cooldownUntilMs) {
                return;
            }
            if (ts.minDistMeters < 110 && distM > ts.minDistMeters + 22) {
                ts.cooldownUntilMs = nowMs + 120_000L;
                return;
            }
            if (distM < ts.minDistMeters) {
                ts.minDistMeters = distM;
            }
        }

        float azDeg = (float) Math.toDegrees(Math.atan2(dx, dy));
        if (azDeg < 0f) azDeg += 360f;

        float absAngle = 0f;
        if (hasValidHeading) {
            absAngle = Math.abs(signedAngleDiff(smoothHeadingDeg, azDeg));
            float maxCone = distM < 50 ? 68f : 42f;
            if (absAngle > maxCone) {
                if (ts != null && ts.minDistMeters < 80 && absAngle > 105f) {
                    ts.cooldownUntilMs = nowMs + 120_000L;
                }
                return;
            }
            if (distM <= 200) {
                double crossTrack = distM * Math.sin(Math.toRadians(absAngle));
                if (crossTrack > 40.0) {
                    return;
                }
            }
            if (poiHeading != HEADING_OMNI && (flags & FLAG_TWO_WAY) == 0) {
                float dirDiff = Math.abs(signedAngleDiff(smoothHeadingDeg, poiHeading));
                int tol = Math.max(30, headingTol);
                if (dirDiff > tol) {
                    return;
                }
            }
        } else {
            if (distM > 180) {
                return;
            }
        }

        if (ts == null) {
            ts = new TrackState();
            ts.minDistMeters = distM;
            ts.lastSeenMs = nowMs;
            tracks.put(poiId, ts);
        }

        String label = directLabel != null ? directLabel : readPoolString(labelOff, labelLen);
        out.add(new RadarAlert(poiId, cat, spdLimit, flags, distM, lookahead, absAngle, label, isUserPin));
    }

    private void checkAudioTrigger(RadarAlert primary, int speedKmh, long nowMs, List<AudioTrigger> out) {
        TrackState ts = tracks.get(primary.poiId);
        if (ts == null) return;
        int farTrig = Math.min(primary.lookaheadMeters, speedKmh > 65 ? 800 : 480);
        int nearTrig = 135;

        if (!ts.farAnnounced && primary.distanceMeters <= farTrig) {
            ts.farAnnounced = true;
            out.add(new AudioTrigger(primary, false));
        } else if (!ts.nearAnnounced && primary.distanceMeters <= nearTrig) {
            boolean critical = primary.category <= CAT_POLICE || (primary.flags & FLAG_MOTO_PROHIBITED) != 0;
            if (critical || (primary.speedLimitKmh > 0 && speedKmh > primary.speedLimitKmh)) {
                ts.nearAnnounced = true;
                out.add(new AudioTrigger(primary, true));
            }
        }
    }

    private static int effectiveLookahead(int cat, int flags, int speedKmh) {
        int base = speedKmh < 40 ? 350 : (speedKmh <= 68 ? 550 : 950);
        if ((flags & FLAG_MOTO_PROHIBITED) != 0 || cat == CAT_POLICE || cat == CAT_CAM_SPEED) {
            return Math.max(base, 500);
        }
        if (cat == CAT_TRAFFIC_LIGHT) {
            return Math.min(base, speedKmh < 45 ? 260 : 400);
        }
        return base;
    }

    private static int priorityRank(RadarAlert a) {
        if ((a.flags & FLAG_MOTO_PROHIBITED) != 0) return 0;
        switch (a.category) {
            case CAT_CAM_SPEED: return 1;
            case CAT_CAM_PENALTY: return 1;
            case CAT_CAM_RED_LIGHT: return 1;
            case CAT_POLICE: return 2;
            case CAT_WARNING_SIGN: return 3;
            case CAT_URBAN_SIGN: return 4;
            case CAT_TRAFFIC_LIGHT: return 5;
            default: return 6;
        }
    }

    private int binarySearchCell(int targetKey) {
        long target = targetKey & 0xFFFFFFFFL;
        int lo = 0;
        int hi = cellCount - 1;
        int lim = buf.limit();
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int off = headerSize + mid * CELL_ENTRY_SIZE;
            if (off + 4 > lim) return -1;
            long midKey = buf.getInt(off) & 0xFFFFFFFFL;
            if (midKey < target) lo = mid + 1;
            else if (midKey > target) hi = mid - 1;
            else return mid;
        }
        return -1;
    }

    private String readPoolString(int offset, int len) {
        if (buf == null || len <= 0 || offset < 0) return "Cảnh báo giao thông";
        long pos = (long) stringPoolOffset + (offset & 0xFFFFFFFFL);
        int lim = buf.limit();
        if (pos < stringPoolOffset || pos + len > lim) return "Cảnh báo giao thông";
        byte[] tmp = new byte[len];
        int p = (int) pos;
        for (int i = 0; i < len; i++) {
            tmp[i] = buf.get(p + i);
        }
        return new String(tmp, StandardCharsets.UTF_8);
    }

    private static int floorDiv(int x, int y) {
        int r = x / y;
        if ((x ^ y) < 0 && (r * y != x)) {
            r--;
        }
        return r;
    }

    private static double approxDistMeters(double lat1, double lon1, double lat2, double lon2) {
        double cos = Math.cos(Math.toRadians((lat1 + lat2) * 0.5));
        double dy = (lat2 - lat1) * METERS_PER_DEG_LAT;
        double dx = (lon2 - lon1) * METERS_PER_DEG_LAT * cos;
        return Math.sqrt(dx * dx + dy * dy);
    }

    static float normalizeDeg(float deg) {
        if (!Float.isFinite(deg)) return 0f;
        float r = deg % 360f;
        if (r < 0f) r += 360f;
        if (r >= 360f) r = 0f; // Guard IEEE-754 -0.00001f + 360f == 360.0f rounding edge!
        return r;
    }

    static float signedAngleDiff(float fromDeg, float toDeg) {
        if (!Float.isFinite(fromDeg) || !Float.isFinite(toDeg)) return 0f;
        float diff = (normalizeDeg(toDeg) - normalizeDeg(fromDeg) + 540f) % 360f - 180f;
        return diff;
    }
}
