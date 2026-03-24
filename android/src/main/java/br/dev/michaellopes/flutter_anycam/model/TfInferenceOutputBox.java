package br.dev.michaellopes.flutter_anycam.model;

import android.graphics.Rect;
import android.util.Size;

import java.util.HashMap;
import java.util.Map;

public class TfInferenceOutputBox {
    public Size srcSize;
    public int xMin;
    public int yMin;
    public int xMax;
    public int yMax;
    public double score;
    public int classId = -1;

    public static TfInferenceOutputBox fromMap(Map<String, Object> map) {
        TfInferenceOutputBox box = new TfInferenceOutputBox();

        box.xMin = ((Number) map.get("xMin")).intValue();
        box.yMin = ((Number) map.get("yMin")).intValue();
        box.xMax = ((Number) map.get("xMax")).intValue();
        box.yMax = ((Number) map.get("yMax")).intValue();
        box.score = ((Number) map.get("score")).doubleValue();

        box.xMin &= ~1;
        box.yMin &= ~1;
        box.xMax &= ~1;
        box.yMax &= ~1;


        int srcWidth = ((Number) map.get("srcWidth")).intValue();
        int srcHeight = ((Number) map.get("srcHeight")).intValue();

        box.srcSize = new Size(srcWidth, srcHeight);

        if (map.get("classId") != null) {
            box.classId = ((Number) map.get("classId")).intValue();
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

    public Rect getScaledRect(Size toSize) {
        return getScaledRect(toSize, new Size(64, 64));
    }

    public Size getSrcSize() {
        return srcSize;
    }

//    public Rect getScaledRect(Size toSize, Size minSize) {
//        try {
//            Size size = srcSize;
//            double ratioW = (double) size.getWidth() / toSize.getWidth();
//            double ratioH = (double) size.getHeight() / toSize.getHeight();
//
//            double scale = Math.min(ratioW, ratioH);
//
//            double newWidth = toSize.getWidth() * scale;
//            double newHeight = toSize.getHeight() * scale;
//
//            double padX = (size.getWidth() - newWidth) / 2.0;
//            double padY = (size.getHeight() - newHeight) / 2.0;
//
//            // Remove letterbox
//            double left = (getLeft() - padX) / scale;
//            double top = (getTop() - padY) / scale;
//            double width = size.getWidth() / scale;
//            double height = size.getHeight() / scale;
//
//            // Centro real do bbox
//            double centerX = left + width / 2.0;
//            double centerY = top + height / 2.0;
//
//            // Padding proporcional (16% cada lado)
//            width *= 1.32;
//            height *= 1.32;
//
//            double minCropWidth = minSize.getWidth();
//            double minCropHeight = minSize.getHeight();
//
//            width = Math.max(width, minCropWidth);
//            height = Math.max(height, minCropHeight);
//
//            left = centerX - width / 2.0;
//            top = centerY - height / 2.0;
//
//            if (left < 0) {
//                left = 0;
//            } else if (left + width > toSize.getWidth()) {
//                left = toSize.getWidth() - width;
//            }
//
//            if (top < 0) {
//                top = 0;
//            } else if (top + height > toSize.getHeight()) {
//                top = toSize.getHeight() - height;
//            }
//
//            int finalLeft = Math.max(0, (int) left);
//            int finalTop = Math.max(0, (int) top);
//            int finalWidth = Math.min(toSize.getWidth(), (int) width);
//            int finalHeight = Math.min(toSize.getHeight(), (int) height);
//
//            return new Rect(
//                    finalLeft,
//                    finalTop,
//                    finalLeft + finalWidth,
//                    finalTop + finalHeight
//            );
//
//        } catch (Exception e) {
//            throw e;
//        }
//    }

   public Rect getScaledRect(Size toSize, Size minSize) {
        try {
            double bboxWidth  = getRight() - getLeft();
            double bboxHeight = getBottom() - getTop();
            double x = getLeft();
            double y = getTop();

            double srcW = srcSize.getWidth();
            double srcH = srcSize.getHeight();

            double ratioW = srcW / toSize.getWidth();
            double ratioH = srcH / toSize.getHeight();

            double scale = Math.min(ratioW, ratioH);

            double newWidth  = toSize.getWidth()  * scale;
            double newHeight = toSize.getHeight() * scale;

            double padX = (srcW - newWidth)  / 2.0;
            double padY = (srcH - newHeight) / 2.0;

            // Remove letterbox
            double left   = (x - padX) / scale;
            double top    = (y - padY) / scale;
            double width  = bboxWidth  / scale;
            double height = bboxHeight / scale;

            // Centro real do bbox
            double centerX = left + width  / 2.0;
            double centerY = top  + height / 2.0;

            // Padding proporcional (16% cada lado)
            width  *= 1.32;
            height *= 1.32;

            // Tamanho mínimo
            width  = Math.max(width,  minSize.getWidth());
            height = Math.max(height, minSize.getHeight());

            // Recentraliza após padding
            left = centerX - width  / 2.0;
            top  = centerY - height / 2.0;

            // Clamp dentro dos limites do toSize
            if (left < 0) {
                left = 0;
            } else if (left + width > toSize.getWidth()) {
                left = toSize.getWidth() - width;
            }

            if (top < 0) {
                top = 0;
            } else if (top + height > toSize.getHeight()) {
                top = toSize.getHeight() - height;
            }

            int finalLeft   = Math.max(0, (int) left)  & ~1;
            int finalTop    = Math.max(0, (int) top)   & ~1;

            // Desconta offset para não ultrapassar o buffer
            int finalWidth  = Math.min(toSize.getWidth()  - finalLeft, (int) width)  & ~1;
            int finalHeight = Math.min(toSize.getHeight() - finalTop,  (int) height) & ~1;

            // Garante tamanho mínimo de 2x2
            finalWidth  = Math.max(2, finalWidth);
            finalHeight = Math.max(2, finalHeight);

            return new Rect(
                    finalLeft,
                    finalTop,
                    finalLeft + finalWidth,
                    finalTop  + finalHeight
            );

        } catch (Exception e) {
            throw e;
        }
    }

 /*   public Rect getScaledRect(Size toSize) {
        try {
            Size modelSize = srcSize;
            double bboxWidth  = getRight() - getLeft();
            double bboxHeight = getBottom() - getTop();
            double x = getLeft();
            double y = getTop();

            double ratioW = modelSize.getWidth() / (double) toSize.getWidth();
            double ratioH = modelSize.getHeight() / (double) toSize.getHeight();

            double scale = Math.min(ratioW, ratioH);

            double newWidth  = toSize.getWidth()  * scale;
            double newHeight = toSize.getHeight() * scale;

            double padX = (modelSize.getWidth() - newWidth)  / 2.0;
            double padY = (modelSize.getHeight() - newHeight) / 2.0;

            // Remove letterbox
            double left   = (x - padX) / scale;
            double top    = (y - padY) / scale;
            double width  = bboxWidth  / scale;
            double height = bboxHeight / scale;

            // Clamp dentro dos limites do toSize (igual ao dart)
            if (left < 0) {
                left = 0;
            } else if (left + width > toSize.getWidth()) {
                left = toSize.getWidth() - width;
            }

            if (top < 0) {
                top = 0;
            } else if (top + height > toSize.getHeight()) {
                top = toSize.getHeight() - height;
            }

            int finalLeft   = Math.max(0, (int) left)  & ~1;
            int finalTop    = Math.max(0, (int) top)   & ~1;

            // Desconta offset para não ultrapassar o buffer
            int finalWidth  = Math.min(toSize.getWidth()  - finalLeft, (int) width)  & ~1;
            int finalHeight = Math.min(toSize.getHeight() - finalTop,  (int) height) & ~1;

            // Garante tamanho mínimo de 2x2
            finalWidth  = Math.max(2, finalWidth);
            finalHeight = Math.max(2, finalHeight);

            return new Rect(
                    finalLeft,
                    finalTop,
                    finalLeft + finalWidth,
                    finalTop  + finalHeight
            );

        } catch (Exception e) {
            throw e;
        }
    }*/

    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();

        map.put("xMin", xMin);
        map.put("yMin", yMin);
        map.put("xMax", xMax);
        map.put("yMax", yMax);
        map.put("srcWidth", srcSize.getWidth());
        map.put("srcHeight", srcSize.getHeight());
        map.put("score", score);
        map.put("classId", classId);

        return map;
    }
}
