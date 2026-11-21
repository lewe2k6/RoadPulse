// c:\Users\Acer\Downloads\gsm\roadpulse_lite\app\src\main\java\vn\roadpulse\lite\UserPinStore.java
// Java 8 / Android SDK 26..34 / Self-healing TSV checkpoint persistence with per-line recovery & atomic fsync
package vn.roadpulse.lite;

import android.content.Context;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Fault-tolerant local TSV storage for driver-pinned police checkpoints and cameras.
 */
public final class UserPinStore {
    public static final class UserPin {
        public final int id;
        public final int latE6;
        public final int lonE6;
        public final int headingDeg;
        public final int category;
        public final int speedLimitKmh;
        public final String label;
        public final long createdSec;

        public UserPin(int id, int latE6, int lonE6, int headingDeg, int category, int speedLimitKmh, String label, long createdSec) {
            this.id = id;
            this.latE6 = latE6;
            this.lonE6 = lonE6;
            this.headingDeg = headingDeg;
            this.category = category;
            this.speedLimitKmh = speedLimitKmh;
            this.label = label;
            this.createdSec = createdSec;
        }
    }

    private static final String FILE_NAME = "user_pins.tsv";
    private static final int USER_ID_BASE = 0x40000000;

    private final File file;
    private final List<UserPin> pins = new ArrayList<>();
    private int nextSeq = 1;
    private int lastHealedLines = 0;

    public UserPinStore(Context context) {
        this(new File(context.getFilesDir(), FILE_NAME));
    }

    public UserPinStore(File customFile) {
        this.file = customFile;
        reload();
    }

    public synchronized int reload() {
        pins.clear();
        int healed = 0;
        if (!file.exists()) {
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            if (tmp.exists() && tmp.length() > 0) {
                // Recover from interrupted atomic rename
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(file);
            } else {
                lastHealedLines = 0;
                return 0;
            }
        }

        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                try {
                    String[] parts = trimmed.split("\t", -1);
                    if (parts.length < 7) {
                        healed++;
                        continue;
                    }
                    int id = Integer.parseInt(parts[0].trim());
                    int latE6 = Integer.parseInt(parts[1].trim());
                    int lonE6 = Integer.parseInt(parts[2].trim());
                    if (latE6 < -90_000_000 || latE6 > 90_000_000 || lonE6 < -180_000_000 || lonE6 > 180_000_000) {
                        healed++;
                        continue;
                    }
                    int rawHdg = Integer.parseInt(parts[3].trim());
                    int hdg = (rawHdg >= 0 && rawHdg < 360) ? rawHdg : SpatialRadarEngine.HEADING_OMNI;
                    boolean rowRepaired = false;
                    int cat = Integer.parseInt(parts[4].trim());
                    if (cat < 1 || cat > 8) {
                        cat = SpatialRadarEngine.CAT_POLICE;
                        rowRepaired = true;
                    }
                    int rawSpd = Integer.parseInt(parts[5].trim());
                    int spd = Math.max(0, Math.min(150, rawSpd));
                    if (spd != rawSpd) {
                        rowRepaired = true;
                    }
                    if (rowRepaired) {
                        healed++;
                    }
                    String label = sanitizeLabel(parts[6]);
                    long ts = parts.length >= 8 ? Long.parseLong(parts[7].trim()) : 0L;

                    pins.add(new UserPin(id, latE6, lonE6, hdg, cat, spd, label, ts));
                    if (id >= USER_ID_BASE) {
                        nextSeq = Math.max(nextSeq, (id - USER_ID_BASE) + 1);
                    }
                } catch (Exception lineErr) {
                    // Isolated per-line recovery: skip broken row and keep valid pins intact
                    healed++;
                }
            }
        } catch (Exception ignored) {
            healed++;
        }

        lastHealedLines = healed;
        if (healed > 0) {
            saveAllAtomic();
        }
        return healed;
    }

    public synchronized int getLastHealedLines() {
        return lastHealedLines;
    }

    public synchronized UserPin addPin(double lat, double lon, int headingDeg, int category, int speedLimitKmh, String label) {
        if (!Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat) > 90.0 || Math.abs(lon) > 180.0) {
            return null;
        }
        int id = USER_ID_BASE + (nextSeq++);
        int latE6 = (int) Math.round(lat * 1_000_000.0);
        int lonE6 = (int) Math.round(lon * 1_000_000.0);
        int hdg = (headingDeg >= 0 && headingDeg < 360) ? headingDeg : SpatialRadarEngine.HEADING_OMNI;
        int cat = (category >= 1 && category <= 8) ? category : SpatialRadarEngine.CAT_POLICE;
        int spd = Math.max(0, Math.min(150, speedLimitKmh));
        String cleanLabel = sanitizeLabel(label);
        UserPin pin = new UserPin(id, latE6, lonE6, hdg, cat, spd, cleanLabel, System.currentTimeMillis() / 1000L);
        pins.add(pin);
        saveAllAtomic();
        return pin;
    }

    public synchronized boolean removePinById(int id) {
        for (int i = 0; i < pins.size(); i++) {
            if (pins.get(i).id == id) {
                pins.remove(i);
                saveAllAtomic();
                return true;
            }
        }
        return false;
    }

    public synchronized void clearAll() {
        pins.clear();
        saveAllAtomic();
    }

    public synchronized List<UserPin> snapshot() {
        return new ArrayList<>(pins);
    }

    public synchronized int count() {
        return pins.size();
    }

    private static String sanitizeLabel(String raw) {
        if (raw == null) return "Chốt CSGT tự ghim";
        String clean = raw.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').trim();
        if (clean.isEmpty()) return "Chốt CSGT tự ghim";
        if (clean.length() > 80) clean = clean.substring(0, 80).trim();
        return clean;
    }

    private void saveAllAtomic() {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        File tmp = new File(parent, file.getName() + ".tmp");
        try {
            try (FileOutputStream fos = new FileOutputStream(tmp, false);
                 BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(fos, StandardCharsets.UTF_8))) {
                for (UserPin p : pins) {
                    bw.write(p.id + "\t" + p.latE6 + "\t" + p.lonE6 + "\t" + p.headingDeg + "\t"
                            + p.category + "\t" + p.speedLimitKmh + "\t" + p.label + "\t" + p.createdSec);
                    bw.newLine();
                }
                bw.flush();
                fos.getFD().sync();
            }
            if (!tmp.renameTo(file)) {
                // Windows / cross-lock fallback: delete destination then rename
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(file);
            }
        } catch (Exception ignored) {
        }
    }
}
