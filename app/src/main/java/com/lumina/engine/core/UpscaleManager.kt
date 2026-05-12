package com.lumina.engine.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Handles Super-Resolution and Texture Injection.
 * Model: Real-ESRGAN-AnimeVideo-v3
 */
class UpscaleManager(context: Context) {
    // TFLite model devre dışı - siyah/beyaz çıktı veriyor

    companion object {
        private const val MAX_INPUT_SIZE = 2000 // Maksimum 2000px (bellek koruması)
    }

    fun upscale(bitmap: Bitmap): Bitmap {
        // Çok büyük resimleri önce küçült
        val safeBitmap = if (bitmap.width > MAX_INPUT_SIZE || bitmap.height > MAX_INPUT_SIZE) {
            val scale = MAX_INPUT_SIZE.toFloat() / maxOf(bitmap.width, bitmap.height)
            val newWidth = (bitmap.width * scale).toInt()
            val newHeight = (bitmap.height * scale).toInt()
            Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        } else {
            bitmap
        }
        
        return highQualityUpscale(safeBitmap)
    }
    
    /**
     * YÜKSEK KALİTELİ CPU Upscale - Renkleri mükemmel korur, agresif netleştirir
     * TFLite model yerine kullanılır (model siyah/beyaz çıktı veriyor)
     */
    private fun highQualityUpscale(bitmap: Bitmap): Bitmap {
        return try {
            val targetWidth = bitmap.width * 2
            val targetHeight = bitmap.height * 2
            
            // Direkt 2x upscale (filter=true = bicubic quality)
            val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
            
            // Agresif netleme + renk koruma
            val sharpened = applyAdvancedSharpen(scaled)
            val finalBitmap = preserveOriginalColors(bitmap, sharpened)
            
            // Bellek temizliği
            System.gc()
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
     * Basit upscale + agresif netleme - fallback
     */
    private fun simpleUpscale(bitmap: Bitmap): Bitmap {
        val targetWidth = bitmap.width * 2
        val targetHeight = bitmap.height * 2
        val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        return applyAdvancedSharpen(scaled)
    }
}
