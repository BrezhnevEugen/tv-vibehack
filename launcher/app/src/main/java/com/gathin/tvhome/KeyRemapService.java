package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.Map;

/**
 * Remaps the remote's app buttons (res/values/config.xml, key_remaps: "keycode=package"; changed on the TV in
 * Settings → Кнопки пульта, Prefs).
 * The TV firmware launches the button's app in interceptKeyBeforeDispatching, which runs after the
 * accessibility key filter, so consuming the key here stops the stock app from opening.
 * Enable with: settings put secure enabled_accessibility_services com.gathin.tvhome/.KeyRemapService
 */
public class KeyRemapService extends AccessibilityService {

    private Map<Integer, String> remaps;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        remaps = Prefs.keyRemaps(this);
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (remaps == null) return false;
        // Re-read on each press of a remapped button, so a change in Settings applies at once.
        if (remaps.containsKey(event.getKeyCode()) && event.getAction() == KeyEvent.ACTION_DOWN) remaps = Prefs.keyRemaps(this);
        String pkg = remaps.get(event.getKeyCode());
        if (pkg == null || pkg.isEmpty()) return false;   // "" = leave the button to the TV
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
