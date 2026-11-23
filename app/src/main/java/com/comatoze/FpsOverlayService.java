package com.comatoze;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Overlay FPS: chip bulat hitam (FPS + baterai) di kiri atas, chip rekam layar merah-berkedip di kanan atas.
 * Tap chip -> mini menu hitam glass: slider kecerahan, Rekam FPS/Layar, pilih Mode & Hz, Thermal Off, DND, Screenshot.
 * Drag = pindah, tap di luar = tutup. Ukuran & transparansi diatur lewat Intent extra (dari WebUI).
 * FPS dibaca dari `ultrapower statline` (kolom FPS saat merekam), fallback SurfaceFlinger --latency.
 */
public class FpsOverlayService extends Service {
    private static final String CH = "fps_overlay";
    private static final int ID = 9;
    private WindowManager wm;
    private View chip, batChip, recChip, menu;
    private WindowManager.LayoutParams lpChip, lpBat, lpRec, lpMenu;
    private final Handler h = new Handler(Looper.getMainLooper());
    private volatile boolean running = false;
    private boolean recording = false;
    private long recStart = 0;
    private float chipAlpha = 0.78f;
    private int chipSize = 38;
    private String curMode = "balance";
    private int curHz = 120;
    private final Root.Shell shell = new Root.Shell();
    private static volatile FpsOverlayService inst;

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
            // coba ambil FPS dari perekam bawaan (kolom 11 saat merekam), kalau tidak pakai jumlah frame SF
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

    @Override public void onCreate() { super.onCreate(); inst = this; }
    @Override public void onDestroy() { inst = null; teardown(); super.onDestroy(); }

    public static void applyStyleStatic(int size, int alpha) { if (inst != null) inst.applyStyle(size, alpha); }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String act = intent != null ? intent.getAction() : null;
        if ("STOP".equals(act)) { stopSelf(); return START_NOT_STICKY; }
        if (intent != null && "SET".equals(act)) {
            chipSize = intent.getIntExtra("size", chipSize);
            chipAlpha = intent.getIntExtra("alpha", 78) / 100f;
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel(CH, "Overlay FPS", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, CH).setSmallIcon(R.drawable.ic_notif).setContentTitle("Overlay FPS aktif").build();
        try { startForeground(ID, n); } catch (Throwable t) { stopSelf(); return START_NOT_STICKY; }
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
    private GradientDrawable glassBg(float a) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(20));
        g.setColor(Color.argb((int) (a * 255), 0, 0, 0));
        g.setStroke(dp(1), Color.argb(45, 255, 255, 255));
        return g;
    }

    private void show() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        int fl = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        int wtype = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        lpChip = new WindowManager.LayoutParams(-2, -2, wtype, fl, PixelFormat.TRANSLUCENT);
        lpChip.gravity = Gravity.TOP | Gravity.LEFT; lpChip.x = dp(14); lpChip.y = dp(28);
        chip = mkChip(11, "60"); chip.setBackground(circle(chipAlpha));
        wm.addView(chip, lpChip);
        chip.setOnTouchListener(new View.OnTouchListener() {
            float x, y; int ox, oy; boolean moved;
            @Override public boolean onTouch(View vv, MotionEvent e) {
                WindowManager.LayoutParams lp = (WindowManager.LayoutParams) vv.getLayoutParams();
                if (e.getAction() == MotionEvent.ACTION_DOWN) { x = e.getRawX(); y = e.getRawY(); ox = lp.x; oy = lp.y; moved = false; return true; }
                if (e.getAction() == MotionEvent.ACTION_MOVE) { if (Math.abs(e.getRawX() - x) + Math.abs(e.getRawY() - y) > 8) moved = true; lp.x = ox + (int) (e.getRawX() - x); lp.y = oy + (int) (e.getRawY() - y); try { wm.updateViewLayout(vv, lp); } catch (Throwable t) { } return true; }
                if (e.getAction() == MotionEvent.ACTION_UP) { if (!moved) toggleMenu(); return true; }
                return false;
            }
        });

        lpBat = new WindowManager.LayoutParams(-2, -2, wtype, fl, PixelFormat.TRANSLUCENT);
        lpBat.gravity = Gravity.TOP | Gravity.LEFT; lpBat.x = dp(14) + chipSize + dp(10); lpBat.y = dp(28);
        batChip = mkBar(); wm.addView(batChip, lpBat);

        lpRec = new WindowManager.LayoutParams(-2, -2, wtype, fl, PixelFormat.TRANSLUCENT);
        lpRec.gravity = Gravity.TOP | Gravity.RIGHT; lpRec.x = dp(14); lpRec.y = dp(28);
        recChip = mkRec(); recChip.setVisibility(View.GONE); wm.addView(recChip, lpRec);

        lpMenu = new WindowManager.LayoutParams(-2, -2, wtype, fl, PixelFormat.TRANSLUCENT);
        lpMenu.gravity = Gravity.TOP | Gravity.LEFT; lpMenu.x = dp(14); lpMenu.y = dp(28) + chipSize + dp(12);
        menu = mkMenu(); menu.setVisibility(View.GONE); wm.addView(menu, lpMenu);
        attachMenuOutside(menu);
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

    private TextView row(String txt, View.OnClickListener l) {
        TextView b = new TextView(this); b.setText(txt); b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); b.setPadding(dp(14), dp(9), dp(14), dp(9));
        b.setOnClickListener(l); return b;
    }
    private TextView sec(String txt) {
        TextView t = new TextView(this); t.setText(txt);
        t.setTextColor(Color.argb(150, 255, 255, 255)); t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        t.setPadding(dp(14), dp(8), dp(14), 0); return t;
    }

    private LinearLayout mkMenu() {
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(glassBg(0.84f)); l.setPadding(dp(8), dp(8), dp(8), dp(8)); l.setMinimumWidth(dp(216));

        l.addView(sec("Kecerahan"));
        android.widget.SeekBar sb = new android.widget.SeekBar(this); sb.setMax(255); sb.setProgress(120);
        sb.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(android.widget.SeekBar s2, int p, boolean u) { try { Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, Math.max(5, p)); } catch (Throwable t) { } }
            public void onStartTrackingTouch(android.widget.SeekBar s2) { } public void onStopTrackingTouch(android.widget.SeekBar s2) { }
        });
        l.addView(sb, new LinearLayout.LayoutParams(-1, -2));

        l.addView(sec("Rekam"));
        LinearLayout rr = new LinearLayout(this); rr.setOrientation(LinearLayout.HORIZONTAL);
        rr.addView(row("Rekam FPS", v -> shell.send("sh " + Root.BIN + " rec " + (recording ? "stop" : "start") + " </dev/null >/dev/null 2>&1")));
        rr.addView(row("Rekam Layar", v -> toggleRec()), new LinearLayout.LayoutParams(0, -2, 1));
        l.addView(rr);

        l.addView(sec("Mode"));
        String[] md = {"Performa", "Balance", "Eco"}; String[] mv = {"perf", "balance", "eco"};
        for (int i = 0; i < md.length; i++) { final String m = mv[i]; l.addView(row((curMode.equals(m) ? "● " : "○ ") + md[i], v -> mode(m))); }

        l.addView(sec("Hz"));
        int[] hz = {60, 90, 120}; for (int h2 : hz) { final int v = h2; l.addView(row((curHz == v ? "● " : "○ ") + v + " Hz", x -> hzSet(v))); }

        l.addView(sec("Lainnya"));
        l.addView(row("Thermal Off", v -> shell.send("sh " + Root.BIN + " thermal off </dev/null >/dev/null 2>&1")));
        l.addView(row("DND", v -> shell.send("sh " + Root.BIN + " feat dnd on </dev/null >/dev/null 2>&1")));
        l.addView(row("Screenshot", v -> { hideMenu(); shot(); }));
        return l;
    }

    private void rebuildMenu() {
        if (wm == null || lpMenu == null) return;
        try { wm.removeView(menu); } catch (Throwable t) { }
        menu = mkMenu(); menu.setVisibility(View.GONE); wm.addView(menu, lpMenu); attachMenuOutside(menu);
    }

    private void mode(String m) { curMode = m; shell.send("sh " + Root.BIN + " mode " + m + " </dev/null >/dev/null 2>&1"); rebuildMenu(); }
    private void hzSet(int v) { curHz = v; shell.send("sh " + Root.BIN + " refresh " + v + " </dev/null >/dev/null 2>&1"); rebuildMenu(); }
    private void toggleRec() {
        recording = !recording;
        if (recording) { recStart = System.currentTimeMillis(); recChip.setVisibility(View.VISIBLE); shell.send("screenrecord --time-limit 3600 /sdcard/Movies/comatoze_rec.mp4 </dev/null >/dev/null 2>&1 &"); }
        else recChip.setVisibility(View.GONE);
    }
    private void shot() { shell.send("screencap -p /sdcard/Pictures/comatoze_ss_" + System.currentTimeMillis() + ".png </dev/null >/dev/null 2>&1"); }
    private void hideMenu() { if (menu != null) menu.setVisibility(View.GONE); }
    private void toggleMenu() { if (menu != null) menu.setVisibility(menu.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); }

    private void applyStyle(int size, int alpha) {
        chipSize = size; chipAlpha = alpha / 100f;
        if (chip != null) { chip.setBackground(circle(chipAlpha)); chip.setMinimumWidth(chipSize); chip.setMinimumHeight(chipSize); }
        if (menu != null) { GradientDrawable g = glassBg(0.84f); menu.setBackground(g); }
    }

    private void attachMenuOutside(View m) {
        m.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_OUTSIDE) { m.setVisibility(View.GONE); return true; }
            return false;
        });
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private void teardown() {
        running = false; h.removeCallbacks(tick); try { shell.close(); } catch (Throwable t) { }
        try { wm.removeView(chip); } catch (Throwable t) { }
        try { wm.removeView(batChip); } catch (Throwable t) { }
        try { wm.removeView(recChip); } catch (Throwable t) { }
        try { wm.removeView(menu); } catch (Throwable t) { }
        chip = batChip = recChip = menu = null;
    }
}
