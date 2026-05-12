package com.lumina.engine.core

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Hybrid Color Science Engine - GERÇEK IMPLEMENTASYON
 * Apple (Natural) + Samsung (HDR) + Leica (Contrast)
 */
class ColorScience {

    /**
     * Native call for Local Laplacian Filter (OpenCV varsa çalışır)
     */
    private external fun applyLocalLaplacian(bitmap: Bitmap, sigma: Float, fact: Float)

    /**
     * Native call for Adaptive Sharpening (OpenCV varsa çalışır)
     */
    private external fun applyAdaptiveSharpen(bitmap: Bitmap, amount: Float)

    fun applyHybridLogic(bitmap: Bitmap, masks: Map<String, Bitmap?>): Bitmap {
        // Her zaman mutable kopya oluştur - orijinali değiştirme
        var result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        
        // 1. Apple Base: Auto White Balance (AWB) Gray World + Warm Offset
        result = applyAppleTrueTone(result)

        // 2. Samsung Tone: Shadow Recovery (Simple Gamma)
        result = applySamsungHDR(result, masks["background"])

        // 3. Leica Contrast: Midtone S-Curve & Selective Saturation
        result = applyLeicaContrast(result)

        // 4. Local Laplacian (Native Depth) - Sadece OpenCV varsa çalışır
        try {
            applyLocalLaplacian(result, 0.5f, 0.6f)
        } catch (e: Exception) {
            // OpenCV yoksa atla
        }

        // 5. The Final Pop: Adaptive Sharpening & Vignette
        result = applyFinalPop(result)

        return result
    }

    /**
     * Apple TrueTone: Gray World White Balance + Warm Tint
     */
    private fun applyAppleTrueTone(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        // 1. Gray World: Ortalama rengi hesapla
        var avgR = 0.0
        var avgG = 0.0
        var avgB = 0.0
        
        for (pixel in pixels) {
            avgR += Color.red(pixel)
            avgG += Color.green(pixel)
            avgB += Color.blue(pixel)
        }
        
        val pixelCount = (width * height).toDouble()
        avgR /= pixelCount
        avgG /= pixelCount
        avgB /= pixelCount
        
        // Gray World: R ve B kanallarını G'ye eşitle
        val rGain = avgG / avgR
        val bGain = avgG / avgB
        
        // 2. Warm Offset (5500K -> daha sıcak/sarımsı)
        val warmFactor = 1.08f // %8 warm boost
        
        for (i in pixels.indices) {
            val r = Color.red(pixels[i])
            val g = Color.green(pixels[i])
            val b = Color.blue(pixels[i])
            
            // White Balance
            val newR = (r * rGain * warmFactor).coerceIn(0.0, 255.0).toInt()
            val newG = g.coerceIn(0, 255) // Yeşil değiştirme
            val newB = (b * bGain * 0.95f).coerceIn(0.0, 255.0).toInt() // Hafif soğuk azaltımı
            
            pixels[i] = Color.argb(255, newR, newG, newB)
        }
        
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    /**
     * Samsung HDR: Gamma correction for shadow lifting
     */
    private fun applySamsungHDR(bitmap: Bitmap, backgroundMask: Bitmap?): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        // Shadow lifting gamma (1.2 gamma - daha aydınlık gölgeler)
        val gamma = 1.2f
        val gammaInv = 1.0f / gamma
        
        for (i in pixels.indices) {
            val r = Color.red(pixels[i]) / 255.0f
            val g = Color.green(pixels[i]) / 255.0f
            val b = Color.blue(pixels[i]) / 255.0f
            
            // Gamma correction (lift shadows)
            val newR = (r.pow(gammaInv) * 255).toInt().coerceIn(0, 255)
            val newG = (g.pow(gammaInv) * 255).toInt().coerceIn(0, 255)
            val newB = (b.pow(gammaInv) * 255).toInt().coerceIn(0, 255)
            
            pixels[i] = Color.argb(255, newR, newG, newB)
        }
        
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    /**
     * Leica Contrast: Sigmoid S-curve + Saturation boost
     */
    private fun applyLeicaContrast(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        // Sigmoid parametreleri
        val k = 8.0 // Contrast strength
        val midpoint = 0.5 // Midtones
        
        // Saturation boost
        val satBoost = 1.15f
        
        for (i in pixels.indices) {
            val r = Color.red(pixels[i]) / 255.0
            val g = Color.green(pixels[i]) / 255.0
            val b = Color.blue(pixels[i]) / 255.0
            
            // Sigmoid contrast
            val newR = sigmoid(r, k, midpoint)
            val newG = sigmoid(g, k, midpoint)
            val newB = sigmoid(b, k, midpoint)
            
            // Saturation boost
            val avg = (newR + newG + newB) / 3.0
            val finalR = (avg + (newR - avg) * satBoost).coerceIn(0.0, 1.0)
            val finalG = (avg + (newG - avg) * satBoost).coerceIn(0.0, 1.0)
            val finalB = (avg + (newB - avg) * satBoost).coerceIn(0.0, 1.0)
            
            pixels[i] = Color.argb(
                255,
                (finalR * 255).toInt(),
                (finalG * 255).toInt(),
                (finalB * 255).toInt()
            )
        }
        
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    private fun sigmoid(x: Double, k: Double, midpoint: Double): Double {
        return 1.0 / (1.0 + kotlin.math.exp(-k * (x - midpoint)))
    }

    private fun applyFinalPop(bitmap: Bitmap): Bitmap {
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        
        // 1. Adaptive Sharpening (Native) - Sadece OpenCV varsa
        try {
            applyAdaptiveSharpen(result, 0.3f)
        } catch (e: Exception) {
            // OpenCV yoksa atla
        }
        
        // 2. Vignette ekle
        return addVignette(result)
    }

    /**
     * Soft vignette (köşeleri karanlıklaştır, merkezi vurgula)
     */
    private fun addVignette(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        val centerX = width / 2.0
        val centerY = height / 2.0
        val maxDist = sqrt(centerX * centerX + centerY * centerY)
        
        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                
                // Merkeze olan uzaklık
                val dx = x - centerX
                val dy = y - centerY
                val dist = sqrt(dx * dx + dy * dy)
                
                // Vignette factor (1.0 = merkez, ~0.7 = köşeler)
                val vignette = 1.0 - (0.25 * (dist / maxDist).pow(1.5))
                
                val r = (Color.red(pixels[idx]) * vignette).toInt().coerceIn(0, 255)
                val g = (Color.green(pixels[idx]) * vignette).toInt().coerceIn(0, 255)
                val b = (Color.blue(pixels[idx]) * vignette).toInt().coerceIn(0, 255)
                
                pixels[idx] = Color.argb(255, r, g, b)
            }
        }
        
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }
}
