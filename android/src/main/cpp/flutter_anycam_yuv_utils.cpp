#include <jni.h>
#include <cstdint>
#include <cstring>
#include <cstdlib> 
#include <android/log.h>
#include <__algorithm/max.h>
#include <__algorithm/min.h>

#define LOG_TAG "YuvUtils"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_br_dev_michaellopes_flutter_1anycam_utils_YuvUtil_yuv420888ToNv21JNI(
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
Java_br_dev_michaellopes_flutter_1anycam_utils_YuvUtil_yuv420888ToNv21IntoJNI(
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

//extern "C"
//JNIEXPORT void JNICALL
//Java_br_dev_michaellopes_flutter_1anycam_utils_YuvUtil_resizeNv21JNI(
//        JNIEnv *env,
//        jclass,
//        jbyteArray srcArray,
//        jint srcW,
//        jint srcH,
//        jbyteArray dstArray,
//        jint dstW,
//        jint dstH) {
//
//    dstW &= ~1;
//    dstH &= ~1;
//
//    jbyte* src = env->GetByteArrayElements(srcArray, nullptr);
//    jbyte* dst = env->GetByteArrayElements(dstArray, nullptr);
//
//    int srcFrameSize = srcW * srcH;
//    int dstFrameSize = dstW * dstH;
//
//    // ─────────────────────────────
//    // Calcular escala preservando proporção
//    // ─────────────────────────────
//    float scale = std::max(
//            (float)dstW / srcW,
//            (float)dstH / srcH
//    );
//
//    int scaledW = (int)(srcW * scale);
//    int scaledH = (int)(srcH * scale);
//
//    int offsetX = (scaledW - dstW) / 2;
//    int offsetY = (scaledH - dstH) / 2;
//
//    float invScale = 1.0f / scale;
//
//    // ───────────────
//    // Y plane
//    // ───────────────
//    for (int j = 0; j < dstH; j++) {
//
//        int srcY = (int)((j + offsetY) * invScale);
//        int dstRow = j * dstW;
//
//        for (int i = 0; i < dstW; i++) {
//
//            int srcX = (int)((i + offsetX) * invScale);
//            dst[dstRow + i] = src[srcY * srcW + srcX];
//        }
//    }
//
//    // ───────────────
//    // UV plane (NV21)
//    // ───────────────
//    int srcUV = srcFrameSize;
//    int dstUV = dstFrameSize;
//
//    for (int j = 0; j < dstH / 2; j++) {
//
//        int srcYuv = (int)(((j * 2 + offsetY) * invScale) / 2);
//        int dstRow = dstUV + j * dstW;
//
//        for (int i = 0; i < dstW / 2; i++) {
//
//            int srcXuv = (int)(((i * 2 + offsetX) * invScale) / 2);
//
//            int srcIndex = srcUV + srcYuv * srcW + srcXuv * 2;
//            int dstIndex = dstRow + i * 2;
//
//            dst[dstIndex]     = src[srcIndex];     // V
//            dst[dstIndex + 1] = src[srcIndex + 1]; // U
//        }
//    }
//
//    env->ReleaseByteArrayElements(srcArray, src, JNI_ABORT);
//    env->ReleaseByteArrayElements(dstArray, dst, 0);
//}

extern "C"
JNIEXPORT void JNICALL
Java_br_dev_michaellopes_flutter_1anycam_utils_YuvUtil_resizeNv21JNI(
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

extern "C"
JNIEXPORT void JNICALL
Java_br_dev_michaellopes_flutter_1anycam_utils_YuvUtil_nv21ToNv12JNI(
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
JNIEXPORT jbyteArray JNICALL
Java_br_dev_michaellopes_flutter_1anycam_utils_YuvUtil_rotateNV21JNI(
    JNIEnv *env, 
    jobject thiz,
    jbyteArray input, 
    jint width,
    jint height, 
    jint rotation) {
    
    jbyte* inputBytes = env->GetByteArrayElements(input, nullptr);
    jsize inputLength = env->GetArrayLength(input);
    
    auto* output = (uint8_t*)malloc(inputLength);
    if (output == nullptr) {
        env->ReleaseByteArrayElements(input, inputBytes, JNI_ABORT);
        return nullptr;
    }
    
    // Sem rotação - apenas copia
    if (rotation == 0) {
        memcpy(output, inputBytes, inputLength);
    } else {
        // Dimensões de saída (trocam se rotação for 90 ou 270)
        int outWidth = (rotation == 90 || rotation == 270) ? height : width;
        int outHeight = (rotation == 90 || rotation == 270) ? width : height;
        
        // Rotaciona o plano Y
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
                        break;
                }
                
                output[outY * outWidth + outX] = inputBytes[y * width + x];
            }
        }
        
        // Rotaciona o plano UV (NV21 = VU interleaved)
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
                        break;
                }
                
                int inIndex = ySize + (y * uvWidth + x) * 2;
                int outIndex = outYSize + (outY * outUvWidth + outX) * 2;
                
                // Copia V e U (NV21 format)
                output[outIndex] = inputBytes[inIndex];         // V
                output[outIndex + 1] = inputBytes[inIndex + 1]; // U
            }
        }
    }
    
    jbyteArray result = env->NewByteArray(inputLength);
    env->SetByteArrayRegion(result, 0, inputLength, (jbyte*)output);
    
    env->ReleaseByteArrayElements(input, inputBytes, JNI_ABORT);
    free(output);
    
    return result;
}