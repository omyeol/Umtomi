package xyz.sabok.umtomi.ui

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/** Verified, catalog-order-preserving filename migration for an app-private shelf. */
internal object BookshelfImageNames {
    private val numbered = Regex("^image([1-9][0-9]*)\\.[A-Za-z0-9]{1,8}$", RegexOption.IGNORE_CASE)

    data class Plan(
        val items: List<BookshelfItem>,
        private val copies: List<Pair<File, File>>,
        private val created: List<File>,
    ) {
        fun rollback() = created.forEach { it.delete() }

        fun committed(root: File) {
            val rootPath = root.canonicalPath + File.separator
            val retained = items.map { File(it.filePath).canonicalPath }.toSet()
            copies.forEach { (source, target) ->
                if (source.path !in retained && source.canonicalPath.startsWith(rootPath) &&
                    source.isFile && target.isFile && sameBytes(source, target)) source.delete()
            }
        }
    }

    fun needsMigration(root: File, items: List<BookshelfItem>): Boolean =
        items.any { item ->
            val source = File(item.filePath).canonicalFile
            source.parentFile != folder(root, item.folder) || number(source) == null
        }

    fun prepare(root: File, items: List<BookshelfItem>): Plan {
        val created = mutableListOf<File>()
        val copies = mutableListOf<Pair<File, File>>()
        val updated = mutableListOf<BookshelfItem>()
        val nextNumbers = mutableMapOf<String, Int>()
        try {
            items.forEach { item ->
                val source = File(item.filePath).canonicalFile
                check(source.isFile) { "책장 원본 파일이 없습니다. 이름 변경을 보류했습니다." }
                val directory = folder(root, item.folder)
                check(directory.mkdirs() || directory.isDirectory)
                val destination = if (source.parentFile == directory && number(source) != null) source else {
                    val next = nextNumbers.getOrPut(item.folder) {
                        (directory.listFiles()?.mapNotNull(::number)?.maxOrNull() ?: 0) + 1
                    }
                    check(next < Int.MAX_VALUE)
                    nextNumbers[item.folder] = next + 1
                    File(directory, "image$next.${extension(source)}").canonicalFile
                }
                if (source != destination) {
                    val temporary = File(directory, ".${UUID.randomUUID()}.partial")
                    try {
                        source.inputStream().use { input ->
                            FileOutputStream(temporary).use { output -> input.copyTo(output); output.fd.sync() }
                        }
                        check(sameBytes(source, temporary)) { "책장 원본 복사 검증에 실패했습니다." }
                        check(!destination.exists() && temporary.renameTo(destination))
                        created += destination
                    } finally { temporary.delete() }
                    copies += source to destination
                }
                updated += item.copy(filePath = destination.path)
            }
            return Plan(updated, copies, created)
        } catch (error: Throwable) {
            created.forEach { it.delete() }
            throw error
        }
    }

    private fun folder(root: File, name: String): File {
        val segment = name.map { if (it.code < 32 || it in "\\/:*?\"<>|") '_' else it }
            .joinToString("").trim().ifEmpty { "shelf" }
        val directory = File(root, segment).canonicalFile
        require(directory.path.startsWith(root.canonicalPath + File.separator))
        return directory
    }

    private fun number(file: File): Int? =
        numbered.matchEntire(file.name)?.groupValues?.get(1)?.toIntOrNull()

    private fun extension(file: File): String {
        val header = ByteArray(12)
        file.inputStream().use { it.read(header) }
        return when {
            header[0] == 0x89.toByte() && header[1] == 0x50.toByte() -> "png"
            header[0] == 0xff.toByte() && header[1] == 0xd8.toByte() -> "jpg"
            String(header, 0, 3, Charsets.US_ASCII) == "GIF" -> "gif"
            String(header, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
            else -> "img"
        }
    }

    private fun sameBytes(first: File, second: File): Boolean =
        first.length() == second.length() && digest(first).contentEquals(digest(second))

    private fun digest(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }
}
