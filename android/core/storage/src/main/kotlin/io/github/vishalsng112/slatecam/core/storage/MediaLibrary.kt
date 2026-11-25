package io.github.vishalsng112.slatecam.core.storage

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.util.Size
import java.io.File
import java.io.FileOutputStream

/** One recorded SlateCam clip as MediaStore reports it. */
data class StoredClip(
    val id: String,
    val name: String,
    val uri: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val dateAddedMs: Long,
)

data class StorageInfo(val freeBytes: Long, val totalBytes: Long)

/**
 * Read/delete/share access to the clips SlateCam wrote under
 * `Movies/SlateCam`. Playback itself lives in the Flutter media screen.
 */
class MediaLibrary(private val context: Context) {

    private val resolver get() = context.contentResolver
    private val collection: Uri =
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    fun list(limit: Int = 500): List<StoredClip> {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DATE_ADDED,
        )
        val selection =
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? AND " +
                "${MediaStore.Video.Media.IS_PENDING} = 0"
        val args = arrayOf("%${Environment.DIRECTORY_MOVIES}/SlateCam%")
        val clips = mutableListOf<StoredClip>()

        runCatching {
            resolver.query(
                collection,
                projection,
                selection,
                args,
                "${MediaStore.Video.Media.DATE_ADDED} DESC",
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                while (cursor.moveToNext() && clips.size < limit) {
                    val id = cursor.getLong(idCol)
                    clips += StoredClip(
                        id = id.toString(),
                        name = cursor.getString(nameCol) ?: "SlateCam clip",
                        uri = ContentUris.withAppendedId(collection, id).toString(),
                        durationMs = cursor.getLong(durationCol).coerceAtLeast(0L),
                        sizeBytes = cursor.getLong(sizeCol).coerceAtLeast(0L),
                        width = cursor.getInt(widthCol).coerceAtLeast(0),
                        height = cursor.getInt(heightCol).coerceAtLeast(0),
                        dateAddedMs = cursor.getLong(dateCol).coerceAtLeast(0L) * 1_000L,
                    )
                }
            }
        }
        return clips
    }

    /** JPEG in the app cache. Generated once per clip and reused. */
    fun thumbnail(id: String): String? {
        val numericId = id.toLongOrNull() ?: return null
        val dir = File(context.cacheDir, THUMB_DIR).apply { mkdirs() }
        val file = File(dir, "$id.jpg")
        if (file.length() > 0L) return file.absolutePath
        val uri = ContentUris.withAppendedId(collection, numericId)
        return runCatching {
            val bitmap: Bitmap = resolver.loadThumbnail(uri, Size(480, 270), null)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
            }
            bitmap.recycle()
            file.absolutePath
        }.getOrNull()
    }

    fun delete(id: String): Boolean {
        val numericId = id.toLongOrNull() ?: return false
        val name = displayName(numericId)
        val uri = ContentUris.withAppendedId(collection, numericId)
        val deleted = runCatching { resolver.delete(uri, null, null) > 0 }
            .getOrDefault(false)
        if (!deleted) return false
        name?.let { clipName ->
            val base = clipName.substringBeforeLast('.')
            File(File(context.filesDir, GYRO_DIR), "$base.gyro.json").delete()
        }
        File(File(context.cacheDir, THUMB_DIR), "$id.jpg").delete()
        return true
    }

    fun share(id: String): Boolean {
        val numericId = id.toLongOrNull() ?: return false
        val uri = ContentUris.withAppendedId(collection, numericId)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching {
            context.startActivity(
                Intent.createChooser(send, "Share clip")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrDefault(false)
    }

    fun storage(): StorageInfo = runCatching {
        val stat = StatFs(Environment.getExternalStorageDirectory().path)
        StorageInfo(stat.availableBytes, stat.totalBytes)
    }.getOrElse { StorageInfo(0L, 0L) }

    private fun displayName(numericId: Long): String? = runCatching {
        resolver.query(
            ContentUris.withAppendedId(collection, numericId),
            arrayOf(MediaStore.Video.Media.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private companion object {
        const val THUMB_DIR = "clip-thumbnails"
        const val GYRO_DIR = "gyro"
    }
}
