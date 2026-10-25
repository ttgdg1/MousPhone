package io.pocketpad.remote;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.LocaleList;
import java.util.Locale;

/** Per-app locale only. Never changes the phone's system locale or keyboard. */
final class UiLanguage {
    static final String[] CODES = {"auto", "ru", "en", "de", "fr", "es"};
    static final String[] NATIVE_NAMES = {"", "Русский", "English", "Deutsch", "Français", "Español"};
    private static final String PREFS = "ui_settings";
    private static final String KEY = "uiLanguage";

    static String selected(Context context) {
        String value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "auto");
        for (String code : CODES) if (code.equals(value)) return code;
        return "auto";
    }

    static void select(Context context, String code) {
        String choice = "auto";
        for (String candidate : CODES) if (candidate.equals(code)) choice = candidate;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, choice).apply();
    }

    private static Locale effectiveLocale(Context context) {
        String code = selected(context);
        if (!"auto".equals(code)) return Locale.forLanguageTag(code);
        // Resources.getSystem avoids retaining a previous app override when Auto
        // is selected, and re-reads the phone language on Activity recreation.
        LocaleList languages = Resources.getSystem().getConfiguration().getLocales();
        for (int i = 0; i < languages.size(); i++) {
            Locale locale = languages.get(i);
            String language = locale.getLanguage();
            for (String supported : CODES) if (!"auto".equals(supported) && supported.equals(language)) return locale;
        }
        return Locale.ENGLISH;
    }

    static Context wrap(Context context) {
        Configuration config = new Configuration(context.getResources().getConfiguration());
        Locale locale = effectiveLocale(context);
        config.setLocales(new LocaleList(locale));
        config.setLayoutDirection(locale);
        return context.createConfigurationContext(config);
    }

    static String getString(Context context, int id, Object... arguments) {
        return wrap(context).getString(id, arguments);
    }

    private UiLanguage() {}
}
