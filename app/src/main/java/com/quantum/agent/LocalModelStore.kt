package com.quantum.agent

import java.io.File
import java.io.RandomAccessFile

data class LocalModelEntry(
    val fileName: String,
    val absolutePath: String
)

object LocalModelStore {
    private const val GGUF_MAGIC = "GGUF"

    fun modelFileNameFromUrl(modelUrl: String): String {
        val candidate = modelUrl.substringAfterLast('/').substringBefore('?').trim()
        return if (candidate.isNotBlank() && candidate.lowercase().endsWith(".gguf")) candidate else "runtime_model.gguf"
    }

    fun fileForModel(baseDir: File?, modelUrl: String): File {
        val targetDir = baseDir ?: File(System.getProperty("java.io.tmpdir").orEmpty())
        targetDir.mkdirs()
        return File(targetDir, modelFileNameFromUrl(modelUrl))
    }

    fun isValidGgufFile(file: File): Boolean {
        if (file.isDirectory || !file.exists() || !file.isFile || !file.canRead()) return false
        if (!file.name.lowercase().endsWith(".gguf")) return false
        if (file.length() < GGUF_MAGIC.length) return false

        return try {
            // Only read the first 4 bytes instead of the entire file to avoid OOM on large GGUF models
            val header = ByteArray(GGUF_MAGIC.length)
            RandomAccessFile(file, "r").use { raf ->
                raf.readFully(header)
            }
            header.contentEquals(GGUF_MAGIC.toByteArray())
        } catch (_: Throwable) {
            false
        }
    }

    fun validateModelFileIntegrity(file: File): ValidationResult {
        if (!file.exists()) return ValidationResult(false, "Model file does not exist: ${file.absolutePath}")
        if (!file.isFile) return ValidationResult(false, "Path is not a file: ${file.absolutePath}")
        if (!file.canRead()) return ValidationResult(false, "Model file is not readable. Check file permissions.")
        
        val sizeBytes = file.length()
        if (sizeBytes == 0L) return ValidationResult(false, "Model file is empty (0 bytes)")
        
        // Minimum viable model size is ~10MB (roughly)
        val minSizeBytes = 10 * 1024 * 1024
        if (sizeBytes < minSizeBytes) {
            return ValidationResult(false, "Model file appears incomplete or corrupted. Size is ${sizeBytes / (1024*1024)}MB, expected at least ${minSizeBytes / (1024*1024)}MB")
        }
        
        if (!isValidGgufFile(file)) {
            return ValidationResult(false, "Model file is not a valid GGUF format. File may be corrupted or not a valid LLM model.")
        }
        
        return ValidationResult(true, "Model file validation passed")
    }

    data class ValidationResult(val isValid: Boolean, val message: String)

    fun deleteStoredModel(baseDir: File?, modelName: String): Boolean {
        if (modelName.isBlank()) return true
        val candidates = listOfNotNull(
            baseDir?.let { File(it, modelName) },
            File(modelName)
        )
        val file = candidates.firstOrNull { it.exists() && it.isFile }
        return file?.delete() ?: false
    }

    private fun candidateRoots(baseDir: File?): List<File> {
        val roots = linkedSetOf<File>()
        baseDir?.let(roots::add)
        roots.add(File("/data/local/tmp"))
        roots.add(File("/storage/emulated/0/Download"))
        roots.add(File("/storage/emulated/0/Android/data"))
        return roots.filter { it.exists() && it.isDirectory }
    }

    fun discoverAvailableModels(baseDir: File?): List<LocalModelEntry> {
        val seen = linkedSetOf<LocalModelEntry>()
        candidateRoots(baseDir).forEach { root ->
            root.walkTopDown().filter { it.isFile && isValidGgufFile(it) }.forEach { file ->
                seen.add(LocalModelEntry(file.name, file.absolutePath))
            }
        }
        return seen.toList().sortedBy { it.fileName.lowercase() }
    }

    fun resolveDefaultModelPath(baseDir: File?): String {
        return discoverAvailableModels(baseDir).firstOrNull()?.absolutePath.orEmpty()
    }

    fun unloadModel(modelPath: String): Boolean {
        if (modelPath.isBlank()) return true
        val file = File(modelPath)
        return if (file.exists()) file.delete() else true
    }
}
