#include <jni.h>
#include <string>
#include <opencv2/opencv.hpp>
#include <android/bitmap.h>
#include <vector>
#include <android/log.h>

#define LOG_TAG "LuminaNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

using namespace cv;

/**
 * Utility to convert Android Bitmap to OpenCV Mat
 */
void bitmapToMat(JNIEnv* env, jobject bitmap, Mat& mat) {
    AndroidBitmapInfo info;
    void* pixels = 0;
    AndroidBitmap_getInfo(env, bitmap, &info);
    AndroidBitmap_lockPixels(env, bitmap, &pixels);
    mat.create(info.height, info.width, CV_8UC4);
    if (info.format == ANDROID_BITMAP_FORMAT_RGBA_8888) {
        Mat tmp(info.height, info.width, CV_8UC4, pixels);
        tmp.copyTo(mat);
    }
    AndroidBitmap_unlockPixels(env, bitmap);
}

/**
 * Utility to convert OpenCV Mat back to Android Bitmap
 */
void matToBitmap(JNIEnv* env, Mat& mat, jobject bitmap) {
    AndroidBitmapInfo info;
    void* pixels = 0;
    AndroidBitmap_getInfo(env, bitmap, &info);
    AndroidBitmap_lockPixels(env, bitmap, &pixels);
    if (info.format == ANDROID_BITMAP_FORMAT_RGBA_8888) {
        Mat tmp(info.height, info.width, CV_8UC4, pixels);
        if (mat.type() == CV_8UC3) {
            cvtColor(mat, tmp, COLOR_RGB2RGBA);
        } else {
            mat.copyTo(tmp);
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_lumina_engine_core_UpscaleManager_applyLuminanceReinjection(
    JNIEnv* env, jobject thiz, jobject ai_bitmap, jobject original_low_res, jfloat strength) {
    
    Mat aiMat, lowResMat;
    bitmapToMat(env, ai_bitmap, aiMat);
    bitmapToMat(env, original_low_res, lowResMat);

    Mat aiRGB, lowResRGB;
    if (aiMat.channels() == 4) cvtColor(aiMat, aiRGB, COLOR_RGBA2RGB); else aiRGB = aiMat;
    if (lowResMat.channels() == 4) cvtColor(lowResMat, lowResRGB, COLOR_RGBA2RGB); else lowResRGB = lowResMat;

    Mat upscaledOriginal;
    resize(lowResRGB, upscaledOriginal, aiRGB.size(), 0, 0, INTER_AREA);

    Mat aiYUV, origYUV;
    cvtColor(aiRGB, aiYUV, COLOR_RGB2YUV);
    cvtColor(upscaledOriginal, origYUV, COLOR_RGB2YUV);

    std::vector<Mat> aiChannels, origChannels;
    split(aiYUV, aiChannels);
    split(origYUV, origChannels);

    addWeighted(aiChannels[0], 1.0 - strength, origChannels[0], strength, 0, aiChannels[0]);

    merge(aiChannels, aiYUV);
    cvtColor(aiYUV, aiRGB, COLOR_YUV2RGB);
    
    matToBitmap(env, aiRGB, ai_bitmap);
}

JNIEXPORT void JNICALL
Java_com_lumina_engine_core_ColorScience_applyLocalLaplacian(
    JNIEnv* env, jobject thiz, jobject bitmap, jfloat sigma, jfloat fact) {
    Mat mat;
    bitmapToMat(env, bitmap, mat);
    
    Mat gray, log;
    if (mat.channels() == 4) cvtColor(mat, gray, COLOR_RGBA2GRAY); else cvtColor(mat, gray, COLOR_RGB2GRAY);
    
    GaussianBlur(gray, log, Size(0, 0), sigma);
    addWeighted(gray, 1.0 + fact, log, -fact, 0, gray);
    
    if (mat.channels() == 4) cvtColor(gray, mat, COLOR_GRAY2RGBA); else cvtColor(gray, mat, COLOR_GRAY2RGB);
    
    matToBitmap(env, mat, bitmap);
}

JNIEXPORT void JNICALL
Java_com_lumina_engine_core_TextureManager_applyAdaptiveGrain(
    JNIEnv* env, jobject thiz, jobject bitmap, jfloat strength) {
    Mat frame;
    bitmapToMat(env, bitmap, frame);
    
    Mat frameRGB;
    if (frame.channels() == 4) cvtColor(frame, frameRGB, COLOR_RGBA2RGB); else frameRGB = frame;

    Mat noise = Mat(frameRGB.size(), frameRGB.type());
    randn(noise, 0, strength * 25.5f);

    Mat gray, mask;
    cvtColor(frameRGB, gray, COLOR_RGB2GRAY);
    bitwise_not(gray, mask);
    
    GaussianBlur(mask, mask, Size(7, 7), 0);
    mask.convertTo(mask, CV_32F, 1.0/255.0);

    Mat noiseF;
    noise.convertTo(noiseF, CV_32FC3);
    
    std::vector<Mat> channels;
    split(noiseF, channels);
    for(int i=0; i<3; i++) {
        multiply(channels[i], mask, channels[i]);
    }
    merge(channels, noiseF);
    
    Mat finalNoise;
    noiseF.convertTo(finalNoise, CV_8UC3);
    add(frameRGB, finalNoise, frameRGB);

    matToBitmap(env, frameRGB, bitmap);
}

JNIEXPORT void JNICALL
Java_com_lumina_engine_core_ColorScience_applyAdaptiveSharpen(
    JNIEnv* env, jobject thiz, jobject bitmap, jfloat amount) {
    Mat frame;
    bitmapToMat(env, frame, frame);

    Mat frameRGB;
    if (frame.channels() == 4) cvtColor(frame, frameRGB, COLOR_RGBA2RGB); else frameRGB = frame;

    Mat blurred;
    GaussianBlur(frameRGB, blurred, Size(0, 0), 3);
    addWeighted(frameRGB, 1.0 + amount, blurred, -amount, 0, frameRGB);

    matToBitmap(env, frameRGB, bitmap);
}

} // extern "C"
