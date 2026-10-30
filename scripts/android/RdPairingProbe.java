package com.jedflix.test;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.widget.ImageView;
import android.widget.TextView;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/** Disposable-package checks; only synthetic keys are sent through the live transfer service. */
public final class RdPairingProbe extends Instrumentation {
    private ClassLoader loader;
    private int checks;
    private Class<?> type(String name) throws Exception { return loader.loadClass(name); }
    private void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
    }
    private Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle report = new Bundle();
        Activity activity = null;
        try {
            check(getTargetContext().getPackageName().equals("com.jedflix.presettest"), "requires disposable package");
            loader = getTargetContext().getClassLoader();
            Class<?> clientType = type("com.jedflix.tv.RdKeyPairing");
            String code = (String) clientType.getMethod("newCode").invoke(null);
            check(code.matches("[0-9a-f]{64}"), "256-bit code format");
            check(!code.equals(clientType.getMethod("newCode").invoke(null)), "fresh codes");
            check(mock("PUT", 201, "", code) == null, "opens slot");
            check(mock("GET", 204, "", code) == null, "204 waits");
            check("".equals(mock("GET", 404, "", code)), "404 expires");
            check("SYNTHETIC_KEY".equals(mock("GET", 200, "{\"apiKey\":\" SYNTHETIC_KEY \"}", code)), "key parsed and trimmed");
            for (String body : new String[]{"{}", "bad json", "{\"apiKey\":42}", "{\"apiKey\":\"\"}", "{\"apiKey\":\"bad key!\"}", new String(new char[4097]).replace('\0', 'x')}) {
                boolean rejected = false;
                try { mock("GET", 200, body, code); } catch (InvocationTargetException expected) { rejected = true; }
                check(rejected, "reject malformed/oversized response");
            }
            boolean rejected = false;
            try { mock("GET", 503, "", code); } catch (InvocationTargetException expected) { rejected = true; }
            check(rejected, "service errors rejected");
            Object cancelled = clientType.getConstructor().newInstance();
            clientType.getMethod("cancel").invoke(cancelled);
            rejected = false;
            try { clientType.getMethod("poll", String.class).invoke(cancelled, code); }
            catch (InvocationTargetException expected) { rejected = true; }
            check(rejected, "cancelled client refuses requests");

            Intent launch = new Intent().setClassName(getTargetContext().getPackageName(), "com.stremio.tv.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = startActivitySync(launch);
            final Activity owner = activity;
            final Class<?> formType = type("com.jedflix.tv.CometSettings$Form");
            Constructor<?> formConstructor = formType.getDeclaredConstructor(android.content.Context.class);
            formConstructor.setAccessible(true);
            final Object form = formConstructor.newInstance(owner);
            final Method pair = formType.getDeclaredMethod("pair"); pair.setAccessible(true);
            Field results = formType.getDeclaredField("results"); results.setAccessible(true); results.set(form, 7);
            final AtomicReference<Object> session = new AtomicReference<>();
            runOnMainSync(new Runnable() { @Override public void run() {
                try { session.set(pair.invoke(form)); }
                catch (Exception error) { throw new RuntimeException(error); }
            }});
            String url = waitForQr(session.get());
            check(url.startsWith("https://borseth.ddns.net/tv/rd/"), "QR uses existing phone page");
            String liveCode = url.substring(url.lastIndexOf('/') + 1);
            check(liveCode.matches("[0-9a-f]{64}"), "QR decodes to complete random code");
            String apiBase = (String) clientType.getField("API_BASE").get(null);
            HttpURLConnection submit = (HttpURLConnection) new URL(apiBase + liveCode).openConnection();
            submit.setConnectTimeout(15000); submit.setReadTimeout(15000);
            submit.setRequestMethod("POST"); submit.setDoOutput(true); submit.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = submit.getOutputStream()) { out.write("{\"apiKey\":\"JEDFLIX_QR_TEST_KEY\"}".getBytes(StandardCharsets.UTF_8)); }
            check(submit.getResponseCode() == 204, "phone submission accepted"); submit.disconnect();
            long deadline = SystemClock.elapsedRealtime() + 15000;
            while (!"JEDFLIX_QR_TEST_KEY".equals(field(form, "key")) && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100);
            check("JEDFLIX_QR_TEST_KEY".equals(field(form, "key")), "live QR transfer fills actual settings draft");
            check(Integer.valueOf(7).equals(field(form, "results")), "QR preserves other draft settings");
            waitForIdleSync();
            check(!(Boolean)((AlertDialog)field(session.get(), "dialog")).isShowing(), "success closes QR dialog");
            check((Boolean)field(session.get(), "closed"), "success cleans session");
            Object saved = type("com.jedflix.tv.CometConfig").getMethod("settings").invoke(null);
            check(!new org.json.JSONObject(saved.toString()).has("apiKey"), "received key is not persisted before Save");

            // A second independent session exercises in-flight cancellation and ignores late delivery.
            runOnMainSync(new Runnable() { @Override public void run() {
                try { session.set(pair.invoke(form)); }
                catch (Exception error) { throw new RuntimeException(error); }
            }});
            waitForQr(session.get());
            final AlertDialog dialog = (AlertDialog)field(session.get(), "dialog");
            runOnMainSync(new Runnable() { @Override public void run() { dialog.cancel(); }});
            Thread worker = (Thread)field(session.get(), "worker"); worker.join(4000);
            check((Boolean)field(session.get(), "closed"), "cancel closes session");
            check(!worker.isAlive(), "cancel interrupts long poll");
            check("JEDFLIX_QR_TEST_KEY".equals(field(form, "key")), "cancel preserves existing draft key");
            runOnMainSync(new Runnable() { @Override public void run() {
                try { session.set(pair.invoke(form)); }
                catch (Exception error) { throw new RuntimeException(error); }
            }});
            waitForQr(session.get());
            runOnMainSync(new Runnable() { @Override public void run() {
                try { session.get().getClass().getMethod("onActivityStopped", Activity.class).invoke(session.get(), owner); }
                catch (Exception error) { throw new RuntimeException(error); }
            }});
            worker = (Thread)field(session.get(), "worker"); worker.join(4000);
            check((Boolean)field(session.get(), "closed") && !worker.isAlive(), "backgrounding cancels session");
            report.putString("result", "PASS: " + checks + " QR pairing checks");
            finish(Activity.RESULT_OK, report);
        } catch (Throwable error) {
            report.putString("result", "FAIL: " + error); finish(Activity.RESULT_CANCELED, report);
        } finally {
            if (activity != null) { final Activity owner = activity; runOnMainSync(new Runnable() { @Override public void run() { owner.finish(); }}); }
        }
    }
    private String waitForQr(Object session) throws Exception {
        final ImageView qr = (ImageView)field(session, "qr");
        final TextView status = (TextView)field(session, "status");
        long deadline = SystemClock.elapsedRealtime() + 45000;
        final AtomicReference<Bitmap> bitmap = new AtomicReference<>();
        while (SystemClock.elapsedRealtime() < deadline) {
            runOnMainSync(new Runnable() { @Override public void run() {
                if (qr.getDrawable() instanceof BitmapDrawable) bitmap.set(((BitmapDrawable)qr.getDrawable()).getBitmap());
            }});
            if (bitmap.get() != null) return decode(bitmap.get());
            if (((Thread)field(session, "worker")).getState() == Thread.State.TERMINATED) throw new AssertionError("QR failed: " + status.getText());
            Thread.sleep(100);
        }
        throw new AssertionError("QR timed out");
    }
    private String decode(Bitmap bitmap) throws Exception {
        int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
        bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        Object source = type("com.google.zxing.RGBLuminanceSource").getConstructor(int.class, int.class, int[].class)
            .newInstance(bitmap.getWidth(), bitmap.getHeight(), pixels);
        Object binarizer = type("com.google.zxing.common.HybridBinarizer").getConstructor(type("com.google.zxing.LuminanceSource")).newInstance(source);
        Object binary = type("com.google.zxing.BinaryBitmap").getConstructor(type("com.google.zxing.Binarizer")).newInstance(binarizer);
        Object result = type("com.google.zxing.qrcode.QRCodeReader").getMethod("decode", binary.getClass())
            .invoke(type("com.google.zxing.qrcode.QRCodeReader").getConstructor().newInstance(), binary);
        return (String)result.getClass().getMethod("getText").invoke(result);
    }
    private String mock(final String method, final int status, final String body, String code) throws Exception {
        try (final ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            final AtomicReference<Throwable> serverError = new AtomicReference<>();
            server.setSoTimeout(5000);
            Thread responder = new Thread(new Runnable() { @Override public void run() {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    String request = reader.readLine();
                    if (!request.startsWith(method + " /")) throw new AssertionError("wrong request method");
                    while (!reader.readLine().isEmpty()) {}
                    byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                    OutputStream out = socket.getOutputStream();
                    out.write(("HTTP/1.1 " + status + " Test\r\nContent-Length: " + payload.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.write(payload); out.flush();
                } catch (Throwable error) { serverError.set(error); }
            }}); responder.start();
            Constructor<?> constructor = type("com.jedflix.tv.RdKeyPairing").getDeclaredConstructor(String.class);
            constructor.setAccessible(true);
            Object client = constructor.newInstance("http://127.0.0.1:" + server.getLocalPort() + "/");
            try { return (String)client.getClass().getMethod(method.equals("PUT") ? "open" : "poll", String.class).invoke(client, code); }
            finally {
                responder.join(6000);
                if (serverError.get() != null) throw new AssertionError(serverError.get());
            }
        }
    }
}
