package com.lumina.engine.core

import android.graphics.Bitmap

/**
 * Handles microscopic grain injection to prevent "plastic" AI look.
 */
class TextureManager {

    /**
     * Native call for Adaptive Grain Injection
     */
    private external fun applyAdaptiveGrain(bitmap: Bitmap, strength: Float)

    fun injectNaturalGrain(bitmap: Bitmap): Bitmap {
        // Gaussian Noise Generation targeting shadows
        // 2% intensity digital grain
        applyAdaptiveGrain(bitmap, 0.02f)
        return bitmap
    }
}
