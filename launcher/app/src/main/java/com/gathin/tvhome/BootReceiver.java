package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

/**
 * After a reboot or an update of this app, Android/TCL may leave the remote-button service unbound.
 * Re-enable it a little after boot (the TV settles accessibility late), and again a bit later to be sure.
 * TCL only delivers this when the app may auto-start: install.sh runs `appops set … APP_AUTO_START allow`.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        final Context app = ctx.getApplicationContext();
        final PendingResult pending = goAsync();
        Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(() -> MainActivity.ensureKeyRemap(app), 3_000);
        h.postDelayed(() -> {
            MainActivity.ensureKeyRemap(app);
            pending.finish();
        }, 9_000);
    }
}
