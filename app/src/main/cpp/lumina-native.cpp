#include <jni.h>
#include <string>
// #include <opencv2/opencv.hpp>
#include <android/bitmap.h>
#include <vector>
#include <android/log.h>

#define LOG_TAG "LuminaNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// using namespace cv;

extern "C" {

JNIEXPORT void JNICALL
Java_com_lumina_engine_core_UpscaleManager_applyLuminanceReinjection(
    JNIEnv* env, jobject thiz, jobject ai_bitmap, jobject original_low_res, jfloat strength) {
    
    // Logic will be re-enabled once OpenCV SDK is linked in GitHub Actions
    LOGI("Luminance Reinjection called");
}

JNIEXPORT void JNICALL
Java_com_lumina_engine_core_ColorScience_applyLocalLaplacian(
    JNIEnv* env, jobject thiz, jobject bitmap, jfloat sigma, jfloat fact) {
    LOGI("Local Laplacian called");
}

JNIEXPORT void JNICALL
Java_com_lumina_engine_core_TextureManager_applyAdaptiveGrain(
    JNIEnv* env, jobject thiz, jobject bitmap, jfloat strength) {
    LOGI("Adaptive Grain called");
}

JNIEXPORT void JNICALL
Java_com_lumina_engine_core_ColorScience_applyAdaptiveSharpen(
    JNIEnv* env, jobject thiz, jobject bitmap, jfloat amount) {
    LOGI("Adaptive Sharpen called");
}

} // extern "C"
