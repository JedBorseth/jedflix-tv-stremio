package com.jedflix.tv;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.TextView;

/** Foreground restart handoff: survives the native core's process being stopped. */
public final class CometRestartActivity extends Activity {
    public static boolean isRestartProcess(Context context) {
        if (android.os.Build.VERSION.SDK_INT >= 28) return Application.getProcessName().endsWith(":restart");
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        java.util.List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
        if (processes != null) for (ActivityManager.RunningAppProcessInfo process : processes)
            if (process.pid == android.os.Process.myPid()) return process.processName.endsWith(":restart");
        return false;
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        TextView message = new TextView(this);
        message.setText("Applying Real-Debrid settings…"); message.setTextColor(android.graphics.Color.WHITE);
        message.setTextSize(24); message.setGravity(Gravity.CENTER);
        message.setBackgroundColor(android.graphics.Color.BLACK); setContentView(message);
        int oldPid = getIntent().getIntExtra("oldPid", -1);
        if (oldPid > 0 && oldPid != android.os.Process.myPid()) android.os.Process.killProcess(oldPid);
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() { @Override public void run() {
            Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(launch);
            }
            finish();
        }}, 350);
    }
}
