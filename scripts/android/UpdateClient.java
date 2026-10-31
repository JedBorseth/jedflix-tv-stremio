package com.jedflix.tv;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.SystemClock;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipFile;
import org.json.*;

/** GitHub transport and validation. No installer or UI state lives here. */
public class UpdateClient {
    static final String REPO = JedflixBuild.REPOSITORY;
    static final String RELEASES = "https://github.com/" + REPO + "/releases/download/";
    private final boolean migration;
    public UpdateClient() { this(false); }
    private UpdateClient(boolean migration) { this.migration=migration; }
    public static UpdateClient migration() { return new UpdateClient(true); }
    private String targetPackage(Context context) {
        return migration ? JedflixBuild.APPLICATION_ID : context.getPackageName();
    }
    private long installedCode(Context context) throws Exception {
        if (!migration) return code(installed(context));
        if (!"com.stremio.one".equals(context.getPackageName())) throw new IOException("Migration is only available from the old Jedflix package.");
        try { return code(context.getPackageManager().getPackageInfo(targetPackage(context),signingFlags())); }
        catch (PackageManager.NameNotFoundException missing) { return 0; }
    }
    private volatile boolean cancelled;
    private volatile HttpURLConnection connection;
    public interface Progress { void changed(long read, long total); }

    public static final class Release {
        public final long code, size;
        public final String version, notes, abi, url, sha256;
        Release(long code, long size, String version, String notes, String abi, String url, String sha256) {
            this.code=code; this.size=size; this.version=version; this.notes=notes;
            this.abi=abi; this.url=url; this.sha256=sha256;
        }
    }
    static long code(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }
    static PackageInfo installed(Context context) throws Exception {
        return context.getPackageManager().getPackageInfo(context.getPackageName(), signingFlags());
    }
    static int signingFlags() { return Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES; }
    public void cancel() { cancelled=true; HttpURLConnection active=connection; if (active!=null) active.disconnect(); }
    private void active() throws IOException { if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Update cancelled"); }

    // Protected only so on-device instrumentation can supply deterministic network failures.
    protected HttpURLConnection open(URL url) throws IOException { return (HttpURLConnection)url.openConnection(); }
    static void trusted(URL url) throws IOException {
        String host=url.getHost();
        if (!"https".equals(url.getProtocol()) || url.getUserInfo()!=null || (url.getPort()!=-1 && url.getPort()!=443)
                || !(host.equals("api.github.com") || host.equals("github.com")
                || host.equals("release-assets.githubusercontent.com") || host.equals("objects.githubusercontent.com")))
            throw new IOException("Update address is not a trusted GitHub HTTPS address.");
    }
    private HttpURLConnection request(String address, boolean notFound) throws Exception {
        URL url=new URL(address);
        for (int redirects=0; redirects<6; redirects++) {
            active(); trusted(url);
            HttpURLConnection c=open(url); connection=c;
            c.setConnectTimeout(15000); c.setReadTimeout(30000); c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "Jedflix/"+JedflixBuild.VERSION_NAME);
            c.setRequestProperty("Accept", "application/octet-stream, application/vnd.github+json");
            int status;
            try { status=c.getResponseCode(); }
            catch (IOException failure) { c.disconnect(); connection=null; throw failure; }
            if (status==301 || status==302 || status==303 || status==307 || status==308) {
                String location=c.getHeaderField("Location"); c.disconnect();
                if (location==null) throw new IOException("Missing update redirect.");
                url=new URL(url, location); continue;
            }
            if (notFound && status==404) { c.disconnect(); return null; }
            if (status!=200) {
                c.disconnect();
                throw new IOException(status==403 || status==429 ? "GitHub is limiting requests. Try again later." : "Update request failed (HTTP "+status+").");
            }
            return c;
        }
        throw new IOException("Too many update redirects.");
    }
    private String json(String url, boolean notFound) throws Exception {
        HttpURLConnection c=request(url, notFound);
        if (c==null) return null;
        try (InputStream in=c.getInputStream(); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[8192]; int n;
            while ((n=in.read(buffer))!=-1) {
                active(); if (out.size()+n>262144) throw new IOException("Update metadata is too large.");
                out.write(buffer,0,n);
            }
            return out.toString("UTF-8");
        } finally { c.disconnect(); connection=null; }
    }
    public Release latest(Context context) throws Exception {
        String body=json("https://api.github.com/repos/"+REPO+"/releases/latest",true);
        if (body==null) return null;
        JSONObject release=new JSONObject(body);
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null;
        String tag=release.getString("tag_name");
        if (!tag.matches("v[0-9]+\\.[0-9]+\\.[0-9]+")) throw new IOException("Release tag must be vMAJOR.MINOR.PATCH.");
        String prefix=RELEASES+tag+"/";
        JSONArray assets=release.getJSONArray("assets");
        String manifest=null;
        for (int i=0;i<assets.length();i++) {
            JSONObject asset=assets.getJSONObject(i);
            if (JedflixBuild.UPDATE_MANIFEST.equals(asset.optString("name"))) {
                manifest=asset.getString("browser_download_url");
                if (!manifest.equals(prefix+JedflixBuild.UPDATE_MANIFEST)) throw new IOException("Unexpected update manifest address.");
            }
        }
        if (manifest==null) throw new IOException("This release has no Jedflix update manifest.");
        return parseManifest(new JSONObject(json(manifest,false)),tag,targetPackage(context),
            installedCode(context),Build.SUPPORTED_ABIS,Build.VERSION.SDK_INT);
    }
    static Release parseManifest(JSONObject m,String tag,String packageName,long installed,String[] abis,int sdk) throws Exception {
        if (m.getInt("schemaVersion")!=1 || !packageName.equals(m.getString("packageName"))) throw new IOException("Update is for a different app.");
        long code=m.getLong("versionCode");
        String version=m.getString("versionName");
        if (!("v"+version).equals(tag) || code<=0 || code>2100000000L) throw new IOException("Invalid release version.");
        if (code<=installed) return null;
        if (m.getInt("minSdk")>sdk) throw new IOException("This update needs a newer Android version.");
        JSONObject choices=m.getJSONObject("artifacts"), artifact=null;
        String abi=null;
        for (String supported:abis) if (choices.has(supported)) { abi=supported; artifact=choices.getJSONObject(supported); break; }
        if (artifact==null) throw new IOException("No update is available for this TV's processor.");
        String url=artifact.getString("url"), hash=artifact.getString("sha256");
        long size=artifact.getLong("size");
        String expected=RELEASES+tag+"/"+JedflixBuild.APK_PREFIX+"-"+version+"-"+abi+".apk";
        if (!url.equals(expected) || !hash.matches("[0-9a-f]{64}") || size<=0 || size>1073741824L)
            throw new IOException("Invalid update file metadata.");
        return new Release(code,size,version,m.optString("notes", "").substring(0,Math.min(4000,m.optString("notes", "").length())),abi,url,hash);
    }
    public File download(Context context,Release release,Progress progress) throws Exception {
        active();
        File directory=new File(context.getCacheDir(),"updates");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create update storage.");
        File destination=new File(directory,release.code+"-"+release.sha256+".apk");
        File[] old=directory.listFiles();
        if (old!=null) for (File f:old) if (!f.equals(destination)) f.delete();
        if (destination.exists()) {
            try { verify(context,destination,release); return destination; }
            catch (Exception error) { active(); destination.delete(); }
        }
        if (directory.getUsableSpace()<release.size*2+16777216L) throw new IOException("Free some TV storage before updating.");
        File partial=new File(directory,destination.getName()+".part");
        try {
            HttpURLConnection c=request(release.url,false);
            try (InputStream in=c.getInputStream(); FileOutputStream out=new FileOutputStream(partial)) {
                long length=c.getContentLengthLong();
                if (length>=0 && length!=release.size) throw new IOException("Update size differs from the release manifest.");
                byte[] buffer=new byte[65536]; long read=0, deadline=SystemClock.elapsedRealtime()+900000; int n;
                while ((n=in.read(buffer))!=-1) {
                    active(); read+=n;
                    if (read>release.size || SystemClock.elapsedRealtime()>deadline) throw new IOException("Update download is incomplete. Try again.");
                    out.write(buffer,0,n); progress.changed(read,release.size);
                }
                out.getFD().sync();
            } finally { c.disconnect(); connection=null; }
            verify(context,partial,release); active();
            if (!partial.renameTo(destination)) throw new IOException("Cannot save downloaded update.");
            return destination;
        } finally { partial.delete(); }
    }
    public void verify(Context context,File file,Release release) throws Exception {
        active();
        if (file.length()!=release.size) throw new IOException("Update download is incomplete. Try again.");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try (InputStream in=new FileInputStream(file)) {
            byte[] buffer=new byte[65536]; int n;
            while ((n=in.read(buffer))!=-1) { active(); digest.update(buffer,0,n); }
        }
        StringBuilder hash=new StringBuilder(); for (byte b:digest.digest()) hash.append(String.format(Locale.ROOT,"%02x",b&255));
        if (!hash.toString().equals(release.sha256)) throw new IOException("Update checksum failed. Check for updates and retry.");
        PackageInfo apk=context.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(),signingFlags());
        PackageInfo current=installed(context);
        if (apk==null || !targetPackage(context).equals(apk.packageName)) throw new IOException("Downloaded APK is for a different app.");
        if (code(apk)!=release.code || code(apk)<=installedCode(context) || !release.version.equals(apk.versionName)) throw new IOException("APK version does not match this update.");
        if (apk.applicationInfo==null || apk.applicationInfo.minSdkVersion>Build.VERSION.SDK_INT) throw new IOException("APK needs a newer Android version.");
        if (!signers(current).equals(signers(apk))) throw new IOException("Update signing key differs from the installed Jedflix app. Contact the app owner.");
        try (ZipFile zip=new ZipFile(file)) {
            Enumeration<? extends java.util.zip.ZipEntry> entries=zip.entries();
            while (entries.hasMoreElements()) {
                String name=entries.nextElement().getName();
                if (name.startsWith("lib/"+release.abi+"/") && name.endsWith(".so")) return;
            }
            throw new IOException("APK does not support this TV's processor.");
        }
    }
    private static Set<String> signers(PackageInfo info) throws IOException {
        Signature[] signatures=Build.VERSION.SDK_INT>=28 ? (info.signingInfo==null ? null : info.signingInfo.getApkContentsSigners()) : info.signatures;
        if (signatures==null || signatures.length==0) throw new IOException("Cannot verify update signing certificate.");
        Set<String> result=new HashSet<>(); for (Signature s:signatures) result.add(s.toCharsString()); return result;
    }
}
