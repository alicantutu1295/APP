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
        // Tek mutable bitmap üzerinde çalış - hafıza verimliliği
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        
        // 1. Apple Base: Auto White Balance (AWB) Gray World + Warm Offset
        applyAppleTrueToneInPlace(result)

        // 2. Samsung Tone: Shadow Recovery (Simple Gamma)
        applySamsungHDRInPlace(result)

        // 3. Leica Contrast: Midtone S-Curve & Selective Saturation
        applyLeicaContrastInPlace(result)

        // 4. Local Laplacian (Native Depth) - Sadece OpenCV varsa çalışır
        try {
            applyLocalLaplacian(result, 0.5f, 0.6f)
        } catch (e: Exception) {
            // OpenCV yoksa atla
        }

        // 5. The Final Pop: Adaptive Sharpening & Vignette
        applyFinalPopInPlace(result)

        return result
    }

    /**
     * Apple TrueTone: Gray World White Balance + Warm Tint (In-place)
     */
    private fun applyAppleTrueToneInPlace(bitmap: Bitmap) {
        val width = bitmap.width
        val height = bitmap.height
        val pixelCount = width * height
        val pixels = IntArray(pixelCount)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        // 1. Gray World: Ortalama rengi hesapla (örnekleme ile hızlandır)
        var avgR = 0.0
        var avgG = 0.0
        var avgB = 0.0
        val sampleStep = maxOf(1, pixelCount / 5000) // Her 5000 pikselde bir örnek al
        var sampleCount = 0
        
        for (i in pixels.indices step sampleStep) {
            avgR += Color.red(pixels[i])
            avgG += Color.green(pixels[i])
            avgB += Color.blue(pixels[i])
            sampleCount++
        }
        
        avgR /= sampleCount
        avgG /= sampleCount
        avgB /= sampleCount
        
        // Gray World: R ve B kanallarını G'ye eşitle
        val rGain = if (avgR > 0) avgG / avgR else 1.0
        val bGain = if (avgB > 0) avgG / avgB else 1.0
        
        // 2. Warm Offset (5500K -> daha sıcak/sarımsı)
        val warmFactor = 1.08f // %8 warm boost
        
        for (i in pixels.indices) {
            val r = Color.red(pixels[i])
            val g = Color.green(pixels[i])
            val b = Color.blue(pixels[i])
            
            // White Balance + Warm tint
            val newR = (r * rGain * warmFactor).coerceIn(0.0, 255.0).toInt()
            val newG = g
            val newB = (b * bGain * 0.95f).coerceIn(0.0, 255.0).toInt()
            
            pixels[i] = Color.argb(255, newR, newG, newB)
        }
        
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    /**
     * Samsung HDR: Gamma correction for shadow lifting (In-place)
     */
    private fun applySamsungHDRInPlace(bitmap: Bitmap) {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        // Shadow lifting gamma (1.15 gamma - daha aydınlık gölgeler)
        val gamma = 1.15f
        val gammaInv = 1.0f / gamma
        
        for (i in pixels.indices) {
            val r = Color.red(pixels[i]) / 255.0f
            val g = Color.green(pixels[i]) / 255.0f
            val b = Color.blue(pixels[i]) / 255.0f
            
            // Gamma correction (lift shadows) - optimize edilmiş
            val newR = (fastPow(r, gammaInv) * 255).toInt().coerceIn(0, 255)
            val newG = (fastPow(g, gammaInv) * 255).toInt().coerceIn(0, 255)
            val newB = (fastPow(b, gammaInv) * 255).toInt().coerceIn(0, 255)
            
            pixels[i] = Color.argb(255, newR, newG, newB)
        }
        
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }
    
    // Hızlı üs alma - Math.pow yerine
    private fun fastPow(base: Float, exp: Float): Float {
        return kotlin.math.pow(base, exp)
    }

    /**
     * Leica Contrast: Sigmoid S-curve + Saturation boost (In-place)
     */
    private fun applyLeicaContrastInPlace(bitmap: Bitmap) {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        // Hafifletilmiş parametreler (hız için)
        val k = 6.0 // Contrast strength (düşürüldü)
        val midpoint = 0.5
        val satBoost = 1.12f // Hafif saturation
        
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
        
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    private fun sigmoid(x: Double, k: Double, midpoint: Double): Double {
        return 1.0 / (1.0 + kotlin.math.exp(-k * (x - midpoint)))
    }

    private fun applyFinalPopInPlace(bitmap: Bitmap) {
        // 1. Adaptive Sharpening (Native) - Sadece OpenCV varsa
        try {
            applyAdaptiveSharpen(bitmap, 0.25f)
        } catch (e: Exception) {
            // OpenCV yoksa atla
        }
        
        // 2. Vignette ekle (in-place)
        addVignetteInPlace(bitmap)
    }

    /**
     * Soft vignette (köşeleri karanlıklaştır, merkezi vurgula) - In-place
     */
    private fun addVignetteInPlace(bitmap: Bitmap) {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        val centerX = width / 2.0
        val centerY = height / 2.0
        val maxDist = sqrt(centerX * centerX + centerY * centerY)
        val maxDistInv = 1.0 / maxDist
        
        for (y in 0 until height) {
            val dy = y - centerY
            val dy2 = dy * dy
            
            for (x in 0 until width) {
                val idx = y * width + x
                
                // Merkeze olan uzaklık (optimize edilmiş)
                val dx = x - centerX
                val dist = sqrt(dx * dx + dy2)
                
                // Vignette factor (hafifletilmiş)
                val vignette = 1.0 - (0.15 * dist * maxDistInv) // Daha hafif vignette
                
                val r = (Color.red(pixels[idx]) * vignette).toInt().coerceIn(0, 255)
                val g = (Color.green(pixels[idx]) * vignette).toInt().coerceIn(0, 255)
                val b = (Color.blue(pixels[idx]) * vignette).toInt().coerceIn(0, 255)
                
                pixels[idx] = Color.argb(255, r, g, b)
            }
        }
        
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }
}
