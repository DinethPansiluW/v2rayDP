package com.example.core.block

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.V2RayApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class BlockListManager(private val context: Context) {

    private val blocklistDir: File
        get() {
            val dir = File(context.filesDir, "blocklists")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    fun loadSourcesFromAssets(): List<BlocklistSource> {
        return try {
            val jsonString = context.assets.open("blocklist_sources.json").bufferedReader().use { it.readText() }
            val array = JSONArray(jsonString)
            val list = mutableListOf<BlocklistSource>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    BlocklistSource(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        description = obj.optString("description", ""),
                        url = obj.getString("url"),
                        format = obj.optString("format", "hosts")
                    )
                )
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load blocklist_sources.json: ${e.message}")
            listOf(
                BlocklistSource("ads", "Ads", "Blocks ad networks and sponsored tracking", "https://adaway.org/hosts.txt", "hosts"),
                BlocklistSource("trackers", "Trackers", "Blocks behavioural analytics and telemetry", "https://v.firebog.net/hosts/Easyprivacy.txt", "domains"),
                BlocklistSource("malware", "Malware & Phishing", "Blocks phishing, fraud and spyware hosts", "https://malware-filter.gitlab.io/malware-filter/phishing-filter-hosts.txt", "hosts")
            )
        }
    }

    suspend fun downloadPreset(source: BlocklistSource): Result<Int> = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val url = URL(source.url)
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "v2rayDP-Android/1.0")
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                return@withContext Result.failure(Exception("HTTP $code: ${conn.responseMessage}"))
            }

            val targetFile = File(blocklistDir, "${source.id}.txt")
            val tempFile = File(blocklistDir, "${source.id}.tmp")

            var count = 0
            val maxBytes = 25L * 1024 * 1024 // 25 MB limit
            var totalBytesRead = 0L

            tempFile.bufferedWriter().use { writer ->
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                var line: String? = reader.readLine()
                while (line != null) {
                    totalBytesRead += line.length + 1
                    if (totalBytesRead > maxBytes) {
                        break
                    }
                    val domain = parseDomainFromLine(line)
                    if (domain != null) {
                        writer.write(domain)
                        writer.newLine()
                        count++
                    }
                    line = reader.readLine()
                }
            }

            // Atomic rename
            if (tempFile.exists()) {
                if (targetFile.exists()) targetFile.delete()
                val renamed = tempFile.renameTo(targetFile)
                if (!renamed) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }
            }

            val now = System.currentTimeMillis()
            val prefs = V2RayApplication.get().preferencesRepository
            prefs.setPresetLastUpdated(source.id, now)
            prefs.setPresetEntryCount(source.id, count)

            BlockEngine.getInstance(context).reload()
            Result.success(count)
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading blocklist ${source.id}: ${e.message}")
            Result.failure(e)
        } finally {
            conn?.disconnect()
        }
    }

    suspend fun downloadAll(): Map<String, Result<Int>> = withContext(Dispatchers.IO) {
        val sources = loadSourcesFromAssets()
        val results = mutableMapOf<String, Result<Int>>()
        for (source in sources) {
            results[source.id] = downloadPreset(source)
        }
        BlockEngine.getInstance(context).reload()
        results
    }

    fun getCustomRules(isBlock: Boolean): List<String> {
        val fileName = if (isBlock) "custom_block.txt" else "custom_allow.txt"
        val file = File(blocklistDir, fileName)
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { normalizeRule(it) }.distinct()
    }

    fun addCustomRule(rule: String, isBlock: Boolean): Boolean {
        val norm = normalizeRule(rule) ?: return false
        val fileName = if (isBlock) "custom_block.txt" else "custom_allow.txt"
        val file = File(blocklistDir, fileName)
        val existing = if (file.exists()) file.readLines().toMutableSet() else mutableSetOf()
        if (existing.add(norm)) {
            file.writeText(existing.joinToString("\n") + "\n")
            BlockEngine.getInstance(context).reload()
            return true
        }
        return false
    }

    fun removeCustomRule(rule: String, isBlock: Boolean): Boolean {
        val norm = normalizeRule(rule) ?: rule.trim().lowercase()
        val fileName = if (isBlock) "custom_block.txt" else "custom_allow.txt"
        val file = File(blocklistDir, fileName)
        if (!file.exists()) return false
        val existing = file.readLines().toMutableSet()
        val removed = existing.remove(norm) || existing.remove(rule.trim().lowercase())
        if (removed) {
            file.writeText(existing.joinToString("\n") + "\n")
            BlockEngine.getInstance(context).reload()
            return true
        }
        return false
    }

    suspend fun importRules(uri: Uri, isBlock: Boolean): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext Result.failure(Exception("Cannot open file stream"))

            val newRules = mutableSetOf<String>()
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    val norm = normalizeRule(line) ?: parseDomainFromLine(line)
                    if (norm != null) newRules.add(norm)
                    line = reader.readLine()
                }
            }

            if (newRules.isEmpty()) {
                return@withContext Result.success(0)
            }

            val fileName = if (isBlock) "custom_block.txt" else "custom_allow.txt"
            val file = File(blocklistDir, fileName)
            val existing = if (file.exists()) file.readLines().toMutableSet() else mutableSetOf()
            existing.addAll(newRules)
            file.writeText(existing.joinToString("\n") + "\n")

            BlockEngine.getInstance(context).reload()
            Result.success(newRules.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun exportRules(uri: Uri, isBlock: Boolean): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val rules = getCustomRules(isBlock)
            val outputStream = context.contentResolver.openOutputStream(uri)
                ?: return@withContext Result.failure(Exception("Cannot open destination stream"))

            outputStream.bufferedWriter().use { writer ->
                for (r in rules) {
                    writer.write(r)
                    writer.newLine()
                }
            }
            Result.success(rules.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun normalizeRule(raw: String): String? {
        val trimmed = raw.trim().lowercase()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
        var s = trimmed.removePrefix("*.")
        s = s.removeSuffix(".")
        return if (isValidDomainOrIp(s)) s else null
    }

    private fun parseDomainFromLine(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) return null

        val parts = trimmed.split(Regex("\\s+"))
        val candidate = when {
            parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1") -> parts[1]
            parts.isNotEmpty() -> parts[0]
            else -> return null
        }

        val domain = candidate.lowercase().trim().removeSuffix(".")
        if (domain == "localhost" || domain == "broadcasthost" || domain == "local") return null
        return if (isValidDomainOrIp(domain)) domain else null
    }

    private fun isValidDomainOrIp(s: String): Boolean {
        if (s.isEmpty() || s.length > 253) return false
        return !s.contains(" ") && !s.contains("/") && !s.contains("\\")
    }

    companion object {
        private const val TAG = "BlockListManager"
    }
}
