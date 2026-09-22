package br.dev.michaellopes.flutter_anycam.utils;

import android.app.Activity;
import android.content.Context;

import androidx.lifecycle.LifecycleOwner;

/**
 * Separates long-lived Application context from the current Activity/LifecycleOwner.
 * CameraManager/USB/assets must use {@link #get()}; CameraX bind and runtime permissions
 * must use {@link #getLifecycleOwner()} / {@link #getActivity()}.
 */
public class ContextUtil {
    private static Context appContext;
    private static Activity activity;

    private ContextUtil() {
    }

    public static void initApp(Context context) {
        if (context != null) {
            appContext = context.getApplicationContext();
        }
    }

    /** @deprecated Prefer {@link #initApp(Context)} + {@link #setActivity(Activity)}. */
    public static void init(Context context) {
        if (context instanceof Activity) {
            setActivity((Activity) context);
            if (appContext == null) {
                appContext = context.getApplicationContext();
            }
        } else if (context != null) {
            initApp(context);
        }
    }

    public static void setActivity(Activity newActivity) {
        activity = newActivity;
    }

    public static void clearActivity() {
        activity = null;
    }

    /**
     * Always Application context when available (safe for services, CameraManager, assets).
     */
    public static Context get() {
        if (appContext != null) {
            return appContext;
        }
        return activity;
    }

    public static Activity getActivity() {
        return activity;
    }

    public static LifecycleOwner getLifecycleOwner() {
        if (activity instanceof LifecycleOwner) {
            return (LifecycleOwner) activity;
        }
        return null;
    }

    public static boolean hasActivity() {
        return activity != null;
    }
}
