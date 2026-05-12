package com.lumina.engine.core

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Handles Super-Resolution and Texture Injection.
 * Model: Real-ESRGAN-AnimeVideo-v3
 */
class UpscaleManager(context: Context) {

    private var interpreter: Interpreter? = null

    init {
        setupInterpreter(context)
    }

    private fun setupInterpreter(context: Context) {
        val options = Interpreter.Options().apply {
            // 1. Try to add GPU Delegate for acceleration (may fail on some devices)
            try {
                val gpuDelegate = GpuDelegate()
                addDelegate(gpuDelegate)
            } catch (e: Exception) {
                e.printStackTrace()
                // GPU not available, fallback to CPU
            }
            
            // 2. Multi-threaded processing if falls back to CPU
            setNumThreads(4)
        }

        // 3. Load FP16 Model (Gold standard for Pixel-like quality)
        try {
            val modelBuffer = loadModelFile(context, "real_esrgan_fp16.tflite")
            interpreter = Interpreter(modelBuffer, options)
        } catch (e: Exception) {
            e.printStackTrace()
            interpreter = null
        }
    }

    private fun loadModelFile(context: Context, modelName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.startOffset, fileDescriptor.declaredLength)
    }

    /**
     * Native call for Luminance Re-injection
     */
    private external fun applyLuminanceReinjection(
        aiBitmap: Bitmap, 
        originalLowRes: Bitmap, 
        strength: Float
    )

    fun upscale(bitmap: Bitmap): Bitmap {
        // TFLite Real-ESRGAN inference
        val highResBitmap = runInference(bitmap) 
        
        // Luminance Re-injection: Blend original texture back at 15% strength
        // (Sadece OpenCV varsa çalışır, yoksa atlanır)
        try {
            applyLuminanceReinjection(highResBitmap, bitmap, 0.15f)
        } catch (e: Exception) {
            // OpenCV/native kütüphane yoksa atla
        }
        
        return highResBitmap
    }

    private fun runInference(bitmap: Bitmap): Bitmap {
        // Eğer interpreter yoksa, basit bicubic upscale yap (model yoksa bile çalışır)
        val interpreter = interpreter ?: return simpleUpscale(bitmap)
        
        try {
            // Real-ESRGAN TFLite modeli için optimize edilmiş parametreler
            val inputSize = 128 // Real-ESRGAN modelleri genellikle 128x128 girdi alır
            val scaleFactor = 4 // x4 upscale (128 -> 512)
            val outputSize = inputSize * scaleFactor // 512
            
            // Görüntüyü modelin beklediği boyuta getir (bilinear interpolation)
            val scaledInput = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
            
            // Input buffer: [1, height, width, 3] - NHWC format
            val inputBuffer = java.nio.ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4)
            inputBuffer.order(java.nio.ByteOrder.nativeOrder())
            
            // Bitmap'i RGB float array'e çevir (0-1 aralığında, normalized)
            val intValues = IntArray(inputSize * inputSize)
            scaledInput.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize)
            
            for (pixel in intValues) {
                // RGB sırası: Model genellikle RGB bekler
                val r = ((pixel shr 16) and 0xFF) / 255.0f
                val g = ((pixel shr 8) and 0xFF) / 255.0f
                val b = (pixel and 0xFF) / 255.0f
                inputBuffer.putFloat(r)
                inputBuffer.putFloat(g)
                inputBuffer.putFloat(b)
            }
            inputBuffer.rewind() // Buffer'ı başa sar
            
            // Output buffer: [1, height*4, width*4, 3]
            val outputBuffer = java.nio.ByteBuffer.allocateDirect(1 * outputSize * outputSize * 3 * 4)
            outputBuffer.order(java.nio.ByteOrder.nativeOrder())
            
            // AI inference çalıştır
            interpreter.run(inputBuffer, outputBuffer)
            
            // Output'u bitmap'e çevir
            val upscaled = convertByteBufferToBitmap(outputBuffer, outputSize, outputSize)
            
            // Orijinal görüntünün 2x boyutuna ölçeklendir (x2 total upscale - dengeli kalite/hız)
            val finalWidth = bitmap.width * 2
            val finalHeight = bitmap.height * 2
            return Bitmap.createScaledBitmap(upscaled, finalWidth, finalHeight, true)
            
        } catch (e: Exception) {
            e.printStackTrace()
            // Model hatası olursa basit upscale yap
            return simpleUpscale(bitmap)
        }
    }

    /**
     * Basit bicubic upscale - Model çalışmazsa fallback olarak kullanılır
     */
    private fun simpleUpscale(bitmap: Bitmap): Bitmap {
        val targetWidth = bitmap.width * 2
        val targetHeight = bitmap.height * 2
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private fun convertByteBufferToBitmap(buffer: java.nio.ByteBuffer, width: Int, height: Int): Bitmap {
        // Buffer'ı başa sar (rewind) - çok önemli!
        buffer.rewind()
        
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        
        for (i in 0 until width * height) {
            // Float değerleri oku (0.0 - 1.0 aralığında)
            // Model çıktısı RGB formatında, her bir kanal ayrı ayrı
            val r = (buffer.float * 255.0f).coerceIn(0.0f, 255.0f).toInt()
            val g = (buffer.float * 255.0f).coerceIn(0.0f, 255.0f).toInt()
            val b = (buffer.float * 255.0f).coerceIn(0.0f, 255.0f).toInt()
            
            // ARGB formatında pixel oluştur (Alpha = 255 - opaque)
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }
}
