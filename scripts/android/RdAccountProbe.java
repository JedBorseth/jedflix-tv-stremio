package com.jedflix.test;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** All account identities and credentials in this test are synthetic; no live RD account is used. */
public final class RdAccountProbe extends Instrumentation {
    private ClassLoader loader;
    private int checks;
    private Class<?> type(String name) throws Exception { return loader.loadClass(name); }
    private void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); checks++; }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            loader = getTargetContext().getClassLoader();
            check(getTargetContext().getPackageName().equals("com.jedflix.presettest"), "requires disposable storage");
            JSONObject account;
            try (Service service = new Service(200, "{\"username\":\"TestViewer\",\"email\":\"private@example.test\",\"type\":\"premium\"}", false)) {
                account = validate(service.client());
                check(account.getString("username").equals("TestViewer"), "username parsed");
                check(account.getLong("validatedAt") > 0, "validation timestamp recorded");
                check(account.length() == 2 && !account.has("email"), "only username and timestamp retained");
                check(service.request.get().startsWith("GET /user HTTP/1.1"), "key absent from URL");
                check(service.request.get().contains("Authorization: Bearer SYNTHETIC_KEY"), "uses bearer authentication");
            }
            for (int status : new int[]{401, 403, 429, 500}) {
                try (Service service = new Service(status, "{\"error\":\"ignored\"}", false)) {
                    boolean failed = false;
                    try { validate(service.client()); } catch (InvocationTargetException expected) { failed = true; }
                    check(failed, "unsuccessful account validation rejected");
                }
            }
            for (String body : new String[]{"{}", "bad json", "{\"username\":42}", "{\"username\":\" \"}", "{\"username\":\"bad\\nname\"}", new String(new char[16385]).replace('\0', 'x')}) {
                try (Service service = new Service(200, body, false)) {
                    boolean failed = false;
                    try { validate(service.client()); } catch (InvocationTargetException expected) { failed = true; }
                    check(failed, "malformed account rejected");
                }
            }
            Class<?> config = type("com.jedflix.tv.CometConfig");
            Method save = config.getMethod("save", String.class, int.class, double.class, String.class, boolean.class, boolean.class, boolean.class);
            Method verified = config.getMethod("saveVerified", String.class, int.class, double.class, String.class, boolean.class, boolean.class, boolean.class, JSONObject.class);
            Method settings = config.getMethod("settings");
            verified.invoke(null, "SYNTHETIC_KEY", 7, 10d, "1080p", true, true, true, account);
            JSONObject stored = (JSONObject)settings.invoke(null);
            check(stored.getJSONObject("account").getString("username").equals("TestViewer"), "identity stored beside matching key");
            File file = new File(getTargetContext().getNoBackupFilesDir(), "comet-settings");
            byte[] bytes = new byte[(int)file.length()];
            try (DataInputStream input = new DataInputStream(new FileInputStream(file))) { input.readFully(bytes); }
            String encrypted = new String(bytes, StandardCharsets.UTF_8);
            check(!encrypted.contains("TestViewer") && !encrypted.contains("SYNTHETIC_KEY"), "key and identity encrypted together");
            Constructor<?> constructor = type("com.jedflix.tv.CometSettings$Form").getDeclaredConstructor(android.content.Context.class);
            constructor.setAccessible(true);
            Method summary = constructor.getDeclaringClass().getDeclaredMethod("accountSummary"); summary.setAccessible(true);
            Object form = constructor.newInstance(getTargetContext());
            check(summary.invoke(form).equals("Authenticated as TestViewer"), "settings displays cached account name");
            Object reopened = constructor.newInstance(getTargetContext());
            check(summary.invoke(reopened).equals("Authenticated as TestViewer"), "reopening settings uses cache");
            Field key = form.getClass().getDeclaredField("key"); key.setAccessible(true); key.set(form, "CHANGED_KEY");
            check(summary.invoke(form).equals("Save to verify account"), "draft key change hides previous identity");
            save.invoke(null, "SYNTHETIC_KEY", 9, 15d, "all", true, true, true);
            check(((JSONObject)settings.invoke(null)).has("account"), "stream edits preserve cached account");
            String beforeFailure = settings.invoke(null).toString();
            try (Service service = new Service(401, "", false)) {
                try { validate(service.client()); } catch (InvocationTargetException expected) {}
            }
            check(beforeFailure.equals(settings.invoke(null).toString()), "invalid replacement leaves saved key and identity untouched");
            boolean rejected = false;
            try { verified.invoke(null, "NEW_KEY", 0, 0d, "all", true, true, true, new JSONObject()); }
            catch (InvocationTargetException expected) { rejected = true; }
            check(rejected && beforeFailure.equals(settings.invoke(null).toString()), "missing identity cannot overwrite saved config");
            save.invoke(null, "CHANGED_KEY", 0, 0d, "all", true, true, true);
            check(!((JSONObject)settings.invoke(null)).has("account"), "key changes discard stale identity");
            form = constructor.newInstance(getTargetContext());
            check(summary.invoke(form).equals("Save to verify account"), "legacy unverified key is labelled honestly");
            try (Service service = new Service(200, "{\"username\":\"TooLate\"}", true)) {
                final Object client = service.client(); final AtomicReference<Throwable> failure = new AtomicReference<>();
                Thread worker = new Thread(new Runnable() { @Override public void run() {
                    try { validate(client); } catch (Throwable error) { failure.set(error); }
                }}); worker.start();
                check(service.started.await(5, TimeUnit.SECONDS), "validation request started");
                client.getClass().getMethod("cancel").invoke(client); worker.join(3000);
                check(!worker.isAlive() && failure.get() != null, "cancellation aborts validation request");
            }
            config.getMethod("clear").invoke(null);
            check(((JSONObject)settings.invoke(null)).length() == 0, "removal clears key and account together");
            result.putString("result", "PASS: " + checks + " account checks"); finish(Activity.RESULT_OK, result);
        } catch (Throwable error) { result.putString("result", "FAIL: " + error); finish(Activity.RESULT_CANCELED, result); }
    }
    private JSONObject validate(Object client) throws Exception {
        return (JSONObject)client.getClass().getMethod("validate", String.class).invoke(client, "SYNTHETIC_KEY");
    }
    private final class Service implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        final AtomicReference<String> request = new AtomicReference<>();
        final CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        final Thread responder;
        Service(final int status, final String body, final boolean delayed) throws Exception {
            server.setSoTimeout(5000);
            responder = new Thread(new Runnable() { @Override public void run() {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    StringBuilder headers = new StringBuilder(); String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) headers.append(line).append('\n');
                    request.set(headers.toString()); started.countDown();
                    if (delayed) release.await(5, TimeUnit.SECONDS);
                    byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                    OutputStream output = socket.getOutputStream();
                    output.write(("HTTP/1.1 " + status + " Test\r\nContent-Length: " + payload.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    output.write(payload); output.flush();
                } catch (Exception ignored) { started.countDown(); }
            }}); responder.start();
        }
        Object client() throws Exception {
            Constructor<?> constructor = type("com.jedflix.tv.RdAccountClient").getDeclaredConstructor(String.class);
            constructor.setAccessible(true); return constructor.newInstance("http://127.0.0.1:" + server.getLocalPort() + "/user");
        }
        @Override public void close() throws Exception { release.countDown(); server.close(); responder.join(6000); }
    }
}
