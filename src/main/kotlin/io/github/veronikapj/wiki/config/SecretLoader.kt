package io.github.veronikapj.wiki.config

import java.io.File
import org.slf4j.LoggerFactory

object SecretLoader {
    private val log = LoggerFactory.getLogger(SecretLoader::class.java)
    private val dotEnvCache: Map<String, String> by lazy { loadDotEnv() }

    fun resolve(envKey: String, configValue: String): String =
        System.getenv(envKey)
            ?: dotEnvCache[envKey]
            ?: configValue

    fun resolveNullable(envKey: String, configValue: String?): String? =
        System.getenv(envKey)
            ?: dotEnvCache[envKey]
            ?: configValue

    internal fun parseDotEnv(content: String): Map<String, String> =
        content.lines()
            .filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx < 1) return@mapNotNull null
                val key = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                if (key.isBlank() || value.isBlank()) null else key to value
            }
            .toMap()

    private fun loadDotEnv(): Map<String, String> {
        val file = File(".env")
        if (!file.exists()) return emptyMap()
        log.info("Loading secrets from .env")
        return parseDotEnv(file.readText())
    }
}
