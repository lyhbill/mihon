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
import mihon.app.di.appGraph
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.nio.ByteBuffer

/**
 * Real-ESRGAN x4plus inference for the chapter build pipeline.
 *
 * Only the w8a8 quantised export is shipped: it takes uint8 tiles directly, needs no float
 * conversion and is noticeably faster on ARM CPUs than the float export was.
 *
 * The model has a fixed 128x128 input and a 512x512 (4x) output, so a page is processed as
 * overlapping tiles and the result is scaled down to the requested factor. Inference is CPU
 * bound, which is why it only runs inside the background build queue.
 */
object OnnxSuperResolution {

    private const val TILE = 128
    private const val MODEL_SCALE = 4
    private const val OVERLAP = 16
    private const val STRIDE = TILE - OVERLAP
    private const val MAX_SOURCE_SIDE = 1600

    private const val MODEL_DIRECTORY = "super_resolution"
    private const val MODEL_FILE = "real_esrgan_x4plus.onnx"
    private const val MODEL_DATA_FILE = "real_esrgan_x4plus.data"

    @Volatile
    private var runtime: Runtime? = null

    fun upscale(context: Context, source: Bitmap, factor: Int): Bitmap? {
        val active = runCatching { ensureRuntime(context) }.getOrNull() ?: return null
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
            runTiles(active, input, factor)
        } catch (error: Throwable) {
            logcat(LogPriority.ERROR, error) { "Super resolution inference failed" }
            null
        } finally {
            prepared?.recycle()
        }
    }

    private fun runTiles(active: Runtime, input: Bitmap, factor: Int): Bitmap {
        val width = input.width
        val height = input.height
        val targetFactor = factor.coerceIn(2, 4)
        val output = Bitmap.createBitmap(width * targetFactor, height * targetFactor, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        val sourcePixels = IntArray(width * height)
        input.getPixels(sourcePixels, 0, width, 0, 0, width, height)

        val outSide = TILE * MODEL_SCALE
        val tileBitmap = Bitmap.createBitmap(outSide, outSide, Bitmap.Config.ARGB_8888)
        val tilePixels = IntArray(outSide * outSide)
        val buffer = ByteBuffer.allocateDirect(3 * TILE * TILE)

        for (y0 in positions(height)) {
            for (x0 in positions(width)) {
                infer(active, sourcePixels, width, x0, y0, outSide, buffer, tilePixels)
                tileBitmap.setPixels(tilePixels, 0, outSide, 0, 0, outSide, outSide)
                canvas.drawBitmap(
                    tileBitmap,
                    Rect(0, 0, outSide, outSide),
                    RectF(
                        x0.toFloat() * targetFactor,
                        y0.toFloat() * targetFactor,
                        (x0 + TILE).toFloat() * targetFactor,
                        (y0 + TILE).toFloat() * targetFactor,
                    ),
                    paint,
                )
            }
        }
        tileBitmap.recycle()
        return output
    }

    private fun infer(
        active: Runtime,
        sourcePixels: IntArray,
        width: Int,
        x0: Int,
        y0: Int,
        outSide: Int,
        buffer: ByteBuffer,
        pixels: IntArray,
    ) {
        buffer.clear()
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

        OnnxTensor.createTensor(
            active.environment,
            buffer,
            longArrayOf(1, 3, TILE.toLong(), TILE.toLong()),
            OnnxJavaType.UINT8,
        ).use { tensor ->
            active.session.run(mapOf(active.inputName to tensor)).use { result ->
                val bytes = (result[0] as OnnxTensor).byteBuffer
                val plane = outSide * outSide
                for (index in 0 until plane) {
                    val r = bytes.get(index).toInt() and 0xFF
                    val g = bytes.get(plane + index).toInt() and 0xFF
                    val b = bytes.get(2 * plane + index).toInt() and 0xFF
                    pixels[index] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
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

    private fun ensureRuntime(context: Context): Runtime? {
        runtime?.let { return it }
        synchronized(this) {
            runtime?.let { return it }
            val directory = File(context.filesDir, MODEL_DIRECTORY)
            if (!directory.exists()) directory.mkdirs()
            val modelFile = File(directory, MODEL_FILE)
            val dataFile = File(directory, MODEL_DATA_FILE)
            copyAssetIfNeeded(context, MODEL_FILE, modelFile)
            copyAssetIfNeeded(context, MODEL_DATA_FILE, dataFile)

            val environment = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(
                    context.appGraph.readerPreferences.inferenceThreads.get().coerceIn(1, 4),
                )
                setInterOpNumThreads(1)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setCPUArenaAllocator(false)
                setMemoryPatternOptimization(false)
            }
            val session = environment.createSession(modelFile.absolutePath, options)
            return Runtime(environment, session, options, session.inputNames.first()).also {
                runtime = it
            }
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
