package com.mercury.yktwx.data

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Only explicitly retired caches belong here; settings retire after durable DataStore migration. */
internal class RetiredStorageCleanup internal constructor(private val cacheDirectory: File, completionFile: File) {
    constructor(context: Context) : this(context.cacheDir, File(context.noBackupFilesDir, "retired-cache-cleanup.version"))
    private val completion = AtomicFile(completionFile)

    fun cleanUp() {
        if (runCatching { completion.openRead().use { it.readBytes().decodeToString() } }.getOrNull() == VERSION) return
        val cleaned = listOf("display-v1").all { name ->
            runCatching {
                val target = File(cacheDirectory, name).toPath()
                if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return@runCatching
                // walkFileTree does not follow symbolic links into other storage.
                Files.walkFileTree(target, object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attributes: BasicFileAttributes) =
                        super.visitFile(file, attributes).also { Files.deleteIfExists(file) }

                    override fun postVisitDirectory(directory: Path, error: IOException?) =
                        super.postVisitDirectory(directory, error).also { Files.deleteIfExists(directory) }
                })
            }.isSuccess
        }
        if (!cleaned) return
        // Commit only after every retired cache is gone. A failed write also retries at the next start.
        runCatching {
            val output = completion.startWrite()
            try {
                output.write(VERSION.toByteArray())
                completion.finishWrite(output)
            } catch (error: Exception) {
                completion.failWrite(output)
                throw error
            }
        }
    }

    private companion object {
        const val VERSION = "1"
    }
}
