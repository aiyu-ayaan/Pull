package com.aiyu.pull.ui.components

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

private val memoryCache = LruCache<String, ImageBitmap>(64)
private val imageHttpClient by lazy { OkHttpClient.Builder().build() }

/**
 * Lightweight Compose image component for video/media thumbnails.
 * Caches in-memory via LRU cache and fetches on IO thread via OkHttp.
 */
@Composable
fun RemoteThumbnail(
    url: String?,
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    contentScale: ContentScale = ContentScale.Crop,
    contentDescription: String? = null,
    placeholder: @Composable () -> Unit,
) {
    if (url.isNullOrBlank()) {
        placeholder()
        return
    }

    var bitmap by remember(url) { mutableStateOf(memoryCache.get(url)) }

    LaunchedEffect(url) {
        if (bitmap != null) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url(url).build()
                imageHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val bytes = resp.body.bytes()
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }
            }.getOrNull()
        }
        if (loaded != null) {
            memoryCache.put(url, loaded)
            bitmap = loaded
        }
    }

    val clipModifier = if (shape != null) modifier.clip(shape) else modifier

    Box(modifier = clipModifier, contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!,
                contentDescription = contentDescription,
                modifier = Modifier.matchParentSize(),
                contentScale = contentScale,
            )
        } else {
            placeholder()
        }
    }
}
