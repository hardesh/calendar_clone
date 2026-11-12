package app.calendar.platform

import app.calendar.data.FileCache
import java.io.File

/** [FileCache] backed by a folder; writes go through a temp file so a crash never leaves half a file. */
class DirectoryCache(private val dir: File) : FileCache {
    init {
        dir.mkdirs()
    }

    private fun file(name: String) = File(dir, name.replace('/', '_'))

    override fun read(name: String): String? = file(name).takeIf { it.isFile }?.readText()

    @Synchronized
    override fun write(name: String, text: String) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file(name))) {
            file(name).delete()
            tmp.renameTo(file(name))
        }
    }

    override fun delete(name: String) {
        file(name).delete()
    }

    override fun list(): List<String> =
        dir.listFiles { f -> f.isFile && !f.name.endsWith(".tmp") }.orEmpty().sortedBy { it.lastModified() }.map { it.name }
}
