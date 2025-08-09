package br.dev.michaellopes.flutter_anycam.utils;

import android.graphics.Bitmap;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class BitmapPoolUtil {

    private static final int MAX_SIZE_BYTES = 10 * 1024 * 1024; // 10MB

    // LRU ordering
    private static final LinkedHashMap<String, List<Bitmap>> pool =
            new LinkedHashMap<>(0, 0.75f, true);

    private static int currentSize = 0;

    private static String getKey(int width, int height, Bitmap.Config config) {
        return width + "-" + height + "-" + config.name();
    }

    public static synchronized Bitmap get(int width, int height, Bitmap.Config config) {
        if (config == null) {
            config = Bitmap.Config.ARGB_8888;
        }

        String key = getKey(width, height, config);
        List<Bitmap> list = pool.get(key);

        if (list != null && !list.isEmpty()) {
            Bitmap bmp = list.remove(list.size() - 1);
            currentSize -= bmp.getByteCount();
            if (list.isEmpty()) {
                pool.remove(key);
            }
            if (!bmp.isRecycled()) {
                return bmp;
            }
        }

        return Bitmap.createBitmap(width, height, config);
    }

    public static synchronized void put(Bitmap bitmap) {
        if (bitmap.isRecycled()) {
            return;
        }

        String key = getKey(bitmap.getWidth(), bitmap.getHeight(), bitmap.getConfig());
        int size = bitmap.getByteCount();

        if (size > MAX_SIZE_BYTES) {
            bitmap.recycle();
            return;
        }

        List<Bitmap> list = pool.get(key);
        if (list == null) {
            list = new ArrayList<>();
            pool.put(key, list);
        }
        list.add(bitmap);
        currentSize += size;

        trimToSize();
    }

    private static void trimToSize() {
        Iterator<Map.Entry<String, List<Bitmap>>> it = pool.entrySet().iterator();
        while (currentSize > MAX_SIZE_BYTES && it.hasNext()) {
            Map.Entry<String, List<Bitmap>> entry = it.next();
            List<Bitmap> bitmaps = entry.getValue();

            while (!bitmaps.isEmpty() && currentSize > MAX_SIZE_BYTES) {
                Bitmap bmp = bitmaps.remove(0);
                currentSize -= bmp.getByteCount();
                bmp.recycle();
            }

            if (bitmaps.isEmpty()) {
                it.remove();
            }
        }
    }

    public static synchronized void clear() {
        for (List<Bitmap> bitmaps : pool.values()) {
            for (Bitmap bmp : bitmaps) {
                bmp.recycle();
            }
        }
        pool.clear();
        currentSize = 0;
    }
}
