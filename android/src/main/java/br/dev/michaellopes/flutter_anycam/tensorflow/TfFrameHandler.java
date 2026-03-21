package br.dev.michaellopes.flutter_anycam.tensorflow;

import android.media.Image;
import android.util.Size;

import androidx.annotation.OptIn;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageProxy;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import br.dev.michaellopes.flutter_anycam.utils.ByteArrayPoolUtil;
import br.dev.michaellopes.flutter_anycam.utils.NativeUtil;
import io.flutter.Log;
import io.github.crow_misia.libyuv.ArgbBuffer;
import io.github.crow_misia.libyuv.FilterMode;
import io.github.crow_misia.libyuv.Nv21Buffer;
import io.github.crow_misia.libyuv.RotateMode;

public class TfFrameHandler {
    private TfFrameHandler() {}

    private final ByteArrayPoolUtil bytePool = new ByteArrayPoolUtil(6, 10);
    private static TfFrameHandler instance;

    final private List<TfFrame> frames = new ArrayList<>();
    final private List<TfFrame> rawFrames = new ArrayList<>();

    public static synchronized TfFrameHandler getInstance() {
        if (instance == null) instance = new TfFrameHandler();
        return instance;
    }


    @OptIn(markerClass = ExperimentalGetImage.class)
    public Map<String, Object> addFrame(ImageProxy imageProxy, Size resizeFrame, int filter, Integer customRotationDegrees) {

        long start = System.currentTimeMillis();
        int srcWidth = imageProxy.getWidth();
        int srcHeight = imageProxy.getHeight();
        Image image = imageProxy.getImage();
        int rotation = imageProxy.getImageInfo().getRotationDegrees();

        if (customRotationDegrees != null) {
            rotation = customRotationDegrees;
        }

        RotateMode rotateMode;
        switch (rotation) {
            case 90:
                rotateMode = RotateMode.ROTATE_90;
                break;
            case 180:
                rotateMode = RotateMode.ROTATE_180;
                break;
            case 270:
                rotateMode = RotateMode.ROTATE_270;
                break;
            default:
                rotateMode = RotateMode.ROTATE_0;
                break;
        }

        int srcSize = srcWidth * srcHeight * 3 / 2;
        ByteArrayPoolUtil.PoolItem poolItem = bytePool.acquire(srcSize);
        NativeUtil.yuv420ToNv21(image, poolItem.data);

        ByteBuffer srcNv21ByteBuffer = ByteBuffer.allocateDirect(poolItem.data.length);
        srcNv21ByteBuffer.put(poolItem.data);
        srcNv21ByteBuffer.flip();

        bytePool.release(poolItem);

        Nv21Buffer srcNv21Buffer = Nv21Buffer.Factory.wrap(srcNv21ByteBuffer, srcWidth, srcHeight);
        if(rotation > 0) {
            Nv21Buffer nv21RotateBuffer = Nv21Buffer.Factory.allocate(srcHeight, srcWidth);
            srcNv21Buffer.rotate(nv21RotateBuffer, rotateMode);
            srcNv21Buffer.close();
            srcNv21Buffer = nv21RotateBuffer;
            if (rotation == 270) {
                srcNv21Buffer.mirrorTo(srcNv21Buffer);
            }
        }



        final Map<String, Object> result = addFrame(srcNv21Buffer, resizeFrame, filter, rotation);
        long time = System.currentTimeMillis() - start;
        Log.d("addFrame_PERF", "time=" + time + "ms");
        return result;
    }

    private Map<String, Object> addFrame(Nv21Buffer nv21Buffer, Size resizeFrame, int filter, Integer rotation) {

        int srcWidth = nv21Buffer.getWidth();
        int srcHeight = nv21Buffer.getHeight();

        ArgbBuffer rawArgbBuffer = ArgbBuffer.Factory.allocate(srcWidth, srcHeight);
        nv21Buffer.convertTo(rawArgbBuffer);

        ArgbBuffer resizedArgbBuffer = null;

        if (resizeFrame != null) {
            int rszWidth = resizeFrame.getWidth();
            int rszHeight = resizeFrame.getHeight();

            if (rszWidth == -1 && rszHeight == -1) {
                int minSize = Math.min(srcWidth, srcHeight);
                rszWidth = minSize;
                rszHeight = minSize;
            } else if (rotation == 90 || rotation == 270) {
                final int rszTemp = rszWidth;
                rszWidth = rszHeight;
                rszHeight = rszTemp;
            }

            resizedArgbBuffer = ArgbBuffer.Factory.allocate(rszWidth, rszHeight);
            rawArgbBuffer.scale(resizedArgbBuffer, FilterMode.BILINEAR);

            if(filter > 0) {
                resizedArgbBuffer.drawGray(0, 0, rszWidth, rszHeight);
            }


           /* Rgb565Buffer abgrFixed = Rgb565Buffer.Factory.allocate(srcWidth, srcHeight);
            rawArgbBuffer.convertTo(abgrFixed);

            Bitmap bitmap = Bitmap.createBitmap(srcWidth, srcHeight, Bitmap.Config.RGB_565);

           // bitmap.setHasAlpha(false);
            bitmap.copyPixelsFromBuffer(abgrFixed.asBuffer());

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);*/

           TfFrameNormalizer.getInstance().normalize(resizedArgbBuffer, "none", "float32");

            TfFrame rawFrame = new TfFrame(srcWidth, srcHeight, rawArgbBuffer);
            TfFrame frame = new TfFrame(srcWidth, srcHeight, rawFrame.id, resizedArgbBuffer);
            rawFrames.add(rawFrame);
            frames.add(frame);
            return frame.toMap();
        } else {
            TfFrame frame = new TfFrame(srcWidth, srcHeight, rawArgbBuffer);
            frames.add(frame);
            return  frame.toMap();
        }

    }

    public TfFrame getRawFrameById(String id) {
        final List<TfFrame> lst = rawFrames.stream()
                .filter(item -> item.id.equals(id))
                .collect(Collectors.toList());
        if(lst.isEmpty()) {
            return  null;
        }
        return lst.get(0);
    }

    public List<TfFrame> getAllFramesByRawFrameId(String rawFrameId) {
        return frames.stream()
                .filter(item -> item.rawFrameId != null && item.rawFrameId.equals(rawFrameId))
                .collect(Collectors.toList());
    }

    public boolean hasFramesByRawFrameId(String rawFrameId) {
        return !getAllFramesByRawFrameId(rawFrameId).isEmpty();
    }

    public class TfFrame {
        final String id;
        final int width;
        final int height;
        final String rawFrameId;
        final ArgbBuffer buffer;

        public TfFrame(int width, int height, ArgbBuffer buffer) {
            this.width = width;
            this.height = height;
            this.buffer = buffer;
            this.id = UUID.randomUUID().toString();
            ;
            this.rawFrameId = null;
        }

        public TfFrame(int width, int height, String rawFrameId, ArgbBuffer buffer) {
            this.width = width;
            this.height = height;
            this.buffer = buffer;
            this.id = UUID.randomUUID().toString();
            this.rawFrameId = rawFrameId;
        }

        Map<String, Object> toMap() {
            return new HashMap<String, Object>() {{
                put("id", id);
                put("width", width);
                put("height", height);
            }};
        }

        public void close() {
            buffer.close();
            if(rawFrameId == null) {
                rawFrames.remove(this);
                frames.remove(this);
            } else {
                frames.remove(this);
            }
            if(rawFrameId != null && !hasFramesByRawFrameId(rawFrameId)) {
                TfFrame rawFrame = getRawFrameById(rawFrameId);
                rawFrame.close();
            }
        }
    }
}
