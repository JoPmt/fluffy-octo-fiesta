package com.quantum.agent

import org.junit.Assert.assertTrue
import org.junit.Test

class HuggingFaceRepositoryTest {
    @Test
    fun `buildModelsRequestUrlIncludesLibraryAndSearchQuery`() {
        val url = HuggingFaceRepository().buildModelsRequestUrl("lfm", 30)

        assertTrue(url.contains("library=gguf"))
        assertTrue(url.contains("search=lfm"))
        assertTrue(url.contains("limit=30"))
    }
}
