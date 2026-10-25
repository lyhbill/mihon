package eu.kanade.tachiyomi.ui.reader.viewer

import ai.onnxruntime.NodeInfo
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
import mihon.app.di.appGraph
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.nio.ByteBuffer
import java.nio.FloatBuffer

/**
 * waifu2x (CUNet, 2x, no denoise) inference for the chapter build pipeline.
 *
 * waifu2x is roughly ten times smaller than Real-ESRGAN x4plus, runs its native 2x scale
 * (no "compute 4x then throw 3/4 away") and is trained on anime line art, which is what
 * manga pages are. The tile size and input element type are read from the model itself, so
 * swapping the .onnx file does not require code changes.
 *
 * Inference is CPU bound, so it only ever runs inside the background build queue.
 */
object OnnxSuperResolution {

    private const val OVERLAP = 8
    private const val DEFAULT_TILE = 128
    private const val MAX_SOURCE_SIDE = 1600
    private const val MODEL_SCALE = 2

    private const val MODEL_DIRECTORY = "super_resolution"
    private const val MODEL_FILE = "waifu2x_cunet_2x.onnx"

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
        val targetFactor = factor.coerceIn(2, MODEL_SCALE)
        val tile = active.tile
        val stride = (tile - OVERLAP).coerceAtLeast(1)
        val outSide = tile * MODEL_SCALE
        val output = Bitmap.createBitmap(width * targetFactor, height * targetFactor, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        val sourcePixels = IntArray(width * height)
        input.getPixels(sourcePixels, 0, width, 0, 0, width, height)

        val tileBitmap = Bitmap.createBitmap(outSide, outSide, Bitmap.Config.ARGB_8888)
        val tilePixels = IntArray(outSide * outSide)

        for (y0 in positions(height, tile, stride)) {
            for (x0 in positions(width, tile, stride)) {
                infer(active, sourcePixels, width, x0, y0, tile, outSide, tilePixels)
                tileBitmap.setPixels(tilePixels, 0, outSide, 0, 0, outSide, outSide)
                canvas.drawBitmap(
                    tileBitmap,
                    Rect(0, 0, outSide, outSide),
                    RectF(
                        x0.toFloat() * targetFactor,
                        y0.toFloat() * targetFactor,
                        (x0 + tile).toFloat() * targetFactor,
                        (y0 + tile).toFloat() * targetFactor,
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
        tile: Int,
        outSide: Int,
        pixels: IntArray,
    ) {
        val plane = tile * tile
        val buffer = if (active.inputType == OnnxJavaType.UINT8) {
            ByteBuffer.allocateDirect(3 * plane).also { out ->
                for (channel in 0 until 3) {
                    val shift = shiftFor(channel)
                    for (y in 0 until tile) {
                        val rowOffset = (y0 + y) * width + x0
                        for (x in 0 until tile) {
                            out.put(((sourcePixels[rowOffset + x] shr shift) and 0xFF).toByte())
                        }
                    }
                }
                out.rewind()
            }
        } else {
            FloatBuffer.allocate(3 * plane).also { out ->
                for (channel in 0 until 3) {
                    val shift = shiftFor(channel)
                    for (y in 0 until tile) {
                        val rowOffset = (y0 + y) * width + x0
                        for (x in 0 until tile) {
                            out.put(((sourcePixels[rowOffset + x] shr shift) and 0xFF) / 255f)
                        }
                    }
                }
                out.rewind()
            }
        }

        val shape = longArrayOf(1, 3, tile.toLong(), tile.toLong())
        val tensor = if (active.inputType == OnnxJavaType.UINT8) {
            OnnxTensor.createTensor(active.environment, buffer as ByteBuffer, shape, OnnxJavaType.UINT8)
        } else {
            OnnxTensor.createTensor(active.environment, buffer as FloatBuffer, shape)
        }
        tensor.use {
            active.session.run(mapOf(active.inputName to it)).use { result ->
                val out = result[0] as OnnxTensor
                val planeOut = outSide * outSide
                if (active.inputType == OnnxJavaType.UINT8) {
                    val bytes = out.byteBuffer
                    for (index in 0 until planeOut) {
                        val r = bytes.get(index).toInt() and 0xFF
                        val g = bytes.get(planeOut + index).toInt() and 0xFF
                        val b = bytes.get(2 * planeOut + index).toInt() and 0xFF
                        pixels[index] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                } else {
                    val floats = out.floatBuffer
                    for (index in 0 until planeOut) {
                        val r = (floats.get(index) * 255f).toInt().coerceIn(0, 255)
                        val g = (floats.get(planeOut + index) * 255f).toInt().coerceIn(0, 255)
                        val b = (floats.get(2 * planeOut + index) * 255f).toInt().coerceIn(0, 255)
                        pixels[index] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
            }
        }
    }

    private fun shiftFor(channel: Int) = when (channel) {
        0 -> 16
        1 -> 8
        else -> 0
    }

    /** Tile origins along one axis; the last tile is clamped so the edge is always covered. */
    private fun positions(length: Int, tile: Int, stride: Int): IntArray {
        if (length <= tile) return intArrayOf(0)
        val result = ArrayList<Int>()
        var position = 0
        while (position + tile < length) {
            result.add(position)
            position += stride
        }
        result.add(length - tile)
        return result.toIntArray()
    }

    private fun ensureRuntime(context: Context): Runtime? {
        runtime?.let { return it }
        synchronized(this) {
            runtime?.let { return it }
            val directory = File(context.filesDir, MODEL_DIRECTORY)
            if (!directory.exists()) directory.mkdirs()
            val modelFile = File(directory, MODEL_FILE)
            copyAssetIfNeeded(context, MODEL_FILE, modelFile)

            val threads = context.appGraph.readerPreferences.inferenceThreads.get().coerceIn(1, 4)
            val environment = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threads)
                setInterOpNumThreads(1)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setCPUArenaAllocator(false)
                setMemoryPatternOptimization(false)
            }
            val session = environment.createSession(modelFile.absolutePath, options)
            val inputName = session.inputNames.first()
            val info = session.inputInfo[inputName]?.info as? NodeInfo.TensorInfo
            val inputType = info?.type ?: OnnxJavaType.FLOAT
            val shape = info?.shape ?: longArrayOf(-1, 3, -1, -1)
            val tile = shape.getOrNull(2)?.takeIf { it > 0 }?.toInt() ?: DEFAULT_TILE
            return Runtime(environment, session, options, inputName, inputType, tile).also {
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
        val inputType: OnnxJavaType,
        val tile: Int,
    )
}
