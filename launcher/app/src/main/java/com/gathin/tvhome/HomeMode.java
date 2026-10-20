package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Switches between this home screen and the TV's own one ("Original"), and turns the extra apps
 * (config.xml, debloat_apps) off or back on, from the TV itself through its own ADB (Adb). Same commands as
 * build-and-install.sh and tools/disable.sh, so a computer is only needed for the first install.
 * The idea and the safety checks come from Free My TV's HomeMode.
 */
final class HomeMode {

    interface Listener {
        /** Something to show while it runs (e.g. "accept the prompt on the TV"). Main thread. */
        void progress(String text);

        /** Done: a short summary, or what went wrong. Main thread. */
        void done(boolean ok, String text);
    }

    private static final String HOME = "com.gathin.tvhome/.MainActivity";
    private static final String DREAM = "com.gathin.tvhome/.ClockDream";

    /**
     * Never turned off, not even if someone adds them to debloat_apps: picture, sound, inputs, the remote, setup,
     * Google services and the store. (Free My TV's list, minus what our TCL list turns off on purpose.)
     */
    private static final Set<String> PROTECTED = new HashSet<>(Arrays.asList(
            "com.tcl.ttvs", "com.tvos", "com.tcl.systemserver", "com.tcl.systemui.plugin", "com.tcl.tv", "com.tcl.tvinput",
            "com.tcl.hearaid", "com.dolby.android.audio.service", "com.tcl.globalkeyoverlay", "com.tcl.autopair",
            "com.tcl.dashboard", "com.tcl.suspension", "com.tcl.providers.config", "com.tcl.initsetup",
            "com.tcl.useragreement", "com.tcl.factory.view", "com.tcl.guard", "com.tcl.t_solo",
            "com.google.android.gms", "com.google.android.gsf", "com.android.vending", "com.google.android.apps.mediashell",
            "com.google.android.inputmethod.latin", "com.google.android.webview", "com.google.android.tvsetup",
            "com.android.tv.settings", "com.android.systemui", "android", "com.gathin.tvhome",
            "com.mediatek.tv.service", "com.mediatek.tvinput", "com.realtek.tv.service"));

    private static final Object RUN = new Object();

    private HomeMode() {
    }

    // ---- requests (main thread) ------------------------------------------------------------------

    /** Ours ↔ Original: the home screen, the screensaver and the remote-button service. */
    static void switchTo(Context ctx, String mode, Listener l) {
        run(ctx, l, (app, adb) -> Prefs.MODE_ORIGINAL.equals(mode) ? original(app, adb) : ours(app, adb));
    }

    /** Turns the apps in config.xml (debloat_apps) off, or back on. */
    static void debloat(Context ctx, boolean off, Listener l) {
        run(ctx, l, (app, adb) -> off ? disableExtras(app, adb) : enableExtras(app, adb));
    }

    // ---- the switches (background thread) --------------------------------------------------------

    private static String ours(Context app, Adb adb) throws IOException {
        Prefs.setMode(app, Prefs.MODE_OURS);
        String stock = app.getString(R.string.stock_launcher).trim();
        adb.shell("cmd package set-home-activity --user 0 " + HOME);
        if (!stock.isEmpty() && packages(adb, "").contains(stock) && !wifiOwners(adb).contains(stock)) {
            adb.shell("pm disable-user --user 0 " + stock);
        }
        String dream = adb.shell("settings get secure screensaver_components").trim();
        if (dream.contains("/") && !dream.startsWith("com.gathin.tvhome/")) Prefs.setOriginalDream(app, dream);
        adb.shell("settings put secure screensaver_components " + DREAM);
        MainActivity.ensureKeyRemap(app);
        return "Готово: главный экран и заставка — наши.";
    }

    private static String original(Context app, Adb adb) throws IOException {
        // First, so the boot receiver and Home stop turning our remote-button service back on.
        Prefs.setMode(app, Prefs.MODE_ORIGINAL);
        String stock = app.getString(R.string.stock_launcher).trim();
        if (stock.isEmpty()) throw new IOException("в config.xml не указан stock_launcher");
        adb.shell("pm enable " + stock);
        String home = adb.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN"
                + " -c android.intent.category.HOME " + stock + " | tail -n 1").trim();
        if (!COMPONENT.matcher(home).matches()) throw new IOException("не нашёл домашний экран " + stock);
        adb.shell("cmd package set-home-activity --user 0 " + home);
        String dream = Prefs.originalDream(app);
        adb.shell(COMPONENT.matcher(dream).matches() ? "settings put secure screensaver_components " + dream
                : "settings delete secure screensaver_components");
        removeKeyRemap(app);
        adb.shell("am start -a android.intent.action.MAIN -c android.intent.category.HOME");
        return "Вернул главный экран телевизора. Обратно: Приложения → Home → «Главный экран».";
    }

    private static String disableExtras(Context app, Adb adb) throws IOException {
        Set<String> present = packages(adb, ""), disabled = packages(adb, "-d"), owners = wifiOwners(adb);
        int done = 0;
        StringBuilder kept = new StringBuilder();
        for (String pkg : app.getResources().getStringArray(R.array.debloat_apps)) {
            if (!present.contains(pkg) || disabled.contains(pkg)) continue;
            // Android erases the Wi-Fi networks an app saved when the app is turned off.
            if (PROTECTED.contains(pkg) || owners.contains(pkg)) {
                kept.append(kept.length() > 0 ? ", " : "").append(pkg);
                continue;
            }
            if (adb.shell("pm disable-user --user 0 " + pkg).contains("new state")) done++;
        }
        return "Отключено приложений: " + done + (kept.length() > 0 ? ". Не тронул (защищены или хранят Wi-Fi): " + kept : ".");
    }

    private static String enableExtras(Context app, Adb adb) throws IOException {
        Set<String> disabled = packages(adb, "-d");
        int done = 0;
        for (String pkg : app.getResources().getStringArray(R.array.debloat_apps)) {
            if (disabled.contains(pkg) && adb.shell("pm enable " + pkg).contains("new state")) done++;
        }
        return "Включено обратно: " + done + ".";
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /** "package/activity", nothing that could break out of a shell command. */
    private static final Pattern COMPONENT = Pattern.compile("[\\w.]+/[\\w.$]+");

    /** Packages the shell sees ("-d": only the disabled ones). */
    private static Set<String> packages(Adb adb, String flags) throws IOException {
        Set<String> out = new HashSet<>();
        for (String line : adb.shell("pm list packages " + flags).split("\n")) {
            line = line.trim();
            if (line.startsWith("package:")) out.add(line.substring(8));
        }
        return out;
    }

    /** Apps that saved a Wi-Fi network (Free My TV #14: turning one off makes Android delete its networks). */
    private static Set<String> wifiOwners(Adb adb) throws IOException {
        Set<String> owners = new HashSet<>();
        Matcher m = Pattern.compile("cname=([\\w.]+)").matcher(adb.shell("dumpsys wifi | grep -o 'cname=[^ ]*'"));
        while (m.find()) owners.add(m.group(1));
        return owners;
    }

    /** Takes our remote-button service out of the enabled list, keeping the others. Needs WRITE_SECURE_SETTINGS. */
    private static void removeKeyRemap(Context app) {
        try {
            ContentResolver cr = app.getContentResolver();
            String me = new ComponentName(app, KeyRemapService.class).flattenToString();
            String list = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (list == null) return;
            StringBuilder others = new StringBuilder();
            for (String part : list.split(":")) {
                ComponentName cn = ComponentName.unflattenFromString(part);
                if (part.isEmpty() || (cn != null && cn.flattenToString().equals(me))) continue;
                if (others.length() > 0) others.append(':');
                others.append(part);
            }
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.toString());
        } catch (SecurityException ignored) {
        }
    }

    private interface Job {
        String run(Context app, Adb adb) throws IOException;
    }

    /** One job at a time, on a background thread; the TV's ADB prompt is explained while it waits. */
    private static void run(Context ctx, Listener l, Job job) {
        final Context app = ctx.getApplicationContext();
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            synchronized (RUN) {
                try (Adb adb = Adb.connect(app, 60_000, () -> ui.post(() -> l.progress(
                        "На телевизоре появится «Разрешить отладку?». Отметьте «Всегда разрешать» и нажмите OK.")))) {
                    String text = job.run(app, adb);
                    ui.post(() -> l.done(true, text));
                } catch (IOException e) {
                    String why = e.getMessage() != null ? e.getMessage() : e.toString();
                    ui.post(() -> l.done(false, "Не получилось: " + why));
                }
            }
        }, "home-mode").start();
    }
}
