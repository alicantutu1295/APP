package com.lumina.engine.core

import android.media.ExifInterface
import java.io.File

/**
 * Utility to transfer EXIF metadata and preserve photo identity.
 */
object ExifUtils {

    fun transferMetadata(sourcePath: String, targetPath: String) {
        val sourceExif = ExifInterface(sourcePath)
        val targetExif = ExifInterface(targetPath)

        // List of tags to preserve
        val tags = arrayOf(
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_ISO_SPEED_RATINGS,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_ORIENTATION
        )

        for (tag in tags) {
            val value = sourceExif.getAttribute(tag)
            if (value != null) {
                targetExif.setAttribute(tag, value)
            }
        }

        // Add custom Lumina Engine tag in UserComment or Software
        targetExif.setAttribute(ExifInterface.TAG_SOFTWARE, "Lumina Hybrid Engine v1.0")
        
        targetExif.saveAttributes()
    }
}
