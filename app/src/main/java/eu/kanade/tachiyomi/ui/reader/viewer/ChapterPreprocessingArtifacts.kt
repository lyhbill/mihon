package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import logcat.LogPriority
import mihon.core.archive.archiveReader
import okio.Buffer
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * One extensible SQLite build artifact per downloaded chapter.
 *
 * Every page is committed independently, so readers can use completed pages while the rest of the
 * chapter is still being built. The same file also acts as the resume point after cancellation or
 * process death. Future output types can be added as new [OUTPUT_KIND] values.
 */
object ChapterPreprocessingArtifacts {

    private val artifactIndex = ConcurrentHashMap<Long, ArtifactStamp>()
    private val _pageChanges = MutableSharedFlow<PageChange>(extraBufferCapacity = 128)
    val pageChanges = _pageChanges.asSharedFlow()

    fun isGenerated(context: Context, chapterId: Long): Boolean {
        val file = artifactFile(context, chapterId)
        if (!isSQLiteDatabase(file)) {
            artifactIndex.remove(chapterId)
            return false
        }
        artifactIndex[chapterId]?.takeIf { stamp ->
            stamp.length == file.length() && stamp.lastModified == file.lastModified()
        }?.let { return it.complete }
        val complete = runCatching {
            openReadOnly(file).use { database ->
                val metadata = readMetadata(database)
                val pageCount = metadata[KEY_PAGE_COUNT]?.toIntOrNull() ?: return@use false
                val upscaleFactor = metadata[KEY_UPSCALE_FACTOR]?.toIntOrNull() ?: 0
                metadata[KEY_SCHEMA]?.toIntOrNull() == DATABASE_SCHEMA &&
                    metadata[KEY_CHAPTER_ID]?.toLongOrNull() == chapterId &&
                    metadata[KEY_ALGORITHM] == TextEnhancementMaskProcessor.CACHE_ALGORITHM_VERSION &&
                    metadata[KEY_COMPLETE] == VALUE_TRUE &&
                    outputCount(database, OUTPUT_KIND_MASK) == pageCount &&
                    (upscaleFactor == 0 || outputCount(database, OUTPUT_KIND_UPSCALED) == pageCount)
            }
        }.getOrDefault(false)
        artifactIndex[chapterId] = ArtifactStamp(file.length(), file.lastModified(), complete)
        return complete
    }

    fun loadPage(context: Context, chapterId: Long, pageIndex: Int): Bitmap? {
        val file = artifactFile(context, chapterId)
        if (!isSQLiteDatabase(file)) return null
        return try {
            val payload = openReadOnly(file).use { database ->
                val metadata = readMetadata(database)
                val pageCount = metadata[KEY_PAGE_COUNT]?.toIntOrNull() ?: return null
                if (
                    metadata[KEY_SCHEMA]?.toIntOrNull() != DATABASE_SCHEMA ||
                    metadata[KEY_CHAPTER_ID]?.toLongOrNull() != chapterId ||
                    metadata[KEY_ALGORITHM] != TextEnhancementMaskProcessor.CACHE_ALGORITHM_VERSION ||
                    pageIndex !in 0 until pageCount
                ) {
                    return null
                }
                database.rawQuery(
                    "SELECT payload FROM outputs WHERE kind = ? AND page_index = ?",
                    arrayOf(OUTPUT_KIND_MASK, pageIndex.toString()),
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getBlob(0) else null
                }
            } ?: return null
            openPayload(payload).use(TextEnhancementMaskProcessor::readArtifactMask)
        } catch (error: Throwable) {
            logcat(LogPriority.WARN, error) { "Unable to read chapter build artifact" }
            null
        }
    }

    suspend fun build(
        context: Context,
        chapterId: Long,
        chapterFile: UniFile,
        parallelism: Int,
        onPrepared: (pageCount: Int) -> Unit,
        onPageBuilt: (pageIndex: Int, pageCount: Int) -> Unit,
    ): Boolean {
        return try {
            if (chapterFile.isFile) {
                buildArchive(context, chapterId, chapterFile, parallelism, onPrepared, onPageBuilt)
            } else {
                buildDirectory(context, chapterId, chapterFile, parallelism, onPrepared, onPageBuilt)
            }.also { complete ->
                if (complete) deleteLegacyPageStorage(context, chapterId)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logcat(LogPriority.ERROR, error) { "Unable to build chapter artifact" }
            false
        }
    }

    fun delete(context: Context, chapterIds: Collection<Long>) {
        chapterIds.forEach { chapterId ->
            artifactIndex.remove(chapterId)
            val file = artifactFile(context, chapterId)
            file.delete()
            sidecarFiles(file).forEach(File::delete)
            deleteLegacyPageStorage(context, chapterId)
        }
    }

    fun recoverInterruptedBuilds(context: Context) {
        val directory = artifactDirectory(context)
        directory.listFiles { file -> file.extension == "tmp" || file.extension == "bak" }
            .orEmpty()
            .forEach(File::delete)
        directory.listFiles { file ->
            file.name.endsWith("-journal") || file.name.endsWith("-wal") || file.name.endsWith("-shm")
        }.orEmpty().forEach { sidecar ->
            val databaseName = sidecar.name
                .removeSuffix("-journal")
                .removeSuffix("-wal")
                .removeSuffix("-shm")
            if (!directory.resolve(databaseName).exists()) sidecar.delete()
        }
    }

    /** Removes manifests and masks written by the original hash-addressed implementation. */
    fun deleteLegacy(context: Context, manga: Manga, chapters: Collection<Chapter>) {
        val directory = context.noBackupFilesDir.resolve(LEGACY_MANIFEST_DIRECTORY)
        chapters.forEach { chapter ->
            val prefix = chapterIdentity(manga, chapter)
            directory.listFiles { file ->
                file.name.startsWith("$prefix-") && file.extension == LEGACY_MANIFEST_EXTENSION
            }.orEmpty().forEach { manifest ->
                runCatching {
                    manifest.readLines().filter(String::isNotBlank).forEach { key ->
                        TextEnhancementMaskProcessor.deleteCachedMask(context, key)
                    }
                }
                manifest.delete()
            }
        }
    }

    /** Reads the upscaled page produced by the build pipeline, if any. */
    fun loadUpscaledPage(context: Context, chapterId: Long, pageIndex: Int): Bitmap? {
        val file = artifactFile(context, chapterId)
        if (!isSQLiteDatabase(file)) return null
        return try {
            val payload = openReadOnly(file).use { database ->
                database.rawQuery(
                    "SELECT payload FROM outputs WHERE kind = ? AND page_index = ?",
                    arrayOf(OUTPUT_KIND_UPSCALED, pageIndex.toString()),
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getBlob(0) else null
                }
            } ?: return null
            BitmapFactory.decodeByteArray(payload, 0, payload.size)
        } catch (error: Throwable) {
            logcat(LogPriority.WARN, error) { "Unable to read upscaled chapter page" }
            null
        }
    }

    /** Deletes every chapter artifact and returns how many bytes were freed. */
    fun deleteAll(context: Context): Long {
        val directory = artifactDirectory(context)
        val files = directory.listFiles().orEmpty()
        var freed = 0L
        files.forEach { file ->
            val length = if (file.isFile) file.length() else 0L
            if (file.delete()) freed += length
        }
        artifactIndex.clear()
        return freed
    }

    /** Total size of every chapter artifact on disk, in bytes. */
    fun totalSize(context: Context): Long {
        return artifactDirectory(context)
            .listFiles { file -> file.isFile && file.extension == ARTIFACT_EXTENSION }
            ?.sumOf(File::length)
            ?: 0L
    }

    /**
     * Deletes whole chapters, oldest first, until the artifact directory fits in [maxBytes].
     * Chapters listed in [keep] (currently reading / being built) are never touched.
     */
    fun enforceBudget(context: Context, maxBytes: Long, keep: Set<Long> = emptySet()): Long {
        if (maxBytes <= 0L) return 0L
        val files = artifactDirectory(context)
            .listFiles { file -> file.isFile && file.extension == ARTIFACT_EXTENSION }
            ?.sortedBy(File::lastModified)
            ?: return 0L
        var total = files.sumOf(File::length)
        var removed = 0L
        for (file in files) {
            if (total <= maxBytes) break
            val chapterId = file.name
                .removePrefix("chapter-")
                .removeSuffix(".$ARTIFACT_EXTENSION")
                .toLongOrNull()
                ?: continue
            if (chapterId in keep) continue
            val length = file.length()
            if (file.delete()) {
                sidecarFiles(file).forEach(File::delete)
                artifactIndex.remove(chapterId)
                total -= length
                removed += length
            }
        }
        return removed
    }

    private fun encodeJpeg(bitmap: Bitmap): ByteArray {
        return ByteArrayOutputStream(bitmap.byteCount / 4).use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, UPSCALED_JPEG_QUALITY, output)
            output.toByteArray()
        }
    }

    internal fun artifactFile(context: Context, chapterId: Long): File {
        return artifactDirectory(context).resolve("chapter-$chapterId.$ARTIFACT_EXTENSION")
    }

    private suspend fun buildArchive(
        context: Context,
        chapterId: Long,
        chapterFile: UniFile,
        parallelism: Int,
        onPrepared: (Int) -> Unit,
        onPageBuilt: (Int, Int) -> Unit,
    ): Boolean {
        return chapterFile.archiveReader(context).use { reader ->
            val names = reader.useEntries { entries ->
                entries
                    .filter { entry ->
                        entry.isFile && ImageUtil.isImage(entry.name) { reader.getInputStream(entry.name)!! }
                    }
                    .map { it.name }
                    .sortedWith { first, second -> first.compareToCaseInsensitiveNaturalOrder(second) }
                    .toList()
            }
            buildDatabase(
                context = context,
                chapterId = chapterId,
                pageCount = names.size,
                sourceSignature = sourceSignature(chapterFile.length(), names.map { it to -1L }),
                parallelism = parallelism,
                onPrepared = onPrepared,
                onPageBuilt = onPageBuilt,
            ) { index ->
                reader.getInputStream(names[index])?.use { input ->
                    Buffer().readFrom(input)
                }
            }
        }
    }

    private suspend fun buildDirectory(
        context: Context,
        chapterId: Long,
        directory: UniFile,
        parallelism: Int,
        onPrepared: (Int) -> Unit,
        onPageBuilt: (Int, Int) -> Unit,
    ): Boolean {
        val files = directory.listFiles().orEmpty()
            .filter { file -> !file.isDirectory && ImageUtil.isImage(file.name) { file.openInputStream() } }
            .sortedWith { first, second ->
                first.name.orEmpty().compareToCaseInsensitiveNaturalOrder(second.name.orEmpty())
            }
        return buildDatabase(
            context = context,
            chapterId = chapterId,
            pageCount = files.size,
            sourceSignature = sourceSignature(directory.length(), files.map { it.name.orEmpty() to it.length() }),
            parallelism = parallelism,
            onPrepared = onPrepared,
            onPageBuilt = onPageBuilt,
        ) { index ->
            files[index].openInputStream().use { input ->
                Buffer().readFrom(input)
            }
        }
    }

    private suspend fun buildDatabase(
        context: Context,
        chapterId: Long,
        pageCount: Int,
        sourceSignature: String,
        parallelism: Int,
        onPrepared: (Int) -> Unit,
        onPageBuilt: (Int, Int) -> Unit,
        readPage: (pageIndex: Int) -> Buffer?,
    ): Boolean {
        if (pageCount <= 0) return false
        val file = artifactFile(context, chapterId)
        if (file.exists() && !isSQLiteDatabase(file)) file.delete()
        artifactIndex.remove(chapterId)

        val upscaleFactor = if (context.appGraph.readerPreferences.superResolutionEnabled.get()) {
            context.appGraph.readerPreferences.superResolutionFactor.get()
                .coerceIn(SuperResolution.MIN_FACTOR, SuperResolution.MAX_FACTOR)
        } else {
            0
        }
        val database = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            prepareDatabase(database, chapterId, pageCount, sourceSignature, upscaleFactor)
            val completedPages = builtPages(database, pageCount)
            onPrepared(pageCount)
            if (completedPages.isNotEmpty()) {
                onPageBuilt(completedPages.size - 1, pageCount)
            }

            val pendingPages = (0 until pageCount).filterNot(completedPages::contains)
            buildPendingPages(
                context = context,
                chapterId = chapterId,
                database = database,
                pendingPages = pendingPages,
                completedPageCount = completedPages.size,
                pageCount = pageCount,
                parallelism = parallelism,
                readPage = readPage,
                onPageBuilt = onPageBuilt,
            )

            val masksComplete = outputCount(database, OUTPUT_KIND_MASK) == pageCount
            val upscaledComplete = upscaleFactor == 0 ||
                outputCount(database, OUTPUT_KIND_UPSCALED) == pageCount
            if (!masksComplete || !upscaledComplete) return false
            putMetadata(database, KEY_COMPLETE, VALUE_TRUE)
            artifactIndex.remove(chapterId)
            return true
        } finally {
            database.close()
        }
    }

    private suspend fun buildPendingPages(
        context: Context,
        chapterId: Long,
        database: SQLiteDatabase,
        pendingPages: List<Int>,
        completedPageCount: Int,
        pageCount: Int,
        parallelism: Int,
        readPage: (pageIndex: Int) -> Buffer?,
        onPageBuilt: (pageIndex: Int, pageCount: Int) -> Unit,
    ) = coroutineScope {
        if (pendingPages.isEmpty()) return@coroutineScope

        val upscaleFactor = if (context.appGraph.readerPreferences.superResolutionEnabled.get()) {
            context.appGraph.readerPreferences.superResolutionFactor.get()
                .coerceIn(SuperResolution.MIN_FACTOR, SuperResolution.MAX_FACTOR)
        } else {
            0
        }
        // Upscaling multiplies peak memory by four, so never run it in parallel.
        val effectiveParallelism = if (upscaleFactor > 0) 1 else parallelism
        val workerCount = effectiveParallelism.coerceIn(MIN_PARALLELISM, MAX_PARALLELISM)
            .coerceAtMost(pendingPages.size)
        val pageSources = Channel<PageSource>()
        val pageOutputs = Channel<PageOutput>(capacity = workerCount)

        val producer = launch(Dispatchers.IO) {
            try {
                pendingPages.forEach { pageIndex ->
                    val source = readPage(pageIndex)
                        ?: error("Unable to read chapter page $pageIndex")
                    pageSources.send(PageSource(pageIndex, source))
                }
            } finally {
                pageSources.close()
            }
        }
        val workers = List(workerCount) {
            launch(Dispatchers.Default) {
                for (page in pageSources) {
                    val decoded = TextEnhancementMaskProcessor.decodePage(page.source)
                        ?: error("Unable to decode chapter page ${page.index}")
                    val upscaled = if (upscaleFactor > 0) {
                        runCatching { SuperResolution.upscale(decoded, upscaleFactor) }.getOrNull()
                    } else {
                        null
                    }
                    val base = upscaled ?: decoded
                    try {
                        val mask = TextEnhancementMaskProcessor.createArtifactMaskFromBitmap(context, base)
                            ?: error("Unable to build chapter page ${page.index}")
                        val jpeg = upscaled?.let(::encodeJpeg)
                        pageOutputs.send(PageOutput(page.index, compressPayload(mask), jpeg))
                    } finally {
                        upscaled?.recycle()
                        decoded.recycle()
                    }
                }
            }
        }
        val outputCloser = launch {
            try {
                workers.joinAll()
            } finally {
                pageOutputs.close()
            }
        }

        var completed = completedPageCount
        for (page in pageOutputs) {
            writeOutput(database, OUTPUT_KIND_MASK, page.index, page.payload)
            page.upscaled?.let { writeOutput(database, OUTPUT_KIND_UPSCALED, page.index, it) }
            completed++
            onPageBuilt(completed - 1, pageCount)
            _pageChanges.tryEmit(PageChange(chapterId, page.index))
        }
        producer.join()
        outputCloser.join()
    }

    private fun prepareDatabase(
        database: SQLiteDatabase,
        chapterId: Long,
        pageCount: Int,
        sourceSignature: String,
        upscaleFactor: Int,
    ) {
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS metadata (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL) WITHOUT ROWID",
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS outputs (" +
                "kind TEXT NOT NULL, page_index INTEGER NOT NULL, payload BLOB NOT NULL, " +
                "PRIMARY KEY (kind, page_index)) WITHOUT ROWID",
        )
        val metadata = readMetadata(database)
        val compatible = metadata[KEY_SCHEMA]?.toIntOrNull() == DATABASE_SCHEMA &&
            metadata[KEY_CHAPTER_ID]?.toLongOrNull() == chapterId &&
            metadata[KEY_ALGORITHM] == TextEnhancementMaskProcessor.CACHE_ALGORITHM_VERSION &&
            metadata[KEY_PAGE_COUNT]?.toIntOrNull() == pageCount &&
            (metadata[KEY_UPSCALE_FACTOR]?.toIntOrNull() ?: 0) == upscaleFactor &&
            metadata[KEY_SOURCE] == sourceSignature

        database.beginTransaction()
        try {
            if (!compatible) {
                database.delete("outputs", null, null)
                database.delete("metadata", null, null)
                putMetadata(database, KEY_SCHEMA, DATABASE_SCHEMA.toString())
                putMetadata(database, KEY_CHAPTER_ID, chapterId.toString())
                putMetadata(database, KEY_ALGORITHM, TextEnhancementMaskProcessor.CACHE_ALGORITHM_VERSION)
                putMetadata(database, KEY_PAGE_COUNT, pageCount.toString())
                putMetadata(database, KEY_SOURCE, sourceSignature)
                putMetadata(database, KEY_UPSCALE_FACTOR, upscaleFactor.toString())
            }
            putMetadata(database, KEY_COMPLETE, VALUE_FALSE)
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    private fun writeOutput(database: SQLiteDatabase, kind: String, pageIndex: Int, payload: ByteArray) {
        database.compileStatement(
            "INSERT OR REPLACE INTO outputs (kind, page_index, payload) VALUES (?, ?, ?)",
        ).use { statement ->
            statement.bindString(1, kind)
            statement.bindLong(2, pageIndex.toLong())
            statement.bindBlob(3, payload)
            statement.executeInsert()
        }
    }

    private fun builtPages(database: SQLiteDatabase, pageCount: Int): Set<Int> {
        return database.rawQuery(
            "SELECT page_index FROM outputs WHERE kind = ? AND page_index >= 0 AND page_index < ?",
            arrayOf(OUTPUT_KIND_MASK, pageCount.toString()),
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getInt(0))
            }
        }
    }

    private fun outputCount(database: SQLiteDatabase, kind: String = OUTPUT_KIND_MASK): Int {
        return database.rawQuery(
            "SELECT COUNT(*) FROM outputs WHERE kind = ?",
            arrayOf(kind),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun readMetadata(database: SQLiteDatabase): Map<String, String> {
        return database.rawQuery("SELECT key, value FROM metadata", null).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1))
            }
        }
    }

    private fun putMetadata(database: SQLiteDatabase, key: String, value: String) {
        database.execSQL(
            "INSERT OR REPLACE INTO metadata (key, value) VALUES (?, ?)",
            arrayOf(key, value),
        )
    }

    private fun openReadOnly(file: File): SQLiteDatabase {
        return SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
    }

    internal fun compressPayload(bytes: ByteArray): ByteArray {
        return ByteArrayOutputStream(bytes.size / 2).use { output ->
            DeflaterOutputStream(output).use { compressed -> compressed.write(bytes) }
            output.toByteArray()
        }
    }

    internal fun openPayload(bytes: ByteArray): InputStream {
        return InflaterInputStream(ByteArrayInputStream(bytes))
    }

    private fun isSQLiteDatabase(file: File): Boolean {
        if (!file.isFile || file.length() < SQLITE_HEADER.size) return false
        return runCatching {
            val header = ByteArray(SQLITE_HEADER.size)
            file.inputStream().use { input -> input.read(header) == header.size } && header.contentEquals(SQLITE_HEADER)
        }.getOrDefault(false)
    }

    private fun sidecarFiles(file: File): List<File> {
        return listOf("-journal", "-wal", "-shm").map { suffix -> file.parentFile!!.resolve(file.name + suffix) }
    }

    private fun deleteLegacyPageStorage(context: Context, chapterId: Long) {
        listOf("v5", "v6", TextEnhancementMaskProcessor.CACHE_ALGORITHM_VERSION).forEach { version ->
            TextEnhancementMaskProcessor.deletePersistentMasks(context, "chapter-$chapterId-$version-")
            context.noBackupFilesDir.resolve(
                "text-enhancement-chapters-v3/chapter-$chapterId-$version.chapter",
            ).delete()
        }
    }

    private fun sourceSignature(containerLength: Long, pages: List<Pair<String, Long>>): String {
        val value = buildString {
            appendLine(containerLength)
            pages.forEach { (name, length) -> appendLine("$name\t$length") }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .take(12)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun chapterIdentity(manga: Manga, chapter: Chapter): String {
        val value = "${manga.source}\n${manga.id}\n${chapter.id}\n${chapter.url}"
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun artifactDirectory(context: Context): File {
        return context.noBackupFilesDir.resolve(ARTIFACT_DIRECTORY).apply { mkdirs() }
    }

    data class PageChange(val chapterId: Long, val pageIndex: Int)

    private data class PageSource(val index: Int, val source: Buffer)
    private data class PageOutput(val index: Int, val payload: ByteArray, val upscaled: ByteArray?)
    private data class ArtifactStamp(val length: Long, val lastModified: Long, val complete: Boolean)

    internal const val DATABASE_SCHEMA = 2
    internal const val ARTIFACT_EXTENSION = "ppc"
    internal const val MIN_PARALLELISM = 1
    internal const val MAX_PARALLELISM = 8
    private const val ARTIFACT_DIRECTORY = "chapter-preprocessing-v1"
    private const val UPSCALED_JPEG_QUALITY = 90
    private const val OUTPUT_KIND_MASK = "text-mask"
    private const val OUTPUT_KIND_UPSCALED = "upscaled-page"
    private const val KEY_SCHEMA = "schema"
    private const val KEY_UPSCALE_FACTOR = "upscale_factor"
    private const val KEY_CHAPTER_ID = "chapter"
    private const val KEY_ALGORITHM = "algorithm"
    private const val KEY_PAGE_COUNT = "pages"
    private const val KEY_SOURCE = "source"
    private const val KEY_COMPLETE = "complete"
    private const val VALUE_TRUE = "1"
    private const val VALUE_FALSE = "0"
    private const val LEGACY_MANIFEST_DIRECTORY = "text-enhancement-chapters-v2"
    private const val LEGACY_MANIFEST_EXTENSION = "chapter"
    private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
}
