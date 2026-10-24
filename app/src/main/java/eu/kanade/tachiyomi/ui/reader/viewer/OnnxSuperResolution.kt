package eu.kanade.tachiyomi.ui.reader.viewer

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.nio.ByteBuffer
import java.nio.FloatBuffer

/**
 * Real-ESRGAN x4plus inference for the chapter build pipeline.
 *
 * The exported model has a fixed 128x128 input and a 512x512 (4x) output, so a page is
 * processed as overlapping tiles and the result is scaled down to the requested factor.
 * Inference is CPU bound and slow (roughly one second per tile on an e-ink tablet), which is
 * why it only ever runs inside the background build queue.
 */
object OnnxSuperResolution {

    private const val TILE = 128
    private const val MODEL_SCALE = 4
    private const val OVERLAP = 16
    private const val STRIDE = TILE - OVERLAP
    private const val MAX_SOURCE_SIDE = 1600

    private const val MODEL_DIRECTORY = "super_resolution"
    private const val FLOAT_MODEL = "real_esrgan_x4plus.onnx"
    private const val FLOAT_DATA = "real_esrgan_x4plus.data"
    private const val QUANTIZED_MODEL = "real_esrgan_x4plus_w8a8.onnx"
    private const val QUANTIZED_DATA = "real_esrgan_x4plus_w8a8.data"

    @Volatile
    private var floatRuntime: Runtime? = null

    @Volatile
    private var quantizedRuntime: Runtime? = null

    fun isAvailable(context: Context): Boolean {
        return runCatching { ensureRuntime(context) != null }.getOrDefault(false)
    }

    /**
     * Upscales [source] with the neural network. Returns null when the model cannot be used,
     * in which case the caller falls back to [SuperResolution].
     */
    fun upscale(context: Context, source: Bitmap, factor: Int, quantized: Boolean): Bitmap? {
        val active = runCatching { ensureRuntime(context, quantized) }.getOrNull() ?: return null
        val prepared = if (maxOf(source.width, source.height) > MAX_SOURCE_SIDE) {
            val ratio = MAX_SOURCE_SIDE.toFloat() / maxOf(source.width, source.height)
            Bitmap.createScaledBitmap(
                source,
                (source.width * ratio).toInt().coerceAtLeast(1),
                (source.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            null
        }
        val input = prepared ?: source
        return try {
            runTiles(active, input, factor, quantized)
        } catch (error: Throwable) {
            logcat(LogPriority.ERROR, error) { "Super resolution inference failed" }
            null
        } finally {
            prepared?.recycle()
        }
    }

    private fun runTiles(active: Runtime, input: Bitmap, factor: Int, quantized: Boolean): Bitmap {
        val width = input.width
        val height = input.height
        val targetFactor = factor.coerceIn(2, 4)
        val targetWidth = width * targetFactor
        val targetHeight = height * targetFactor
        val output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        val sourcePixels = IntArray(width * height)
        input.getPixels(sourcePixels, 0, width, 0, 0, width, height)

        val xs = positions(width)
        val ys = positions(height)
        val plane = TILE * TILE
        val buffer = FloatBuffer.allocate(3 * plane)
        val tileBitmap = Bitmap.createBitmap(TILE * MODEL_SCALE, TILE * MODEL_SCALE, Bitmap.Config.ARGB_8888)
        val tilePixels = IntArray(TILE * MODEL_SCALE * TILE * MODEL_SCALE)

        for (y0 in ys) {
            for (x0 in xs) {
                buffer.clear()
                // NCHW float, normalised to 0..1
                for (channel in 0 until 3) {
                    val shift = when (channel) {
                        0 -> 16
                        1 -> 8
                        else -> 0
                    }
                    for (y in 0 until TILE) {
                        val rowOffset = (y0 + y) * width + x0
                        for (x in 0 until TILE) {
                            val color = sourcePixels[rowOffset + x]
                            buffer.put(((color shr shift) and 0xFF) / 255f)
                        }
                    }
                }
                buffer.rewind()

                OnnxTensor.createTensor(
                    active.environment,
                    buffer,
                    longArrayOf(1, 3, TILE.toLong(), TILE.toLong()),
                ).use { tensor ->
                    active.session.run(mapOf(active.inputName to tensor)).use { result ->
                        val out = result[0] as OnnxTensor
                        val floats = out.floatBuffer
                        val outSide = TILE * MODEL_SCALE
                        val planeOut = outSide * outSide
                        for (index in 0 until planeOut) {
                            val r = (floats.get(index) * 255f).toInt().coerceIn(0, 255)
                            val g = (floats.get(planeOut + index) * 255f).toInt().coerceIn(0, 255)
                            val b = (floats.get(2 * planeOut + index) * 255f).toInt().coerceIn(0, 255)
                            tilePixels[index] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        }
                        tileBitmap.setPixels(tilePixels, 0, outSide, 0, 0, outSide, outSide)
                        val destination = RectF(
                            x0.toFloat() * targetFactor,
                            y0.toFloat() * targetFactor,
                            (x0 + TILE).toFloat() * targetFactor,
                            (y0 + TILE).toFloat() * targetFactor,
                        )
                        canvas.drawBitmap(
                            tileBitmap,
                            Rect(0, 0, outSide, outSide),
                            destination,
                            paint,
                        )
                    }
                }
            }
        }
        tileBitmap.recycle()
        return output
    }

    private fun inferQuantized(
        active: Runtime,
        sourcePixels: IntArray,
        width: Int,
        x0: Int,
        y0: Int,
        outSide: Int,
    ): IntArray {
        val plane = TILE * TILE
        val buffer = ByteBuffer.allocateDirect(3 * plane)
        for (channel in 0 until 3) {
            val shift = when (channel) {
                0 -> 16
                1 -> 8
                else -> 0
            }
            for (y in 0 until TILE) {
                val rowOffset = (y0 + y) * width + x0
                for (x in 0 until TILE) {
                    buffer.put(((sourcePixels[rowOffset + x] shr shift) and 0xFF).toByte())
                }
            }
        }
        buffer.rewind()
        val pixels = IntArray(outSide * outSide)
        OnnxTensor.createTensor(
            active.environment,
            buffer,
            longArrayOf(1, 3, TILE.toLong(), TILE.toLong()),
            OnnxJavaType.UINT8,
        ).use { tensor ->
            active.session.run(mapOf(active.inputName to tensor)).use { result ->
                val bytes = (result[0] as OnnxTensor).byteBuffer
                val planeOut = outSide * outSide
                for (index in 0 until planeOut) {
                    val r = bytes.get(index).toInt() and 0xFF
                    val g = bytes.get(planeOut + index).toInt() and 0xFF
                    val b = bytes.get(2 * planeOut + index).toInt() and 0xFF
                    pixels[index] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
        return pixels
    }

    /** Tile origins along one axis; the last tile is clamped so the edge is always covered. */
    private fun positions(length: Int): IntArray {
        if (length <= TILE) return intArrayOf(0)
        val result = ArrayList<Int>()
        var position = 0
        while (position + TILE < length) {
            result.add(position)
            position += STRIDE
        }
        result.add(length - TILE)
        return result.toIntArray()
    }

    private fun ensureRuntime(context: Context, quantized: Boolean): Runtime? {
        val cached = if (quantized) quantizedRuntime else floatRuntime
        if (cached != null) return cached
        synchronized(this) {
            val current = if (quantized) quantizedRuntime else floatRuntime
            if (current != null) return current
            val modelName = if (quantized) QUANTIZED_MODEL else FLOAT_MODEL
            val dataName = if (quantized) QUANTIZED_DATA else FLOAT_DATA
            val directory = File(context.filesDir, MODEL_DIRECTORY)
            if (!directory.exists()) directory.mkdirs()
            val modelFile = File(directory, modelName)
            val dataFile = File(directory, dataName)
            copyAssetIfNeeded(context, modelName, modelFile)
            copyAssetIfNeeded(context, dataName, dataFile)

            val environment = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setInterOpNumThreads(1)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setCPUArenaAllocator(false)
            }
            val session = environment.createSession(modelFile.absolutePath, options)
            val created = Runtime(environment, session, options, session.inputNames.first())
            if (quantized) quantizedRuntime = created else floatRuntime = created
            return created
        }
    }

    private fun copyAssetIfNeeded(context: Context, assetName: String, destination: File) {
        val marker = File(destination.parentFile, "$assetName.ok")
        if (destination.isFile && marker.isFile && destination.length() > 0L) return
        context.assets.open("$MODEL_DIRECTORY/$assetName").use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        }
        marker.writeText(destination.length().toString())
    }

    private data class Runtime(
        val environment: OrtEnvironment,
        val session: OrtSession,
        @Suppress("unused") val options: OrtSession.SessionOptions,
        val inputName: String,
    )
}
