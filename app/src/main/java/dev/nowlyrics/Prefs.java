package dev.nowlyrics;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    static final String FILE = "nowlyrics_prefs";
    static final String KEY_STATUS_BAR_ENABLED = "status_bar_enabled";

    private Prefs() {}

    static SharedPreferences get(Context context) {
        return context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static boolean isStatusBarActive(Context context) {
        return get(context).getBoolean(KEY_STATUS_BAR_ENABLED, true);
    }

    static void setStatusBarActive(Context context, boolean enabled) {
        get(context).edit().putBoolean(KEY_STATUS_BAR_ENABLED, enabled).apply();
        StatusLyricProvider.notifyChanged(context);
    }
}
