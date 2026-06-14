#include <jni.h>
#include <cstdint>
#include <cstring>
#include <cstdlib>
#include <android/log.h>

#define LOG_TAG "YuvUtils"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C"
JNIEXPORT jobject JNICALL
Java_br_dev_michaellopes_flutter_1anycam_webrtc_YuvUtil_normalizeToI420JNI(
        JNIEnv* env, jclass clazz, jobject src) {

    jclass cls = env->GetObjectClass(src);

    jint width = env->GetIntField(src, env->GetFieldID(cls, "width", "I"));
    jint height = env->GetIntField(src, env->GetFieldID(cls, "height", "I"));
    jint strideY = env->GetIntField(src, env->GetFieldID(cls, "strideY", "I"));
    jint strideU = env->GetIntField(src, env->GetFieldID(cls, "strideU", "I"));
    jint strideV = env->GetIntField(src, env->GetFieldID(cls, "strideV", "I"));
    jint pixelStrideU = env->GetIntField(src, env->GetFieldID(cls, "pixelStrideU", "I"));
    jint pixelStrideV = env->GetIntField(src, env->GetFieldID(cls, "pixelStrideV", "I"));
    jint rotation = env->GetIntField(src, env->GetFieldID(cls, "rotation", "I"));

    jobject dataY = env->GetObjectField(src, env->GetFieldID(cls, "dataY", "Ljava/nio/ByteBuffer;"));
    jobject dataU = env->GetObjectField(src, env->GetFieldID(cls, "dataU", "Ljava/nio/ByteBuffer;"));
    jobject dataV = env->GetObjectField(src, env->GetFieldID(cls, "dataV", "Ljava/nio/ByteBuffer;"));

    uint8_t* srcY = reinterpret_cast<uint8_t*>(env->GetDirectBufferAddress(dataY));
    uint8_t* srcU = reinterpret_cast<uint8_t*>(env->GetDirectBufferAddress(dataU));
    uint8_t* srcV = reinterpret_cast<uint8_t*>(env->GetDirectBufferAddress(dataV));

    int chromaWidth = width / 2;
    int chromaHeight = height / 2;

    uint8_t* outY = (uint8_t*) malloc(width * height);
    uint8_t* outU = (uint8_t*) malloc(chromaWidth * chromaHeight);
    uint8_t* outV = (uint8_t*) malloc(chromaWidth * chromaHeight);

    for (int row = 0; row < height; row++) {
        for (int col = 0; col < width; col++) {
            outY[row * width + col] = srcY[row * strideY + col];
        }
    }

    if (pixelStrideU == 1 && pixelStrideV == 1) {
        for (int row = 0; row < chromaHeight; row++) {
            for (int col = 0; col < chromaWidth; col++) {
                outU[row * chromaWidth + col] = srcU[row * strideU + col];
                outV[row * chromaWidth + col] = srcV[row * strideV + col];
            }
        }
    } else {
        for (int row = 0; row < chromaHeight; row++) {
            for (int col = 0; col < chromaWidth; col++) {
                outU[row * chromaWidth + col] = srcU[row * strideU + col * pixelStrideU];
                outV[row * chromaWidth + col] = srcV[row * strideV + col * pixelStrideV];
            }
        }
    }

    jobject bufY = env->NewDirectByteBuffer(outY, width * height);
    jobject bufU = env->NewDirectByteBuffer(outU, chromaWidth * chromaHeight);
    jobject bufV = env->NewDirectByteBuffer(outV, chromaWidth * chromaHeight);

    jclass i420Cls = env->FindClass("br/dev/michaellopes/flutter_anycam/webrtc/I420Image");
    jmethodID ctor = env->GetMethodID(i420Cls, "<init>",
                                      "(IIILjava/nio/ByteBuffer;ILjava/nio/ByteBuffer;ILjava/nio/ByteBuffer;III)V");

    jobject outImg = env->NewObject(i420Cls, ctor,
                                    width, height, rotation,
                                    bufY, width,
                                    bufU, chromaWidth,
                                    bufV, chromaWidth,
                                    1, 1);

    return outImg;
}

extern "C"
JNIEXPORT void JNICALL
Java_br_dev_michaellopes_flutter_1anycam_webrtc_YuvUtil_releaseNativeBuffer(
        JNIEnv* env, jclass clazz, jobject buffer) {

    if (buffer == nullptr) return;

    void* nativeBuffer = env->GetDirectBufferAddress(buffer);
    if (nativeBuffer != nullptr) {
        free(nativeBuffer);
        LOGD("Memória nativa liberada: %p", nativeBuffer);
    }
}
