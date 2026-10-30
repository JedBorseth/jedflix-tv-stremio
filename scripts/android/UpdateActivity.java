package com.jedflix.tv;

import android.app.Activity;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import androidx.core.content.FileProvider;

/** Foreground installer flow. Android owns confirmation; the main launcher stays unchanged. */
public final class UpdateActivity extends Activity {
    private static final int PERMISSION=70, INSTALL=71;
    private JedflixUpdates updates;
    private TextView status, notes;
    private Button primary, check, cancel;
    private boolean waitingPermission, installing, wasDownloading;
    private final Runnable observer=()->render();
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        updates=JedflixUpdates.get(this);
        waitingPermission=saved!=null && saved.getBoolean("permission");
        installing=saved!=null && saved.getBoolean("installing");
        LinearLayout body=new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(64),dp(36),dp(64),dp(36)); body.setBackgroundColor(Color.rgb(14,14,14));
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.addView(body); setContentView(scroll);
        label(body,"Jedflix updates",30);
        label(body,"Installed: "+JedflixBuild.VERSION_NAME+" • Based on Stremio 1.10.4",18);
        status=label(body,"",21); status.setPadding(0,dp(22),0,dp(12));
        notes=label(body,"",18);
        primary=button(body,"Download and install",()->{
            if (installing || updates.busy) return;
            if (updates.release==null) updates.check(null);
            else if (updates.apk!=null) install();
            else updates.download();
        });
        check=button(body,"Check for updates",()->updates.check(null));
        cancel=button(body,"Cancel download",()->updates.cancel());
        button(body,"Back to settings",()->finish());
        updates.listen(observer); render(); primary.requestFocus();
        if (updates.release==null && !updates.busy && !updates.restoreReady() && saved==null) updates.check(null);
    }
    private int dp(int value) { return (int)(getResources().getDisplayMetrics().density*value+0.5f); }
    private TextView label(LinearLayout body,String text,int size) {
        TextView view=new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(Color.WHITE);
        body.addView(view,new LinearLayout.LayoutParams(-1,-2)); return view;
    }
    private Button button(LinearLayout body,String title,Runnable action) {
        Button button=new Button(this); button.setText(title); button.setAllCaps(false);
        button.setOnClickListener(v->action.run());
        LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(dp(360),dp(54)); layout.topMargin=dp(8);
        body.addView(button,layout); return button;
    }
    private boolean allowed() { return Build.VERSION.SDK_INT<26 || getPackageManager().canRequestPackageInstalls(); }
    private void render() {
        if (isDestroyed()) return;
        status.setText(updates.status);
        notes.setText(updates.release==null ? "Updates come from the Jedflix GitHub releases." : "New version: "+updates.release.version+"\n"+updates.release.notes);
        primary.setText(updates.release==null ? "Check for updates" : updates.apk==null ? "Download and install" : allowed() ? "Install update" : "Allow installs");
        primary.setEnabled(!updates.busy && !installing); check.setEnabled(!updates.busy && !installing);
        cancel.setEnabled(updates.downloading);
        boolean completed=wasDownloading && !updates.downloading && updates.apk!=null;
        wasDownloading=updates.downloading;
        if ((completed || waitingPermission && allowed() && updates.apk!=null) && !isFinishing() && hasWindowFocus()) {
            waitingPermission=false; install();
        }
    }
    private void install() {
        if (updates.apk==null || updates.release==null || installing) return;
        if (!allowed()) {
            updates.rememberReady();
            updates.message("Allow Jedflix to install updates, then return here to continue.");
            waitingPermission=true;
            try { startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())),PERMISSION); }
            catch (ActivityNotFoundException error) { waitingPermission=false; updates.message("Open Android Settings → Apps → Special app access → Install unknown apps and allow Jedflix."); }
            return;
        }
        // Hash/certificate verification already completed off the UI thread. Android
        // rechecks the package and signer when it reads this private, read-only URI.
        Uri uri=FileProvider.getUriForFile(this,getPackageName()+".updates",updates.apk);
        Intent intent=new Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri,"application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_RETURN_RESULT,true);
        intent.setClipData(ClipData.newRawUri("Jedflix update",uri));
        try { installing=true; updates.message("Confirm the update in Android's installer."); startActivityForResult(intent,INSTALL); }
        catch (RuntimeException error) { installing=false; updates.message("Android could not open the installer. Try again or install the APK manually."); }
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if (request==PERMISSION) {
            waitingPermission=allowed() && updates.apk==null;
            if (allowed()) install(); else updates.message("Installation permission is still off. Choose Allow installs to retry.");
        }
        if (request==INSTALL) {
            installing=false;
            updates.message(result==RESULT_OK ? "Update installed. Open Jedflix from your TV launcher." : result==RESULT_CANCELED
                ? "Installation cancelled. The verified download is ready to retry." : "Android could not install this update. Check free storage and try again.");
        }
        render();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("permission",waitingPermission); state.putBoolean("installing",installing); super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() {
        updates.unlisten(observer);
        if (updates.downloading) updates.cancel();
        super.onDestroy();
    }
}
