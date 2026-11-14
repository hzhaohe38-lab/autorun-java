package com.qinghaipao.autorun;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Alarm target. Android thaws a frozen process to deliver a broadcast, so this
 * receiver is what makes a due task actually execute while the app sits in the
 * background untouched.
 *
 * <p>The previous Node build needed a 3-minute heartbeat just to keep the JS timer
 * from being starved; here the alarm carries the real target time, so delivery is
 * the trigger and this receiver only has to start the service and let the engines
 * scan. Tasks are persisted, so even a missed delivery is recoverable: the next
 * wake still sees the pending task and can still act inside the 15-minute window.
 */
public final class WakeReceiver extends BroadcastReceiver {

    private static final String TAG = "Autorun/Wake";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "alarm fired: " + (intent == null ? "-" : intent.getAction()));
        try {
            AutorunService.tick(context);
        } catch (Exception e) {
            Log.e(TAG, "could not start service from alarm", e);
        }
    }
}
