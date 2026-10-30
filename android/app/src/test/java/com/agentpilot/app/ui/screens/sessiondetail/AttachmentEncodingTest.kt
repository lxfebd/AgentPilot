package com.agentpilot.app.ui.screens.sessiondetail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentEncodingTest {

    @Test
    fun targetImageSizeKeepsSmallImagesUntouched() {
        assertEquals(TargetImageSize(1200, 800), targetImageSize(1200, 800, 1920))
    }

    @Test
    fun targetImageSizeScalesTheLongSideDown() {
        assertEquals(TargetImageSize(1920, 1080), targetImageSize(3840, 2160, 1920))
        assertEquals(TargetImageSize(862, 1920), targetImageSize(1200, 2670, 1920))
    }

    @Test
    fun targetImageSizeNeverReturnsZero() {
        assertEquals(TargetImageSize(1, 1), targetImageSize(3, 40, 1))
    }

    @Test
    fun targetImageSizeIgnoresInvalidInput() {
        assertEquals(TargetImageSize(10, 0), targetImageSize(10, 0, 1920))
        assertEquals(TargetImageSize(10, 10), targetImageSize(10, 10, 0))
    }

    @Test
    fun imageSampleSizeDecodesJustAboveTheTarget() {
        assertEquals(1, imageSampleSize(1200, 800, 1200, 800))
        assertEquals(2, imageSampleSize(4000, 3000, 1920, 1440))
        assertEquals(1, imageSampleSize(4000, 3000, 3000, 2000))
        assertEquals(8, imageSampleSize(8000, 6000, 1000, 750))
    }

    @Test
    fun preferredImageEncodingKeepsAnimatedAndNonImages() {
        assertEquals(ImageEncoding.KeepOriginal, preferredImageEncoding("image/gif"))
        assertEquals(ImageEncoding.KeepOriginal, preferredImageEncoding("image/svg+xml"))
        assertEquals(ImageEncoding.KeepOriginal, preferredImageEncoding("image/tiff"))
        assertEquals(ImageEncoding.KeepOriginal, preferredImageEncoding("application/pdf"))
    }

    @Test
    fun preferredImageEncodingKeepsScreenshotsLossless() {
        assertEquals(ImageEncoding.Png, preferredImageEncoding("image/png"))
        assertEquals(ImageEncoding.Png, preferredImageEncoding("IMAGE/WEBP"))
    }

    @Test
    fun preferredImageEncodingCompressesPhotos() {
        assertEquals(ImageEncoding.Jpeg, preferredImageEncoding("image/jpeg"))
        assertEquals(ImageEncoding.Jpeg, preferredImageEncoding("image/heic"))
    }

    @Test
    fun encodedFileNameSwapsTheExtension() {
        assertEquals("IMG_0001.jpg", encodedFileName("IMG_0001.heic", ImageEncoding.Jpeg))
        assertEquals("Screenshot.png", encodedFileName("Screenshot.webp", ImageEncoding.Png))
        assertEquals("noextension.jpg", encodedFileName("noextension", ImageEncoding.Jpeg))
        assertEquals("animation.gif", encodedFileName("animation.gif", ImageEncoding.KeepOriginal))
    }

    @Test
    fun encodedMediaTypeTracksTheEncoding() {
        assertEquals("image/jpeg", encodedMediaType(ImageEncoding.Jpeg, "image/heic"))
        assertEquals("image/png", encodedMediaType(ImageEncoding.Png, "image/webp"))
        assertEquals("image/heic", encodedMediaType(ImageEncoding.KeepOriginal, "image/heic"))
    }

    @Test
    fun shouldReencodeAttachmentGuardsSmallAndNonImageFiles() {
        assertFalse(shouldReencodeAttachment("image/jpeg", 120L * 1024, IMAGE_UPLOAD_MIN_SOURCE_BYTES))
        assertTrue(shouldReencodeAttachment("image/jpeg", 4L * 1024 * 1024, IMAGE_UPLOAD_MIN_SOURCE_BYTES))
        assertFalse(shouldReencodeAttachment("video/mp4", 9L * 1024 * 1024, IMAGE_UPLOAD_MIN_SOURCE_BYTES))
        assertFalse(shouldReencodeAttachment("image/gif", 9L * 1024 * 1024, IMAGE_UPLOAD_MIN_SOURCE_BYTES))
    }
}
