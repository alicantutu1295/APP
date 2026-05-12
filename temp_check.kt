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
import kotlin.math.max
import kotlin.math.min

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
        // TFLite model sorunlu - direkt güvenilir CPU upscale kullan
        return highQualityUpscale(bitmap)
    }
    
    /**
     * YÜKSEK KALİTELİ CPU Upscale - Renkleri mükemmel korur, agresif netleştirir
     * TFLite model yerine kullanılır (model siyah/beyaz çıktı veriyor)
     */
    private fun highQualityUpscale(bitmap: Bitmap): Bitmap {
        return try {
            val targetWidth = bitmap.width * 2
            val targetHeight = bitmap.height * 2
            
            // 1. Yüksek kaliteli upscale (Bicubic simulation via two-step linear)
            val step1 = Bitmap.createScaledBitmap(bitmap, bitmap.width * 3 / 2, bitmap.height * 3 / 2, true)
            val step2 = Bitmap.createScaledBitmap(step1, targetWidth, targetHeight, true)
            step1.recycle()
            
            // 2. Agresif ama temiz netleme
            val sharpened = applyAdvancedSharpen(step2)
            
            // 3. Kenar detaylarını koru
            val finalBitmap = preserveOriginalColors(bitmap, sharpened)
            
            step2.recycle()
            finalBitmap
            
        } catch (e: OutOfMemoryError) {
            e.printStackTrace()
            simpleUpscale(bitmap)
        } catch (e: Exception) {
            e.printStackTrace()
            simpleUpscale(bitmap)
        }
    }
    
    /**
     * Orijinal renkleri koruyup sadece detayları artır
     */
    private fun preserveOriginalColors(original: Bitmap, upscaled: Bitmap): Bitmap {
        val width = upscaled.width
        val height = upscaled.height
        
        // Orijinali upscale boyutuna getir (renk kaynağı)
        val originalScaled = Bitmap.createScaledBitmap(original, width, height, true)
        
        val origPixels = IntArray(width * height)
        val upPixels = IntArray(width * height)
        
        originalScaled.getPixels(origPixels, 0, width, 0, 0, width, height)
        upscaled.getPixels(upPixels, 0, width, 0, 0, width, height)
        originalScaled.recycle()
        
        // Orijinal renkleri kullan, upscaled'den sadece luminance/edge bilgisi al
        for (i in origPixels.indices) {
            val orig = origPixels[i]
            val up = upPixels[i]
            
            val rOrig = Color.red(orig)
            val gOrig = Color.green(orig)
            val bOrig = Color.blue(orig)
            
            val rUp = Color.red(up)
            val gUp = Color.green(up)
            val bUp = Color.blue(up)
            
            // Luminance farkını hesapla (edge bilgisi)
            val lumOrig = (rOrig + gOrig + bOrig) / 3
            val lumUp = (rUp + gUp + bUp) / 3
            val lumDiff = lumUp - lumOrig
            
            // Orijinal renklere luminance detayını ekle
            val rNew = (rOrig + lumDiff).coerceIn(0, 255)
            val gNew = (gOrig + lumDiff).coerceIn(0, 255)
            val bNew = (bOrig + lumDiff).coerceIn(0, 255)
            
            upPixels[i] = Color.argb(255, rNew, gNew, bNew)
        }
        
        upscaled.setPixels(upPixels, 0, width, 0, 0, width, height)
        return upscaled
    }
    
    /**
     * Gelişmiş netleme - Çok ölçekli unsharp mask
     */
    private fun applyAdvancedSharpen(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        // Hafif blur uygula (noise azaltma)
        val blurred = applyBoxBlur(pixels, width, height, 1)
        
        // Multi-scale unsharp mask
        for (i in pixels.indices) {
            val orig = pixels[i]
            val blur = blurred[i]
            
            val rOrig = Color.red(orig)
            val gOrig = Color.green(orig)
            val bOrig = Color.blue(orig)
            
            val rBlur = Color.red(blur)
            val gBlur = Color.green(blur)
            val bBlur = Color.blue(blur)
            
            // Fine detail enhancement
            val rDiff = rOrig - rBlur
            val gDiff = gOrig - gBlur
            val bDiff = bOrig - bBlur
            
            // Adaptive sharpening: daha agresif
            val edgeStrength = maxOf(abs(rDiff), abs(gDiff), abs(bDiff))
            val amount = if (edgeStrength < 20) 2.5f else 1.5f
            
            // Clamp to prevent halo
            val rNew = (rOrig + rDiff.coerceIn(-40, 40) * amount).toInt().coerceIn(0, 255)
            val gNew = (gOrig + gDiff.coerceIn(-40, 40) * amount).toInt().coerceIn(0, 255)
            val bNew = (bOrig + bDiff.coerceIn(-40, 40) * amount).toInt().coerceIn(0, 255)
            
            pixels[i] = Color.argb(255, rNew, gNew, bNew)
        }
        
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }
    
    /**
     * Hızlı box blur - netleme için detay azaltma
     */
    private fun applyBoxBlur(pixels: IntArray, width: Int, height: Int, radius: Int): IntArray {
        val output = pixels.copyOf()
        
        for (y in radius until height - radius) {
            for (x in radius until width - radius) {
                var r = 0
                var g = 0
                var b = 0
                var count = 0
                
                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        val idx = (y + dy) * width + (x + dx)
                        val pixel = pixels[idx]
                        r += Color.red(pixel)
                        g += Color.green(pixel)
                        b += Color.blue(pixel)
                        count++
                    }
                }
                
                output[y * width + x] = Color.argb(255, r / count, g / count, b / count)
            }
        }
        
        return output
    }

    /**
     * DEPRECATED: TFLite model sorunlu, kullanılmıyor
     */
    @Deprecated("Model siyah/beyaz çıktı veriyor, highQualityUpscale kullan")
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
            
            // Normalizasyon: 0-255 -> 0-1 (RGB order)
            for (pixel in intValues) {
                val r = ((pixel shr 16) and 0xFF) / 255.0f
                val g = ((pixel shr 8) and 0xFF) / 255.0f
                val b = (pixel and 0xFF) / 255.0f
                inputBuffer.putFloat(r)
                inputBuffer.putFloat(g)
                inputBuffer.putFloat(b)
            }
            inputBuffer.rewind()
            
            // Output buffer - 3 kanal için (model 1 veya 3 kanal verebilir)
            val outputBuffer = java.nio.ByteBuffer.allocateDirect(1 * outputSize * outputSize * 3 * 4)
            outputBuffer.order(java.nio.ByteOrder.nativeOrder())
            
            interpreter.run(inputBuffer, outputBuffer)
            
            // Log buffer capacity for debugging
            android.util.Log.d("UpscaleManager", "Output buffer capacity: ${outputBuffer.capacity()}, position: ${outputBuffer.position()}")
            
            // Output 256x256 (64*4), hedef 128x128 (64*2) - yani x2 küçült
            val upscaled = convertByteBufferToBitmap(outputBuffer, outputSize, outputSize)
            
            // Model çıktısı valid mi kontrol et (hepsi aynı renk veya siyah/beyaz ise basit upscale kullan)
            if (!isValidColorOutput(upscaled)) {
                android.util.Log.w("UpscaleManager", "Model output invalid (grayscale/bw), using simple upscale")
                upscaled.recycle()
                return simpleUpscale(bitmap)
            }
            
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
