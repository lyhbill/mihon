package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Bitmap
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * High quality upscaler used by the chapter build pipeline.
 *
 * A separable Lanczos-3 resampler is used rather than a neural network: manga is line art,
 * so there is no texture to reconstruct, while generic SR models tend to smear thin strokes
 * and invent ringing next to panel borders. Results are deterministic, which also keeps the
 * build cache stable across devices.
 *
 * Swapping in an ONNX model later only requires replacing [upscale]; the pipeline, cache
 * format and reader stay untouched.
 */
object SuperResolution {

    const val MIN_FACTOR = 2
    const val MAX_FACTOR = 2
    const val DEFAULT_FACTOR = 2

    private const val LANCZOS_A = 3.0
    private const val TAP_COUNT = 6
    private const val TILE_ROWS = 64

    fun upscale(source: Bitmap, factor: Int = DEFAULT_FACTOR): Bitmap {
        val f = factor.coerceIn(MIN_FACTOR, MAX_FACTOR)
        val srcWidth = source.width
        val srcHeight = source.height
        if (srcWidth <= 0 || srcHeight <= 0) return source

        val dstWidth = srcWidth * f
        val dstHeight = srcHeight * f
        val pixels = IntArray(srcWidth * srcHeight)
        source.getPixels(pixels, 0, srcWidth, 0, 0, srcWidth, srcHeight)

        val horizontal = IntArray(dstWidth * srcHeight)
        val xTaps = taps(srcWidth, dstWidth)
        for (y in 0 until srcHeight) {
            val rowOffset = y * srcWidth
            val outOffset = y * dstWidth
            for (x in 0 until dstWidth) {
                val tap = xTaps[x]
                var alpha = 0.0
                var red = 0.0
                var green = 0.0
                var blue = 0.0
                for (k in 0 until TAP_COUNT) {
                    val sx = (tap.start + k).coerceIn(0, srcWidth - 1)
                    val color = pixels[rowOffset + sx]
                    val w = tap.weights[k]
                    alpha += w * ((color ushr 24) and 0xFF)
                    red += w * ((color ushr 16) and 0xFF)
                    green += w * ((color ushr 8) and 0xFF)
                    blue += w * (color and 0xFF)
                }
                horizontal[outOffset + x] = pack(alpha, red, green, blue)
            }
        }

        val output = Bitmap.createBitmap(dstWidth, dstHeight, Bitmap.Config.ARGB_8888)
        val yTaps = taps(srcHeight, dstHeight)
        val strip = IntArray(dstWidth * TILE_ROWS)
        var top = 0
        while (top < dstHeight) {
            val rows = min(TILE_ROWS, dstHeight - top)
            for (row in 0 until rows) {
                val tap = yTaps[top + row]
                val outOffset = row * dstWidth
                for (x in 0 until dstWidth) {
                    var alpha = 0.0
                    var red = 0.0
                    var green = 0.0
                    var blue = 0.0
                    for (k in 0 until TAP_COUNT) {
                        val sy = (tap.start + k).coerceIn(0, srcHeight - 1)
                        val color = horizontal[sy * dstWidth + x]
                        val w = tap.weights[k]
                        alpha += w * ((color ushr 24) and 0xFF)
                        red += w * ((color ushr 16) and 0xFF)
                        green += w * ((color ushr 8) and 0xFF)
                        blue += w * (color and 0xFF)
                    }
                    strip[outOffset + x] = pack(alpha, red, green, blue)
                }
            }
            output.setPixels(strip, 0, dstWidth, 0, top, dstWidth, rows)
            top += rows
        }
        return output
    }

    private fun pack(alpha: Double, red: Double, green: Double, blue: Double): Int {
        val a = alpha.toInt().coerceIn(0, 255)
        val r = red.toInt().coerceIn(0, 255)
        val g = green.toInt().coerceIn(0, 255)
        val b = blue.toInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun taps(srcLength: Int, dstLength: Int): Array<Tap> {
        val scale = dstLength.toDouble() / srcLength
        return Array(dstLength) { index ->
            val center = (index + 0.5) / scale - 0.5
            val start = floor(center - LANCZOS_A).toInt() + 1
            val weights = DoubleArray(TAP_COUNT)
            var sum = 0.0
            for (i in 0 until TAP_COUNT) {
                val distance = center - (start + i)
                val w = sinc(distance) * sinc(distance / LANCZOS_A)
                weights[i] = w
                sum += w
            }
            if (sum != 0.0) {
                for (i in 0 until TAP_COUNT) weights[i] /= sum
            }
            Tap(start, weights)
        }
    }

    private fun sinc(x: Double): Double {
        if (x == 0.0) return 1.0
        val px = PI * x
        return sin(px) / px
    }

    private class Tap(val start: Int, val weights: DoubleArray)

    /** Unsharp mask: crisper strokes without changing the pixel count. */
    fun sharpen(source: Bitmap, amount: Float = 0.6f): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val output = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val center = pixels[index]
                var rSum = 0
                var gSum = 0
                var bSum = 0
                for (dy in -1..1) {
                    val sy = (y + dy).coerceIn(0, height - 1)
                    for (dx in -1..1) {
                        val sx = (x + dx).coerceIn(0, width - 1)
                        val c = pixels[sy * width + sx]
                        rSum += (c shr 16) and 0xFF
                        gSum += (c shr 8) and 0xFF
                        bSum += c and 0xFF
                    }
                }
                val r = (center shr 16) and 0xFF
                val g = (center shr 8) and 0xFF
                val b = center and 0xFF
                val nr = (r + amount * (r - rSum / 9f)).toInt().coerceIn(0, 255)
                val ng = (g + amount * (g - gSum / 9f)).toInt().coerceIn(0, 255)
                val nb = (b + amount * (b - bSum / 9f)).toInt().coerceIn(0, 255)
                output[index] = ((center shr 24) shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(output, 0, width, 0, 0, width, height)
        }
    }
}
