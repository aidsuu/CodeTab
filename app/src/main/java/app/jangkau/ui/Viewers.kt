package app.jangkau.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

private fun humanSize(bytes: Long): String = when {
    bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1 shl 10 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

fun decodeScaled(file: File, maxSide: Int = 4096): Pair<Bitmap, Pair<Int, Int>>? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
    val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    return bmp to (bounds.outWidth to bounds.outHeight)
}

@Composable
private fun ViewerBar(info: String, extra: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(info, style = Ide.small.copy(color = Ide.textDim), modifier = Modifier.weight(1f))
        extra()
    }
}

@Composable
fun ImageViewer(tab: EditorTab) {
    val file = tab.file ?: return
    val loaded by produceState<Pair<ImageBitmap, Pair<Int, Int>>?>(null, file) {
        value = withContext(Dispatchers.IO) { decodeScaled(file)?.let { (b, size) -> b.asImageBitmap() to size } }
    }
    var scale by remember(file) { mutableFloatStateOf(1f) }
    var offset by remember(file) { mutableStateOf(Offset.Zero) }
    Column(Modifier.fillMaxSize()) {
        val l = loaded
        ViewerBar(
            if (l == null) "Memuat gambar…" else "${l.second.first} × ${l.second.second} px  ·  ${humanSize(tab.size)}  ·  ${(scale * 100).toInt()}%",
        ) {
            SmallIcon(Icons.Outlined.ZoomOut, "Perkecil") { scale = (scale / 1.25f).coerceAtLeast(0.2f) }
            SmallIcon(Icons.Outlined.ZoomIn, "Perbesar") { scale = (scale * 1.25f).coerceAtMost(12f) }
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(12.dp).clip(Ide.itemShape).background(Color(0xFF111111))
                .pointerInput(file) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.2f, 12f)
                        offset += pan
                    }
                }
                .pointerInput(file) { detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero }) },
            contentAlignment = Alignment.Center,
        ) {
            if (l == null) {
                Text(if (tab.size > 0) "Memuat…" else "Gambar tidak bisa dibaca", style = Ide.label.copy(color = Ide.textDim))
            } else {
                Image(
                    l.first, tab.title,
                    modifier = Modifier.fillMaxSize().padding(8.dp).graphicsLayer {
                        scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                    },
                )
            }
        }
    }
}

/** PdfRenderer allows only one open page at a time. */
private class PdfDoc(file: File) {
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(fd)
    private val lock = Mutex()
    val pageCount = renderer.pageCount
    val ratios: List<Float> = (0 until pageCount).map { i -> renderer.openPage(i).use { it.height.toFloat() / it.width } }

    suspend fun render(index: Int, widthPx: Int): Bitmap = lock.withLock {
        withContext(Dispatchers.IO) {
            renderer.openPage(index).use { page ->
                val w = widthPx.coerceIn(200, 3000)
                val h = (w * page.height.toFloat() / page.width).toInt().coerceAtLeast(1)
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(android.graphics.Color.WHITE)
                    page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
    }

    fun close() { runCatching { renderer.close() }; runCatching { fd.close() } }
}

@Composable
fun PdfViewer(tab: EditorTab) {
    val file = tab.file ?: return
    var doc by remember(file) { mutableStateOf<PdfDoc?>(null) }
    var error by remember(file) { mutableStateOf<String?>(null) }
    LaunchedEffect(file) {
        runCatching { withContext(Dispatchers.IO) { PdfDoc(file) } }
            .onSuccess { doc = it }.onFailure { error = "PDF tidak bisa dibuka: ${it.message ?: it.javaClass.simpleName}" }
    }
    DisposableEffect(file) { onDispose { doc?.close() } }
    var zoom by remember(file) { mutableFloatStateOf(1f) }
    val list = rememberLazyListState()
    Column(Modifier.fillMaxSize()) {
        val d = doc
        ViewerBar(
            when {
                error != null -> error!!
                d == null -> "Memuat PDF…"
                else -> "Halaman ${list.firstVisibleItemIndex + 1} / ${d.pageCount}  ·  ${humanSize(tab.size)}  ·  ${(zoom * 100).toInt()}%"
            },
        ) {
            SmallIcon(Icons.Outlined.ZoomOut, "Perkecil") { zoom = (zoom / 1.2f).coerceAtLeast(0.5f) }
            SmallIcon(Icons.Outlined.ZoomIn, "Perbesar") { zoom = (zoom * 1.2f).coerceAtMost(4f) }
        }
        if (d == null) return@Column
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
            val density = LocalDensity.current
            val pageWidth = maxWidth * 0.92f * zoom
            val px = with(density) { pageWidth.roundToPx() }
            val hScroll = rememberScrollState()
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize().horizontalScroll(hScroll),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                items(d.pageCount) { i ->
                    val bmp by produceState<ImageBitmap?>(null, i, px) { value = d.render(i, px).asImageBitmap() }
                    Box(
                        Modifier.width(pageWidth).aspectRatio(1f / d.ratios[i]).background(Color.White),
                        contentAlignment = Alignment.Center,
                    ) {
                        bmp?.let { Image(it, "Halaman ${i + 1}", Modifier.fillMaxSize()) }
                            ?: Text("${i + 1}", style = Ide.label.copy(color = Color.Gray))
                    }
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}
