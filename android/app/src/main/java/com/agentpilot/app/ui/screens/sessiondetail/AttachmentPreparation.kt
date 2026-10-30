package com.agentpilot.app.ui.screens.sessiondetail

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.core.graphics.scale
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

internal data class PreparedUploadBytes(
    val name: String,
    val mediaType: String,
    val bytes: ByteArray,
)

/**
 * Downscales and re-encodes an image attachment before upload, honouring its
 * EXIF rotation. Non-images, animated GIFs and anything that would not get
 * smaller are returned byte-for-byte, so the fast path never regresses.
 */
internal fun prepareUploadBytes(
    sourceBytes: ByteArray,
    name: String,
    mediaType: String,
    maxDimension: Int = IMAGE_UPLOAD_MAX_DIMENSION,
    jpegQuality: Int = IMAGE_UPLOAD_JPEG_QUALITY,
    minSourceBytes: Long = IMAGE_UPLOAD_MIN_SOURCE_BYTES,
): PreparedUploadBytes {
    val mime = mediaType.trim().lowercase()
    if (!shouldReencodeAttachment(mime, sourceBytes.size.toLong(), minSourceBytes)) {
        return PreparedUploadBytes(name, mime, sourceBytes)
    }
    val encoding = preferredImageEncoding(mime)
    val reencoded = runCatching { reencodeImage(sourceBytes, encoding, maxDimension, jpegQuality) }.getOrNull()
    if (reencoded == null || reencoded.size >= sourceBytes.size) {
        return PreparedUploadBytes(name, mime, sourceBytes)
    }
    return PreparedUploadBytes(
        name = encodedFileName(name, encoding),
        mediaType = encodedMediaType(encoding, mime),
        bytes = reencoded,
    )
}

private const val EXIF_ORIENTATION_NORMAL = 1
private const val EXIF_ORIENTATION_ROTATE_180 = 3
private const val EXIF_ORIENTATION_ROTATE_90 = 6
private const val EXIF_ORIENTATION_ROTATE_270 = 8

private fun reencodeImage(
    source: ByteArray,
    encoding: ImageEncoding,
    maxDimension: Int,
    jpegQuality: Int,
): ByteArray {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
    val sourceWidth = bounds.outWidth
    val sourceHeight = bounds.outHeight
    if (sourceWidth <= 0 || sourceHeight <= 0) {
        throw IllegalStateException("Attachment image could not be decoded")
    }
    val orientation = exifOrientation(source)
    val quarterTurned = orientation == EXIF_ORIENTATION_ROTATE_90 || orientation == EXIF_ORIENTATION_ROTATE_270
    // Camera photos store their rotation in EXIF, so the visible long side can
    // differ from the stored one.
    val target = if (quarterTurned) {
        targetImageSize(sourceHeight, sourceWidth, maxDimension)
    } else {
        targetImageSize(sourceWidth, sourceHeight, maxDimension)
    }
    val decodeWidth = if (quarterTurned) target.height else target.width
    val decodeHeight = if (quarterTurned) target.width else target.height
    val options = BitmapFactory.Options().apply {
        inSampleSize = imageSampleSize(sourceWidth, sourceHeight, decodeWidth, decodeHeight)
    }
    val decoded = BitmapFactory.decodeByteArray(source, 0, source.size, options)
        ?: throw IllegalStateException("Attachment image could not be decoded")
    val oriented = applyExifOrientation(decoded, orientation)
    val scaled = if (oriented.width == target.width && oriented.height == target.height) {
        oriented
    } else {
        oriented.scale(target.width, target.height)
    }
    return try {
        ByteArrayOutputStream().use { output ->
            val format = if (encoding == ImageEncoding.Jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
            if (!scaled.compress(format, jpegQuality, output)) {
                throw IllegalStateException("Attachment image could not be encoded")
            }
            output.toByteArray()
        }
    } finally {
        if (scaled !== oriented) scaled.recycle()
        if (oriented !== decoded) oriented.recycle()
        decoded.recycle()
    }
}

// android.media.ExifInterface avoids another artifact; the picker only yields
// camera or gallery images, which this API handles on every supported release.
@SuppressLint("ExifInterface")
private fun exifOrientation(source: ByteArray): Int = runCatching {
    ExifInterface(ByteArrayInputStream(source))
        .getAttributeInt(ExifInterface.TAG_ORIENTATION, EXIF_ORIENTATION_NORMAL)
}.getOrDefault(EXIF_ORIENTATION_NORMAL)

private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
    val degrees = when (orientation) {
        EXIF_ORIENTATION_ROTATE_90 -> 90f
        EXIF_ORIENTATION_ROTATE_180 -> 180f
        EXIF_ORIENTATION_ROTATE_270 -> 270f
        else -> return bitmap
    }
    val matrix = Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}
