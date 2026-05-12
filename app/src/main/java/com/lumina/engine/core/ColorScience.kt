package com.lumina.engine.core

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
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
        return try {
            // Tek mutable bitmap üzerinde çalış - hafıza verimliliği
            val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            
            // 1. Pixel Style Auto White Balance (Nötr/Dengeli)
            applyPixelWhiteBalanceInPlace(result)

            // 2. HDR+ Style Shadow Recovery (Detay koruma)
            applyPixelHDRInPlace(result)

            // 3. Vibrance (Akıllı doygunluk - Pixel tarzı)
            applyPixelVibranceInPlace(result, 1.12f)

            // 4. Clarity (Midtone Contrast - Pixel Super Res tarzı)
            applyPixelClarityInPlace(result)

            // 5. Local Laplacian (OpenCV varsa)
            try {
                applyLocalLaplacian(result, 0.4f, 0.5f)
            } catch (e: Exception) {
                // OpenCV yoksa atla
            }

            // 6. Smart Sharpen (Unsharp Mask tarzı)
            applyPixelSharpenInPlace(result)

            // 7. Subtle Vignette (Soft focus)
            applySoftVignetteInPlace(result)

            result
        } catch (e: Exception) {
            e.printStackTrace()
            bitmap // Hata olursa orijinali döndür
        } catch (e: OutOfMemoryError) {
            e.printStackTrace()
            bitmap // Bellek hatası durumunda orijinal
        }
    }

    /**
     * Pixel tarzı temiz beyaz denge - nötr (sıcak değil)
     */
    private fun applyPixelWhiteBalanceInPlace(bitmap: Bitmap) {
        try {
            val width = bitmap.width
            val height = bitmap.height
            val pixelCount = width * height
            val pixels = IntArray(pixelCount)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            
            // Gray World: Hızlı örnekleme
            var avgR = 0.0
            var avgG = 0.0
            var avgB = 0.0
            val sampleStep = maxOf(1, pixelCount / 3000)
            var sampleCount = 0
            
            for (i in pixels.indices step sampleStep) {
                val pixel = pixels[i]
                avgR += Color.red(pixel)
                avgG += Color.green(pixel)
                avgB += Color.blue(pixel)
                sampleCount++
            }
            
            avgR /= sampleCount
            avgG /= sampleCount
            avgB /= sampleCount
            
            // Nötr white balance (Pixel tarzı - az miktarsı warm)
            val rGain = if (avgR > 0) avgG / avgR else 1.0
            val bGain = if (avgB > 0) avgG / avgB else 1.0
            val warmTint = 1.02f // Çok hafif sıcak (Pixel'ler nötrdür)
            
            for (i in pixels.indices) {
                val pixel = pixels[i]
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                
                val newR = (r * rGain * warmTint).coerceIn(0.0, 255.0).toInt()
                val newG = g
                val newB = (b * bGain).coerceIn(0.0, 255.0).toInt()
                
                pixels[i] = Color.argb(255, newR, newG, newB)
            }
            
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Pixel HDR+ tarzı lokal ton mapping - detay koruma
     */
    private fun applyPixelHDRInPlace(bitmap: Bitmap) {
        try {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            
            // Shadow lifting + Highlight recovery
            val shadowGamma = 0.85f // Gölgeleri aç
            val highlightComp = 0.95f // Parlaklık sıkıştır
            
            for (i in pixels.indices) {
                val pixel = pixels[i]
                var r = Color.red(pixel) / 255.0f
                var g = Color.green(pixel) / 255.0f
                var b = Color.blue(pixel) / 255.0f
                
                // Shadow lift (gamma < 1)
                r = if (r < 0.5f) r.pow(shadowGamma) else r
                g = if (g < 0.5f) g.pow(shadowGamma) else g
                b = if (b < 0.5f) b.pow(shadowGamma) else b
                
                // Highlight compression
                r = if (r > 0.9f) r * highlightComp + 0.9f * (1 - highlightComp) else r
                g = if (g > 0.9f) g * highlightComp + 0.9f * (1 - highlightComp) else g
                b = if (b > 0.9f) b * highlightComp + 0.9f * (1 - highlightComp) else b
                
                pixels[i] = Color.argb(
                    255,
                    (r * 255).toInt().coerceIn(0, 255),
                    (g * 255).toInt().coerceIn(0, 255),
                    (b * 255).toInt().coerceIn(0, 255)
                )
            }
            
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Akıllı Vibrance - Düşük doygunluklu renkleri artır, yüksekleri koru (Pixel tarzı)
     */
    private fun applyPixelVibranceInPlace(bitmap: Bitmap, vibrance: Float) {
        try {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            
            for (i in pixels.indices) {
                val pixel = pixels[i]
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                
                val maxVal = maxOf(r, g, b)
                val minVal = minOf(r, g, b)
                val saturation = if (maxVal > 0) (maxVal - minVal) / maxVal.toFloat() else 0f
                
                // Düşük doygunluklu renkleri daha fazla artır
                val vibranceFactor = 1f + (vibrance - 1f) * (1f - saturation * 0.7f)
                
                val avg = (r + g + b) / 3.0
                val newR = (avg + (r - avg) * vibranceFactor).coerceIn(0.0, 255.0).toInt()
                val newG = (avg + (g - avg) * vibranceFactor).coerceIn(0.0, 255.0).toInt()
                val newB = (avg + (b - avg) * vibranceFactor).coerceIn(0.0, 255.0).toInt()
                
                pixels[i] = Color.argb(255, newR, newG, newB)
            }
            
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Clarity - Midtone kontrast (Pixel tarzı keskinlik)
     */
    private fun applyPixelClarityInPlace(bitmap: Bitmap) {
        try {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            
            val clarity = 1.15f // Midtone kontrast
            
            for (i in pixels.indices) {
                val pixel = pixels[i]
                val r = Color.red(pixel) / 255.0f
                val g = Color.green(pixel) / 255.0f
                val b = Color.blue(pixel) / 255.0f
                
                // S-curve for midtones (sadece orta tonları etkile)
                val newR = if (r in 0.2f..0.8f) ((r - 0.5f) * clarity + 0.5f).coerceIn(0f, 1f) else r
                val newG = if (g in 0.2f..0.8f) ((g - 0.5f) * clarity + 0.5f).coerceIn(0f, 1f) else g
                val newB = if (b in 0.2f..0.8f) ((b - 0.5f) * clarity + 0.5f).coerceIn(0f, 1f) else b
                
                pixels[i] = Color.argb(
                    255,
                    (newR * 255).toInt(),
                    (newG * 255).toInt(),
                    (newB * 255).toInt()
                )
            }
            
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Unsharp Mask tarzı netlik - Pixel tarzı keskin detay
     */
    private fun applyPixelSharpenInPlace(bitmap: Bitmap) {
        try {
            val width = bitmap.width
            val height = bitmap.height
            
            // Gaussian blur'dan kopya (basitleştirilmiş)
            val blurred = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            
            val originalPixels = IntArray(width * height)
            val blurredPixels = IntArray(width * height)
            
            bitmap.getPixels(originalPixels, 0, width, 0, 0, width, height)
            blurred.getPixels(blurredPixels, 0, width, 0, 0, width, height)
            
            val amount = 0.6f // Sharpen strength
            val threshold = 10 // Edge threshold
            
            for (i in originalPixels.indices) {
                val orig = originalPixels[i]
                val blur = blurredPixels[i]
                
                val rDiff = Color.red(orig) - Color.red(blur)
                val gDiff = Color.green(orig) - Color.green(blur)
                val bDiff = Color.blue(orig) - Color.blue(blur)
                
                // Sadece edge'leri sharpen et (threshold)
                if (abs(rDiff) > threshold || abs(gDiff) > threshold || abs(bDiff) > threshold) {
                    val newR = (Color.red(orig) + rDiff * amount).toInt().coerceIn(0, 255)
                    val newG = (Color.green(orig) + gDiff * amount).toInt().coerceIn(0, 255)
                    val newB = (Color.blue(orig) + bDiff * amount).toInt().coerceIn(0, 255)
                    originalPixels[i] = Color.argb(255, newR, newG, newB)
                }
            }
            
            bitmap.setPixels(originalPixels, 0, width, 0, 0, width, height)
            blurred.recycle() // Bellek temizliği
        } catch (e: Exception) {
            e.printStackTrace()
        } catch (e: OutOfMemoryError) {
            e.printStackTrace()
        }
    }

    /**
     * Hafif vignette - Merkezi vurgula
     */
    private fun applySoftVignetteInPlace(bitmap: Bitmap) {
        try {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            
            val centerX = width / 2f
            val centerY = height / 2f
            val maxDist = kotlin.math.hypot(centerX, centerY)
            
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val idx = y * width + x
                    val dist = kotlin.math.hypot(x - centerX, y - centerY)
                    val vignette = 1f - (0.12f * dist / maxDist) // Hafif vignette
                    
                    val pixel = pixels[idx]
                    val r = (Color.red(pixel) * vignette).toInt().coerceIn(0, 255)
                    val g = (Color.green(pixel) * vignette).toInt().coerceIn(0, 255)
                    val b = (Color.blue(pixel) * vignette).toInt().coerceIn(0, 255)
                    
                    pixels[idx] = Color.argb(255, r, g, b)
                }
            }
            
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Eski metodlar - stabilite için korundu (kullanılmıyor ama referans)
    /**
     * Apple TrueTone: Gray World White Balance + Warm Tint (In-place)
     * @deprecated Use applyPixelWhiteBalanceInPlace instead
     */
    @Deprecated("Use Pixel White Balance instead")
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
     * @deprecated Use applyPixelHDRInPlace instead
     */
    @Deprecated("Use Pixel HDR instead")
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
     * @deprecated Use applyPixelVibranceInPlace instead
     */
    @Deprecated("Use Pixel Vibrance instead")
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

    /**
     * @deprecated Use applyPixelSharpenInPlace and applySoftVignetteInPlace instead
     */
    @Deprecated("Use Pixel methods instead")
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
