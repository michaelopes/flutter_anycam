package br.dev.michaellopes.flutter_anycam.tensorflow;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import br.dev.michaellopes.flutter_anycam.utils.ByteBufferPoolUtil;
import br.dev.michaellopes.flutter_anycam.utils.NativeUtil;
import io.github.crow_misia.libyuv.AbgrBuffer;
import io.github.crow_misia.libyuv.ArgbBuffer;

public class TfFrameNormalizer {

    final ByteBufferPoolUtil byteBufferPool = new ByteBufferPoolUtil(6);

    private TfFrameNormalizer() {
    }

    private static TfFrameNormalizer instance;


    public static synchronized TfFrameNormalizer getInstance() {
        if (instance == null) instance = new TfFrameNormalizer();
        return instance;
    }

    public ByteBufferPoolUtil.PoolItem normalize(
            ArgbBuffer argbBuffer,
            String normalization, String dataType
    ) {
        return normalize(
                argbBuffer,
                normalization, dataType,
                1f, 0,           // invScale, zeroPoint (só usado no int8)
                0f, 0f, 0f,      // mean
                1f, 1f, 1f       // std
        );
    }

    // Float32 simples
    public ByteBufferPoolUtil.PoolItem normalizeFloat32(
            ArgbBuffer argbBuffer,
            String normalization
    ) {
        return normalize(
                argbBuffer, normalization, "float32"
        );
    }

    public ByteBufferPoolUtil.PoolItem normalizeUint8(
            ArgbBuffer argbBuffer,
            String normalization
    ) {
        return normalize(
                argbBuffer, normalization, "uint8"
        );
    }

    public ByteBufferPoolUtil.PoolItem normalizeInt8(
            ArgbBuffer argbBuffer,
            String normalization,
            float invScale, int zeroPoint
    ) {
        return normalize(
                argbBuffer, normalization, "int8", invScale, zeroPoint,
                0f, 0f, 0f,      // mean
                1f, 1f, 1f
        );
    }

  /* public  ByteBufferPoolUtil.PoolItem normalize(
            ArgbBuffer argbBuffer,
            String normalization,
            String dataType,
            float invScale, int zeroPoint,
            // custom normalization
            float meanR, float meanG, float meanB,
            float stdR, float stdG, float stdB
    ) {
        int width = argbBuffer.getWidth();
        int height = argbBuffer.getHeight();

        ByteBuffer src = argbBuffer.asBuffer();
        src.rewind();

        int pixelCount = width * height;

       int outputSize;
       switch (dataType) {
           case "float32": outputSize = pixelCount * 3 * 4; break;
           case "uint8":
           case "int8":    outputSize = pixelCount * 3; break;
           default: throw new IllegalArgumentException("dataType inválido: " + dataType);
       }

       ByteBufferPoolUtil.PoolItem item = byteBufferPool.acquire(outputSize);
       ByteBuffer output = item.buffer;

        float scaleR, scaleG, scaleB;
        float offsetR, offsetG, offsetB;

        switch (normalization) {
            case "simple":
                scaleR = scaleG = scaleB = 1.0f / 255.0f;
                offsetR = offsetG = offsetB = 0.0f;
                break;
            case "centered":
                scaleR = scaleG = scaleB = 1.0f / 127.5f;
                offsetR = offsetG = offsetB = -1.0f;
                break;
            case "imagenet":
                scaleR = 1.0f / (255.0f * 0.229f);
                scaleG = 1.0f / (255.0f * 0.224f);
                scaleB = 1.0f / (255.0f * 0.225f);
                offsetR = -0.485f / 0.229f;
                offsetG = -0.456f / 0.224f;
                offsetB = -0.406f / 0.225f;
                break;
            case "custom":
                if (stdR == 0.0f || stdG == 0.0f || stdB == 0.0f)
                    throw new IllegalArgumentException("std values must not be zero");
                scaleR = 1.0f / (255.0f * stdR);
                scaleG = 1.0f / (255.0f * stdG);
                scaleB = 1.0f / (255.0f * stdB);
                offsetR = -meanR / stdR;
                offsetG = -meanG / stdG;
                offsetB = -meanB / stdB;
                break;
            default: // "none"
                scaleR = scaleG = scaleB = 1.0f;
                offsetR = offsetG = offsetB = 0.0f;
                break;
        }

        for (int i = 0; i < pixelCount; i++) {
            int base = i * 4;
            float r = (src.get(base + 1) & 0xFF);
            float g = (src.get(base + 2) & 0xFF);
            float b = (src.get(base + 3) & 0xFF);

            float fR = r * scaleR + offsetR;
            float fG = g * scaleG + offsetG;
            float fB = b * scaleB + offsetB;

            switch (dataType) {
                case "float32":
                    output.putFloat(fR);
                    output.putFloat(fG);
                    output.putFloat(fB);
                    break;

                case "uint8": {
                    int pR = Math.round(fR);
                    int pG = Math.round(fG);
                    int pB = Math.round(fB);
                    output.put((byte) Math.max(0, Math.min(255, pR)));
                    output.put((byte) Math.max(0, Math.min(255, pG)));
                    output.put((byte) Math.max(0, Math.min(255, pB)));
                    break;
                }

                case "int8": {
                    int qR = Math.round(fR * invScale) + zeroPoint;
                    int qG = Math.round(fG * invScale) + zeroPoint;
                    int qB = Math.round(fB * invScale) + zeroPoint;
                    output.put((byte) Math.max(-128, Math.min(127, qR)));
                    output.put((byte) Math.max(-128, Math.min(127, qG)));
                    output.put((byte) Math.max(-128, Math.min(127, qB)));
                    break;
                }
            }
        }

        output.rewind();
        item.release();
        return item;
    }*/
/*
    public ByteBufferPoolUtil.PoolItem normalize(
            ArgbBuffer argbBuffer,
            String normalization,
            String dataType,
            float invScale, int zeroPoint,
            float meanR, float meanG, float meanB,
            float stdR, float stdG, float stdB
    ) {
        int width = argbBuffer.getWidth();
        int height = argbBuffer.getHeight();
        int pixelCount = width * height;

        ByteBuffer src = argbBuffer.asBuffer();
        src.rewind();
        IntBuffer srcInt = src.asIntBuffer(); // lê 4 bytes de uma vez

        int outputSize;
        switch (dataType) {
            case "float32": outputSize = pixelCount * 3 * 4; break;
            case "uint8":
            case "int8":    outputSize = pixelCount * 3; break;
            default: throw new IllegalArgumentException("dataType inválido: " + dataType);
        }

        // Pega do pool em vez de alocar
        ByteBufferPoolUtil.PoolItem item = byteBufferPool.acquire(outputSize);
        ByteBuffer output = item.buffer;

        // Parâmetros de normalização (igual ao seu código atual)
        float scaleR, scaleG, scaleB, offsetR, offsetG, offsetB;
        switch (normalization) {
            case "simple":
                scaleR = scaleG = scaleB = 1.0f / 255.0f;
                offsetR = offsetG = offsetB = 0.0f;
                break;
            case "centered":
                scaleR = scaleG = scaleB = 1.0f / 127.5f;
                offsetR = offsetG = offsetB = -1.0f;
                break;
            case "imagenet":
                scaleR = 1.0f / (255.0f * 0.229f);
                scaleG = 1.0f / (255.0f * 0.224f);
                scaleB = 1.0f / (255.0f * 0.225f);
                offsetR = -0.485f / 0.229f;
                offsetG = -0.456f / 0.224f;
                offsetB = -0.406f / 0.225f;
                break;
            case "custom":
                if (stdR == 0.0f || stdG == 0.0f || stdB == 0.0f)
                    throw new IllegalArgumentException("std values must not be zero");
                scaleR = 1.0f / (255.0f * stdR);
                scaleG = 1.0f / (255.0f * stdG);
                scaleB = 1.0f / (255.0f * stdB);
                offsetR = -meanR / stdR;
                offsetG = -meanG / stdG;
                offsetB = -meanB / stdB;
                break;
            default: // none
                scaleR = scaleG = scaleB = 1.0f;
                offsetR = offsetG = offsetB = 0.0f;
                break;
        }

        // Loop separado por dataType — switch fora do loop
        switch (dataType) {
            case "float32": {
                FloatBuffer out = output.asFloatBuffer();
                for (int i = 0; i < pixelCount; i++) {
                    int pixel = srcInt.get(i);
                    out.put(((pixel >> 16) & 0xFF) * scaleR + offsetR); // R
                    out.put(((pixel >>  8) & 0xFF) * scaleG + offsetG); // G
                    out.put(( pixel        & 0xFF) * scaleB + offsetB); // B
                }
                break;
            }
            case "uint8": {
                for (int i = 0; i < pixelCount; i++) {
                    int pixel = srcInt.get(i);
                    int pR = Math.round(((pixel >> 16) & 0xFF) * scaleR + offsetR);
                    int pG = Math.round(((pixel >>  8) & 0xFF) * scaleG + offsetG);
                    int pB = Math.round(( pixel        & 0xFF) * scaleB + offsetB);
                    output.put((byte) Math.max(0, Math.min(255, pR)));
                    output.put((byte) Math.max(0, Math.min(255, pG)));
                    output.put((byte) Math.max(0, Math.min(255, pB)));
                }
                break;
            }
            case "int8": {
                for (int i = 0; i < pixelCount; i++) {
                    int pixel = srcInt.get(i);
                    int qR = Math.round(((pixel >> 16) & 0xFF) * scaleR + offsetR) * (int)(invScale) + zeroPoint;
                    int qG = Math.round(((pixel >>  8) & 0xFF) * scaleG + offsetG) * (int)(invScale) + zeroPoint;
                    int qB = Math.round(( pixel        & 0xFF) * scaleB + offsetB) * (int)(invScale) + zeroPoint;
                    output.put((byte) Math.max(-128, Math.min(127, qR)));
                    output.put((byte) Math.max(-128, Math.min(127, qG)));
                    output.put((byte) Math.max(-128, Math.min(127, qB)));
                }
                break;
            }
        }

        output.rewind();
        return item;
    }*/

    public ByteBufferPoolUtil.PoolItem normalize(
            ArgbBuffer argbBuffer,
            String normalization,
            String dataType,
            float invScale, int zeroPoint,
            float meanR, float meanG, float meanB,
            float stdR, float stdG, float stdB
    ) {
        int width = argbBuffer.getWidth();
        int height = argbBuffer.getHeight();
        int pixelCount = width * height;


        int outputSize;
        switch (dataType) {
            case "float32":
                outputSize = pixelCount * 3 * 4;
                break;
            case "uint8":
            case "int8":
                outputSize = pixelCount * 3;
                break;
            default:
                throw new IllegalArgumentException("dataType inválido: " + dataType);
        }

        ByteBufferPoolUtil.PoolItem item = byteBufferPool.acquire(outputSize);
        ByteBuffer output = item.buffer;

        ByteBuffer src = argbBuffer.asBuffer();
        src.rewind();

        NativeUtil.normalizeNative(
                src,
                output,
                pixelCount,
                mapDataType(dataType),
                mapNormalization(normalization),
                invScale,
                zeroPoint,
                meanR, meanG, meanB,
                stdR, stdG, stdB
        );

        output.rewind();
        item.release();
        return item;
    }

    private static int mapNormalization(String normalization) {
        switch (normalization) {
            case "simple":
                return 0;
            case "centered":
                return 1;
            case "imagenet":
                return 2;
            case "custom":
                return 3;
            default:
                return 4;
        }
    }

    private static int mapDataType(String dataType) {
        switch (dataType) {
            case "float32":
                return 0;
            case "uint8":
                return 1;
            case "int8":
                return 2;
            default:
                throw new IllegalArgumentException("dataType inválido: " + dataType);
        }
    }
}
