package com.qinghaipao.autorun;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Restarts the backend after a reboot or an app update.
 *
 * <p>Without this, every reboot silently ends the schedule: the foreground service
 * is gone and no alarm survives. The persisted tasks would still be there, waiting
 * for a process that never starts.
 */
public final class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : String.valueOf(intent.getAction());
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)
                && !"com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }
        Log.i("Autorun/Boot", "restarting service after " + action);
        try {
            AutorunService.start(context);
        } catch (Exception e) {
            Log.e("Autorun/Boot", "could not start service", e);
        }
    }
}
