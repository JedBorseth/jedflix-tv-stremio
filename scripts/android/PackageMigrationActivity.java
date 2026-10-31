package com.jedflix.tv;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.content.FileProvider;
import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Signed handoff for users of the original com.stremio.one fork. */
public final class PackageMigrationActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private TextView status;
    private Button action;
    private UpdateClient client;
    private File ready;
    private boolean busy, waitingPermission, installerOpened;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout page=new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL); page.setGravity(Gravity.CENTER);
        page.setPadding(100,70,100,70); page.setBackgroundColor(0xff090909);
        TextView title=new TextView(this); title.setText("Jedflix can now live alongside Stremio");
        title.setTextColor(0xffffffff); title.setTextSize(28); page.addView(title);
        TextView body=new TextView(this);
        body.setText("Install the separate Jedflix app to keep both apps on this TV. This old Jedflix stays installed until you choose to remove it. Configure Real-Debrid again in the new app; settings and library are not copied automatically.");
        body.setTextColor(0xffcccccc); body.setTextSize(18); body.setPadding(0,24,0,24); page.addView(body);
        status=new TextView(this); status.setTextColor(0xffcccccc); status.setTextSize(16); page.addView(status);
        action=new Button(this); action.setText("Install separate Jedflix"); action.setOnClickListener(v->move()); page.addView(action);
        Button close=new Button(this); close.setText("Close"); close.setOnClickListener(v->finish()); page.addView(close);
        setContentView(page); action.requestFocus();
    }

    private boolean openInstalled() {
        try {
            PackageInfo current=UpdateClient.installed(this);
            PackageInfo installed=getPackageManager().getPackageInfo(JedflixBuild.APPLICATION_ID,UpdateClient.signingFlags());
            Signature[] ours=android.os.Build.VERSION.SDK_INT>=28 ? current.signingInfo.getApkContentsSigners() : current.signatures;
            Signature[] theirs=android.os.Build.VERSION.SDK_INT>=28 ? installed.signingInfo.getApkContentsSigners() : installed.signatures;
            if (ours==null || theirs==null || ours.length==0 || theirs.length==0
                    || !new HashSet<>(Arrays.asList(ours)).equals(new HashSet<>(Arrays.asList(theirs))))
                throw new SecurityException("The installed separate Jedflix has an unexpected signing key.");
            Intent launch=getPackageManager().getLaunchIntentForPackage(JedflixBuild.APPLICATION_ID);
            if (launch==null) throw new IllegalStateException("The installed Jedflix has no launcher.");
            startActivity(launch); finish(); return true;
        } catch (android.content.pm.PackageManager.NameNotFoundException missing) { return false; }
        catch (Exception error) { status.setText(error.getMessage()); return false; }
    }

    @Override public void onResume() {
        super.onResume();
        if (openInstalled()) return;
        if (waitingPermission && allowed()) {
            waitingPermission=false; install();
        } else if (installerOpened) {
            installerOpened=false; status.setText("Installation was not completed. You can retry.");
        }
    }

    private void move() {
        if (busy || openInstalled()) return;
        if (ready!=null) { install(); return; }
        busy=true; action.setEnabled(false); status.setText("Finding the separate Jedflix…");
        client=UpdateClient.migration();
        worker.execute(()->{
            try {
                UpdateClient.Release release=client.latest(this);
                if (release==null) throw new IllegalStateException("No separate Jedflix release is available yet.");
                File apk=client.download(this,release,(read,total)->runOnUiThread(()->status.setText("Downloading Jedflix: "+read*100/total+"%")));
                runOnUiThread(()->{ if (isDestroyed()) return; busy=false; action.setEnabled(true); ready=apk; install(); });
            } catch (Exception error) {
                runOnUiThread(()->{ if (isDestroyed()) return; busy=false; action.setEnabled(true); status.setText(error.getMessage()); });
            }
        });
    }

    private void install() {
        if (ready==null) return;
        if (!allowed()) {
            waitingPermission=true; status.setText("Allow this Jedflix to install apps, then return here.");
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName()))); return;
        }
        Uri uri=FileProvider.getUriForFile(this,getPackageName()+".updates",ready);
        Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        installerOpened=true; startActivity(intent);
    }

    private boolean allowed() {
        return android.os.Build.VERSION.SDK_INT<26 || getPackageManager().canRequestPackageInstalls();
    }

    @Override public void onDestroy() {
        if (client!=null) client.cancel(); worker.shutdownNow(); super.onDestroy();
    }
}
