package com.lumina.engine.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * OneUI 6.1 Styled Compare Slider
 * Allows users to drag a handle to see before/after effects.
 */
@Composable
fun CompareSlider(original: Bitmap, enhanced: Bitmap) {
    var width by remember { mutableStateOf(0f) }
    var offsetX by remember { mutableStateOf(0f) }
    
    // Initialize slider in the middle
    LaunchedEffect(width) {
        if (width > 0 && offsetX == 0f) {
            offsetX = width / 2
        }
    }

    Box(modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(1f) // Square form for professional look
        .clip(RoundedCornerShape(28.dp)) // OneUI signature curves
        .onSizeChanged { width = it.width.toFloat() }
        .pointerInput(Unit) {
            detectDragGestures { change, dragAmount ->
                change.consume()
                offsetX = (offsetX + dragAmount.x).coerceIn(0f, width)
            }
        }
    ) {
        // Bottom Layer: Original Photo
        Image(
            bitmap = original.asImageBitmap(),
            contentDescription = "Original Photo",
            modifier = Modifier.fillMaxSize()
        )

        // Top Layer: Enhanced Photo (Clipped)
        Box(modifier = Modifier
            .fillMaxSize()
            .clip(GenericShape { size, _ ->
                // Only show the right side based on drag
                addRect(Rect(offsetX, 0f, size.width, size.height))
            })
        ) {
            Image(
                bitmap = enhanced.asImageBitmap(),
                contentDescription = "Enhanced Photo",
                modifier = Modifier.fillMaxSize()
            )
        }

        // Separator Line and OneUI Handle
        Box(modifier = Modifier
            .offset { IntOffset(offsetX.toInt(), 0) }
            .fillMaxHeight()
            .width(2.dp)
            .background(Color.White.copy(alpha = 0.8f))
        ) {
            // Samsung style circular handle
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(40.dp)
                    .offset(x = (-20).dp), // Center the circle on the line
                shape = CircleShape,
                color = Color.White,
                shadowElevation = 8.dp
            ) {
                Icon(
                    imageVector = Icons.Default.SwapHoriz,
                    contentDescription = "Compare",
                    tint = Color.Black,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
    }
}
