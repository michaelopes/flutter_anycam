package br.dev.michaellopes.flutter_anycam.utils;

import android.content.Context;

public class LivecycleUtil {
    private static Object object;

    public static void init( Object object) {
        LivecycleUtil.object = object;
    }

    public static Object get() {
        return object;
    }
}
