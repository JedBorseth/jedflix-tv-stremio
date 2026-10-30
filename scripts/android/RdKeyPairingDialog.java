package com.jedflix.tv;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.ViewGroup;
import android.widget.ImageView;
import androidx.compose.runtime.Composer;
import androidx.compose.runtime.MutableState;
import androidx.compose.runtime.RememberObserver;
import androidx.compose.runtime.SnapshotStateKt;
import androidx.compose.ui.viewinterop.AndroidView_androidKt;
import com.stremio.tv.views.settings.composables.SectionItemKt;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.functions.Function2;

/** Pairing content owned by the app's Compose modal; no platform dialog or success alert. */
public final class RdKeyPairingDialog implements RememberObserver, Application.ActivityLifecycleCallbacks {
    public interface Receiver { void received(String key); }
    private final RdKeyPairing client = new RdKeyPairing();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final MutableState<Integer> revision = SnapshotStateKt.mutableStateOf(0, SnapshotStateKt.<Integer>structuralEqualityPolicy());
    private final Activity activity;
    private final Receiver receiver;
    private final Function0<Unit> dismiss;
    private volatile boolean closed, expired;
    private Thread worker;
    private Bitmap bitmap;
    private String status = "Connecting to Jedflix…";
    private final Function2<Composer, Integer, Unit> empty = new Function2<Composer, Integer, Unit>() {
        @Override public Unit invoke(Composer c, Integer flags) { return Unit.INSTANCE; }
    };
    private final Function1<Context, ImageView> imageFactory = new Function1<Context, ImageView>() {
        @Override public ImageView invoke(Context context) {
            ImageView image = new ImageView(context);
            image.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.round(220 * context.getResources().getDisplayMetrics().density)));
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setContentDescription("Scan with your phone to send a Real-Debrid API key");
            return image;
        }
    };
    private final Function1<ImageView, Unit> imageUpdate = new Function1<ImageView, Unit>() {
        @Override public Unit invoke(ImageView image) { image.setImageBitmap(bitmap); return Unit.INSTANCE; }
    };

    public RdKeyPairingDialog(Context context, Receiver receiver, Function0<Unit> dismiss) {
        while (context instanceof ContextWrapper && !(context instanceof Activity)) context = ((ContextWrapper)context).getBaseContext();
        activity = context instanceof Activity ? (Activity)context : null;
        this.receiver = receiver; this.dismiss = dismiss;
    }

    public void render(Composer composer) {
        revision.getValue();
        if (bitmap != null) AndroidView_androidKt.AndroidView(imageFactory, null, imageUpdate, composer, 0, 2);
        SectionItemKt.SectionItem(status, null, empty, composer, 0, 2);
    }
    @Override public void onRemembered() {
        if (activity != null) activity.getApplication().registerActivityLifecycleCallbacks(this);
        worker = new Thread(new Runnable() { @Override public void run() {
            try {
                String code = RdKeyPairing.newCode();
                long deadline = SystemClock.elapsedRealtime() + RdKeyPairing.TTL_MS;
                client.open(code);
                final Bitmap qr = qrBitmap(RdKeyPairing.PAGE_BASE + code);
                post(new Runnable() { @Override public void run() {
                    bitmap = qr; status = "Scan with your phone. Paste your key and tap Send to TV. This code expires in 10 minutes."; changed();
                }});
                int failures = 0;
                while (!closed && SystemClock.elapsedRealtime() < deadline) {
                    String key;
                    try { key = client.poll(code); failures = 0; }
                    catch (java.io.IOException error) {
                        if (++failures >= 3) throw error;
                        Thread.sleep(2000); continue;
                    }
                    if (SystemClock.elapsedRealtime() >= deadline) break;
                    if (key == null) { Thread.sleep(250); continue; }
                    if (key.isEmpty()) break;
                    final String received = key;
                    post(new Runnable() { @Override public void run() {
                        receiver.received(received); dismiss.invoke();
                    }});
                    return;
                }
                fail("This code has expired. Close this menu and scan a new QR code.");
            } catch (Exception error) {
                if (!expired) fail("Could not connect. Check your connection, then close this menu and try again.");
            }
        }}, "Jedflix-RD-pairing");
        worker.start();
        main.postDelayed(new Runnable() { @Override public void run() {
            if (!closed && worker.isAlive()) {
                expired = true; client.cancel(); worker.interrupt();
                fail("This code has expired. Close this menu and scan a new QR code.");
            }
        }}, RdKeyPairing.TTL_MS);
    }
    public static Bitmap qrBitmap(String url) throws Exception {
        BitMatrix matrix = new QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 512, 512);
        int[] pixels = new int[512 * 512];
        for (int y = 0; y < 512; y++) for (int x = 0; x < 512; x++)
            pixels[y * 512 + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
        return Bitmap.createBitmap(pixels, 512, 512, Bitmap.Config.ARGB_8888);
    }
    private void changed() { revision.setValue(revision.getValue() + 1); }
    private void post(final Runnable action) { main.post(new Runnable() { @Override public void run() { if (!closed) action.run(); }}); }
    private void fail(final String message) {
        post(new Runnable() { @Override public void run() {
            main.removeCallbacksAndMessages(null); bitmap = null; status = message; changed();
        }});
    }
    @Override public void onForgotten() {
        if (closed) return;
        closed = true; main.removeCallbacksAndMessages(null); client.cancel();
        if (activity != null) activity.getApplication().unregisterActivityLifecycleCallbacks(this);
        if (worker != null) worker.interrupt();
        bitmap = null;
    }
    @Override public void onAbandoned() { onForgotten(); }
    @Override public void onActivityStopped(Activity owner) { if (owner == activity) { onForgotten(); dismiss.invoke(); } }
    @Override public void onActivityDestroyed(Activity owner) { if (owner == activity) { onForgotten(); dismiss.invoke(); } }
    @Override public void onActivityCreated(Activity owner, Bundle state) {}
    @Override public void onActivityStarted(Activity owner) {}
    @Override public void onActivityResumed(Activity owner) {}
    @Override public void onActivityPaused(Activity owner) {}
    @Override public void onActivitySaveInstanceState(Activity owner, Bundle state) {}
}
