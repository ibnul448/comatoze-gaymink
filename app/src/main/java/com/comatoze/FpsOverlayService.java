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
 * Overlay FPS: chip bulat hitam kecil di kiri atas + chip baterai + chip rekam layar di kanan atas.
 * Tap chip -> mini menu hitam glass: slider kecerahan, baris Rekam FPS/Layar, pilih Mode & Hz,
 * Thermal Off, DND, Screenshot. Semua dibuat dari kode (tanpa layout XML).
 * Data FPS dibaca dari `ultrapower statline` tiap 1 dtk lewat `su` (Root).
 * Ukuran chip & transparansi diatur via Intent extra, disimpan di SharedPreferences.
 */
public class FpsOverlayService extends Service {
    private static final String CH = "fps_overlay";
    private static final int ID = 9;
    private WindowManager wm;
    private View chip, batChip, recChip, menu;
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean running = false;
    private boolean recording = false;
    private long recStart = 0;
    private float chipAlpha = 0.78f;
    private int chipSize = 38;
    private String curMode = "balance";
    private int curHz = 120;
    private int brt = 62;
    private Process suProc;
    private java.io.OutputStream suIn;
    private final Root.Shell shell = new Root.Shell();

    private final Runnable fpsTick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            shell.send("sh " + Root.BIN + " statline >/dev/null 2>&1");
            int fps = readFps();
            ((TextView) chip.findViewById(11)).setText(String.valueOf(fps));
            int bat = readBat();
            ((TextView) batChip.findViewById(12)).setText(bat + "%");
            if (recording) {
                long s = (System.currentTimeMillis() - recStart) / 1000;
                ((TextView) recChip.findViewById(13)).setText(String.format("%02d:%02d", s / 60, s % 60));
                recChip.setAlpha(((recStart + s) % 2 == 0) ? 1f : 0.55f); // kedip tiap detik
            }
            h.postDelayed(this, 1000);
        }
    };

    private int readFps() {
        try {
            Root.Res r = Root.exec("dumpsys SurfaceFlinger --latency 2>/dev/null | wc -l", 3);
            int n = Integer.parseInt(r.out().trim());
            return n > 0 ? Math.min(144, n) : 0;
        } catch (Exception e) { return 0; }
    }
    private int readBat() {
        try {
            Root.Res r = Root.exec("cat /sys/class/power_supply/battery/capacity", 3);
            return Integer.parseInt(r.out().trim());
        } catch (Exception e) { return 0; }
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (intent != null) {
            chipSize = intent.getIntExtra("size", chipSize);
            chipAlpha = intent.getIntExtra("alpha", (int) (chipAlpha * 100)) / 100f;
            if ("SET".equals(intent.getAction())) { if (chip != null) applyStyle(); if (menu != null) applyStyle(); return START_STICKY; }
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CH, "Overlay FPS", NotificationManager.IMPORTANCE_MIN));
        startForeground(ID, new Notification.Builder(this, CH).setSmallIcon(R.drawable.ic_notif).setContentTitle("Overlay FPS aktif").build());
        if (!running) { running = true; show(); h.postDelayed(fpsTick, 600); }
        return START_STICKY;
    }

    private GradientDrawable circle(int alpha) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(Color.argb((int) (alpha * 255), 0, 0, 0));
        return d;
    }

    private void show() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        int t = PixelFormat.TRANSLUCENT;
        int fl = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(-2, -2, t, fl, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT; lp.x = 24; lp.y = 48;
        chip = mkChip(11, "60"); chip.setBackground(circle(0.78f));
        chip.setPadding(dp(8), 0, dp(8), 0);
        chip.setElevation(dp(4));
        wm.addView(chip, lp);

        WindowManager.LayoutParams lp2 = new WindowManager.LayoutParams(-2, -2, t, fl, PixelFormat.TRANSLUCENT);
        lp2.gravity = Gravity.TOP | Gravity.LEFT; lp2.x = 24 + chipSize + dp(10); lp2.y = 48;
        batChip = mkBar(); wm.addView(batChip, lp2);

        WindowManager.LayoutParams lp3 = new WindowManager.LayoutParams(-2, -2, t, fl, PixelFormat.TRANSLUCENT);
        lp3.gravity = Gravity.TOP | Gravity.RIGHT; lp3.x = 24; lp3.y = 48;
        recChip = mkRec(); recChip.setVisibility(View.GONE); wm.addView(recChip, lp3);

        menu = mkMenu(); menu.setVisibility(View.GONE);
        WindowManager.LayoutParams lp4 = new WindowManager.LayoutParams(-2, -2, t, fl, PixelFormat.TRANSLUCENT);
        lp4.gravity = Gravity.TOP | Gravity.LEFT; lp4.x = 24; lp4.y = 48 + chipSize + dp(14);
        wm.addView(menu, lp4);

        attachDrag(chip, menu);
        attachDrag(batChip, menu);
    }

    private LinearLayout mkChip(int id, String txt) {
        TextView t = new TextView(this);
        t.setText(txt); t.setId(id); t.setTextColor(Color.WHITE); t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout l = new LinearLayout(this); l.addView(t); l.setGravity(Gravity.CENTER);
        return l;
    }
    private LinearLayout mkBar() {
        TextView t = new TextView(this); t.setId(12); t.setText("0%"); t.setTextColor(Color.WHITE); t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f); t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.addView(t); l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding(dp(10), dp(5), dp(10), dp(5));
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(dp(99)); g.setColor(Color.argb(160, 0, 0, 0)); l.setBackground(g);
        return l;
    }
    private LinearLayout mkRec() {
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable g = new GradientDrawable(); g.setShape(GradientDrawable.RECTANGLE); g.setCornerRadius(dp(20));
        g.setColor(Color.argb(170, 0, 0, 0)); g.setStroke(dp(1), Color.argb(140, 255, 80, 80)); l.setBackground(g);
        l.setPadding(dp(8), dp(4), dp(8), dp(4));
        TextView t = new TextView(this); t.setId(13); t.setText("00:00"); t.setTextColor(Color.rgb(255, 90, 90)); t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        l.addView(t); return l;
    }

    private View row(String txt, Runnable run) {
        TextView b = new TextView(this); b.setText(txt); b.setTextColor(Color.WHITE); b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        b.setPadding(dp(14), dp(9), dp(14), dp(9));
        b.setOnClickListener(v -> run.run()); return b;
    }
    private View sec(String txt) {
        TextView t = new TextView(this); t.setText(txt); t.setTextColor(Color.argb(150, 255, 255, 255)); t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f); t.setPadding(dp(14), dp(7), dp(14), 0); return t;
    }

    private LinearLayout mkMenu() {
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(dp(20));
        g.setColor(Color.argb(215, 0, 0, 0)); g.setStroke(dp(1), Color.argb(45, 255, 255, 255)); l.setBackground(g);
        l.setPadding(dp(8), dp(8), dp(8), dp(8)); l.setMinimumWidth(dp(210));

        l.addView(sec("Kecerahan"));
        LinearLayout br = new LinearLayout(this); br.setOrientation(LinearLayout.HORIZONTAL);
        android.widget.SeekBar sb = new android.widget.SeekBar(this); sb.setMax(255); sb.setProgress(brt);
        sb.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(android.widget.SeekBar s, int p, boolean u) { brt = p; try { Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, Math.max(5, p)); } catch (Exception e) { } }
            public void onStartTrackingTouch(android.widget.SeekBar s) { } public void onStopTrackingTouch(android.widget.SeekBar s) { }
        });
        LinearLayout.LayoutParams sbl = new LinearLayout.LayoutParams(0, -2, 1); br.addView(sb, sbl); l.addView(br);

        l.addView(sec("Rekam"));
        LinearLayout rr = new LinearLayout(this); rr.setOrientation(LinearLayout.HORIZONTAL);
        rr.addView(row("Rekam FPS", () -> toggleFps()));
        rr.addView(row("Rekam Layar", () -> toggleRec()), new LinearLayout.LayoutParams(0, -2, 1));
        l.addView(rr);

        l.addView(sec("Mode"));
        String[] md = {"Performa", "Balance", "Eco"}; String[] mv = {"perf", "balance", "eco"};
        for (int i = 0; i < md.length; i++) { final String m = mv[i]; l.addView(row((curMode.equals(m) ? "● " : "○ ") + md[i], () -> mode(m))); }

        l.addView(sec("Hz"));
        int[] hz = {60, 90, 120}; for (int h2 : hz) { final int v = h2; l.addView(row((curHz == v ? "● " : "○ ") + v + " Hz", () -> hzSet(v))); }

        l.addView(sec("Lainnya"));
        l.addView(row("Thermal Off", () -> shell.send(Root.BIN + " thermal off >/dev/null 2>&1")));
        l.addView(row("DND", () -> shell.send(Root.BIN + " feat dnd on >/dev/null 2>&1")));
        l.addView(row("Screenshot", () -> { hideMenu(); shot(); }));
        return l;
    }

    private void mode(String m) { curMode = m; shell.send(Root.BIN + " mode " + m + " >/dev/null 2>&1"); rebuildMenu(); }
    private void hzSet(int v) { curHz = v; shell.send(Root.BIN + " refresh " + v + " >/dev/null 2>&1"); rebuildMenu(); }
    private void toggleFps() { shell.send(Root.BIN + " rec " + (recording ? "stop" : "start") + " >/dev/null 2>&1"); }
    private void toggleRec() {
        recording = !recording;
        if (recording) { recStart = System.currentTimeMillis(); recChip.setVisibility(View.VISIBLE); shell.send("screenrecord --time-limit 3600 /sdcard/Movies/comatoze_rec.mp4 </dev/null >/dev/null 2>&1 &"); }
        else recChip.setVisibility(View.GONE);
    }
    private void shot() {
        String f = "/sdcard/Pictures/comatoze_ss_" + System.currentTimeMillis() + ".png";
        shell.send("screencap -p " + f + " </dev/null >/dev/null 2>&1");
    }
    private void hideMenu() { if (menu != null) menu.setVisibility(View.GONE); }

    private void rebuildMenu() {
        if (menu == null) return;
        View old = menu;
        menu = mkMenu(); menu.setVisibility(old.getVisibility());
        View p = (View) old.getParent(); if (p != null) { WindowManager.LayoutParams lp = (WindowManager.LayoutParams) old.getLayoutParams(); wm.removeView(old); wm.addView(menu, lp); }
    }

    private void applyStyle() {
        if (chip != null) { chip.setBackground(circle(chipAlpha)); }
        if (menu != null) { GradientDrawable g = new GradientDrawable(); g.setCornerRadius(dp(20)); g.setColor(Color.argb(215, 0, 0, 0)); g.setStroke(dp(1), Color.argb(45, 255, 255, 255)); menu.setBackground(g); }
    }

    private void attachDrag(View v, View menu) {
        v.setOnTouchListener(new View.OnTouchListener() {
            float x, y; int ox, oy; boolean moved;
            @Override public boolean onTouch(View vv, MotionEvent e) {
                WindowManager.LayoutParams lp = (WindowManager.LayoutParams) vv.getLayoutParams();
                if (e.getAction() == MotionEvent.ACTION_DOWN) { x = e.getRawX(); y = e.getRawY(); ox = lp.x; oy = lp.y; moved = false; return true; }
                if (e.getAction() == MotionEvent.ACTION_MOVE) { if (Math.abs(e.getRawX() - x) + Math.abs(e.getRawY() - y) > 8) moved = true; lp.x = ox + (int) (e.getRawX() - x); lp.y = oy + (int) (e.getRawY() - y); wm.updateViewLayout(vv, lp); return true; }
                if (e.getAction() == MotionEvent.ACTION_UP) { if (!moved) { if (menu != null) menu.setVisibility(menu.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); } return true; }
                return false;
            }
        });
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    @Override public void onDestroy() {
        running = false; h.removeCallbacks(fpsTick); shell.close();
        try { wm.removeView(chip); } catch (Exception e) { }
        try { wm.removeView(batChip); } catch (Exception e) { }
        try { wm.removeView(recChip); } catch (Exception e) { }
        try { wm.removeView(menu); } catch (Exception e) { }
        if (suProc != null) suProc.destroy();
        super.onDestroy();
    }
}
