package com.lumina.engine.core

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Lumina Hybrid Engine: Non-Destructive Hybrid Processing
 * Optimized for mobile - limits max resolution to prevent ANR/OutOfMemory
 */
class LuminaEngine(private val context: Context) {

    companion object {
        // Mobil cihazlar için makul maksimum boyut (2K - bellek ve hız dengesi)
        const val MAX_PROCESSING_SIZE = 2048
        
        init {
            try {
                System.loadLibrary("luminaengine")
            } catch (e: UnsatisfiedLinkError) {
                // Native kütüphane yüklenemezse (OpenCV yoksa), Java-only modda çalış
                e.printStackTrace()
            }
        }
    }

    private val segmentationManager = SegmentationManager(context)
    private val upscaleManager = UpscaleManager(context)
    private val colorScience = ColorScience()
    private val textureManager = TextureManager()

    suspend fun processImage(inputBitmap: Bitmap, onProgress: (Int) -> Unit): Bitmap = withContext(Dispatchers.Default) {
        try {
            // 0. Boyut kontrolü - çok büyük resimleri önce küçült (hız için kritik)
            onProgress(5)
            val workingBitmap = downsampleIfNeeded(inputBitmap)
            
            // 1. Semantic Analysis (Pre-processing)
            onProgress(20)
            val masks = segmentationManager.analyze(workingBitmap)

            // 2. Neural Super-Resolution (Upscale)
            onProgress(50)
            val upscaledBitmap = upscaleManager.upscale(workingBitmap)

            // 3. Hybrid Color Science (The Core Engine)
            onProgress(80)
            var processedBitmap = colorScience.applyHybridLogic(upscaledBitmap, masks)

            // 4. Anti-Plastic / Texture Injection
            onProgress(95)
            processedBitmap = textureManager.injectNaturalGrain(processedBitmap)

            onProgress(100)
            processedBitmap
        } catch (e: OutOfMemoryError) {
            // Bellek yetersizse orijinal resmi döndür
            e.printStackTrace()
            onProgress(100)
            inputBitmap
        } catch (e: Exception) {
            e.printStackTrace()
            onProgress(100)
            inputBitmap
        }
    }
    
    /**
     * Görüntünün boyutlandırılıp boyutlandırılmadığını kontrol et
     */
    fun wasImageDownsampled(original: Bitmap): Boolean {
        return max(original.width, original.height) > MAX_PROCESSING_SIZE
    }
    
    /**
     * Çok büyük resimleri işlemeden önce küçültür.
     * 4K fotoğraf (12MP+) 20 saniye yerine 3-4 saniyede işlenir.
     */
    private fun downsampleIfNeeded(bitmap: Bitmap): Bitmap {
        val maxDim = max(bitmap.width, bitmap.height)
        
        if (maxDim <= MAX_PROCESSING_SIZE) {
            return bitmap // Zaten uygun boyutta
        }
        
        // Oran koruyarak küçült
        val scale = MAX_PROCESSING_SIZE.toFloat() / maxDim
        val newWidth = (bitmap.width * scale).toInt()
        val newHeight = (bitmap.height * scale).toInt()
        
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
}
