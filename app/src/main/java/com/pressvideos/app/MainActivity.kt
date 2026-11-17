package com.pressvideos.app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale

private val Ink = Color(0xFF211A2D)
private val Muted = Color(0xFF77717F)
private val Purple = Color(0xFF6750A4)
private val PalePurple = Color(0xFFF0EBFA)
private val Canvas = Color(0xFFF8F7FA)

private enum class CompressionPreset(
    val title: String,
    val subtitle: String,
    val height: Int,
    val videoBitrate: Int,
) {
    SPACE("توفير المساحة", "حجم أصغر للمشاركة والتخزين", 480, 700_000),
    BALANCED("متوازن", "جودة واضحة وحجم مناسب", 720, 1_600_000),
    QUALITY("جودة عالية", "تفاصيل أكثر مع حجم أكبر", 1080, 3_500_000),
}

private data class VideoInfo(
    val name: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
)

private data class HistoryItem(
    val name: String,
    val sizeBytes: Long,
    val uri: String,
    val createdAt: Long,
)

@UnstableApi
class MainActivity : ComponentActivity() {
    private var transformer: Transformer? = null
    private var progressHandler: Handler? = null
    private var outputFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VideoCompressorScreen() }
    }

    override fun onDestroy() {
        progressHandler?.removeCallbacksAndMessages(null)
        transformer?.cancel()
        transformer = null
        outputFile?.delete()
        super.onDestroy()
    }

    @Composable
    private fun VideoCompressorScreen() {
        var sourceUri by remember { mutableStateOf<Uri?>(null) }
        var sourceInfo by remember { mutableStateOf<VideoInfo?>(null) }
        var selectedPreset by remember { mutableStateOf(CompressionPreset.BALANCED) }
        var isCompressing by remember { mutableStateOf(false) }
        var progress by remember { mutableFloatStateOf(0f) }
        var message by remember { mutableStateOf<String?>(null) }
        var resultUri by remember { mutableStateOf<Uri?>(null) }
        var history by remember { mutableStateOf(loadHistory(this@MainActivity)) }

        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: SecurityException) {
                    // The picker may return a readable URI without persistable access.
                }
                sourceUri = uri
                sourceInfo = null
                message = null
                resultUri = null
                lifecycleScope.launch {
                    try {
                        sourceInfo = withContext(Dispatchers.IO) { readVideoInfo(this@MainActivity, uri) }
                    } catch (exception: IOException) {
                        sourceUri = null
                        message = "تعذّرت قراءة الفيديو: ${exception.message ?: "تعذّر الوصول إلى الملف"}"
                    } catch (exception: SecurityException) {
                        sourceUri = null
                        message = "لا يمكن الوصول إلى هذا الفيديو"
                    } catch (exception: IllegalArgumentException) {
                        sourceUri = null
                        message = "صيغة الفيديو غير قابلة للقراءة"
                    }
                }
            }
        }

        MaterialTheme(
            colorScheme = lightColorScheme(
                primary = Purple,
                onPrimary = Color.White,
                secondary = Color(0xFF9B83CF),
                background = Canvas,
                surface = Color.White,
                onSurface = Ink,
                error = Color(0xFFB3261E),
            ),
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Surface(modifier = Modifier.fillMaxSize(), color = Canvas) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Header()
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            shape = RoundedCornerShape(24.dp),
                        ) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(46.dp)
                                            .background(PalePurple, CircleShape),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text("▶", color = Purple, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text("الفيديو المصدر", color = Ink, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                        Text("اختر مقطعًا من جهازك للبدء", color = Muted, fontSize = 13.sp)
                                    }
                                }
                                if (sourceInfo != null) {
                                    SourceDetails(sourceInfo!!)
                                } else if (sourceUri != null) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(10.dp))
                                        Text("جارٍ قراءة معلومات الفيديو…", color = Muted, fontSize = 13.sp)
                                    }
                                }
                                OutlinedButton(
                                    enabled = !isCompressing,
                                    onClick = { picker.launch(arrayOf("video/*")) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(14.dp),
                                ) {
                                    Text(if (sourceInfo == null) "اختيار فيديو" else "اختيار فيديو آخر")
                                }
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("اختر مستوى الضغط", color = Ink, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            CompressionPreset.entries.forEach { preset ->
                                PresetCard(
                                    preset = preset,
                                    selected = selectedPreset == preset,
                                    enabled = !isCompressing,
                                    estimate = sourceInfo?.let { estimateSize(it.durationMs, preset) },
                                    originalSize = sourceInfo?.sizeBytes,
                                    onSelect = { selectedPreset = preset },
                                )
                            }
                        }

                        if (isCompressing) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                shape = RoundedCornerShape(18.dp),
                            ) {
                                Column(
                                    Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("جارٍ ضغط الفيديو…", fontWeight = FontWeight.SemiBold)
                                        Text("${(progress * 100).toInt()}٪", color = Purple, fontWeight = FontWeight.Bold)
                                    }
                                    LinearProgressIndicator(
                                        progress = { progress },
                                        modifier = Modifier.fillMaxWidth(),
                                        color = Purple,
                                    )
                                    OutlinedButton(
                                        onClick = {
                                            cancelCompression()
                                            isCompressing = false
                                            progress = 0f
                                            message = "تم إلغاء الضغط"
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text("إلغاء الضغط")
                                    }
                                }
                            }
                        } else {
                            Button(
                                onClick = {
                                    val uri = sourceUri
                                    val info = sourceInfo
                                    if (uri != null && info != null) {
                                        message = null
                                        resultUri = null
                                        isCompressing = true
                                        progress = 0f
                                        try {
                                            startCompression(uri, info, selectedPreset) { value, savedUri, error ->
                                                progress = value
                                                if (savedUri != null) {
                                                    isCompressing = false
                                                    resultUri = savedUri
                                                    history = loadHistory(this@MainActivity)
                                                    message = "اكتمل الضغط وتم حفظ الفيديو في Movies/VideoCompressor"
                                                } else if (error != null) {
                                                    isCompressing = false
                                                    message = "تعذّر ضغط الفيديو: $error"
                                                }
                                            }
                                        } catch (exception: IOException) {
                                            cancelCompression()
                                            isCompressing = false
                                            message = "تعذّر إنشاء ملف الفيديو: ${exception.message ?: "مساحة التخزين غير كافية"}"
                                        } catch (exception: SecurityException) {
                                            cancelCompression()
                                            isCompressing = false
                                            message = "لا يمكن الوصول إلى الفيديو المحدد"
                                        } catch (exception: ExportException) {
                                            cancelCompression()
                                            isCompressing = false
                                            message = "تعذّر بدء الضغط: ${exception.message ?: "الجهاز لا يدعم إعدادات التحويل"}"
                                        } catch (exception: IllegalStateException) {
                                            cancelCompression()
                                            isCompressing = false
                                            message = "تعذّر بدء الضغط: ${exception.message ?: "الجهاز لا يدعم إعدادات التحويل"}"
                                        } catch (exception: IllegalArgumentException) {
                                            cancelCompression()
                                            isCompressing = false
                                            message = "صيغة الفيديو غير مدعومة"
                                        }
                                    }
                                },
                                enabled = sourceInfo != null,
                                modifier = Modifier.fillMaxWidth().height(54.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Purple),
                            ) {
                                Text("ضغط الفيديو", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        if (message != null) {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = if (resultUri != null) Color(0xFFEAF5ED) else Color(0xFFFFF0EF),
                                ),
                                shape = RoundedCornerShape(16.dp),
                            ) {
                                Column(
                                    Modifier.fillMaxWidth().padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(message!!, color = Ink, fontSize = 14.sp)
                                    if (resultUri != null) {
                                        OutlinedButton(
                                            onClick = { shareVideo(resultUri!!) },
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Text("مشاركة الفيديو المضغوط")
                                        }
                                    }
                                }
                            }
                        }

                        if (history.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("آخر الفيديوهات المضغوطة", color = Ink, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                history.take(5).forEach { item ->
                                    HistoryCard(item) { shareVideo(Uri.parse(item.uri)) }
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
    }

    private fun startCompression(
        uri: Uri,
        info: VideoInfo,
        preset: CompressionPreset,
        onUpdate: (Float, Uri?, String?) -> Unit,
    ) {
        val file = File.createTempFile("compressed_", ".mp4", cacheDir)
        outputFile = file
        val targetHeight = if (info.height > 0) minOf(preset.height, info.height) else preset.height
        val effects = Effects(
            emptyList(),
            listOf(Presentation.createForHeight(targetHeight)),
        )
        val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(uri))
            .setEffects(effects)
            .build()
        val encoderFactory = DefaultEncoderFactory.Builder(this)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder().setBitrate(preset.videoBitrate).build(),
            )
            .build()
        lateinit var activeTransformer: Transformer
        activeTransformer = Transformer.Builder(this)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setEncoderFactory(encoderFactory)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (transformer !== activeTransformer) {
                        file.delete()
                        return
                    }
                    progressHandler?.removeCallbacksAndMessages(null)
                    lifecycleScope.launch {
                        try {
                            if (transformer !== activeTransformer) return@launch
                            val published = withContext(Dispatchers.IO) { publishVideo(file, info.name) }
                            recordHistory(this@MainActivity, HistoryItem(info.name, published.second, published.first.toString(), System.currentTimeMillis()))
                            file.delete()
                            outputFile = null
                            transformer = null
                            onUpdate(1f, published.first, null)
                        } catch (exception: IOException) {
                            file.delete()
                            outputFile = null
                            transformer = null
                            onUpdate(0f, null, exception.message ?: "حدث خطأ أثناء حفظ الملف")
                        } catch (exception: SecurityException) {
                            file.delete()
                            outputFile = null
                            transformer = null
                            onUpdate(0f, null, "لا توجد صلاحية لحفظ الملف")
                        } catch (exception: IllegalStateException) {
                            file.delete()
                            outputFile = null
                            transformer = null
                            onUpdate(0f, null, exception.message ?: "حدث خطأ أثناء حفظ الملف")
                        } catch (exception: IllegalArgumentException) {
                            file.delete()
                            outputFile = null
                            transformer = null
                            onUpdate(0f, null, exception.message ?: "حدث خطأ أثناء حفظ الملف")
                        }
                    }
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    if (transformer !== activeTransformer) return
                    progressHandler?.removeCallbacksAndMessages(null)
                    file.delete()
                    outputFile = null
                    transformer = null
                    onUpdate(0f, null, exportException.message ?: "صيغة الفيديو غير مدعومة")
                }
            })
            .build()
        transformer = activeTransformer
        activeTransformer.start(editedMediaItem, file.absolutePath)
        pollProgress(activeTransformer, onUpdate)
    }

    private fun pollProgress(activeTransformer: Transformer, onUpdate: (Float, Uri?, String?) -> Unit) {
        val holder = ProgressHolder()
        val handler = Handler(Looper.getMainLooper())
        progressHandler = handler
        val poll = object : Runnable {
            override fun run() {
                if (transformer !== activeTransformer) return
                if (activeTransformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onUpdate((holder.progress / 100f).coerceIn(0f, 0.99f), null, null)
                }
                handler.postDelayed(this, 250)
            }
        }
        handler.post(poll)
    }

    private fun cancelCompression() {
        progressHandler?.removeCallbacksAndMessages(null)
        transformer?.cancel()
        transformer = null
        outputFile?.delete()
        outputFile = null
    }

    private fun publishVideo(file: File, sourceName: String): Pair<Uri, Long> {
        val baseName = sourceName.substringBeforeLast('.', sourceName).ifBlank { "video" }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "${baseName}_compressed_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/VideoCompressor")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: error("تعذّر إنشاء ملف في مكتبة الفيديو")
        try {
            contentResolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            } ?: error("تعذّر فتح ملف الوجهة")
            val size = contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }?.takeIf { it >= 0 } ?: file.length()
            contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            return uri to size
        } catch (exception: Exception) {
            contentResolver.delete(uri, null, null)
            throw exception
        }
    }

    private fun shareVideo(uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "مشاركة الفيديو"))
    }
}

@Composable
private fun Header() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(52.dp).background(Purple, RoundedCornerShape(17.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("⇣", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(13.dp))
        Column {
            Text("ضغط الفيديوهات", color = Ink, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold)
            Text("جودة مناسبة. مساحة أقل.", color = Muted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SourceDetails(info: VideoInfo) {
    Row(
        Modifier.fillMaxWidth().background(Canvas, RoundedCornerShape(14.dp)).padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(info.name, color = Ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${formatBytes(info.sizeBytes)}  •  ${formatDuration(info.durationMs)}",
                color = Muted,
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            if (info.width > 0 && info.height > 0) "${info.width}×${info.height}" else "فيديو",
            color = Purple,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun PresetCard(
    preset: CompressionPreset,
    selected: Boolean,
    enabled: Boolean,
    estimate: Long?,
    originalSize: Long?,
    onSelect: () -> Unit,
) {
    val borderColor = if (selected) Purple else Color(0xFFE9E6ED)
    Card(
        onClick = onSelect,
        enabled = enabled,
        colors = CardDefaults.cardColors(containerColor = if (selected) Color(0xFFFCFAFF) else Color.White),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(if (selected) 1.5.dp else 1.dp, borderColor),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onSelect, enabled = enabled)
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(preset.title, color = Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("${preset.subtitle} · ${preset.height}p", color = Muted, fontSize = 12.sp)
            }
            if (estimate != null) {
                Column(horizontalAlignment = Alignment.End) {
                    Text("≈ ${formatBytes(estimate)}", color = Purple, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    if (originalSize != null && originalSize > 0) {
                        val reduction = (1 - estimate.toDouble() / originalSize).coerceIn(0.0, 1.0)
                        Text("توفير ${"%.0f".format(Locale.US, reduction * 100)}٪", color = Muted, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(item: HistoryItem, onShare: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(item.name, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text("${formatBytes(item.sizeBytes)} · ${formatDate(item.createdAt)}", color = Muted, fontSize = 12.sp)
            }
            OutlinedButton(onClick = onShare, shape = RoundedCornerShape(12.dp)) {
                Text("مشاركة", fontSize = 12.sp)
            }
        }
    }
}

private fun readVideoInfo(context: Context, uri: Uri): VideoInfo {
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: "video"
    val size = context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
    } ?: 0L
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, uri)
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        return VideoInfo(name, size, duration, width, height)
    } finally {
        retriever.release()
    }
}

private fun estimateSize(durationMs: Long, preset: CompressionPreset): Long {
    if (durationMs <= 0) return 0L
    return ((preset.videoBitrate + 96_000L) * (durationMs / 1000.0) / 8.0).toLong()
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "غير معروف"
    val units = listOf("بايت", "ك.ب", "م.ب", "ج.ب")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return "${"%.1f".format(Locale.US, value)} ${units[unit]}"
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    else String.format(Locale.US, "%d:%02d", minutes, seconds)
}

private fun formatDate(timeMs: Long): String =
    java.text.SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(timeMs)

private fun loadHistory(context: Context): List<HistoryItem> {
    val json = context.getSharedPreferences("compression_history", Context.MODE_PRIVATE)
        .getString("items", "[]") ?: "[]"
    return try {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            HistoryItem(
                item.optString("name"),
                item.optLong("size"),
                item.optString("uri"),
                item.optLong("createdAt"),
            )
        }
    } catch (exception: org.json.JSONException) {
        emptyList()
    }
}

private fun recordHistory(context: Context, item: HistoryItem) {
    val items = (listOf(item) + loadHistory(context)).take(20)
    val array = JSONArray()
    items.forEach { entry ->
        array.put(
            JSONObject()
                .put("name", entry.name)
                .put("size", entry.sizeBytes)
                .put("uri", entry.uri)
                .put("createdAt", entry.createdAt),
        )
    }
    context.getSharedPreferences("compression_history", Context.MODE_PRIVATE)
        .edit()
        .putString("items", array.toString())
        .apply()
}
