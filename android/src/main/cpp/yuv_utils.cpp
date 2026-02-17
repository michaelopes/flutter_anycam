#include <jni.h>
#include <cstdint>
#include <cstring>
#include <cstdlib> 
#include <android/log.h>

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
//Java_br_dev_michaellopes_flutter_1anycam_utils_YuvUtil_yuv420888ToNv21JNI(
//        JNIEnv* env,
//        jclass clazz,
//        jobject yBuffer,
//        jobject uBuffer,
//        jobject vBuffer,
//        jint width,
//        jint height,
//        jint yRowStride,
//        jint uRowStride,
//        jint vRowStride,
//        jint uPixelStride,
//        jint vPixelStride) {
//
//    uint8_t* yData = (uint8_t*) env->GetDirectBufferAddress(yBuffer);
//    uint8_t* uData = (uint8_t*) env->GetDirectBufferAddress(uBuffer);
//    uint8_t* vData = (uint8_t*) env->GetDirectBufferAddress(vBuffer);
//
//    if (!yData || !uData || !vData) {
//        return nullptr;
//    }
//
//    int ySize = width * height;
//    int uvSize = width * height / 2;
//
//    jbyteArray nv21Array = env->NewByteArray(ySize + uvSize);
//    jbyte* nv21 = env->GetByteArrayElements(nv21Array, nullptr);
//
//    // Copy Y plane (respeitando rowStride)
//    int pos = 0;
//    for (int row = 0; row < height; row++) {
//        memcpy(nv21 + pos, yData + row * yRowStride, width);
//        pos += width;
//    }
//
//    // Copy UV (VU interleaved = NV21)
//    int chromaHeight = height / 2;
//    int chromaWidth = width / 2;
//
//    for (int row = 0; row < chromaHeight; row++) {
//        uint8_t* uRow = uData + row * uRowStride;
//        uint8_t* vRow = vData + row * vRowStride;
//
//        for (int col = 0; col < chromaWidth; col++) {
//            nv21[pos++] = vRow[col * vPixelStride]; // V
//            nv21[pos++] = uRow[col * uPixelStride]; // U
//        }
//    }
//
//    env->ReleaseByteArrayElements(nv21Array, nv21, 0);
//    return nv21Array;
//}

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