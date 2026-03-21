#include <jni.h>
#include <cstdint>
#include <cstring>
#include <cstdlib> 
#include <android/log.h>
#include <__algorithm/max.h>
#include <__algorithm/min.h>
#include <cmath>

#define LOG_TAG "YuvUtils"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_br_dev_michaellopes_flutter_1anycam_utils_NativeUtil_yuv420888ToNv21JNI(
        JNIEnv* env,
        jclass clazz,
        jobject yBuffer,
        jobject uBuffer,
        jobject vBuffer,
        jint width,
        jint height,
        jint yRowStride,
        jint uRowStride,
        jint vRowStride,
        jint uPixelStride,
        jint vPixelStride) {

    uint8_t* yData = (uint8_t*) env->GetDirectBufferAddress(yBuffer);
    uint8_t* uData = (uint8_t*) env->GetDirectBufferAddress(uBuffer);
    uint8_t* vData = (uint8_t*) env->GetDirectBufferAddress(vBuffer);

    if (!yData || !uData || !vData) return nullptr;

    const int ySize  = width * height;
    const int uvSize = ySize >> 1;

    // Aloca buffer nativo diretamente, evita GetByteArrayElements + lock
    uint8_t* nv21 = (uint8_t*) malloc(ySize + uvSize);
    if (!nv21) return nullptr;

    // === Y plane ===
    if (yRowStride == width) {
        // Caso ideal: cópia única sem loop
        memcpy(nv21, yData, ySize);
    } else {
        uint8_t* dst = nv21;
        for (int row = 0; row < height; row++) {
            memcpy(dst, yData + row * yRowStride, width);
            dst += width;
        }
    }

    // === UV plane ===
    const int chromaHeight = height >> 1;
    const int chromaWidth  = width  >> 1;
    uint8_t* uvDst = nv21 + ySize;

    // Caso ideal: UV já está interleaved e contíguo (maioria dos dispositivos)
    // vData + 1 == uData (ou vice-versa) → cópia direta
    if (vPixelStride == 2 && uPixelStride == 2 &&
        vRowStride == uRowStride &&
        (vData + 1 == uData || uData + 1 == vData)) {

        uint8_t* src = (vData < uData) ? vData : vData; // começa pelo V (NV21)
        const int rowBytes = chromaWidth * 2;

        if (vRowStride == rowBytes) {
            // Plano inteiro contíguo
            memcpy(uvDst, vData, chromaHeight * rowBytes);
        } else {
            for (int row = 0; row < chromaHeight; row++) {
                memcpy(uvDst, vData + row * vRowStride, rowBytes);
                uvDst += rowBytes;
            }
        }
    } else {
        // Fallback: interleave manual por pixel
        for (int row = 0; row < chromaHeight; row++) {
            const uint8_t* uRow = uData + row * uRowStride;
            const uint8_t* vRow = vData + row * vRowStride;

            for (int col = 0; col < chromaWidth; col++) {
                *uvDst++ = vRow[col * vPixelStride]; // V
                *uvDst++ = uRow[col * uPixelStride]; // U
            }
        }
    }

    // Cria o jbyteArray copiando do buffer nativo (sem lock de GC)
    jbyteArray result = env->NewByteArray(ySize + uvSize);
    env->SetByteArrayRegion(result, 0, ySize + uvSize, (jbyte*) nv21);
    free(nv21);

    return result;
}

extern "C"
JNIEXPORT void JNICALL
Java_br_dev_michaellopes_flutter_1anycam_utils_NativeUtil_yuv420888ToNv21IntoJNI(
        JNIEnv* env,
        jclass,
        jobject yBuffer,
        jobject uBuffer,
        jobject vBuffer,
        jbyteArray outNv21,
        jint width,
        jint height,
        jint yRowStride,
        jint uRowStride,
        jint vRowStride,
        jint uPixelStride,
        jint vPixelStride) {

    uint8_t* yData = (uint8_t*) env->GetDirectBufferAddress(yBuffer);
    uint8_t* uData = (uint8_t*) env->GetDirectBufferAddress(uBuffer);
    uint8_t* vData = (uint8_t*) env->GetDirectBufferAddress(vBuffer);
    if (!yData || !uData || !vData) return;

    const int ySize  = width * height;
    const int uvSize = ySize >> 1;
    const int outSize = ySize + uvSize;

    if (env->GetArrayLength(outNv21) < outSize) return;

    // trava o array o mínimo possível
    uint8_t* out = (uint8_t*) env->GetPrimitiveArrayCritical(outNv21, nullptr);
    if (!out) return;

    // === Y ===
    if (yRowStride == width) {
        memcpy(out, yData, ySize);
    } else {
        uint8_t* dst = out;
        for (int row = 0; row < height; row++) {
            memcpy(dst, yData + row * yRowStride, width);
            dst += width;
        }
    }

    // === UV (NV21 = VU) ===
    uint8_t* uvDst = out + ySize;
    const int chromaHeight = height >> 1;
    const int chromaWidth  = width  >> 1;
    const int rowBytes = chromaWidth * 2;

    // Fast paths quando pixelStride=2 (comum)
    if (uPixelStride == 2 && vPixelStride == 2 && uRowStride == vRowStride) {

        // Caso A: já está VU (NV21)
        if (vData + 1 == uData) {
            if (vRowStride == rowBytes) {
                memcpy(uvDst, vData, chromaHeight * rowBytes);
            } else {
                for (int row = 0; row < chromaHeight; row++) {
                    memcpy(uvDst, vData + row * vRowStride, rowBytes);
                    uvDst += rowBytes;
                }
            }
            env->ReleasePrimitiveArrayCritical(outNv21, out, 0);
            return;
        }

        // Caso B: está UV (NV12) -> swap por par para virar NV21
        if (uData + 1 == vData) {
            for (int row = 0; row < chromaHeight; row++) {
                const uint8_t* src = uData + row * uRowStride; // U,V,U,V...
                for (int i = 0; i < rowBytes; i += 2) {
                    uvDst[i]     = src[i + 1]; // V
                    uvDst[i + 1] = src[i];     // U
                }
                uvDst += rowBytes;
            }
            env->ReleasePrimitiveArrayCritical(outNv21, out, 0);
            return;
        }
    }

    // Fallback: interleave manual
    for (int row = 0; row < chromaHeight; row++) {
        const uint8_t* uRow = uData + row * uRowStride;
        const uint8_t* vRow = vData + row * vRowStride;
        for (int col = 0; col < chromaWidth; col++) {
            *uvDst++ = vRow[col * vPixelStride]; // V
            *uvDst++ = uRow[col * uPixelStride]; // U
        }
    }

    env->ReleasePrimitiveArrayCritical(outNv21, out, 0);
}

extern "C"
JNIEXPORT void JNICALL
Java_br_dev_michaellopes_flutter_1anycam_utils_NativeUtil_resizeNv21JNI(
        JNIEnv *env,
        jclass,
        jbyteArray srcArray,
        jint srcW,
        jint srcH,
        jbyteArray dstArray,
        jint dstW,
        jint dstH) {

    dstW &= ~1;
    dstH &= ~1;

    jbyte* src = env->GetByteArrayElements(srcArray, nullptr);
    jbyte* dst = env->GetByteArrayElements(dstArray, nullptr);

    auto* usrc = reinterpret_cast<uint8_t*>(src);
    auto* udst = reinterpret_cast<uint8_t*>(dst);

    int srcFrameSize = srcW * srcH;
    int dstFrameSize = dstW * dstH;

    float scale = std::max(
            (float)dstW / srcW,
            (float)dstH / srcH
    );

    int scaledW = (int)(srcW * scale);
    int scaledH = (int)(srcH * scale);

    int offsetX = (scaledW - dstW) / 2;
    int offsetY = (scaledH - dstH) / 2;

    float invScale = 1.0f / scale;

    // ─────────────────────────────────────────
    // Helper: bilinear sample no plano Y
    // ─────────────────────────────────────────
    auto sampleY = [&](float fx, float fy) -> uint8_t {
        int x0 = (int)fx;
        int y0 = (int)fy;
        int x1 = std::min(x0 + 1, srcW - 1);
        int y1 = std::min(y0 + 1, srcH - 1);
        x0 = std::max(x0, 0);
        y0 = std::max(y0, 0);

        float dx = fx - x0;
        float dy = fy - y0;

        float top    = usrc[y0 * srcW + x0] * (1 - dx) + usrc[y0 * srcW + x1] * dx;
        float bottom = usrc[y1 * srcW + x0] * (1 - dx) + usrc[y1 * srcW + x1] * dx;
        return (uint8_t)(top * (1 - dy) + bottom * dy);
    };

    // ─────────────────────────────────────────
    // Helper: bilinear sample no plano UV (NV21)
    // channel: 0 = V, 1 = U
    // ─────────────────────────────────────────
    auto sampleUV = [&](float fx, float fy, int channel) -> uint8_t {
        int uvSrcW = srcW / 2;
        int uvSrcH = srcH / 2;

        int x0 = (int)fx;
        int y0 = (int)fy;
        int x1 = std::min(x0 + 1, uvSrcW - 1);
        int y1 = std::min(y0 + 1, uvSrcH - 1);
        x0 = std::max(x0, 0);
        y0 = std::max(y0, 0);

        float dx = fx - x0;
        float dy = fy - y0;

        auto get = [&](int x, int y) -> uint8_t {
            return usrc[srcFrameSize + y * srcW + x * 2 + channel];
        };

        float top    = get(x0, y0) * (1 - dx) + get(x1, y0) * dx;
        float bottom = get(x0, y1) * (1 - dx) + get(x1, y1) * dx;
        return (uint8_t)(top * (1 - dy) + bottom * dy);
    };

    // ───────────────
    // Y plane
    // ───────────────
    for (int j = 0; j < dstH; j++) {
        float fy = (j + offsetY) * invScale;
        int dstRow = j * dstW;

        for (int i = 0; i < dstW; i++) {
            float fx = (i + offsetX) * invScale;
            udst[dstRow + i] = sampleY(fx, fy);
        }
    }

    // ───────────────
    // UV plane (NV21)
    // ───────────────
    for (int j = 0; j < dstH / 2; j++) {
        // Coordenada UV fonte em espaço UV (já dividido por 2)
        float fy = ((j * 2 + offsetY) * invScale) * 0.5f;
        int dstRow = dstFrameSize + j * dstW;

        for (int i = 0; i < dstW / 2; i++) {
            float fx = ((i * 2 + offsetX) * invScale) * 0.5f;

            udst[dstRow + i * 2]     = sampleUV(fx, fy, 0); // V
            udst[dstRow + i * 2 + 1] = sampleUV(fx, fy, 1); // U
        }
    }

    env->ReleaseByteArrayElements(srcArray, src, JNI_ABORT);
    env->ReleaseByteArrayElements(dstArray, dst, 0);
}

//
//extern "C"
//JNIEXPORT void JNICALL
//Java_br_dev_michaellopes_flutter_1anycam_utils_YuvUtil_cropNv21JNI(
//        JNIEnv *env,
//        jclass,
//        jbyteArray srcArray,
//        jint srcW,
//        jint srcH,
//        jbyteArray dstArray,
//        jint cropX,
//        jint cropY,
//        jint cropW,
//        jint cropH) {
//
//    cropX &= ~1;
//    cropY &= ~1;
//    cropW &= ~1;
//    cropH &= ~1;
//
//    jbyte* src = env->GetByteArrayElements(srcArray, nullptr);
//    jbyte* dst = env->GetByteArrayElements(dstArray, nullptr);
//
//    auto* usrc = reinterpret_cast<uint8_t*>(src);
//    auto* udst = reinterpret_cast<uint8_t*>(dst);
//
//    int srcFrameSize = srcW * srcH;
//    int dstFrameSize = cropW * cropH;
//
//
//    for (int y = 0; y < cropH; y++) {
//
//        int srcOffset = (cropY + y) * srcW + cropX;
//        int dstOffset = y * cropW;
//
//        memcpy(
//                udst + dstOffset,
//                usrc + srcOffset,
//                cropW
//        );
//    }
//
//
//    int uvSrcStart = srcFrameSize;
//    int uvDstStart = dstFrameSize;
//
//    int uvCropY = cropY / 2;
//    int uvCropH = cropH / 2;
//
//    for (int y = 0; y < uvCropH; y++) {
//
//        int srcOffset = uvSrcStart + (uvCropY + y) * srcW + cropX;
//        int dstOffset = uvDstStart + y * cropW;
//
//        memcpy(
//                udst + dstOffset,
//                usrc + srcOffset,
//                cropW
//        );
//    }
//
//    env->ReleaseByteArrayElements(srcArray, src, JNI_ABORT);
//    env->ReleaseByteArrayElements(dstArray, dst, 0);
//}

extern "C"
JNIEXPORT void JNICALL
Java_br_dev_michaellopes_flutter_1anycam_utils_NativeUtil_cropNv21JNI(
        JNIEnv *env,
        jclass,
        jbyteArray srcArray,
        jint srcW,
        jint srcH,
        jbyteArray dstArray,
        jint cropX,
        jint cropY,
        jint cropW,
        jint cropH) {

    cropX &= ~1;
    cropY &= ~1;
    cropW &= ~1;
    cropH &= ~1;

    jbyte* src = env->GetByteArrayElements(srcArray, nullptr);
    jbyte* dst = env->GetByteArrayElements(dstArray, nullptr);

    uint8_t* usrc = reinterpret_cast<uint8_t*>(src);
    uint8_t* udst = reinterpret_cast<uint8_t*>(dst);

    int srcFrameSize = srcW * srcH;
    int dstFrameSize = cropW * cropH;

    // Y PLANE
    uint8_t* srcY = usrc + cropY * srcW + cropX;
    uint8_t* dstY = udst;

    for (int y = 0; y < cropH; y++) {
        memcpy(dstY, srcY, cropW);
        srcY += srcW;
        dstY += cropW;
    }

    // UV PLANE
    int uvSrcStart = srcFrameSize;
    int uvDstStart = dstFrameSize;

    int uvCropY = cropY / 2;
    int uvCropH = cropH / 2;

    uint8_t* srcUV = usrc + uvSrcStart + uvCropY * srcW + cropX;
    uint8_t* dstUV = udst + uvDstStart;

    for (int y = 0; y < uvCropH; y++) {
        memcpy(dstUV, srcUV, cropW);
        srcUV += srcW;
        dstUV += cropW;
    }

    env->ReleaseByteArrayElements(srcArray, src, JNI_ABORT);
    env->ReleaseByteArrayElements(dstArray, dst, 0);
}


extern "C"
JNIEXPORT void JNICALL
Java_br_dev_michaellopes_flutter_1anycam_utils_NativeUtil_nv21ToNv12JNI(
        JNIEnv* env,
        jclass clazz,
        jobject nv21Buffer,
        jobject nv12Buffer,
        jint width,
        jint height) {

    uint8_t* src = (uint8_t*) env->GetDirectBufferAddress(nv21Buffer);
    uint8_t* dst = (uint8_t*) env->GetDirectBufferAddress(nv12Buffer);

    int frameSize = width * height;
    int chromaSize = frameSize / 2;

    // Copia Y direto
    memcpy(dst, src, frameSize);

    // Converte UV (troca V com U)
    for (int i = 0; i < chromaSize; i += 2) {
        dst[frameSize + i]     = src[frameSize + i + 1]; // U
        dst[frameSize + i + 1] = src[frameSize + i];     // V
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_br_dev_michaellopes_flutter_1anycam_utils_NativeUtil_rotateNV21JNI(
        JNIEnv *env,
        jobject thiz,
        jbyteArray input,
        jbyteArray output,
        jint width,
        jint height,
        jint rotation) {

    jbyte* inputBytes = env->GetByteArrayElements(input, nullptr);
    jbyte* outputBytes = env->GetByteArrayElements(output, nullptr);

    int inputLength = env->GetArrayLength(input);

    uint8_t* in = (uint8_t*)inputBytes;
    uint8_t* out = (uint8_t*)outputBytes;

    if (rotation == 0) {
        memcpy(out, in, inputLength);
    } else {

        int outWidth = (rotation == 90 || rotation == 270) ? height : width;
        int outHeight = (rotation == 90 || rotation == 270) ? width : height;

        // ----- Y plane -----
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {

                int outX, outY;

                switch (rotation) {
                    case 90:
                        outX = height - 1 - y;
                        outY = x;
                        break;

                    case 180:
                        outX = width - 1 - x;
                        outY = height - 1 - y;
                        break;

                    case 270:
                        outX = y;
                        outY = width - 1 - x;
                        break;

                    default:
                        outX = x;
                        outY = y;
                }

                out[outY * outWidth + outX] = in[y * width + x];
            }
        }

        // ----- UV plane -----
        int uvHeight = height / 2;
        int uvWidth = width / 2;

        int outUvWidth = outWidth / 2;

        int ySize = width * height;
        int outYSize = outWidth * outHeight;

        for (int y = 0; y < uvHeight; y++) {
            for (int x = 0; x < uvWidth; x++) {

                int outX, outY;

                switch (rotation) {
                    case 90:
                        outX = uvHeight - 1 - y;
                        outY = x;
                        break;

                    case 180:
                        outX = uvWidth - 1 - x;
                        outY = uvHeight - 1 - y;
                        break;

                    case 270:
                        outX = y;
                        outY = uvWidth - 1 - x;
                        break;

                    default:
                        outX = x;
                        outY = y;
                }

                int inIndex = ySize + (y * uvWidth + x) * 2;
                int outIndex = outYSize + (outY * outUvWidth + outX) * 2;

                out[outIndex] = in[inIndex];         // V
                out[outIndex + 1] = in[inIndex + 1]; // U
            }
        }
    }

    env->ReleaseByteArrayElements(input, inputBytes, JNI_ABORT);
    env->ReleaseByteArrayElements(output, outputBytes, 0);
}


extern "C"
JNIEXPORT void JNICALL
Java_br_dev_michaellopes_flutter_1anycam_utils_NativeUtil_normalizeNative(
        JNIEnv *env,
        jclass clazz,
        jobject srcBuffer,
        jobject outBuffer,
        jint pixelCount,
        jint dataType,
        jint normalization,
        jfloat invScale,
        jint zeroPoint,
        jfloat meanR, jfloat meanG, jfloat meanB,
        jfloat stdR, jfloat stdG, jfloat stdB
) {
    uint32_t *src = (uint32_t *)env->GetDirectBufferAddress(srcBuffer);
    uint8_t *outBytes = (uint8_t *)env->GetDirectBufferAddress(outBuffer);

    if (!src || !outBytes) return;

    float scaleR, scaleG, scaleB;
    float offsetR, offsetG, offsetB;

    switch (normalization) {
        case 0: // simple
            scaleR = scaleG = scaleB = 1.0f / 255.0f;
            offsetR = offsetG = offsetB = 0.0f;
            break;
        case 1: // centered
            scaleR = scaleG = scaleB = 1.0f / 127.5f;
            offsetR = offsetG = offsetB = -1.0f;
            break;
        case 2: // imagenet
            scaleR = 1.0f / (255.0f * 0.229f);
            scaleG = 1.0f / (255.0f * 0.224f);
            scaleB = 1.0f / (255.0f * 0.225f);
            offsetR = -0.485f / 0.229f;
            offsetG = -0.456f / 0.224f;
            offsetB = -0.406f / 0.225f;
            break;
        case 3: // custom
            scaleR = 1.0f / (255.0f * stdR);
            scaleG = 1.0f / (255.0f * stdG);
            scaleB = 1.0f / (255.0f * stdB);
            offsetR = -meanR / stdR;
            offsetG = -meanG / stdG;
            offsetB = -meanB / stdB;
            break;
        default:
            scaleR = scaleG = scaleB = 1.0f;
            offsetR = offsetG = offsetB = 0.0f;
            break;
    }

    float lutR[256], lutG[256], lutB[256];

    for (int i = 0; i < 256; i++) {
        lutR[i] = i * scaleR + offsetR;
        lutG[i] = i * scaleG + offsetG;
        lutB[i] = i * scaleB + offsetB;
    }

    if (dataType == 0) {
        float *out = (float *) outBytes;

        for (int i = 0; i < pixelCount; i++) {
            uint32_t p = src[i];

            out[i * 3 + 0] = lutR[(p >> 16) & 0xFF];
            out[i * 3 + 1] = lutG[(p >> 8) & 0xFF];
            out[i * 3 + 2] = lutB[p & 0xFF];
        }
    }
    else if (dataType == 1) {
        uint8_t lutRb[256], lutGb[256], lutBb[256];

        for (int i = 0; i < 256; i++) {
            int r = roundf(lutR[i]);
            int g = roundf(lutG[i]);
            int b = roundf(lutB[i]);

            lutRb[i] = (uint8_t)(r < 0 ? 0 : (r > 255 ? 255 : r));
            lutGb[i] = (uint8_t)(g < 0 ? 0 : (g > 255 ? 255 : g));
            lutBb[i] = (uint8_t)(b < 0 ? 0 : (b > 255 ? 255 : b));
        }

        for (int i = 0; i < pixelCount; i++) {
            uint32_t p = src[i];

            outBytes[i * 3 + 0] = lutRb[(p >> 16) & 0xFF];
            outBytes[i * 3 + 1] = lutGb[(p >> 8) & 0xFF];
            outBytes[i * 3 + 2] = lutBb[p & 0xFF];
        }
    }
    else if (dataType == 2) {
        int8_t *out = (int8_t *) outBytes;
        int8_t lutRb[256], lutGb[256], lutBb[256];

        for (int i = 0; i < 256; i++) {
            int r = roundf(lutR[i] * invScale) + zeroPoint;
            int g = roundf(lutG[i] * invScale) + zeroPoint;
            int b = roundf(lutB[i] * invScale) + zeroPoint;

            lutRb[i] = (int8_t)(r < -128 ? -128 : (r > 127 ? 127 : r));
            lutGb[i] = (int8_t)(g < -128 ? -128 : (g > 127 ? 127 : g));
            lutBb[i] = (int8_t)(b < -128 ? -128 : (b > 127 ? 127 : b));
        }

        for (int i = 0; i < pixelCount; i++) {
            uint32_t p = src[i];

            out[i * 3 + 0] = lutRb[(p >> 16) & 0xFF];
            out[i * 3 + 1] = lutGb[(p >> 8) & 0xFF];
            out[i * 3 + 2] = lutBb[p & 0xFF];
        }
    }
}

//extern "C"
//JNIEXPORT jbyteArray JNICALL
//Java_br_dev_michaellopes_flutter_1anycam_utils_YuvUtil_rotateNV21JNI(
//    JNIEnv *env,
//    jobject thiz,
//    jbyteArray input,
//    jint width,
//    jint height,
//    jint rotation) {
//
//    jbyte* inputBytes = env->GetByteArrayElements(input, nullptr);
//    jsize inputLength = env->GetArrayLength(input);
//
//    auto* output = (uint8_t*)malloc(inputLength);
//    if (output == nullptr) {
//        env->ReleaseByteArrayElements(input, inputBytes, JNI_ABORT);
//        return nullptr;
//    }
//
//    // Sem rotação - apenas copia
//    if (rotation == 0) {
//        memcpy(output, inputBytes, inputLength);
//    } else {
//        // Dimensões de saída (trocam se rotação for 90 ou 270)
//        int outWidth = (rotation == 90 || rotation == 270) ? height : width;
//        int outHeight = (rotation == 90 || rotation == 270) ? width : height;
//
//        // Rotaciona o plano Y
//        for (int y = 0; y < height; y++) {
//            for (int x = 0; x < width; x++) {
//                int outX, outY;
//
//                switch (rotation) {
//                    case 90:
//                        outX = height - 1 - y;
//                        outY = x;
//                        break;
//                    case 180:
//                        outX = width - 1 - x;
//                        outY = height - 1 - y;
//                        break;
//                    case 270:
//                        outX = y;
//                        outY = width - 1 - x;
//                        break;
//                    default:
//                        outX = x;
//                        outY = y;
//                        break;
//                }
//
//                output[outY * outWidth + outX] = inputBytes[y * width + x];
//            }
//        }
//
//        // Rotaciona o plano UV (NV21 = VU interleaved)
//        int uvHeight = height / 2;
//        int uvWidth = width / 2;
//        int outUvWidth = outWidth / 2;
//        int ySize = width * height;
//        int outYSize = outWidth * outHeight;
//
//        for (int y = 0; y < uvHeight; y++) {
//            for (int x = 0; x < uvWidth; x++) {
//                int outX, outY;
//
//                switch (rotation) {
//                    case 90:
//                        outX = uvHeight - 1 - y;
//                        outY = x;
//                        break;
//                    case 180:
//                        outX = uvWidth - 1 - x;
//                        outY = uvHeight - 1 - y;
//                        break;
//                    case 270:
//                        outX = y;
//                        outY = uvWidth - 1 - x;
//                        break;
//                    default:
//                        outX = x;
//                        outY = y;
//                        break;
//                }
//
//                int inIndex = ySize + (y * uvWidth + x) * 2;
//                int outIndex = outYSize + (outY * outUvWidth + outX) * 2;
//
//                // Copia V e U (NV21 format)
//                output[outIndex] = inputBytes[inIndex];         // V
//                output[outIndex + 1] = inputBytes[inIndex + 1]; // U
//            }
//        }
//    }
//
//    jbyteArray result = env->NewByteArray(inputLength);
//    env->SetByteArrayRegion(result, 0, inputLength, (jbyte*)output);
//
//    env->ReleaseByteArrayElements(input, inputBytes, JNI_ABORT);
//    free(output);
//
//    return result;
//}