package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Remaps the remote's app buttons (res/values/config.xml, key_remaps: "keycode=package").
 * The TV firmware launches the button's app in interceptKeyBeforeDispatching, which runs after the
 * accessibility key filter, so consuming the key here stops the stock app from opening.
 * Enable with: settings put secure enabled_accessibility_services com.gathin.tvhome/.KeyRemapService
 */
public class KeyRemapService extends AccessibilityService {

    private final Map<Integer, String> remaps = new HashMap<>();

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        for (String line : getResources().getStringArray(R.array.key_remaps)) {
            String[] kv = line.split("=", 2);
            if (kv.length != 2) continue;
            try {
                remaps.put(Integer.parseInt(kv[0].trim()), kv[1].trim());
            } catch (NumberFormatException ignored) {
            }
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        String pkg = remaps.get(event.getKeyCode());
        if (pkg == null) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                try {
                    startActivity(i);
                } catch (Exception ignored) {
                }
            }
        }
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
