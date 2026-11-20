package xyz.sabok.umtomi.ui

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BookshelfImageNamesTest {
    @Test fun privateMigrationKeepsImageOrderAndBytes() {
        val root = Files.createTempDirectory("shelf-names-").toFile()
        try {
            val old = File(root, "images").apply { mkdirs() }
            val first = File(old, "old-a").apply { writeBytes(byteArrayOf(0x89.toByte(), 0x50, 1, 2)) }
            val second = File(old, "old-b").apply { writeBytes(byteArrayOf(0x89.toByte(), 0x50, 3, 4)) }
            val items = listOf(
                BookshelfItem("b".repeat(32), "Umtomi/paradise", first.path, "", 1, 1, 1),
                BookshelfItem("1".repeat(32), "Umtomi/paradise", second.path, "", 1, 1, 2),
            )
            val plan = BookshelfImageNames.prepare(root, items)
            assertEquals(items.map { it.id }, plan.items.map { it.id })
            assertEquals(listOf("image1.png", "image2.png"), plan.items.map { File(it.filePath).name })
            assertArrayEquals(first.readBytes(), File(plan.items[0].filePath).readBytes())
            assertArrayEquals(second.readBytes(), File(plan.items[1].filePath).readBytes())
            plan.committed(root)
            assertFalse(first.exists())
            assertFalse(second.exists())
            assertFalse(BookshelfImageNames.needsMigration(root, plan.items))
        } finally { root.deleteRecursively() }
    }
}
