package br.dev.michaellopes.flutter_anycam.utils;

import android.content.Context;

public class ContextUtil {
    private static Context appContext;

    public static void init(Context context) {
        appContext = context;
    }

    public static Context get() {
        return appContext;
    }
}
