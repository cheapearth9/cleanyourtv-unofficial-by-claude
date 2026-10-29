package com.gathin.tvhome;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.util.TypedValue;

/**
 * The typeface behind one of the font_* strings in res/values/fonts.xml, for views built in code. The string
 * points at a bundled font, or, in locales Outfit can't draw (values-vi), names a system family (#833).
 */
final class UiFont {
    private UiFont() {}

    static Typeface get(Context context, int fontString) {
        Resources res = context.getResources();
        TypedValue value = new TypedValue();
        res.getValue(fontString, value, true);
        String name = String.valueOf(value.string);
        if (name.startsWith("res/")) {
            // Resources.getFont is API 26; ResourcesCompat unpacks the bundled font on Android 7.
            try {
                Typeface bundled = androidx.core.content.res.ResourcesCompat.getFont(context, value.resourceId);
                if (bundled != null) return bundled;
            } catch (Exception | LinkageError ignored) {
                // fall through to the system sans below
            }
            return Typeface.create("sans-serif", Typeface.NORMAL);
        }
        return Typeface.create(name, Typeface.NORMAL);
    }
}
