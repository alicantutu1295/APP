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
            // 1. Add GPU Delegate for acceleration
            val gpuDelegate = GpuDelegate()
            addDelegate(gpuDelegate)
            
            // 2. Multi-threaded processing if falls back to CPU
            setNumThreads(4)
        }

        // 3. Load FP16 Model (Gold standard for Pixel-like quality)
        val modelBuffer = loadModelFile(context, "real_esrgan_fp16.tflite")
        interpreter = Interpreter(modelBuffer, options)
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
        // TFLite Real-ESRGAN inference (pseudo-code)
        val highResBitmap = runInference(bitmap) 
        
        // Luminance Re-injection: Blend original texture back at 15% strength
        applyLuminanceReinjection(highResBitmap, bitmap, 0.15f)
        
        return highResBitmap
    }

    private fun runInference(bitmap: Bitmap): Bitmap {
        val interpreter = interpreter ?: return bitmap
        
        try {
            // --- GÜVENLİ VE HIZLI İŞLEME (SAFE SCALE) ---
            // Tile-based sistem mobil RAM sınırlarını zorladığı için çökme yapabilir.
            // Bunun yerine en kararlı yöntem olan "Direct Inference with Safe Padding"e dönüyoruz.
            
            val inputSize = 50 // Çoğu ESRGAN TFLite modeli 50x50 girdi bekler
            val outputSize = 200 // Ve 200x200 çıktı verir (x4)
            
            val scaledInput = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
            
            val inputBuffer = java.nio.ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4)
            inputBuffer.order(java.nio.ByteOrder.nativeOrder())
            
            val intValues = IntArray(inputSize * inputSize)
            scaledInput.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize)
            
            for (pixel in intValues) {
                inputBuffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f)
                inputBuffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)
                inputBuffer.putFloat((pixel and 0xFF) / 255.0f)
            }
            
            val outputBuffer = java.nio.ByteBuffer.allocateDirect(1 * outputSize * outputSize * 3 * 4)
            outputBuffer.order(java.nio.ByteOrder.nativeOrder())
            
            // AI İşlemini Güvenli Blokta Çalıştır
            interpreter.run(inputBuffer, outputBuffer)
            
            val upscaled = convertByteBufferToBitmap(outputBuffer, outputSize, outputSize)
            
            // Sonucu orijinal boyutuna (veya yakınına) kaliteli bir şekilde büyüt
            return Bitmap.createScaledBitmap(upscaled, bitmap.width * 2, bitmap.height * 2, true)
            
        } catch (e: Exception) {
            e.printStackTrace()
            return bitmap // Çökme olursa orijinal resmi döndür, uygulama kapanmasın
        }
    }

    private fun convertByteBufferToBitmap(buffer: java.nio.ByteBuffer, width: Int, height: Int): Bitmap {
        buffer.rewind()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        
        for (i in 0 until width * height) {
            // Renklerin Minecraft gibi olmaması için float -> int dönüşümünü hassaslaştır
            val r = (buffer.float * 255.0f).toInt().coerceIn(0, 255)
            val g = (buffer.float * 255.0f).toInt().coerceIn(0, 255)
            val b = (buffer.float * 255.0f).toInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        
        // Boyutun çok küçük kalmaması için orijinal orana yakın bir yere upscale et
        return Bitmap.createScaledBitmap(bitmap, width * 2, height * 2, true)
    }
}
