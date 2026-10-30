package com.jedflix.tv;

import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Installed separately, never packaged in Jedflix. Exercises real APK validation. */
public final class UpdateProbe extends Instrumentation {
    private Bundle args;
    private int checks;
    private Context target;
    private UpdateClient.Release release;
    private File candidate;
    private JSONObject manifest;
    private void check(boolean value,String message) { if (!value) throw new AssertionError(message); checks++; }
    private interface Attempt { void run() throws Exception; }
    private void rejected(Attempt attempt,String message) throws Exception {
        boolean failed=false; try { attempt.run(); } catch (IOException | JSONException expected) { failed=true; }
        check(failed,message);
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); this.args=args; start(); }
    @Override public void onStart() {
        Bundle report=new Bundle();
        try {
            target=getTargetContext();
            if ("after".equals(args.getString("phase"))) {
                android.content.SharedPreferences saved=target.getSharedPreferences("jedflix_update_probe",0);
                check("preserved-through-self-update".equals(saved.getString("marker","")),"private preferences survived");
                check(configDigest().equals(saved.getString("comet","")),"encrypted Comet configuration survived unchanged");
                report.putString("result","PASS: "+checks+" post-update persistence checks"); finish(Activity.RESULT_OK,report); return;
            }
            candidate=new File(args.getString("candidate"));
            manifest=new JSONObject(read(new File(args.getString("manifest"))));
            String tag="v"+manifest.getString("versionName");
            release=UpdateClient.parseManifest(manifest,tag,target.getPackageName(),UpdateClient.code(UpdateClient.installed(target)),Build.SUPPORTED_ABIS,Build.VERSION.SDK_INT);
            check(release!=null,"candidate is newer than installed");
            if ("install".equals(args.getString("phase")) || "prepare".equals(args.getString("phase"))) {
                final File verified=new FakeClient().download(target,release,(r,t)->{});
                // Store a private marker and a digest of actual encrypted configuration;
                // neither credentials nor settings contents are printed.
                target.getSharedPreferences("jedflix_update_probe",0).edit().putString("marker","preserved-through-self-update")
                    .putString("comet",configDigest()).commit();
                runOnMainSync(()->{
                    JedflixUpdates updates=JedflixUpdates.get(target);
                    if (updates.busy) updates.cancel();
                    updates.release=release; updates.apk=verified; updates.status="Test update verified. Ready to install.";
                    updates.rememberReady();
                });
                if ("prepare".equals(args.getString("phase"))) {
                    report.putString("result","PASS: verified update persisted; open Settings → About → Manage updates");
                    finish(Activity.RESULT_OK,report); return;
                }
                Activity activity=startActivitySync(new Intent(target,UpdateActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                report.putString("ready","Real updater activity is ready for permission and install tests"); sendStatus(1,report);
                // Installing the new APK terminates this instrumentation process.
                long deadline=SystemClock.elapsedRealtime()+240000;
                while (SystemClock.elapsedRealtime()<deadline && !activity.isDestroyed()) Thread.sleep(250);
                throw new AssertionError("Install flow ended without a package replacement");
            }
            UpdateClient client=new UpdateClient();
            check(release.abi.equals("x86_64"),"correct emulator architecture");
            check(UpdateClient.parseManifest(manifest,tag,target.getPackageName(),release.code,Build.SUPPORTED_ABIS,36)==null,"equal code is not an update");
            check(UpdateClient.parseManifest(manifest,tag,target.getPackageName(),release.code+1,Build.SUPPORTED_ABIS,36)==null,"older code is not an update");
            rejected(()->UpdateClient.parseManifest(manifest,tag,"com.jedflix.tv",0,Build.SUPPORTED_ABIS,36),"legacy package cannot be a self-update");
            rejected(()->UpdateClient.parseManifest(manifest,tag,target.getPackageName(),0,new String[]{"armeabi-v7a"},36),"unsupported architecture rejected");
            rejected(()->UpdateClient.parseManifest(manifest,tag,target.getPackageName(),0,Build.SUPPORTED_ABIS,1),"minimum SDK enforced");
            rejected(()->UpdateClient.parseManifest(manifest,"v99.0.0",target.getPackageName(),0,Build.SUPPORTED_ABIS,36),"tag and manifest version must match");
            JSONObject invalid=new JSONObject(manifest.toString());
            invalid.getJSONObject("artifacts").getJSONObject("x86_64").put("url","https://evil.example/update.apk");
            rejected(()->UpdateClient.parseManifest(invalid,tag,target.getPackageName(),0,Build.SUPPORTED_ABIS,36),"foreign APK URL rejected");
            for (String url:new String[]{"http://github.com/file","https://github.com.evil.example/file","https://user@github.com/file","https://github.com:8080/file"})
                rejected(()->UpdateClient.trusted(new URL(url)),"unsafe URL rejected");
            FakeClient latest=new FakeClient();
            check(latest.latest(target).code==release.code,"GitHub release and manifest round trip");
            latest=new FakeClient(); latest.response=404; check(latest.latest(target)==null,"no releases is a valid empty result");
            final FakeClient limited=new FakeClient(); limited.response=403;
            rejected(()->limited.latest(target),"rate limit remains a failure");
            final FakeClient missing=new FakeClient(); missing.missing=true;
            rejected(()->missing.latest(target),"missing manifest is a failure");
            final FakeClient redirect=new FakeClient(); redirect.redirect=true;
            rejected(()->redirect.latest(target),"HTTP redirect rejected");
            client.verify(target,candidate,release); check(true,"real APK digest, package, version, ABI and signer validated");
            File verified=new FakeClient().download(target,release,(r,t)->{});
            check(verified.length()==release.size,"real APK streamed into private cache");
            FakeClient cached=new FakeClient(); cached.download(target,release,(r,t)->{});
            check(cached.opens==0,"verified matching download reused");
            try (RandomAccessFile tamper=new RandomAccessFile(verified,"rw")) { tamper.seek(123); tamper.write(0x79); }
            FakeClient repaired=new FakeClient(); repaired.download(target,release,(r,t)->{});
            check(repaired.opens==1,"same-size corrupt cache is redownloaded");
            verified.delete();
            final FakeClient truncated=new FakeClient(); truncated.truncate=true;
            rejected(()->truncated.download(target,release,(r,t)->{}),"truncated download rejected");
            check(noPartials(),"truncated partial removed");
            final FakeClient cancelled=new FakeClient();
            rejected(()->cancelled.download(target,release,(r,t)->cancelled.cancel()),"mid-download cancellation stops request");
            check(noPartials(),"cancelled partial removed");
            File wrongSigner=new File(args.getString("wrongSigner"));
            UpdateClient.Release wrong=new UpdateClient.Release(release.code,wrongSigner.length(),release.version,"",release.abi,release.url,hash(wrongSigner));
            rejected(()->new UpdateClient().verify(target,wrongSigner,wrong),"real APK with a different signing key rejected");
            UpdateClient.Release wrongVersion=new UpdateClient.Release(release.code+1,release.size,release.version,"",release.abi,release.url,release.sha256);
            rejected(()->new UpdateClient().verify(target,candidate,wrongVersion),"APK and manifest code mismatch rejected");
            UpdateClient.Release wrongAbi=new UpdateClient.Release(release.code,release.size,release.version,"","arm64-v8a",release.url,release.sha256);
            rejected(()->new UpdateClient().verify(target,candidate,wrongAbi),"actual native libraries must match selected ABI");
            File finalFile=new FakeClient().download(target,release,(r,t)->{});
            UriTest(finalFile);
            final JedflixUpdates manager=JedflixUpdates.get(target);
            runOnMainSync(()->{
                if (manager.busy) manager.cancel();
                manager.release=release; manager.apk=finalFile; manager.rememberReady();
                manager.release=null; manager.apk=null;
                check(manager.restoreReady(),"persisted update can restore after losing process memory");
            });
            long deadline=SystemClock.elapsedRealtime()+15000;
            while (manager.busy && SystemClock.elapsedRealtime()<deadline) Thread.sleep(50);
            check(manager.apk!=null && manager.release.code==release.code,"restored APK is revalidated and ready without network");
            report.putString("result","PASS: "+checks+" update checks"); finish(Activity.RESULT_OK,report);
        } catch (Throwable error) {
            StringWriter trace=new StringWriter(); error.printStackTrace(new PrintWriter(trace));
            report.putString("error",trace.toString()); finish(Activity.RESULT_CANCELED,report);
        }
    }
    private void UriTest(File file) throws Exception {
        android.net.Uri uri=androidx.core.content.FileProvider.getUriForFile(target,target.getPackageName()+".updates",file);
        try (InputStream in=target.getContentResolver().openInputStream(uri)) { check(in.read()=='P',"private provider serves verified APK"); }
        rejected(()->{
            try { androidx.core.content.FileProvider.getUriForFile(target,target.getPackageName()+".updates",new File(target.getFilesDir(),"credentials")); }
            catch (IllegalArgumentException expected) { throw new IOException(expected); }
        },"provider excludes credentials and other private files");
    }
    private boolean noPartials() { File[] files=new File(target.getCacheDir(),"updates").listFiles(); if(files!=null) for(File f:files) if(f.getName().endsWith(".part")) return false; return true; }
    private String configDigest() throws Exception {
        File file=new File(target.getNoBackupFilesDir(),"comet-settings");
        return file.exists() ? hash(file) : "absent";
    }
    private static String hash(File file) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new FileInputStream(file)) { byte[] buffer=new byte[65536]; int n; while((n=in.read(buffer))!=-1) digest.update(buffer,0,n); }
        StringBuilder out=new StringBuilder(); for(byte b:digest.digest()) out.append(String.format(Locale.ROOT,"%02x",b&255)); return out.toString();
    }
    private static String read(File file) throws Exception {
        try(InputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[8192]; int n; while((n=in.read(buffer))!=-1) out.write(buffer,0,n); return out.toString("UTF-8");
        }
    }
    private final class FakeClient extends UpdateClient {
        int response=200,opens; boolean missing,redirect,truncate;
        @Override protected HttpURLConnection open(URL url) throws IOException {
            opens++;
            return new HttpURLConnection(url) {
                @Override public int getResponseCode() { return redirect ? 302 : response; }
                @Override public String getHeaderField(String name) { return "http://github.com/insecure"; }
                @Override public long getContentLengthLong() { return url.toString().endsWith(".apk") ? candidate.length() : -1; }
                @Override public InputStream getInputStream() throws IOException {
                    try {
                        if(url.toString().endsWith(".apk")) {
                            if(truncate) return new ByteArrayInputStream(new byte[32]);
                            return new FileInputStream(candidate);
                        }
                        if(url.toString().endsWith(".json")) return new ByteArrayInputStream(manifest.toString().getBytes("UTF-8"));
                        JSONObject latest=new JSONObject().put("tag_name","v"+release.version).put("draft",false).put("prerelease",false);
                        JSONArray assets=new JSONArray();
                        if(!missing) assets.put(new JSONObject().put("name","jedflix-update.json").put("browser_download_url",UpdateClient.RELEASES+"v"+release.version+"/jedflix-update.json"));
                        latest.put("assets",assets); return new ByteArrayInputStream(latest.toString().getBytes("UTF-8"));
                    } catch (Exception error) { throw new IOException(error); }
                }
                @Override public void disconnect() {}
                @Override public boolean usingProxy() { return false; }
                @Override public void connect() {}
            };
        }
    }
}
