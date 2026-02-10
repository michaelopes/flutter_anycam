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
Java_br_dev_michaellopes_flutter_1anycam_utils_YuvUtil_yuv420ToNv21JNI(
        JNIEnv* env,
        jclass clazz,
        jobject yBuffer,
        jobject uBuffer,
        jobject vBuffer,
        jint width,
        jint height,
        jint yRowStride,
        jint uvRowStride,
        jint uvPixelStride) {

    int ySize = width * height;
    int uvSize = width * height / 2;

    jbyteArray nv21Array = env->NewByteArray(ySize + uvSize);
    jbyte* nv21 = env->GetByteArrayElements(nv21Array, nullptr);

    uint8_t* yData = (uint8_t*) env->GetDirectBufferAddress(yBuffer);
    uint8_t* uData = (uint8_t*) env->GetDirectBufferAddress(uBuffer);
    uint8_t* vData = (uint8_t*) env->GetDirectBufferAddress(vBuffer);

    // Copy Y plane
    if (yRowStride == width) {
        memcpy(nv21, yData, ySize);
    } else {
        for (int i = 0; i < height; i++) {
            memcpy(nv21 + i * width, yData + i * yRowStride, width);
        }
    }

    // Copy interleaved VU
    int uvIndex = ySize;
    for (int row = 0; row < height / 2; row++) {
        uint8_t* uRow = uData + row * uvRowStride;
        uint8_t* vRow = vData + row * uvRowStride;
        for (int col = 0; col < width / 2; col++) {
            nv21[uvIndex++] = vRow[col * uvPixelStride]; // V
            nv21[uvIndex++] = uRow[col * uvPixelStride]; // U
        }
    }

    env->ReleaseByteArrayElements(nv21Array, nv21, 0);
    return nv21Array;
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