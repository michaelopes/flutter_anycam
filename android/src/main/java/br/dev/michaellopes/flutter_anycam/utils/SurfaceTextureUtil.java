package br.dev.michaellopes.flutter_anycam.utils;

import android.content.Context;

import io.flutter.view.TextureRegistry;

public class SurfaceTextureUtil {
    private static  TextureRegistry textureRegistry;

    public static void init( TextureRegistry textureRegistry) {
        SurfaceTextureUtil.textureRegistry = textureRegistry;
    }

    public static TextureRegistry.SurfaceTextureEntry createSurfaceTexture() {
        return textureRegistry.createSurfaceTexture();
    }
}
