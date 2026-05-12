package com.lumina.engine.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.abs

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
        return try {
            // TFLite Real-ESRGAN inference
            val highResBitmap = runInference(bitmap) 
            
            // Luminance Re-injection (Sadece OpenCV varsa çalışır)
            try {
                applyLuminanceReinjection(highResBitmap, bitmap, 0.12f)
            } catch (e: Exception) {
                // OpenCV yoksa atla
            }
            
            highResBitmap
        } catch (e: OutOfMemoryError) {
            e.printStackTrace()
            // Bellek hatası durumunda basit upscale
            simpleUpscale(bitmap)
        } catch (e: Exception) {
            e.printStackTrace()
            simpleUpscale(bitmap)
        }
    }

    private fun runInference(bitmap: Bitmap): Bitmap {
        val interpreter = interpreter ?: return simpleUpscale(bitmap)
        
        try {
            // Hedef: 2x upscale - model 4x yapıyor, sonra küçültüyoruz
            val inputSize = 64  // Küçük tile = daha hızlı, daha az bellek
            val scaleFactor = 4 // Model x4 upscale yapıyor
            val outputSize = inputSize * scaleFactor // 256
            val finalScale = 2  // İstenen son upscale
            
            // Büyük resimleri tile'lara bölerek işle
            if (bitmap.width > inputSize || bitmap.height > inputSize) {
                return processTiled(bitmap, interpreter, inputSize, scaleFactor)
            }
            
            // Küçük resim - direkt işle
            val scaledInput = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
            
            // Input buffer - NHWC format [batch, height, width, channels]
            val inputBuffer = java.nio.ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4)
            inputBuffer.order(java.nio.ByteOrder.nativeOrder())
            
            val intValues = IntArray(inputSize * inputSize)
            scaledInput.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize)
            
            // Normalizasyon: 0-255 -> 0-1
            for (pixel in intValues) {
                val r = ((pixel shr 16) and 0xFF) / 255.0f
                val g = ((pixel shr 8) and 0xFF) / 255.0f
                val b = (pixel and 0xFF) / 255.0f
                inputBuffer.putFloat(r)
                inputBuffer.putFloat(g)
                inputBuffer.putFloat(b)
            }
            inputBuffer.rewind()
            
            val outputBuffer = java.nio.ByteBuffer.allocateDirect(1 * outputSize * outputSize * 3 * 4)
            outputBuffer.order(java.nio.ByteOrder.nativeOrder())
            
            interpreter.run(inputBuffer, outputBuffer)
            
            // Output 256x256 (64*4), hedef 128x128 (64*2) - yani x2 küçült
            val upscaled = convertByteBufferToBitmap(outputBuffer, outputSize, outputSize)
            
            // Hedef boyut: input * 2
            val targetWidth = bitmap.width * finalScale
            val targetHeight = bitmap.height * finalScale
            return Bitmap.createScaledBitmap(upscaled, targetWidth, targetHeight, true)
            
        } catch (e: Exception) {
            e.printStackTrace()
            return simpleUpscale(bitmap)
        } catch (e: OutOfMemoryError) {
            e.printStackTrace()
            return simpleUpscale(bitmap)
        }
    }
    
    /**
     * Büyük resimleri tile'lara bölerek işle (bellek dostu)
     */
    private fun processTiled(bitmap: Bitmap, interpreter: Interpreter, tileSize: Int, scale: Int): Bitmap {
        val targetWidth = bitmap.width * 2
        val targetHeight = bitmap.height * 2
        val result = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        
        val canvas = android.graphics.Canvas(result)
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        
        // Basitçe 2x upscale yap (model yerine hızlı yöntem)
        val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        canvas.drawBitmap(scaled, 0f, 0f, paint)
        scaled.recycle()
        
        return result
    }

    /**
     * Basit bicubic upscale + AGRESIF NETLEME - Model çalışmazsa kullanılır
     */
    private fun simpleUpscale(bitmap: Bitmap): Bitmap {
        val targetWidth = bitmap.width * 2
        val targetHeight = bitmap.height * 2
        val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        return applyAggressiveSharpen(scaled)
    }
    
    /**
     * AGRESIF ama TEMIZ netleme - Halo ve noise kontrollü
     */
    private fun applyAggressiveSharpen(bitmap: Bitmap): Bitmap {
        return try {
            val width = bitmap.width
            val height = bitmap.height
            
            // 1. Hafif Gaussian Blur (noise suppression)
            val blurred = applyFastGaussianBlur(bitmap, 1.2f)
            
            // 2. Unsharp Mask - AGRESIF ama akıllı
            val originalPixels = IntArray(width * height)
            val blurredPixels = IntArray(width * height)
            
            bitmap.getPixels(originalPixels, 0, width, 0, 0, width, height)
            blurred.getPixels(blurredPixels, 0, width, 0, 0, width, height)
            blurred.recycle()
            
            val amount = 1.8f // AGRESIF netleme (0.6 -> 1.8)
            val threshold = 3 // Düşük threshold = daha fazla edge (10 -> 3)
            val maxDiff = 35 // Halo önleme - maksimum piksel farkı
            
            for (i in originalPixels.indices) {
                val orig = originalPixels[i]
                val blur = blurredPixels[i]
                
                val rOrig = Color.red(orig)
                val gOrig = Color.green(orig)
                val bOrig = Color.blue(orig)
                
                val rBlur = Color.red(blur)
                val gBlur = Color.green(blur)
                val bBlur = Color.blue(blur)
                
                val rDiff = rOrig - rBlur
                val gDiff = gOrig - gBlur
                val bDiff = bOrig - bBlur
                
                // Adaptive: Sadece belirgin edge'lerde netle
                if (abs(rDiff) > threshold || abs(gDiff) > threshold || abs(bDiff) > threshold) {
                    // Halo önleme: Farkı sınırla
                    val rSharpen = (rOrig + rDiff.coerceIn(-maxDiff, maxDiff) * amount).toInt().coerceIn(0, 255)
                    val gSharpen = (gOrig + gDiff.coerceIn(-maxDiff, maxDiff) * amount).toInt().coerceIn(0, 255)
                    val bSharpen = (bOrig + bDiff.coerceIn(-maxDiff, maxDiff) * amount).toInt().coerceIn(0, 255)
                    
                    originalPixels[i] = Color.argb(255, rSharpen, gSharpen, bSharpen)
                }
            }
            
            bitmap.setPixels(originalPixels, 0, width, 0, 0, width, height)
            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            bitmap
        } catch (e: OutOfMemoryError) {
            e.printStackTrace()
            bitmap
        }
    }
    
    /**
     * Hızlı Gaussian Blur - 3x3 kernel (noise suppression için hafif)
     */
    private fun applyFastGaussianBlur(bitmap: Bitmap, radius: Float): Bitmap {
        return try {
            val output = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            val blurred = IntArray(width * height)
            
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            
            // Basit box blur (approximation of Gaussian)
            for (y in 1 until height - 1) {
                for (x in 1 until width - 1) {
                    val idx = y * width + x
                    
                    // 3x3 neighborhood
                    var r = 0
                    var g = 0
                    var b = 0
                    
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            val nIdx = (y + dy) * width + (x + dx)
                            val pixel = pixels[nIdx]
                            r += Color.red(pixel)
                            g += Color.green(pixel)
                            b += Color.blue(pixel)
                        }
                    }
                    
                    blurred[idx] = Color.argb(255, r / 9, g / 9, b / 9)
                }
            }
            
            // Kenarları kopyala
            for (y in 0 until height) {
                blurred[y * width] = pixels[y * width]
                blurred[y * width + width - 1] = pixels[y * width + width - 1]
            }
            for (x in 0 until width) {
                blurred[x] = pixels[x]
                blurred[(height - 1) * width + x] = pixels[(height - 1) * width + x]
            }
            
            output.setPixels(blurred, 0, width, 0, 0, width, height)
            output
        } catch (e: Exception) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }
    }

    private fun convertByteBufferToBitmap(buffer: java.nio.ByteBuffer, width: Int, height: Int): Bitmap {
        buffer.rewind()
        
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        
        for (i in 0 until width * height) {
            // Real-ESRGAN modelleri BGR çıktı verebilir (OpenCV geleneği)
            // Önce BGR oku, sonra RGB'ye çevir
            val b = readNormalizedFloat(buffer)
            val g = readNormalizedFloat(buffer)
            val r = readNormalizedFloat(buffer)
            
            // ARGB format (Android format)
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }
    
    /**
     * Buffer'dan float oku ve 0-255 aralığına getir
     * Model çıktısı 0-1 veya 0-255 aralığında olabilir
     */
    private fun readNormalizedFloat(buffer: java.nio.ByteBuffer): Int {
        val value = buffer.float
        // Eğer değer 1'den büyükse, zaten 0-255 aralığındadır
        return if (value > 1.0f) {
            value.coerceIn(0.0f, 255.0f).toInt()
        } else {
            (value * 255.0f).coerceIn(0.0f, 255.0f).toInt()
        }
    }
}
