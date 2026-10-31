package com.jedflix.tv;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import androidx.core.content.FileProvider;
import java.io.*;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/**
 * One serialized updater per process. Settings → About shows a single action whose
 * label follows the current state: check, download, install, or cancel a download.
 * All observable state belongs to the main thread.
 */
public final class JedflixUpdates {
    private static final String MAIN="com.stremio.tv.MainActivity";
    private static JedflixUpdates instance;
    private final Context app;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Set<Runnable> listeners=new HashSet<>();
    private final SharedPreferences prefs;
    private UpdateClient client;
    private int generation;
    public boolean busy, downloading;
    /** The user asked to install; continue once the download or permission step finishes. */
    private boolean installRequested;
    /** The Android installer was opened; cleared when Jedflix is resumed. */
    private boolean installing;
    public String status="Check for a newer Jedflix release.";
    public UpdateClient.Release release;
    public File apk;

    private JedflixUpdates(Context context) {
        app=context.getApplicationContext(); prefs=app.getSharedPreferences("jedflix_updates",Context.MODE_PRIVATE);
    }
    public static synchronized JedflixUpdates get(Context context) {
        if (instance==null) instance=new JedflixUpdates(context); return instance;
    }
    public static void init(Application app) {
        final JedflixUpdates updates=get(app);
        // Restore a verified download after process death, without repeating the network request.
        if (!updates.prefs.getString("ready","").isEmpty()) updates.restoreReady();
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            WeakReference<Activity> owner=new WeakReference<>(null);
            boolean scheduled;
            @Override public void onActivityResumed(Activity activity) {
                if (!MAIN.equals(activity.getClass().getName())) return;
                if ("com.stremio.one".equals(app.getPackageName())) {
                    activity.startActivity(new Intent(activity,PackageMigrationActivity.class));
                    activity.finish();
                    return;
                }
                owner=new WeakReference<>(activity);
                updates.returned();
                if (scheduled) return;
                scheduled=true;
                updates.main.postDelayed(()->updates.dailyCheck(owner),8000);
            }
            @Override public void onActivityPaused(Activity activity) { if (owner.get()==activity) owner.clear(); }
            @Override public void onActivityCreated(Activity a,Bundle b) {}
            @Override public void onActivityStarted(Activity a) {}
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a,Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
    }
    private void dailyCheck(WeakReference<Activity> owner) {
        long last=prefs.getLong("checkedAt",0), now=System.currentTimeMillis();
        if (now>=last && now-last<86400000L) return;
        check(()->{
            Activity activity=owner.get();
            UpdateClient.Release found=release;
            if (activity==null || activity.isFinishing() || activity.isDestroyed() || found==null
                || !MAIN.equals(activity.getClass().getName()) || prefs.getLong("dismissed",0)==found.code) return;
            new AlertDialog.Builder(activity).setTitle("Jedflix "+found.version+" is available")
                .setMessage("Update now, or later from Settings → About.")
                .setPositiveButton("Update now",(dialog,which)->updateNow())
                .setNegativeButton("Later",(dialog,which)->prefs.edit().putLong("dismissed",found.code).apply()).show();
        });
    }
    public void listen(Runnable listener) { listeners.add(listener); }
    public void unlisten(Runnable listener) { listeners.remove(listener); }
    private void changed() { for (Runnable listener:new ArrayList<>(listeners)) listener.run(); }

    /** Label for the single update action shown in Settings → About. */
    public String actionLabel() {
        if (downloading) return "Cancel download";
        if (busy) return "Please wait…";
        if (release==null) return "Check for updates";
        if (apk==null) return "Download "+release.version;
        return "Install "+release.version;
    }
    /** Runs the action matching {@link #actionLabel()}. */
    public void action() {
        if (downloading) cancel();
        else if (busy) return;
        else if (release==null) check(null);
        else if (apk==null) { installRequested=false; download(); }
        else { installRequested=true; installApk(); }
        changed();
    }
    /** Used by the update prompt: download if needed, then open the installer. */
    public void updateNow() {
        if (release==null) return;
        installRequested=true;
        if (apk!=null) installApk(); else download();
        changed();
    }

    public void rememberReady() {
        if (release==null || apk==null) return;
        try {
            JSONObject artifact=new JSONObject().put("url",release.url).put("sha256",release.sha256).put("size",release.size);
            JSONObject saved=new JSONObject().put("schemaVersion",1).put("packageName",app.getPackageName())
                .put("versionCode",release.code).put("versionName",release.version).put("minSdk",24).put("notes",release.notes)
                .put("artifacts",new JSONObject().put(release.abi,artifact));
            // Permission changes can immediately kill the process. Persist before
            // opening Android Settings, then revalidate the file on restoration.
            prefs.edit().putString("ready",saved.toString()).commit();
        } catch (org.json.JSONException error) { throw new IllegalStateException(error); }
    }
    public boolean restoreReady() {
        if (busy || release!=null) return false;
        final String saved=prefs.getString("ready","");
        if (saved.isEmpty()) return false;
        final int token=++generation; final UpdateClient operation=new UpdateClient(); client=operation;
        busy=true; status="Restoring verified update…"; changed();
        worker.execute(()->{
            UpdateClient.Release selected=null; File file=null; Exception failure=null;
            try {
                JSONObject metadata=new JSONObject(saved);
                selected=UpdateClient.parseManifest(metadata,"v"+metadata.getString("versionName"),app.getPackageName(),
                    UpdateClient.code(UpdateClient.installed(app)),Build.SUPPORTED_ABIS,Build.VERSION.SDK_INT);
                if (selected!=null) {
                    File cached=new File(new File(app.getCacheDir(),"updates"),selected.code+"-"+selected.sha256+".apk");
                    if (cached.isFile()) { operation.verify(app,cached,selected); file=cached; }
                }
            } catch (Exception error) { failure=error; }
            final UpdateClient.Release result=selected; final File ready=file; final Exception failed=failure;
            main.post(()->{
                if (generation!=token) return;
                client=null; busy=false; release=result; apk=ready;
                if (failed!=null || result==null) prefs.edit().remove("ready").apply();
                status=failed!=null ? error(failed) : ready!=null ? "Update verified. Ready to install."
                    : result!=null ? "Download the update again to continue." : "No pending update. Check for a newer release.";
                changed();
            });
        });
        return true;
    }
    private static String error(Exception error) {
        if (error instanceof org.json.JSONException) return "The published update metadata is invalid. Contact the app owner.";
        if (error instanceof java.net.SocketTimeoutException || error instanceof java.net.UnknownHostException)
            return "Cannot reach GitHub. Check your connection and try again.";
        return error.getMessage()==null ? "Update failed. Try again." : error.getMessage();
    }
    public void check(Runnable completion) {
        if (busy) return;
        final int token=++generation; final UpdateClient operation=new UpdateClient(); client=operation;
        busy=true; status="Checking GitHub for updates…"; changed();
        worker.execute(()->{
            UpdateClient.Release found=null; Exception failure=null;
            try { found=operation.latest(app); } catch (Exception e) { failure=e; }
            final UpdateClient.Release result=found; final Exception failed=failure;
            main.post(()->{
                if (generation!=token) return;
                busy=false; client=null; release=result; apk=null;
                if (failed==null) {
                    prefs.edit().putLong("checkedAt",System.currentTimeMillis()).apply();
                    status=result==null ? "No newer Jedflix release is available." : "Jedflix "+result.version+" is available.";
                } else status=error(failed);
                changed(); if (completion!=null) completion.run();
            });
        });
    }
    public void download() {
        if (busy || release==null) return;
        final int token=++generation; final UpdateClient operation=new UpdateClient(); client=operation;
        final UpdateClient.Release selected=release;
        busy=true; downloading=true; status="Preparing Jedflix "+selected.version+"…"; changed();
        worker.execute(()->{
            File file=null; Exception failure=null;
            try {
                final long[] last={0};
                file=operation.download(app,selected,(read,total)->{
                    long now=SystemClock.elapsedRealtime();
                    if (now-last[0]<250 && read!=total) return;
                    last[0]=now;
                    main.post(()->{ if (token==generation) { status="Downloading update: "+read*100/total+"%"; changed(); }});
                });
            } catch (Exception e) { failure=e; }
            final File result=file; final Exception failed=failure;
            main.post(()->{
                if (generation!=token) return;
                busy=false; downloading=false; client=null; apk=result;
                if (result!=null) rememberReady();
                if (failed!=null) {
                    installRequested=false; status=error(failed);
                } else {
                    status="Update verified. Ready to install.";
                    if (installRequested) installApk();
                }
                changed();
            });
        });
    }
    public void cancel() {
        generation++; if (client!=null) client.cancel(); client=null;
        busy=false; downloading=false; installRequested=false;
        status="Download cancelled. You can retry at any time."; changed();
    }

    private boolean allowed() {
        return Build.VERSION.SDK_INT<26 || app.getPackageManager().canRequestPackageInstalls();
    }
    /** Opens Android's installer, or the unknown-sources permission first. Runs on the main thread. */
    private void installApk() {
        if (apk==null || release==null) return;
        if (!allowed()) {
            rememberReady();
            status="Allow Jedflix to install updates. Return here to continue.";
            try {
                app.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+app.getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException error) {
                installRequested=false;
                status="Allow installs for Jedflix in Android Settings, then try again.";
            }
            return;
        }
        try {
            // Hash and certificate checks already passed off the UI thread. Android rechecks the
            // package and signer when it reads this read-only, app-private URI.
            Uri uri=FileProvider.getUriForFile(app,app.getPackageName()+".updates",apk);
            Intent intent=new Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri,"application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.setClipData(ClipData.newRawUri("Jedflix update",uri));
            installRequested=false; installing=true;
            status="Confirm the update in Android's installer.";
            app.startActivity(intent);
        } catch (RuntimeException error) {
            installing=false; installRequested=false;
            status="Android could not open the installer. Try again.";
        }
    }
    /** Called when the main Jedflix activity resumes: after the installer or permission screen closes. */
    private void returned() {
        if (installing) {
            installing=false;
            status="Installation was not completed. The verified update is still ready.";
            changed();
        } else if (installRequested && apk!=null && allowed()) {
            installApk();
            changed();
        }
    }
}
