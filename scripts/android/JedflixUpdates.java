package com.jedflix.tv;

import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** One serialized updater per process; all observable state belongs to the main thread. */
public final class JedflixUpdates {
    private static JedflixUpdates instance;
    private final Context app;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Set<Runnable> listeners=new HashSet<>();
    private final SharedPreferences prefs;
    private UpdateClient client;
    private int generation;
    public boolean busy, downloading;
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
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            WeakReference<Activity> resumed=new WeakReference<>(null);
            boolean scheduled;
            @Override public void onActivityResumed(Activity activity) {
                resumed=new WeakReference<>(activity);
                if (!activity.getClass().getName().equals("com.stremio.tv.MainActivity") || scheduled) return;
                scheduled=true;
                updates.main.postDelayed(()->{
                    long last=updates.prefs.getLong("checkedAt",0), now=System.currentTimeMillis();
                    if (now>=last && now-last<86400000L) return;
                    updates.check(()->{
                        Activity owner=resumed.get();
                        UpdateClient.Release release=updates.release;
                        if (owner==null || owner.isFinishing() || owner.isDestroyed() || release==null
                            || !owner.getClass().getName().equals("com.stremio.tv.MainActivity")
                            || updates.prefs.getLong("dismissed",0)==release.code) return;
                        new AlertDialog.Builder(owner).setTitle("Jedflix "+release.version+" is available")
                            .setMessage("A new version of Jedflix is ready. You can update now or later in Settings → About.")
                            .setPositiveButton("View update",(dialog,which)->open(owner))
                            .setNegativeButton("Later",(dialog,which)->updates.prefs.edit().putLong("dismissed",release.code).apply()).show();
                    });
                },8000);
            }
            @Override public void onActivityPaused(Activity activity) { if (resumed.get()==activity) resumed.clear(); }
            @Override public void onActivityCreated(Activity a,Bundle b) {}
            @Override public void onActivityStarted(Activity a) {}
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a,Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
    }
    public static void open(Context context) {
        Intent intent=new Intent(context,UpdateActivity.class);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
    public void listen(Runnable listener) { listeners.add(listener); }
    public void unlisten(Runnable listener) { listeners.remove(listener); }
    private void changed() { for (Runnable listener:new ArrayList<>(listeners)) listener.run(); }
    public void message(String text) { status=text; changed(); }
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
                    status=result==null ? "No newer published Jedflix update." : "Jedflix "+result.version+" is available.";
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
                status=failed==null ? "Update verified. Ready to install." : error(failed);
                changed();
            });
        });
    }
    public void cancel() {
        generation++; if (client!=null) client.cancel(); client=null;
        busy=false; downloading=false; status="Update cancelled. You can retry at any time."; changed();
    }
}
