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
        var workingBitmap: Bitmap? = null
        var upscaledBitmap: Bitmap? = null
        var processedBitmap: Bitmap? = null
        
        try {
            // 0. Boyut kontrolü - çok büyük resimleri önce küçült
            onProgress(5)
            workingBitmap = downsampleIfNeeded(inputBitmap)
            
            // Bellek temizliği önerisi
            System.gc()
            
            // 1. Semantic Analysis
            onProgress(15)
            val masks = segmentationManager.analyze(workingBitmap)

            // 2. Neural Super-Resolution
            onProgress(35)
            upscaledBitmap = upscaleManager.upscale(workingBitmap)
            
            // Geçici bitmap temizliği
            if (workingBitmap !== inputBitmap && workingBitmap !== upscaledBitmap) {
                workingBitmap.recycle()
            }

            // 3. Hybrid Color Science (Pixel Look)
            onProgress(60)
            processedBitmap = colorScience.applyHybridLogic(upscaledBitmap, masks)
            
            // Eğer renk işleme başarısız olursa upscaled'i kullan
            if (processedBitmap == null) {
                processedBitmap = upscaledBitmap
            } else if (upscaledBitmap !== processedBitmap && upscaledBitmap !== inputBitmap) {
                upscaledBitmap.recycle()
            }

            // 4. Texture Injection
            onProgress(85)
            processedBitmap = textureManager.injectNaturalGrain(processedBitmap ?: inputBitmap)
            
            // Final bellek temizliği
            System.gc()

            onProgress(100)
            processedBitmap ?: inputBitmap
            
        } catch (e: OutOfMemoryError) {
            e.printStackTrace()
            // Bellek temizliği
            workingBitmap?.recycle()
            if (upscaledBitmap !== inputBitmap) upscaledBitmap?.recycle()
            if (processedBitmap !== inputBitmap) processedBitmap?.recycle()
            System.gc()
            onProgress(100)
            inputBitmap
        } catch (e: Exception) {
            e.printStackTrace()
            onProgress(100)
            processedBitmap ?: upscaledBitmap ?: workingBitmap ?: inputBitmap
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
