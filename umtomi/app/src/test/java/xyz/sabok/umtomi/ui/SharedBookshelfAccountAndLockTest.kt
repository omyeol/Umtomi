package xyz.sabok.umtomi.ui

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedBookshelfAccountAndLockTest {
    @Test
    fun accountFolderNamesMatchOmyaruAndAreSafe() {
        assertEquals("alice.test@gmail.com", SharedBookshelfStorage.sanitizeAccountFolderName("Alice.Test@Gmail.com"))
        assertEquals("bob@gmail.com", SharedBookshelfStorage.sanitizeAccountFolderName("bob@gmail.com"))
        assertNotEquals(
            SharedBookshelfStorage.sanitizeAccountFolderName("alice@gmail.com"),
            SharedBookshelfStorage.sanitizeAccountFolderName("bob@gmail.com"),
        )
        assertEquals("default", SharedBookshelfStorage.sanitizeAccountFolderName(""))
        assertEquals("default", SharedBookshelfStorage.sanitizeAccountFolderName(".."))
        assertEquals("default", SharedBookshelfStorage.sanitizeAccountFolderName(".omyaru"))
        val sanitized = SharedBookshelfStorage.sanitizeAccountFolderName("../evil/path:name*?")
        assertFalse(sanitized.contains('/'))
        assertFalse(sanitized.contains('\\'))
    }

    @Test
    fun syncLockPreventsSimultaneousSyncCollision() {
        val root = java.nio.file.Files.createTempDirectory("umtomi-sync-lock-test").toFile()
        try {
            val meta = File(root, ".omyaru").apply { mkdirs() }
            val lockFile = File(meta, "sync.lock")
            RandomAccessFile(lockFile, "rw").use { holder ->
                val held = holder.channel.tryLock()
                assertTrue(held != null)
                try {
                    RandomAccessFile(lockFile, "rw").use { contender ->
                        val second = runCatching { contender.channel.tryLock() }.getOrNull()
                        assertTrue(second == null)
                    }
                } finally {
                    held?.release()
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
