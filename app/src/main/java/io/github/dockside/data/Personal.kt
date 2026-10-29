package io.github.dockside.data

import android.Manifest
import android.app.AlarmManager
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.TimeZone

private fun Context.granted(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

data class CalEvent(
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val color: Int,
    val location: String?,
)

object CalendarRepository {
    fun hasPermission(context: Context) = context.granted(Manifest.permission.READ_CALENDAR)

    suspend fun upcoming(context: Context, days: Int = 7, limit: Int = 12): List<CalEvent> =
        withContext(Dispatchers.IO) {
            if (!hasPermission(context)) return@withContext emptyList()
            val now = System.currentTimeMillis()
            val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(builder, now - 24 * 3600_000L)
            ContentUris.appendId(builder, now + days * 24 * 3600_000L)
            val projection = arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.DISPLAY_COLOR,
                CalendarContract.Instances.EVENT_LOCATION,
            )
            val out = mutableListOf<CalEvent>()
            val tzOffset = { t: Long -> TimeZone.getDefault().getOffset(t).toLong() }
            runCatching {
                context.contentResolver.query(
                    builder.build(), projection, null, null, CalendarContract.Instances.BEGIN + " ASC"
                )?.use { c ->
                    while (c.moveToNext()) {
                        val allDay = c.getInt(3) == 1
                        // All-day instances are stored in UTC; shift them to local midnight.
                        var begin = c.getLong(1)
                        var end = c.getLong(2)
                        if (allDay) {
                            begin -= tzOffset(begin); end -= tzOffset(end)
                        }
                        if (end < now) continue
                        out += CalEvent(
                            title = c.getString(0) ?: "",
                            begin = begin,
                            end = end,
                            allDay = allDay,
                            color = c.getInt(4),
                            location = c.getString(5)?.takeIf { it.isNotBlank() },
                        )
                    }
                }
            }
            out.sortedBy { it.begin }.take(limit)
        }
}

fun nextAlarmTime(context: Context): Long? =
    context.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.triggerTime

data class Album(val id: String, val name: String, val uris: List<Uri>)

object PhotoRepository {
    const val FEATURED = "featured"

    fun hasLibraryPermission(context: Context) = when {
        // Android 14 "selected photos" access also lets us query the chosen items.
        Build.VERSION.SDK_INT >= 34 && context.granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> true
        Build.VERSION.SDK_INT >= 33 -> context.granted(Manifest.permission.READ_MEDIA_IMAGES)
        else -> context.granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** "Featured" = photos the user picked, followed by the largest device albums. */
    suspend fun albums(context: Context, settings: AppSettings, featuredName: String): List<Album> =
        withContext(Dispatchers.IO) {
            val result = mutableListOf<Album>()
            if (settings.photoUris.isNotEmpty()) {
                result += Album(FEATURED, featuredName, settings.photoUris.map(Uri::parse).shuffled())
            }
            if (hasLibraryPermission(context)) result += deviceAlbums(context)
            result
        }

    private fun deviceAlbums(context: Context): List<Album> {
        val buckets = linkedMapOf<String, Pair<String, MutableList<Uri>>>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        )
        runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null,
                MediaStore.Images.Media.DATE_ADDED + " DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val bucket = c.getString(1) ?: continue
                    val name = c.getString(2) ?: continue
                    val entry = buckets.getOrPut(bucket) { name to mutableListOf() }
                    if (entry.second.size < 300) {
                        entry.second += ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    }
                }
            }
        }
        return buckets.entries
            .sortedByDescending { it.value.second.size }
            .take(8)
            .map { (id, v) -> Album(id, v.first, v.second.shuffled()) }
    }
}

/** When a photo was taken, as recorded by the media store. */
object PhotoDates {
    private val cache = LruCache<String, Long>(512)

    suspend fun takenAt(context: Context, uri: Uri): Long? = withContext(Dispatchers.IO) {
        cache.get(uri.toString())?.let { return@withContext it.takeIf { t -> t > 0 } }
        val taken = runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.Images.ImageColumns.DATE_TAKEN), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
        }.getOrNull()?.takeIf { it > 0 }
        cache.put(uri.toString(), taken ?: -1L)
        taken
    }
}

object BitmapLoader {
    private val cache = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    suspend fun load(context: Context, uri: Uri, maxDim: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = "$uri@$maxDim"
        cache.get(key)?.let { return@withContext it }
        val bitmap = runCatching { decode(context, uri, maxDim) }.getOrNull() ?: return@withContext null
        bitmap.asImageBitmap().also { cache.put(key, it) }
    }

    private fun decode(context: Context, uri: Uri, maxDim: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= 29 && uri.authority == MediaStore.AUTHORITY) {
            runCatching { return context.contentResolver.loadThumbnail(uri, Size(maxDim, maxDim), null) }
        }
        if (Build.VERSION.SDK_INT >= 28) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val longest = maxOf(info.size.width, info.size.height)
                if (longest > maxDim) {
                    val scale = maxDim.toFloat() / longest
                    decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }
}
