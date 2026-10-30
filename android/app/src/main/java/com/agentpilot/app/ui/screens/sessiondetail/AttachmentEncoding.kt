package com.agentpilot.app.ui.screens.sessiondetail

/**
 * Pure decisions for preparing an attachment before it is uploaded.
 *
 * The connector pulls every user attachment from the server over the shared,
 * bandwidth-capped public link before the runtime can start the turn, so an
 * untouched camera photo costs the sender ~20 s of waiting. Shrinking the image
 * on the phone is therefore a latency fix, not a cosmetic one.
 */

internal data class TargetImageSize(val width: Int, val height: Int)

internal enum class ImageEncoding { KeepOriginal, Jpeg, Png }

/** A file below this is already cheap to transfer; re-encoding would only lose detail. */
internal const val IMAGE_UPLOAD_MIN_SOURCE_BYTES = 300L * 1024

/** Long-side limit for uploaded images: still large enough for readable screenshots. */
internal const val IMAGE_UPLOAD_MAX_DIMENSION = 1920

/** Quality used when re-encoding photos. */
internal const val IMAGE_UPLOAD_JPEG_QUALITY = 85

/** Scales [width] x [height] down so its long side is at most [maxDimension]. */
internal fun targetImageSize(width: Int, height: Int, maxDimension: Int): TargetImageSize {
    if (width <= 0 || height <= 0 || maxDimension <= 0) return TargetImageSize(width, height)
    val longSide = maxOf(width, height)
    if (longSide <= maxDimension) return TargetImageSize(width, height)
    val scale = maxDimension.toDouble() / longSide.toDouble()
    return TargetImageSize(
        width = maxOf(1, (width * scale).toInt()),
        height = maxOf(1, (height * scale).toInt()),
    )
}

/**
 * Largest power-of-two sample size that still decodes at or above
 * [targetWidth] x [targetHeight]; sampling further would only blur the upscale
 * back to the target size.
 */
internal fun imageSampleSize(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): Int {
    if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) return 1
    var sample = 1
    while (sourceWidth / (sample * 2) >= targetWidth && sourceHeight / (sample * 2) >= targetHeight) {
        sample *= 2
    }
    return sample
}

/**
 * Animated GIFs, vector formats and image subtypes this build cannot decode stay
 * untouched; screenshots keep lossless PNG.
 */
internal fun preferredImageEncoding(mediaType: String): ImageEncoding {
    val mime = mediaType.trim().lowercase()
    if (!mime.startsWith("image/")) return ImageEncoding.KeepOriginal
    return when {
        mime.contains("png") || mime.contains("webp") -> ImageEncoding.Png
        mime.contains("jpeg") || mime.contains("jpg") -> ImageEncoding.Jpeg
        mime.contains("heic") || mime.contains("heif") -> ImageEncoding.Jpeg
        else -> ImageEncoding.KeepOriginal
    }
}

internal fun encodedMediaType(encoding: ImageEncoding, fallback: String): String = when (encoding) {
    ImageEncoding.Jpeg -> "image/jpeg"
    ImageEncoding.Png -> "image/png"
    ImageEncoding.KeepOriginal -> fallback
}

/** Keeps the display name but swaps the extension to the re-encoded format. */
internal fun encodedFileName(originalName: String, encoding: ImageEncoding): String {
    val extension = when (encoding) {
        ImageEncoding.KeepOriginal -> return originalName
        ImageEncoding.Jpeg -> "jpg"
        ImageEncoding.Png -> "png"
    }
    val trimmed = originalName.trim()
    if (trimmed.isEmpty()) return "attachment." + extension
    val base = trimmed.substringBeforeLast('.', trimmed).ifBlank { "attachment" }
    return base + "." + extension
}

/** Whether the media type and size make a re-encode pass worth its CPU cost. */
internal fun shouldReencodeAttachment(mediaType: String, byteCount: Long, minSourceBytes: Long): Boolean {
    if (byteCount < minSourceBytes) return false
    return preferredImageEncoding(mediaType) != ImageEncoding.KeepOriginal
}
