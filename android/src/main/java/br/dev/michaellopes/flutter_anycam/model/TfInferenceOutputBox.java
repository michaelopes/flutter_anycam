package br.dev.michaellopes.flutter_anycam.model;

import android.graphics.Rect;
import android.util.Log;
import android.util.Size;

import java.util.HashMap;
import java.util.Map;

public class TfInferenceOutputBox {
    public Size srcSize;
    public int xMin;
    public int yMin;
    public int xMax;
    public int yMax;
    public int classId = -1;
    public String trackingId;
    public Size minScaledCropSize;

    public double cropPadPercent;

    public static TfInferenceOutputBox fromMap(Map<String, Object> map) {
        TfInferenceOutputBox box = new TfInferenceOutputBox();

        box.xMin = ((Number) map.get("xMin")).intValue();
        box.yMin = ((Number) map.get("yMin")).intValue();
        box.xMax = ((Number) map.get("xMax")).intValue();
        box.yMax = ((Number) map.get("yMax")).intValue();
        box.trackingId = (String)map.get("trackingId");


        box.xMin &= ~1;
        box.yMin &= ~1;
        box.xMax &= ~1;
        box.yMax &= ~1;

       Double cropPadPercent = ((Double) map.get("cropPadPercent"));
       if(cropPadPercent != null) {
           box.cropPadPercent =  cropPadPercent;  
       } else {
           box.cropPadPercent = 0;
       }


        int srcWidth = ((Number) map.get("srcWidth")).intValue();
        int srcHeight = ((Number) map.get("srcHeight")).intValue();

        box.srcSize = new Size(srcWidth, srcHeight);

        if (map.get("classId") != null) {
            box.classId = ((Number) map.get("classId")).intValue();
        }

        Object minScaledCropSizeMap = map.get("minScaledCropSize");
        if (minScaledCropSizeMap instanceof Map) {
            Integer width = (Integer) ((Map<?, ?>) minScaledCropSizeMap).get("width");
            Integer height = (Integer) ((Map<?, ?>) minScaledCropSizeMap).get("height");
            box.minScaledCropSize = new Size(width, height);
        } else {
            box.minScaledCropSize = null;
        }

        return box;
    }

    public Size getSize() {
        return new Size((getRight() - getLeft()), (getBottom() - getTop()));
    }

    public Rect getRect() {
        return new Rect(getLeft(), getTop(), getRight(), getBottom());
    }

    public int getLeft() {
        return Math.max(0, xMin) ;
    }

    public int getTop() {
        return Math.max(0, yMin) ;
    }

    public int getBottom() {
        return Math.min(srcSize.getHeight(), yMax) ;
    }

    public int getRight() {
        return Math.min(srcSize.getWidth(), xMax);
    }


    public Size getSrcSize() {
        return srcSize;
    }

    public Rect getScaledRect(Size toSize) {

        if(minScaledCropSize == null) {
          return getScaledRectSimple(toSize);
        }

        final int ORIGINAL_W = toSize.getWidth();
        final int ORIGINAL_H = toSize.getHeight();
        final int INPUT_W = srcSize.getWidth();
        final int INPUT_H = srcSize.getHeight();
        final int cropLeft   = getLeft();
        final int cropTop    = getTop();
        final int cropRight  = getRight();
        final int cropBottom = getBottom();
        final int minWidth  = minScaledCropSize != null ? minScaledCropSize.getWidth()  : 80;
        final int minHeight = minScaledCropSize != null ? minScaledCropSize.getHeight() : 80;

        double ratioW = (double) INPUT_W / ORIGINAL_W;
        double ratioH = (double) INPUT_H / ORIGINAL_H;
        double scale  = Math.min(ratioW, ratioH);

        double scaledW = ORIGINAL_W * scale;
        double scaledH = ORIGINAL_H * scale;

        double padX = (INPUT_W - scaledW) / 2.0;
        double padY = (INPUT_H - scaledH) / 2.0;

        double boxX = cropLeft;
        double boxY = cropTop;
        double boxW = cropRight  - cropLeft;
        double boxH = cropBottom - cropTop;

        // Remove letterbox → espaço original
        double left   = (boxX - padX) / scale;
        double top    = (boxY - padY) / scale;
        double width  = boxW / scale;
        double height = boxH / scale;

        // Centro real do bbox
        double centerX = left + width  / 2.0;
        double centerY = top  + height / 2.0;

        // Padding proporcional (32%)
        double sumPercent = (cropPadPercent > 0 ? (cropPadPercent/100.0f) : 0);
        width  *= 1 + sumPercent;
        height *= 1 + sumPercent;

        // Aplica tamanho mínimo antes do clamp
        width  = Math.max(width,  minWidth);
        height = Math.max(height, minHeight);

        // Recentraliza em torno do objeto real
        left = centerX - width  / 2.0;
        top  = centerY - height / 2.0;

        // ✅ Clamp com resize: mantém o objeto visível mesmo na borda
        left = Math.max(0, left);
        top  = Math.max(0, top);

        double clampedRight  = Math.min(ORIGINAL_W, left + width);
        double clampedBottom = Math.min(ORIGINAL_H, top  + height);

        width  = clampedRight  - left;
        height = clampedBottom - top;

        // Garante mínimo após clamp (borda pode ter reduzido demais)
        width  = Math.max(width,  minWidth);
        height = Math.max(height, minHeight);

        // Alinha em múltiplos de 2
        int finalLeft   = Math.max(0, (int) left)   & ~1;
        int finalTop    = Math.max(0, (int) top)     & ~1;
        int finalWidth  = Math.min(ORIGINAL_W - finalLeft, (int) width)  & ~1;
        int finalHeight = Math.min(ORIGINAL_H - finalTop,  (int) height) & ~1;

        // Garante mínimo de 2x2
        finalWidth  = Math.max(2, finalWidth);
        finalHeight = Math.max(2, finalHeight);

        // Log.i("getScaledRect", "cropRect: " + cropLeft + "," + cropTop + " → " + cropRight + "," + cropBottom);
        // Log.i("getScaledRect", "converted: left=" + left + " top=" + top + " w=" + width + " h=" + height);
        // Log.i("getScaledRect", "scale=" + scale + " padX=" + padX + " padY=" + padY);

        return new Rect(
                finalLeft,
                finalTop,
                finalLeft + finalWidth,
                finalTop + finalHeight
        );
    }

    public Rect getScaledRectSimple(Size toSize) {
        final int ORIGINAL_W = toSize.getWidth();
        final int ORIGINAL_H = toSize.getHeight();
        final int INPUT_W = srcSize.getWidth();
        final int INPUT_H = srcSize.getHeight();

        final double cropLeft   = getLeft();
        final double cropTop    = getTop();
        final double cropRight  = getRight();
        final double cropBottom = getBottom();

        // 🔹 escala (mantém proporção com letterbox)
        double scale = Math.min(
                (double) INPUT_W / ORIGINAL_W,
                (double) INPUT_H / ORIGINAL_H
        );

        double scaledW = ORIGINAL_W * scale;
        double scaledH = ORIGINAL_H * scale;

        double padX = (INPUT_W - scaledW) / 2.0;
        double padY = (INPUT_H - scaledH) / 2.0;

        // 🔹 remove letterbox
        double left   = (cropLeft   - padX) / scale;
        double top    = (cropTop    - padY) / scale;
        double right  = (cropRight  - padX) / scale;
        double bottom = (cropBottom - padY) / scale;

        // 🔹 clamp inicial
        left   = Math.max(0, left);
        top    = Math.max(0, top);
        right  = Math.min(ORIGINAL_W, right);
        bottom = Math.min(ORIGINAL_H, bottom);

        double bboxW = right - left;
        double bboxH = bottom - top;

        // 🔴 fallback defensivo
        if (bboxW <= 1 || bboxH <= 1) {
            return new Rect(0, 0, 2, 2);
        }

        // 🔹 padding percentual
        double padFactor = cropPadPercent / 100.0;
        double padXBox = bboxW * padFactor;
        double padYBox = bboxH * padFactor;

        left   -= padXBox;
        top    -= padYBox;
        right  += padXBox;
        bottom += padYBox;

        // 🔹 clamp novamente após padding
        left   = Math.max(0, left);
        top    = Math.max(0, top);
        right  = Math.min(ORIGINAL_W, right);
        bottom = Math.min(ORIGINAL_H, bottom);

        // 🔹 alinhamento YUV SAFE (PAR)
        int finalLeft   = ((int) Math.floor(left)) & ~1;
        int finalTop    = ((int) Math.floor(top)) & ~1;

        int finalRight  = ((int) Math.ceil(right)  + 1) & ~1;
        int finalBottom = ((int) Math.ceil(bottom) + 1) & ~1;

        // 🔹 clamp final
        finalLeft   = Math.max(0, finalLeft);
        finalTop    = Math.max(0, finalTop);
        finalRight  = Math.min(ORIGINAL_W, finalRight);
        finalBottom = Math.min(ORIGINAL_H, finalBottom);

        // 🔴 garantir tamanho mínimo válido (par)
        if (finalRight - finalLeft < 2) {
            finalRight = Math.min(ORIGINAL_W, finalLeft + 2);
        }

        if (finalBottom - finalTop < 2) {
            finalBottom = Math.min(ORIGINAL_H, finalTop + 2);
        }

        // 🔴 segurança extra (nunca inverter)
        if (finalRight <= finalLeft || finalBottom <= finalTop) {
            return new Rect(0, 0, 2, 2);
        }

        return new Rect(finalLeft, finalTop, finalRight, finalBottom);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();

        map.put("xMin", xMin);
        map.put("yMin", yMin);
        map.put("xMax", xMax);
        map.put("yMax", yMax);
        map.put("srcWidth", srcSize.getWidth());
        map.put("srcHeight", srcSize.getHeight());
        map.put("classId", classId);
        map.put("trackingId", trackingId);
        map.put("cropPadPercent", cropPadPercent);
        if(minScaledCropSize != null) {
            map.put("minScaledCropSize", new HashMap<String, Object>() {{
                put("width", minScaledCropSize.getWidth());
                put("height", minScaledCropSize.getHeight());
            }});
        }

        return map;
    }
}
