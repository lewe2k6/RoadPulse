// c:\Users\Acer\Downloads\gsm\roadpulse_lite\app\src\main\java\vn\roadpulse\lite\HudOverlayView.java
// Java 8 / Android SDK 26..34 / Zero-allocation OLED Canvas HUD with extreme-bounds safety
package vn.roadpulse.lite;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

/**
 * Dual-purpose Canvas HUD View rendered inside either Floating WindowManager Overlay or Native PiP Activity.
 */
public final class HudOverlayView extends View {
    public interface InteractionListener {
        void onDragDelta(int dxPx, int dyPx);
        void onDragFinished();
        void onToggleCompact();
        void onCycleAudio();
        void onQuickPin();
        void onCloseOverlay();
    }

    private final float density;
    private boolean pipFullBleed;
    private boolean compactMode = false;
    private int audioMode = VoiceAlertManager.MODE_VOICE_AND_BEEP;
    private SpatialRadarEngine.RadarSnapshot snapshot;
    private String tempToastText = null;
    private long tempToastUntilMs = 0L;
    private InteractionListener listener;

    // Pre-allocated Paints & Paths (strictly zero allocations inside onDraw)
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint speedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint signWhitePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint signRedRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint signTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint distPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint btnBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint btnTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF outerRect = new RectF();
    private final RectF iconRect = new RectF();
    private final RectF progBgRect = new RectF();
    private final RectF progFillRect = new RectF();
    private final RectF audioBtnRect = new RectF();
    private final RectF pinBtnRect = new RectF();
    private final RectF closeBtnRect = new RectF();
    private final Path triPath = new Path();

    private float downRawX, downRawY;
    private float lastRawX, lastRawY;
    private float downLocalX, downLocalY;
    private boolean dragging = false;
    private long lastTapMs = 0L;

    public HudOverlayView(Context context, boolean pipFullBleed) {
        super(context);
        float d = context.getResources().getDisplayMetrics().density;
        this.density = (d > 0.2f && Float.isFinite(d)) ? d : 2.75f;
        this.pipFullBleed = pipFullBleed;
        initPaints();
    }

    private float dp(float v) {
        return v * density;
    }

    private void initPaints() {
        bgPaint.setStyle(Paint.Style.FILL);
        bgPaint.setColor(0xEB0B1017);

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(1.6f));
        borderPaint.setColor(0xFF283546);

        speedPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        speedPaint.setTextAlign(Paint.Align.CENTER);
        speedPaint.setColor(0xFF22C55E);

        unitPaint.setTypeface(Typeface.DEFAULT);
        unitPaint.setTextAlign(Paint.Align.CENTER);
        unitPaint.setColor(0xFF8B9BB4);

        signWhitePaint.setStyle(Paint.Style.FILL);
        signWhitePaint.setColor(0xFFFFFFFF);

        signRedRingPaint.setStyle(Paint.Style.STROKE);
        signRedRingPaint.setColor(0xFFDC2626);

        signTextPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        signTextPaint.setTextAlign(Paint.Align.CENTER);
        signTextPaint.setColor(0xFF111827);

        titlePaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        titlePaint.setColor(0xFFF8FAFC);

        subPaint.setTypeface(Typeface.DEFAULT);
        subPaint.setColor(0xFF94A3B8);

        distPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        distPaint.setColor(0xFFFBBF24);

        trackBgPaint.setStyle(Paint.Style.FILL);
        trackBgPaint.setColor(0xFF1E293B);

        trackFillPaint.setStyle(Paint.Style.FILL);
        trackFillPaint.setColor(0xFFF59E0B);

        iconFillPaint.setStyle(Paint.Style.FILL);
        iconStrokePaint.setStyle(Paint.Style.STROKE);
        iconStrokePaint.setStrokeCap(Paint.Cap.ROUND);
        iconStrokePaint.setStrokeJoin(Paint.Join.ROUND);

        btnBgPaint.setStyle(Paint.Style.FILL);
        btnBgPaint.setColor(0xFF1A2433);

        btnTextPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        btnTextPaint.setTextAlign(Paint.Align.CENTER);
        btnTextPaint.setColor(0xFFE2E8F0);
    }

    public void setInteractionListener(InteractionListener l) {
        this.listener = l;
    }

    public void setPipFullBleed(boolean pipFullBleed) {
        this.pipFullBleed = pipFullBleed;
        requestLayout();
        invalidate();
    }

    public void setCompactMode(boolean compact) {
        this.compactMode = compact;
        requestLayout();
        invalidate();
    }

    public boolean isCompactMode() {
        return compactMode;
    }

    public void setAudioMode(int mode) {
        this.audioMode = mode;
        invalidate();
    }

    public void showFlashToast(String text, long durationMs) {
        this.tempToastText = text;
        this.tempToastUntilMs = System.currentTimeMillis() + durationMs;
        invalidate();
    }

    public void updateSnapshot(SpatialRadarEngine.RadarSnapshot snap) {
        this.snapshot = snap;
        invalidate();
    }

    public int getDesiredWidthPx() {
        if (compactMode && (snapshot == null || snapshot.primary == null)) {
            return Math.round(dp(124));
        }
        return Math.round(dp(276));
    }

    public int getDesiredHeightPx() {
        if (compactMode && (snapshot == null || snapshot.primary == null)) {
            return Math.round(dp(56));
        }
        return Math.round(dp(86));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (pipFullBleed) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        } else {
            setMeasuredDimension(getDesiredWidthPx(), getDesiredHeightPx());
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        if (w < dp(36) || h < dp(22)) return;

        SpatialRadarEngine.RadarSnapshot s = this.snapshot;
        int spd = s != null ? s.speedKmh : 0;
        int lim = s != null ? s.speedLimitKmh : 50;
        boolean over = s != null && s.overspeed;
        SpatialRadarEngine.RadarAlert alert = s != null ? s.primary : null;

        long now = System.currentTimeMillis();
        boolean hasFlash = tempToastText != null && now < tempToastUntilMs;

        float pad = pipFullBleed ? dp(4) : dp(2);
        outerRect.set(pad, pad, Math.max(pad + 1f, w - pad), Math.max(pad + 1f, h - pad));
        float radius = pipFullBleed ? dp(14) : dp(18);

        if (over) {
            boolean pulse = ((now / 320L) % 2L) == 0L;
            borderPaint.setColor(pulse ? 0xFFFF3B30 : 0xFF991B1B);
            borderPaint.setStrokeWidth(dp(2.4f));
            bgPaint.setColor(0xF51A0B0E);
        } else if (alert != null && (alert.flags & SpatialRadarEngine.FLAG_MOTO_PROHIBITED) != 0) {
            borderPaint.setColor(0xFFEF4444);
            borderPaint.setStrokeWidth(dp(2.2f));
            bgPaint.setColor(0xF2150D12);
        } else if (alert != null) {
            borderPaint.setColor(categoryAccentColor(alert.category));
            borderPaint.setStrokeWidth(dp(1.8f));
            bgPaint.setColor(0xEB0B1017);
        } else {
            borderPaint.setColor(0xFF283546);
            borderPaint.setStrokeWidth(dp(1.4f));
            bgPaint.setColor(0xE00B1017);
        }
        canvas.drawRoundRect(outerRect, radius, radius, bgPaint);
        canvas.drawRoundRect(outerRect, radius, radius, borderPaint);

        if (over) {
            speedPaint.setColor(0xFFFF453A);
        } else if (lim > 0 && spd >= lim - 5 && spd > 0) {
            speedPaint.setColor(0xFFF59E0B);
        } else {
            speedPaint.setColor(0xFF22C55E);
        }

        if (!pipFullBleed && compactMode && alert == null && !hasFlash) {
            speedPaint.setTextSize(dp(24));
            canvas.drawText(Integer.toString(spd), dp(34), h * 0.57f, speedPaint);
            unitPaint.setTextSize(dp(8.5f));
            canvas.drawText("km/h", dp(34), h * 0.80f, unitPaint);
            drawSpeedLimitSign(canvas, dp(88), h * 0.50f, dp(16), lim);
            audioBtnRect.setEmpty();
            pinBtnRect.setEmpty();
            closeBtnRect.setEmpty();
            return;
        }

        float leftWidth = pipFullBleed ? Math.max(dp(64), w * 0.34f) : dp(104);
        float speedCenterX = pipFullBleed ? leftWidth * 0.42f : dp(34);
        speedPaint.setTextSize(pipFullBleed ? Math.min(h * 0.44f, dp(34)) : dp(27));
        canvas.drawText(Integer.toString(spd), speedCenterX, h * 0.56f, speedPaint);

        unitPaint.setTextSize(pipFullBleed ? dp(10) : dp(9));
        canvas.drawText((s == null || !s.gpsFixed) ? "GPS..." : "km/h", speedCenterX, h * 0.78f, unitPaint);

        float signCx = pipFullBleed ? leftWidth * 0.80f : dp(79);
        float signR = pipFullBleed ? Math.max(dp(10), Math.min(h * 0.23f, dp(20))) : dp(16.5f);
        drawSpeedLimitSign(canvas, signCx, h * 0.50f, signR, lim);

        trackBgPaint.setColor(0xFF243041);
        canvas.drawRect(leftWidth, dp(12), leftWidth + dp(1), Math.max(dp(13), h - dp(12)), trackBgPaint);

        float rx = leftWidth + dp(8);
        float rightEnd = pipFullBleed ? (w - dp(10)) : (w - dp(34));
        if (rightEnd < rx + dp(12)) {
            rightEnd = w - dp(6);
        }

        if (!pipFullBleed && w >= dp(200)) {
            float bx = w - dp(29);
            float bw = dp(22);
            float bh = Math.max(dp(12), (h - dp(16)) / 3f);
            audioBtnRect.set(bx, dp(6), bx + bw, dp(6) + bh - dp(2));
            pinBtnRect.set(bx, dp(6) + bh, bx + bw, dp(6) + bh * 2 - dp(2));
            closeBtnRect.set(bx, dp(6) + bh * 2, bx + bw, Math.max(dp(6) + bh * 2 + dp(8), h - dp(6)));

            btnTextPaint.setTextSize(dp(9.5f));
            btnBgPaint.setColor(audioMode == VoiceAlertManager.MODE_MUTE ? 0xFF331B1E : 0xFF192434);
            canvas.drawRoundRect(audioBtnRect, dp(5), dp(5), btnBgPaint);
            String aTxt = audioMode == VoiceAlertManager.MODE_MUTE ? "X" : (audioMode == VoiceAlertManager.MODE_BEEP_ONLY ? "B" : "V");
            canvas.drawText(aTxt, audioBtnRect.centerX(), audioBtnRect.centerY() + dp(3.5f), btnTextPaint);

            btnBgPaint.setColor(0xFF143124);
            canvas.drawRoundRect(pinBtnRect, dp(5), dp(5), btnBgPaint);
            btnTextPaint.setColor(0xFF4ADE80);
            canvas.drawText("+", pinBtnRect.centerX(), pinBtnRect.centerY() + dp(3.5f), btnTextPaint);

            btnBgPaint.setColor(0xFF1E2530);
            canvas.drawRoundRect(closeBtnRect, dp(5), dp(5), btnBgPaint);
            btnTextPaint.setColor(0xFF94A3B8);
            canvas.drawText("–", closeBtnRect.centerX(), closeBtnRect.centerY() + dp(3.5f), btnTextPaint);
            btnTextPaint.setColor(0xFFE2E8F0);
        } else {
            audioBtnRect.setEmpty();
            pinBtnRect.setEmpty();
            closeBtnRect.setEmpty();
        }

        if (hasFlash) {
            titlePaint.setTextSize(dp(13));
            titlePaint.setColor(0xFF4ADE80);
            canvas.drawText(ellipsize(tempToastText, titlePaint, rightEnd - rx), rx + dp(2), h * 0.48f, titlePaint);
            subPaint.setTextSize(dp(10.5f));
            canvas.drawText("Sẽ tự báo ở lượt đi sau", rx + dp(2), h * 0.72f, subPaint);
            titlePaint.setColor(0xFFF8FAFC);
            return;
        }

        if (alert != null) {
            float iconSize = pipFullBleed ? Math.min(dp(28), h * 0.36f) : dp(26);
            iconRect.set(rx, dp(11), rx + iconSize, dp(11) + iconSize);
            drawCategoryIcon(canvas, iconRect, alert.category, alert.flags);

            distPaint.setTextSize(pipFullBleed ? dp(15) : dp(13.5f));
            distPaint.setColor(categoryAccentColor(alert.category));
            String distStr = formatDistanceFast(alert.distanceMeters);
            canvas.drawText(distStr, rx + iconSize + dp(7), dp(22), distPaint);

            titlePaint.setTextSize(pipFullBleed ? dp(11.5f) : dp(11f));
            String catTag = categoryShortName(alert.category, alert.flags);
            float distTextW = distPaint.measureText(distStr);
            float tagAvail = rightEnd - (rx + iconSize + dp(12) + distTextW);
            if (tagAvail > dp(16)) {
                canvas.drawText(ellipsize(catTag, titlePaint, tagAvail),
                        rx + iconSize + dp(12) + distTextW, dp(21.5f), titlePaint);
            }

            subPaint.setTextSize(pipFullBleed ? dp(11.5f) : dp(10.5f));
            subPaint.setColor(0xFFCBD5E1);
            String label = (alert.label != null && !alert.label.isEmpty()) ? alert.label : catTag;
            canvas.drawText(ellipsize(label, subPaint, rightEnd - (rx + dp(2))), rx, Math.min(h - dp(22), dp(48)), subPaint);

            float ratio = 1f - Math.min(1f, Math.max(0f, (float) alert.distanceMeters / Math.max(1f, alert.lookaheadMeters)));
            float barTop = Math.max(dp(30), h - dp(19));
            float barBot = Math.max(barTop + dp(3), h - dp(13));
            progBgRect.set(rx, barTop, rightEnd, barBot);
            progFillRect.set(rx, barTop, rx + progBgRect.width() * Math.max(0.08f, ratio), barBot);
            trackBgPaint.setColor(0xFF1E293B);
            trackFillPaint.setColor(categoryAccentColor(alert.category));
            canvas.drawRoundRect(progBgRect, dp(3), dp(3), trackBgPaint);
            canvas.drawRoundRect(progFillRect, dp(3), dp(3), trackFillPaint);

            if (s != null && s.secondary != null && !pipFullBleed && h >= dp(76)) {
                subPaint.setTextSize(dp(8.8f));
                subPaint.setColor(0xFF64748B);
                String secStr = "Tiếp: " + categoryShortName(s.secondary.category, s.secondary.flags) + " (" + s.secondary.distanceMeters + "m)";
                canvas.drawText(ellipsize(secStr, subPaint, rightEnd - rx), rx, h - dp(4), subPaint);
            }
        } else {
            titlePaint.setTextSize(pipFullBleed ? dp(13) : dp(12));
            titlePaint.setColor(0xFF34D399);
            String modeLabel = (s != null && !s.motoMode) ? "Ô tô • Radar bật" : "Xe máy • Radar bật";
            canvas.drawText(ellipsize(modeLabel, titlePaint, rightEnd - rx), rx + dp(2), h * 0.42f, titlePaint);
            titlePaint.setColor(0xFFF8FAFC);

            subPaint.setTextSize(pipFullBleed ? dp(11) : dp(10));
            subPaint.setColor(0xFF94A3B8);
            String hdgTxt = (s != null && s.gpsFixed)
                    ? ("Hướng " + s.headingDeg + "° • Đường thông thoáng")
                    : "Đang chờ tín hiệu GNSS...";
            canvas.drawText(ellipsize(hdgTxt, subPaint, rightEnd - rx), rx + dp(2), h * 0.65f, subPaint);

            if (!pipFullBleed && h >= dp(72)) {
                subPaint.setTextSize(dp(8.5f));
                subPaint.setColor(0xFF64748B);
                canvas.drawText(ellipsize("Chạm đúp thu gọn • + Ghim chốt", subPaint, rightEnd - rx), rx + dp(2), h * 0.84f, subPaint);
            }
        }
    }

    static String formatDistanceFast(int distMeters) {
        int d = Math.max(0, distMeters);
        if (d >= 1000) {
            int km = d / 1000;
            int tenths = (d % 1000) / 100;
            return km + "." + tenths + " km";
        }
        return d + " m";
    }

    private void drawSpeedLimitSign(Canvas c, float cx, float cy, float r, int limit) {
        c.drawCircle(cx, cy, r, signWhitePaint);
        signRedRingPaint.setStrokeWidth(Math.max(dp(2.5f), r * 0.22f));
        c.drawCircle(cx, cy, r - signRedRingPaint.getStrokeWidth() * 0.5f, signRedRingPaint);
        String txt = limit > 0 ? Integer.toString(limit) : "--";
        signTextPaint.setTextSize(r * 1.05f);
        Paint.FontMetrics fm = signTextPaint.getFontMetrics();
        float textY = cy - (fm.ascent + fm.descent) * 0.5f;
        c.drawText(txt, cx, textY, signTextPaint);
    }

    private void drawCategoryIcon(Canvas c, RectF r, int cat, int flags) {
        int accent = categoryAccentColor(cat);
        if ((flags & SpatialRadarEngine.FLAG_MOTO_PROHIBITED) != 0) {
            accent = 0xFFEF4444;
        }
        iconFillPaint.setColor((accent & 0x00FFFFFF) | 0x26000000);
        c.drawRoundRect(r, dp(6), dp(6), iconFillPaint);

        float cx = r.centerX();
        float cy = r.centerY();
        float s = r.width();

        if (cat == SpatialRadarEngine.CAT_TRAFFIC_LIGHT || cat == SpatialRadarEngine.CAT_CAM_RED_LIGHT) {
            iconFillPaint.setColor(0xFF1F2937);
            c.drawRoundRect(cx - s * 0.22f, cy - s * 0.40f, cx + s * 0.22f, cy + s * 0.40f, dp(3), dp(3), iconFillPaint);
            float dotR = s * 0.085f;
            iconFillPaint.setColor(0xFFEF4444);
            c.drawCircle(cx, cy - s * 0.22f, dotR, iconFillPaint);
            iconFillPaint.setColor(0xFFF59E0B);
            c.drawCircle(cx, cy, dotR, iconFillPaint);
            iconFillPaint.setColor(0xFF22C55E);
            c.drawCircle(cx, cy + s * 0.22f, dotR, iconFillPaint);
        } else if (cat == SpatialRadarEngine.CAT_POLICE) {
            triPath.reset();
            triPath.moveTo(cx, cy - s * 0.36f);
            triPath.lineTo(cx + s * 0.30f, cy - s * 0.20f);
            triPath.lineTo(cx + s * 0.24f, cy + s * 0.16f);
            triPath.lineTo(cx, cy + s * 0.38f);
            triPath.lineTo(cx - s * 0.24f, cy + s * 0.16f);
            triPath.lineTo(cx - s * 0.30f, cy - s * 0.20f);
            triPath.close();
            iconFillPaint.setColor(0xFF2563EB);
            c.drawPath(triPath, iconFillPaint);
            iconFillPaint.setColor(0xFFFACC15);
            c.drawCircle(cx, cy - s * 0.02f, s * 0.11f, iconFillPaint);
        } else if (cat == SpatialRadarEngine.CAT_WARNING_SIGN || cat == SpatialRadarEngine.CAT_ROAD_HAZARD) {
            triPath.reset();
            triPath.moveTo(cx, cy - s * 0.35f);
            triPath.lineTo(cx + s * 0.36f, cy + s * 0.30f);
            triPath.lineTo(cx - s * 0.36f, cy + s * 0.30f);
            triPath.close();
            iconFillPaint.setColor(0xFFFACC15);
            c.drawPath(triPath, iconFillPaint);
            iconStrokePaint.setColor(0xFFDC2626);
            iconStrokePaint.setStrokeWidth(dp(2));
            c.drawPath(triPath, iconStrokePaint);
            iconFillPaint.setColor(0xFF111827);
            c.drawRect(cx - dp(1.1f), cy - s * 0.12f, cx + dp(1.1f), cy + s * 0.10f, iconFillPaint);
            c.drawCircle(cx, cy + s * 0.20f, dp(1.4f), iconFillPaint);
        } else if (cat == SpatialRadarEngine.CAT_URBAN_SIGN) {
            drawSpeedLimitSign(c, cx, cy, s * 0.38f, 50);
        } else {
            iconFillPaint.setColor(accent);
            c.drawCircle(cx, cy, s * 0.32f, iconFillPaint);
            iconFillPaint.setColor(0xFF0F172A);
            c.drawCircle(cx, cy, s * 0.18f, iconFillPaint);
            iconFillPaint.setColor(0xFFFFFFFF);
            c.drawCircle(cx + s * 0.06f, cy - s * 0.06f, s * 0.06f, iconFillPaint);
        }
    }

    private static int categoryAccentColor(int cat) {
        switch (cat) {
            case SpatialRadarEngine.CAT_CAM_PENALTY:
            case SpatialRadarEngine.CAT_CAM_RED_LIGHT:
            case SpatialRadarEngine.CAT_CAM_SPEED:
                return 0xFFEF4444;
            case SpatialRadarEngine.CAT_POLICE:
                return 0xFF38BDF8;
            case SpatialRadarEngine.CAT_TRAFFIC_LIGHT:
                return 0xFFF59E0B;
            case SpatialRadarEngine.CAT_WARNING_SIGN:
                return 0xFFFACC15;
            case SpatialRadarEngine.CAT_URBAN_SIGN:
                return 0xFFFB7185;
            default:
                return 0xFFF97316;
        }
    }

    static String categoryShortName(int cat, int flags) {
        if ((flags & SpatialRadarEngine.FLAG_MOTO_PROHIBITED) != 0) return "CẤM XE MÁY!";
        switch (cat) {
            case SpatialRadarEngine.CAT_CAM_RED_LIGHT: return "Cam Đèn Đỏ";
            case SpatialRadarEngine.CAT_CAM_SPEED: return "Cam Tốc Độ";
            case SpatialRadarEngine.CAT_CAM_PENALTY: return "Cam Phạt Nguội";
            case SpatialRadarEngine.CAT_TRAFFIC_LIGHT: return "Đèn Đỏ";
            case SpatialRadarEngine.CAT_POLICE: return "Chốt CSGT";
            case SpatialRadarEngine.CAT_URBAN_SIGN: return "Khu Dân Cư";
            case SpatialRadarEngine.CAT_WARNING_SIGN: return "Biển Cảnh Báo";
            default: return "Cảnh Báo";
        }
    }

    private static String ellipsize(String s, Paint p, float maxWidth) {
        if (s == null || maxWidth <= 2f) return "";
        if (p.measureText(s) <= maxWidth) return s;
        float dotsW = p.measureText("…");
        if (maxWidth <= dotsW) return "";
        int count = p.breakText(s, true, maxWidth - dotsW, null);
        if (count <= 0) return "";
        return s.substring(0, Math.min(count, s.length())).trim() + "…";
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (pipFullBleed || listener == null) {
            return super.onTouchEvent(event);
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                lastRawX = downRawX;
                lastRawY = downRawY;
                downLocalX = event.getX();
                downLocalY = event.getY();
                dragging = false;
                return true;

            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - downRawX;
                float dy = event.getRawY() - downRawY;
                if (!dragging && (dx * dx + dy * dy) > dp(6) * dp(6)) {
                    dragging = true;
                }
                if (dragging) {
                    int stepX = Math.round(event.getRawX() - lastRawX);
                    int stepY = Math.round(event.getRawY() - lastRawY);
                    lastRawX = event.getRawX();
                    lastRawY = event.getRawY();
                    listener.onDragDelta(stepX, stepY);
                }
                return true;

            case MotionEvent.ACTION_UP:
                if (dragging) {
                    listener.onDragFinished();
                    return true;
                }
                if (!audioBtnRect.isEmpty() && audioBtnRect.contains(downLocalX, downLocalY)) {
                    listener.onCycleAudio();
                    return true;
                }
                if (!pinBtnRect.isEmpty() && pinBtnRect.contains(downLocalX, downLocalY)) {
                    listener.onQuickPin();
                    return true;
                }
                if (!closeBtnRect.isEmpty() && closeBtnRect.contains(downLocalX, downLocalY)) {
                    listener.onCloseOverlay();
                    return true;
                }
                long now = System.currentTimeMillis();
                if (now - lastTapMs < 320L) {
                    lastTapMs = 0L;
                    listener.onToggleCompact();
                } else {
                    lastTapMs = now;
                }
                return true;
        }
        return super.onTouchEvent(event);
    }
}
