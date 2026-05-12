package com.lumina.engine

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.engine.core.LuminaEngine
import com.lumina.engine.ui.components.CompareSlider
import com.lumina.engine.ui.theme.LuminaTheme
import com.lumina.engine.ui.theme.OneUIPrimary
import kotlinx.coroutines.launch

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
        uri?.let { imageUri ->
            try {
                val bitmap = if (Build.VERSION.SDK_INT < 28) {
                    // Android 9 (API 28) altı için - basit yükleme ama boyut sınırlı
                    MediaStore.Images.Media.getBitmap(context.contentResolver, imageUri)
                } else {
                    // Android 9+ için - downsample ile yükle
                    val source = ImageDecoder.createSource(context.contentResolver, imageUri)
                    ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                        // Maksimum 2048px boyut (bellek tasarrufu)
                        decoder.setTargetSampleSize(2)
                        decoder.isMutableRequired = true
                    }
                }
                // Kopya oluşturma - direkt kullan (bellek tasarrufu)
                selectedBitmap = bitmap?.let { 
                    // Eğer mutable değilse kopyala, mutable ise kullan
                    if (it.isMutable) it else it.copy(Bitmap.Config.ARGB_8888, true)
                }
                isCompleted = false
                processedBitmap = null // Önceki işlenmiş resmi temizle
            } catch (e: OutOfMemoryError) {
                Toast.makeText(context, "Fotoğraf çok büyük! Daha küçük bir fotoğraf seçin.", Toast.LENGTH_LONG).show()
                e.printStackTrace()
            } catch (e: Exception) {
                Toast.makeText(context, "Fotoğraf yüklenirken hata: ${e.message}", Toast.LENGTH_LONG).show()
                e.printStackTrace()
            }
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

                Row(modifier = Modifier.fillMaxWidth()) {
                    if (isCompleted) {
                        Button(
                            onClick = {
                                processedBitmap?.let {
                                    val success = saveBitmapToGallery(context, it)
                                    if (success) {
                                        Toast.makeText(context, "Galeriye kaydedildi!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Kaydetme başarısız!", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f).height(56.dp).padding(end = 8.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = OneUIPrimary)
                        ) {
                            Text("Save", color = Color.White)
                        }
                    }

                    Button(
                        onClick = { 
                            if (selectedBitmap != null && !isProcessing && !isCompleted) {
                                scope.launch {
                                    try {
                                        isProcessing = true
                                        
                                        // Büyük resim uyarısı
                                        if (engine.wasImageDownsampled(selectedBitmap!!)) {
                                            Toast.makeText(context, "Büyük fotoğraf optimize ediliyor...", Toast.LENGTH_SHORT).show()
                                        }
                                        
                                        val result = engine.processImage(selectedBitmap!!) { p ->
                                            progress = p / 100f
                                        }
                                        processedBitmap = result
                                        isCompleted = true
                                    } catch (e: OutOfMemoryError) {
                                        Toast.makeText(context, "Bellek yetersiz! Daha küçük fotoğraf seçin.", Toast.LENGTH_LONG).show()
                                        e.printStackTrace()
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "İşleme hatası: ${e.message}", Toast.LENGTH_LONG).show()
                                        e.printStackTrace()
                                    } finally {
                                        isProcessing = false
                                    }
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
                        modifier = Modifier.weight(1f).height(56.dp),
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
                }
                
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

private fun saveBitmapToGallery(context: android.content.Context, bitmap: Bitmap): Boolean {
    return try {
        val filename = "Lumina_${System.currentTimeMillis()}.jpg"
        var fos: java.io.OutputStream? = null
        context.contentResolver?.also { resolver ->
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/Lumina")
            }
            val imageUri: android.net.Uri? = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            fos = imageUri?.let { resolver.openOutputStream(it) }
        }
        fos?.use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)
        }
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

@Composable
fun ShimmerLoadingEffect() {
    // Pixel tarzı dalga animasyonu
    val transition = rememberInfiniteTransition()
    
    // Dalga pozisyonu - soldan sağa hareket
    val translateAnim by transition.animateFloat(
        initialValue = -1000f,
        targetValue = 2000f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )
    
    // Parlaklık pulse
    val pulseAnim by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // Beyaz overlay pulse
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White.copy(alpha = pulseAnim * 0.15f))
        )
        
        // Dalga/shimmer efekti - diagonal gradient
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = androidx.compose.ui.graphics.Brush.linearGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.0f),
                            Color.White.copy(alpha = 0.4f),
                            Color.White.copy(alpha = 0.6f),
                            Color.White.copy(alpha = 0.4f),
                            Color.White.copy(alpha = 0.0f),
                            Color.Transparent
                        ),
                        start = androidx.compose.ui.geometry.Offset(translateAnim - 300f, 0f),
                        end = androidx.compose.ui.geometry.Offset(translateAnim + 300f, 1000f)
                    )
                )
        )
        
        // İkinci dalga (ters yönde, daha yavaş)
        val translateAnim2 by transition.animateFloat(
            initialValue = 2000f,
            targetValue = -1000f,
            animationSpec = infiniteRepeatable(
                animation = tween(2200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            )
        )
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = androidx.compose.ui.graphics.Brush.linearGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Cyan.copy(alpha = 0.0f),
                            Color.Cyan.copy(alpha = 0.15f),
                            Color.White.copy(alpha = 0.3f),
                            Color.Cyan.copy(alpha = 0.15f),
                            Color.Cyan.copy(alpha = 0.0f),
                            Color.Transparent
                        ),
                        start = androidx.compose.ui.geometry.Offset(translateAnim2 + 200f, 0f),
                        end = androidx.compose.ui.geometry.Offset(translateAnim2 - 200f, 800f)
                    )
                )
        )
    }
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
