package app.atall.core

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal val Json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

/** Temp file + fsync + rename: readers see the old or the new file, never half. */
internal object AtomicFile {
    fun write(file: File, text: String) {
        val dir = file.absoluteFile.parentFile
        dir.mkdirs()
        val tmp = File(dir, ".${file.name}.tmp")
        tmp.outputStream().use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
