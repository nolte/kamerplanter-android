package io.github.nolte.kamerplanter.feature.plants

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nolte.kamerplanter.core.camera.MAX_PHOTO_BYTES
import io.github.nolte.kamerplanter.core.camera.NormalizationProfile
import io.github.nolte.kamerplanter.core.camera.PickedImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.ByteArrayInputStream
import java.io.File

/**
 * What leaves the device on the identification route, asserted on the bytes (F-12
 * acceptance-4; R10, R11): one captured frame, cut once for the recogniser and once for the
 * gallery, each inside its own profile's long edge, both upright, and neither carrying the
 * position or the orientation the capture arrived with.
 *
 * The fixture is a landscape frame whose EXIF says "rotate 90°" and carries a GPS position —
 * the shape a phone camera hands back, where the pixels lie on their side and only the tag
 * knows better. A cut that dropped the tag without turning the pixels would arrive at the
 * instance lying down; a cut that kept it would send the garden's coordinates to Pl@ntNet.
 *
 * Instrumented because `BitmapFactory` is: the JVM suite asserts which profile was asked for,
 * this one asserts what the profile does to the pixels.
 */
@RunWith(JUnit4::class)
class CapturedImageBytesTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theRecognitionCutIsInsideItsEdgeUprightAndFreeOfMetadata() {
        val image = PickedImage(capturedFrame(), rotationDegrees = 90)

        val sent = image.normalized(NormalizationProfile.RECOGNITION, IDENTIFY_MAX_BYTES)

        assertNotNull(sent)
        val bounds = sent!!.bounds()
        assertTrue("long edge ${bounds.longEdge}", bounds.longEdge <= NormalizationProfile.RECOGNITION.maxEdgePx)
        // Rotated into the pixels: the landscape source is portrait once upright.
        assertTrue("upright: ${bounds.width}x${bounds.height}", bounds.height > bounds.width)
        sent.assertNoExif()
    }

    @Test
    fun theGalleryCutIsLargerThanTheRecognitionCutAndAlsoFreeOfMetadata() {
        val image = PickedImage(capturedFrame(), rotationDegrees = 90)

        val sent = image.normalized(NormalizationProfile.RECOGNITION, IDENTIFY_MAX_BYTES)!!
        val kept = image.normalized(NormalizationProfile.GALLERY, MAX_PHOTO_BYTES)!!

        val keptBounds = kept.bounds()
        assertTrue(keptBounds.longEdge <= NormalizationProfile.GALLERY.maxEdgePx)
        assertTrue(keptBounds.longEdge > sent.bounds().longEdge)
        assertTrue(keptBounds.height > keptBounds.width)
        kept.assertNoExif()
        // Two cuts from one original, not one cut re-used: the bytes differ.
        assertFalse(kept.contentEquals(sent))
    }

    /** A 3000×2000 landscape JPEG with an orientation tag and a GPS position. */
    private fun capturedFrame(): ByteArray {
        val file = File(context.cacheDir, "captured-frame-fixture.jpg")
        Bitmap.createBitmap(SOURCE_WIDTH, SOURCE_HEIGHT, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.GREEN)
            file.outputStream().use { compress(Bitmap.CompressFormat.JPEG, SOURCE_QUALITY, it) }
            recycle()
        }
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "52/1,31/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "13/1,24/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
            saveAttributes()
        }
        val bytes = file.readBytes()
        file.delete()
        // The fixture is what it claims to be, or the assertions below prove nothing.
        val fixture = ExifInterface(ByteArrayInputStream(bytes))
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, fixture.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
        assertNotNull(fixture.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        return bytes
    }

    private class Bounds(val width: Int, val height: Int) {
        val longEdge: Int get() = maxOf(width, height)
    }

    private fun ByteArray.bounds(): Bounds {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(this, 0, size, options)
        return Bounds(options.outWidth, options.outHeight)
    }

    /** No EXIF at all — asserted on the tags and on the segment, so an empty APP1 cannot pass. */
    private fun ByteArray.assertNoExif() {
        val exif = ExifInterface(ByteArrayInputStream(this))
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE))
        assertEquals(
            ExifInterface.ORIENTATION_UNDEFINED,
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED),
        )
        assertFalse("an APP1 segment survived", hasApp1Segment())
    }

    /** Walks the JPEG markers up to the scan and reports an APP1 (`FF E1`) segment, where EXIF lives. */
    private fun ByteArray.hasApp1Segment(): Boolean {
        var i = 2
        while (i + 4 <= size && this[i] == MARKER_PREFIX) {
            val marker = this[i + 1].toInt() and BYTE_MASK
            if (marker == APP1) return true
            if (marker == START_OF_SCAN) return false
            val length = ((this[i + 2].toInt() and BYTE_MASK) shl BITS_PER_BYTE) or (this[i + 3].toInt() and BYTE_MASK)
            i += 2 + length
        }
        return false
    }

    private companion object {
        const val SOURCE_WIDTH = 3000
        const val SOURCE_HEIGHT = 2000
        const val SOURCE_QUALITY = 95
        const val IDENTIFY_MAX_BYTES = 5 * 1024 * 1024
        const val MARKER_PREFIX = 0xFF.toByte()
        const val APP1 = 0xE1
        const val START_OF_SCAN = 0xDA
        const val BYTE_MASK = 0xFF
        const val BITS_PER_BYTE = 8
    }
}
