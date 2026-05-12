package com.lumina.engine

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.engine.ui.theme.LuminaTheme
import com.lumina.engine.ui.theme.OneUIPrimary
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import com.lumina.engine.core.LuminaEngine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import com.lumina.engine.ui.components.CompareSlider

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LuminaTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    LuminaMainScreen()
                }
            }
        }
    }
}

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.ImageDecoder
import android.os.Build
import android.provider.MediaStore

@Composable
fun LuminaMainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { LuminaEngine(context) }
    
    var progress by remember { mutableStateOf(0f) }
    var isProcessing by remember { mutableStateOf(false) }
    var isCompleted by remember { mutableStateOf(false) }
    
    var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var processedBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val bitmap = if (Build.VERSION.SDK_INT < 28) {
                MediaStore.Images.Media.getBitmap(context.contentResolver, it)
            } else {
                val source = ImageDecoder.createSource(context.contentResolver, it)
                ImageDecoder.decodeBitmap(source)
            }
            selectedBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            isCompleted = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. Interactive Preview Area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(16.dp)
                .clickable { if (!isProcessing) launcher.launch("image/*") },
            contentAlignment = Alignment.Center
        ) {
            Crossfade(targetState = isCompleted) { completed ->
                if (completed && selectedBitmap != null && processedBitmap != null) {
                    CompareSlider(selectedBitmap!!, processedBitmap!!)
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(28.dp))
                            .background(if (selectedBitmap != null) Color.Transparent else Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        if (selectedBitmap != null && !isProcessing) {
                            Image(
                                bitmap = selectedBitmap!!.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        
                        if (isProcessing) {
                            ShimmerLoadingEffect()
                            CircularProgressIndicator(
                                progress = progress,
                                modifier = Modifier.size(64.dp),
                                color = OneUIPrimary,
                                strokeWidth = 6.dp
                            )
                        } else if (selectedBitmap == null) {
                            Text("Tap to Select an Image", color = Color.White)
                        }
                    }
                }
            }
        }

        // 2. OneUI 6.1 Control Panel
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.LightGray)
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Text(
                    "Lumina Engine",
                    fontSize = 22.sp,
                    style = MaterialTheme.typography.headlineMedium
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    when {
                        isCompleted -> "Processing Complete"
                        selectedBitmap != null -> "Ready to Process"
                        else -> "Non-Destructive Hybrid Processing"
                    },
                    fontSize = 14.sp,
                    color = if (isCompleted) OneUIPrimary else Color.Gray
                )

                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    onClick = { 
                        if (selectedBitmap != null && !isProcessing && !isCompleted) {
                            scope.launch {
                                isProcessing = true
                                val result = engine.processImage(selectedBitmap!!) { p ->
                                    progress = p / 100f
                                }
                                processedBitmap = result
                                isProcessing = false
                                isCompleted = true
                            }
                        } else if (isCompleted) {
                            isCompleted = false
                            isProcessing = false
                            selectedBitmap = null
                            processedBitmap = null
                            progress = 0f
                        } else {
                            launcher.launch("image/*")
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isCompleted) Color.Gray else OneUIPrimary
                    )
                ) {
                    Text(
                        when {
                            isCompleted -> "Reset"
                            selectedBitmap != null -> "Process Image"
                            else -> "Select Image"
                        }, 
                        fontSize = 18.sp, 
                        color = Color.White
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}


@Composable
fun ShimmerLoadingEffect() {
    val transition = rememberInfiniteTransition()
    val alpha by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White.copy(alpha = alpha))
    )
}

private fun createPlaceholderBitmap(color: Color): Bitmap {
    val bitmap = Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint()
    paint.color = android.graphics.Color.argb(
        (color.alpha * 255).toInt(),
        (color.red * 255).toInt(),
        (color.green * 255).toInt(),
        (color.blue * 255).toInt()
    )
    canvas.drawRect(0f, 0f, 500f, 500f, paint)
    return bitmap
}
