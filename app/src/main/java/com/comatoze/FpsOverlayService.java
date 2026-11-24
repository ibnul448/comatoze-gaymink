package com.comatoze;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Overlay FPS (TANPA menu): chip bulat hitam (FPS + baterai) di kiri atas,
 * chip rekam layar merah-berkedip di kanan atas. Drag = pindah.
 * Tap chip = mulai/hentikan perekaman layar (screenrecord via su).
 * Ukuran & transparansi diatur lewat Intent extra (dari WebUI).
 */
public class FpsOverlayService extends Service {
    private static final String CH = "fps_overlay";
    private static final int ID = 9;
    private WindowManager wm;
    private View chip, batChip, recChip;
    private WindowManager.LayoutParams lpChip, lpBat, lpRec;
    private final Handler h = new Handler(Looper.getMainLooper());
    private volatile boolean running = false;
    private boolean recording = false;
    private long recStart = 0;
    private float chipAlpha = 0.78f;
    private int chipSize = 38;
    private final Root.Shell shell = new Root.Shell();

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            update();
            h.postDelayed(this, 1000);
        }
    };

    private void update() {
        int fps = readFps();
        if (chip != null) ((TextView) chip.findViewById(11)).setText(String.valueOf(fps));
        int bat = readBat();
        if (batChip != null) ((TextView) batChip.findViewById(12)).setText(bat + "%");
        if (recording && recChip != null) {
            long s = (System.currentTimeMillis() - recStart) / 1000;
            ((TextView) recChip.findViewById(13)).setText(String.format("%02d:%02d", s / 60, s % 60));
            recChip.setAlpha((s % 2) == 0 ? 1f : 0.5f);
        }
    }

    private int readFps() {
        try {
            Root.Res r = Root.exec("sh " + Root.BIN + " statline 2>/dev/null", 4);
            String o = r.out();
            if (o.startsWith("ST|")) {
                String[] a = o.split("\\|", -1);
                if (a.length > 11) { int f = Integer.parseInt(a[11].trim()); if (f > 0) return f; }
            }
        } catch (Throwable ignored) { }
        try {
            Root.Res r = Root.exec("dumpsys SurfaceFlinger --latency 2>/dev/null | wc -l", 4);
            int n = Integer.parseInt(r.out().trim());
            return n > 0 ? Math.min(144, n) : 0;
        } catch (Throwable ignored) { return 0; }
    }
    private int readBat() {
        try {
            Root.Res r = Root.exec("cat /sys/class/power_supply/battery/capacity", 3);
            return Integer.parseInt(r.out().trim());
        } catch (Throwable ignored) { return 0; }
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String act = intent != null ? intent.getAction() : null;
        if ("STOP".equals(act)) { stopSelf(); return START_NOT_STICKY; }
        if (intent != null && "SET".equals(act)) {
            chipSize = intent.getIntExtra("size", chipSize);
            chipAlpha = intent.getIntExtra("alpha", 78) / 100f;
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel(CH, "Overlay FPS", NotificationManager.IMPORTANCE_MIN));
        try { startForeground(ID, new Notification.Builder(this, CH).setSmallIcon(R.drawable.ic_notif).setContentTitle("Overlay FPS aktif").build()); }
        catch (Throwable t) { stopSelf(); return START_NOT_STICKY; }
        if (!running) { running = true; show(); h.postDelayed(tick, 400); }
        else applyStyle(chipSize, (int) (chipAlpha * 100));
        return START_STICKY;
    }

    private GradientDrawable circle(float a) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(Color.argb((int) (a * 255), 0, 0, 0));
        return d;
    }

    private void show() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        int fl = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        int wtype = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        lpChip = new WindowManager.LayoutParams(-2, -2, wtype, fl, PixelFormat.TRANSLUCENT);
        lpChip.gravity = Gravity.TOP | Gravity.LEFT; lpChip.x = dp(14); lpChip.y = dp(28);
        chip = mkChip(11, "60"); chip.setBackground(circle(chipAlpha));
        wm.addView(chip, lpChip); attachDrag(chip);

        lpBat = new WindowManager.LayoutParams(-2, -2, wtype, fl, PixelFormat.TRANSLUCENT);
        lpBat.gravity = Gravity.TOP | Gravity.LEFT; lpBat.x = dp(14) + chipSize + dp(10); lpBat.y = dp(28);
        batChip = mkBar(); wm.addView(batChip, lpBat); attachDrag(batChip);

        lpRec = new WindowManager.LayoutParams(-2, -2, wtype, fl, PixelFormat.TRANSLUCENT);
        lpRec.gravity = Gravity.TOP | Gravity.RIGHT; lpRec.x = dp(14); lpRec.y = dp(28);
        recChip = mkRec(); recChip.setVisibility(View.GONE); wm.addView(recChip, lpRec);
    }

    private LinearLayout mkChip(int id, String txt) {
        TextView t = new TextView(this); t.setId(id); t.setText(txt);
        t.setTextColor(Color.WHITE); t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f); t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout l = new LinearLayout(this); l.addView(t); l.setGravity(Gravity.CENTER);
        l.setMinimumWidth(chipSize); l.setMinimumHeight(chipSize);
        return l;
    }
    private LinearLayout mkBar() {
        TextView t = new TextView(this); t.setId(12); t.setText("0%");
        t.setTextColor(Color.WHITE); t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f); t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.addView(t); l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding(dp(10), dp(5), dp(10), dp(5));
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(dp(99)); g.setColor(Color.argb(150, 0, 0, 0)); l.setBackground(g);
        return l;
    }
    private LinearLayout mkRec() {
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(dp(18));
        g.setColor(Color.argb(170, 0, 0, 0)); g.setStroke(dp(1), Color.argb(150, 255, 90, 90)); l.setBackground(g);
        l.setPadding(dp(9), dp(4), dp(9), dp(4));
        TextView t = new TextView(this); t.setId(13); t.setText("00:00");
        t.setTextColor(Color.rgb(255, 90, 90)); t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        l.addView(t); return l;
    }

    private void toggleRec() {
        recording = !recording;
        if (recording) { recStart = System.currentTimeMillis(); recChip.setVisibility(View.VISIBLE); shell.send("screenrecord --time-limit 3600 /sdcard/Movies/comatoze_rec.mp4 </dev/null >/dev/null 2>&1 &"); }
        else recChip.setVisibility(View.GONE);
    }
    private void applyStyle(int size, int alpha) {
        chipSize = size; chipAlpha = alpha / 100f;
        if (chip != null) { chip.setBackground(circle(chipAlpha)); chip.setMinimumWidth(chipSize); chip.setMinimumHeight(chipSize); }
    }
    private void attachDrag(final View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            float x, y; int ox, oy; boolean moved;
            @Override public boolean onTouch(View vv, MotionEvent e) {
                WindowManager.LayoutParams lp = (WindowManager.LayoutParams) vv.getLayoutParams();
                if (e.getAction() == MotionEvent.ACTION_DOWN) { x = e.getRawX(); y = e.getRawY(); ox = lp.x; oy = lp.y; moved = false; return true; }
                if (e.getAction() == MotionEvent.ACTION_MOVE) { if (Math.abs(e.getRawX() - x) + Math.abs(e.getRawY() - y) > 8) moved = true; lp.x = ox + (int) (e.getRawX() - x); lp.y = oy + (int) (e.getRawY() - y); try { wm.updateViewLayout(vv, lp); } catch (Throwable t) { } return true; }
                if (e.getAction() == MotionEvent.ACTION_UP) { if (!moved) toggleRec(); return true; }
                return false;
            }
        });
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private void teardown() {
        running = false; h.removeCallbacks(tick); try { shell.close(); } catch (Throwable t) { }
        try { wm.removeView(chip); } catch (Throwable t) { }
        try { wm.removeView(batChip); } catch (Throwable t) { }
        try { wm.removeView(recChip); } catch (Throwable t) { }
        chip = batChip = recChip = null;
    }

    @Override public void onDestroy() { teardown(); super.onDestroy(); }
}
