package de.herbers.sucher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * Einfacher Seiten-Blaetterer fuer PDF/CBZ (das "Quick-Look-Gefuehl" fuer
 * Formate, die schon als fertige Seiten-Bilder vorliegen - Mathias' Wunsch).
 * Kein ViewPager2 (kein Gradle/AndroidX in diesem Projekt) - von Hand: ein
 * Bild, Wisch-/Tipp-Erkennung per GestureDetector, jede Seite wird bei Bedarf
 * frisch im Hintergrund geladen statt mehrere Seiten im Speicher zu halten
 * (wichtig bei Comics/Buechern mit hunderten Seiten).
 */
final class PagerView extends FrameLayout {

    interface PageSource {
        int count();
        Bitmap load(int index); // laeuft im Hintergrund-Thread, darf blockieren
    }

    private final PageSource source;
    private final ImageView image;
    private final TextView status;
    private final GestureDetector gestures;
    private int current;
    private long requestId = 0;

    PagerView(Context ctx, PageSource source, int startIndex) {
        super(ctx);
        this.source = source;
        setBackgroundColor(Color.BLACK);
        setClickable(true);
        int d = Math.round(ctx.getResources().getDisplayMetrics().density);

        image = new ImageView(ctx);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        addView(image, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        status = new TextView(ctx);
        status.setTextColor(Color.WHITE);
        status.setBackgroundColor(0x88000000);
        status.setPadding(10 * d, 4 * d, 10 * d, 4 * d);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        slp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        slp.bottomMargin = 16 * d;
        addView(status, slp);

        gestures = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null) return false;
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                if (Math.abs(dx) > 80 && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                    if (dx < 0) next(); else prev();
                    return true;
                }
                return false;
            }
            @Override public boolean onSingleTapUp(MotionEvent e) {
                // Antippen als Alternative zum Wischen: rechte Haelfte = vor,
                // linke Haelfte = zurueck.
                if (e.getX() > getWidth() / 2f) next(); else prev();
                return true;
            }
        });

        current = Math.max(0, Math.min(startIndex, Math.max(0, source.count() - 1)));
        loadCurrent();
    }

    @Override public boolean onTouchEvent(MotionEvent ev) {
        gestures.onTouchEvent(ev);
        return true;
    }

    private void next() { if (current < source.count() - 1) { current++; loadCurrent(); } }
    private void prev() { if (current > 0) { current--; loadCurrent(); } }

    private void loadCurrent() {
        final long myRequest = ++requestId;
        final int idx = current;
        status.setText((idx + 1) + " / " + source.count());
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            Bitmap bmp = null;
            try { bmp = source.load(idx); } catch (Throwable ignored) {}
            final Bitmap fb = bmp;
            main.post(() -> {
                if (myRequest != requestId) { // durch schnelles Weiterblaettern ueberholt
                    if (fb != null) fb.recycle();
                    return;
                }
                Bitmap old = (image.getDrawable() instanceof BitmapDrawable)
                        ? ((BitmapDrawable) image.getDrawable()).getBitmap() : null;
                image.setImageBitmap(fb);
                if (old != null && old != fb) old.recycle();
            });
        }, "PreviewPageLoader").start();
    }
}
