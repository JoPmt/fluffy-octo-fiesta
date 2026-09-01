package com.quantum.agent

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class LocalModelStoreTest {
    @Test
    fun `discoverAvailableModelsReturnsOnlyGgufFilesAndSortsByName`() {
        val root = createTempDir("gguf-models")
        val validGguf = File(root, "zeta.gguf")
        validGguf.writeBytes(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()) + "payload".toByteArray())
        val validUpperGguf = File(root, "alpha.GGUF")
        validUpperGguf.writeBytes(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()) + "payload".toByteArray())
        File(root, "notes.txt").writeText("c")
        File(root, "fake.gguf").writeText("not a real gguf")

        val models = LocalModelStore.discoverAvailableModels(root)

        assertEquals(listOf("alpha.GGUF", "zeta.gguf"), models.map { it.fileName })
    }

    @Test
    fun `isValidGgufFileRejectsNonGgufHeaders`() {
        val root = createTempDir("gguf-validation")
        val valid = File(root, "valid.gguf")
        valid.writeBytes(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()) + "abc".toByteArray())
        val invalid = File(root, "invalid.gguf")
        invalid.writeText("<html>not actually gguf</html>")

        assertEquals(true, LocalModelStore.isValidGgufFile(valid))
        assertEquals(false, LocalModelStore.isValidGgufFile(invalid))
    }

    @Test
    fun `modelFileNameFromUrlPreservesOriginalGgufName`() {
        val url = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q8_0.gguf?download=1"

        assertEquals("qwen2.5-0.5b-instruct-q8_0.gguf", LocalModelStore.modelFileNameFromUrl(url))
    }

    @Test
    fun `validGgufPathIsUsableForSessionStartup`() {
        val root = createTempDir("gguf-session")
        val valid = File(root, "session-model.gguf")
        valid.writeBytes(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()) + "payload".toByteArray())

        assertEquals(true, NativeEngine.isUsableSessionModel(valid.absolutePath))
    }
}
