package com.aiyu.pull.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiyu.pull.ui.components.RemoteThumbnail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Site icons the WebView reported, per host, kept on disk so shortcuts show them across launches. */
object Favicons {
    private val memory = LruCache<String, ImageBitmap>(64)

    private fun host(url: String): String? = runCatching { Uri.parse(url).host?.removePrefix("www.") }.getOrNull()

    private fun file(context: Context, host: String) = File(context.cacheDir, "favicons/$host.png")

    fun save(context: Context, url: String, icon: Bitmap) {
        val host = host(url) ?: return
        memory.put(host, icon.asImageBitmap())
        runCatching {
            file(context, host).apply { parentFile?.mkdirs() }.outputStream().use { icon.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    suspend fun load(context: Context, url: String): ImageBitmap? {
        val host = host(url) ?: return null
        memory.get(host)?.let { return it }
        return withContext(Dispatchers.IO) {
            val f = file(context, host)
            if (!f.exists()) null else BitmapFactory.decodeFile(f.path)?.asImageBitmap()?.also { memory.put(host, it) }
        }
    }

    fun cached(url: String): ImageBitmap? = host(url)?.let { memory.get(it) }
}

/** Favicon in a circle: the one seen while browsing, else the site's /favicon.ico, else its first letter. */
@Composable
fun SiteIcon(url: String, title: String, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val context = LocalContext.current
    var icon by remember(url) { mutableStateOf(Favicons.cached(url)) }
    var looked by remember(url) { mutableStateOf(icon != null) }
    LaunchedEffect(url) {
        if (icon == null) icon = Favicons.load(context, url)
        looked = true
    }
    val letter: @Composable () -> Unit = {
        Box(
            modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            val ch = (title.ifBlank { Uri.parse(url).host.orEmpty().removePrefix("www.") }).firstOrNull()?.uppercaseChar() ?: '?'
            Text(
                ch.toString(),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.45f).sp,
            )
        }
    }
    val current = icon
    when {
        current != null -> Image(current, null, modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop)
        !looked -> letter()
        else -> {
            val origin = runCatching { Uri.parse(url).let { "${it.scheme}://${it.host}/favicon.ico" } }.getOrNull()
            RemoteThumbnail(url = origin, modifier = modifier.size(size), shape = CircleShape, placeholder = letter)
        }
    }
}
