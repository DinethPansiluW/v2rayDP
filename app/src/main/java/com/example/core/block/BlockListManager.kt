package com.example.core.block

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.BufferedReader
import java.io.File
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

    fun isCombinedListPresent(): Boolean {
        val file = File(blocklistDir, "combined.txt")
        return file.exists() && file.length() > 0
    }

    fun loadSources(): List<BlocklistSource> {
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
                        url = obj.getString("url")
                    )
                )
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load blocklist_sources.json: ${e.message}")
            listOf(
                BlocklistSource("ads", "Ads", "https://adaway.org/hosts.txt"),
                BlocklistSource("trackers", "Trackers", "https://v.firebog.net/hosts/Easyprivacy.txt"),
                BlocklistSource("malware", "Malware & Phishing", "https://malware-filter.gitlab.io/malware-filter/phishing-filter-hosts.txt")
            )
        }
    }

    suspend fun downloadAndMergeAll(): Result<Int> = withContext(Dispatchers.IO) {
        val sources = loadSources()
        val allDomains = LinkedHashSet<String>(128_000)

        for (source in sources) {
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
                if (code in 200..299) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    var line: String? = reader.readLine()
                    var bytesRead = 0L
                    while (line != null) {
                        bytesRead += line.length + 1
                        if (bytesRead > 30L * 1024 * 1024) break // Safety cap per list
                        val domain = parseDomainFromLine(line)
                        if (domain != null) {
                            allDomains.add(domain)
                        }
                        line = reader.readLine()
                    }
                } else {
                    Log.w(TAG, "Source ${source.name} returned HTTP $code")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading ${source.name}: ${e.message}")
            } finally {
                conn?.disconnect()
            }
        }

        if (allDomains.isEmpty()) {
            return@withContext Result.failure(Exception("Could not download blocklists. Check your internet and try again."))
        }

        try {
            val tempFile = File(blocklistDir, "combined.tmp")
            val targetFile = File(blocklistDir, "combined.txt")

            tempFile.bufferedWriter().use { writer ->
                for (domain in allDomains) {
                    writer.write(domain)
                    writer.newLine()
                }
            }

            if (tempFile.exists()) {
                if (targetFile.exists()) targetFile.delete()
                if (!tempFile.renameTo(targetFile)) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }
            }
            Result.success(allDomains.size)
        } catch (e: Exception) {
            Log.e(TAG, "Error writing combined blocklist: ${e.message}")
            Result.failure(e)
        }
    }

    fun getCustomRules(): List<String> {
        val file = File(blocklistDir, "custom_block.txt")
        if (!file.exists()) return emptyList()
        return try {
            file.readLines()
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addCustomRule(rawDomain: String): Boolean {
        val domain = normalizeCustomRule(rawDomain) ?: return false
        val current = getCustomRules().toMutableList()
        if (current.contains(domain)) return false

        current.add(0, domain)
        val file = File(blocklistDir, "custom_block.txt")
        return try {
            file.writeText(current.joinToString("\n"))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save custom rule: ${e.message}")
            false
        }
    }

    fun removeCustomRule(rawDomain: String): Boolean {
        val domain = normalizeCustomRule(rawDomain) ?: rawDomain.trim().lowercase()
        val current = getCustomRules().toMutableList()
        val removed = current.remove(domain)
        if (!removed) return false

        val file = File(blocklistDir, "custom_block.txt")
        return try {
            file.writeText(current.joinToString("\n"))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove custom rule: ${e.message}")
            false
        }
    }

    private fun normalizeCustomRule(raw: String): String? {
        var s = raw.trim().lowercase()
        if (s.startsWith("*.")) {
            s = s.substring(2)
        }
        s = s.removePrefix("http://").removePrefix("https://").substringBefore("/").substringBefore(":")
        s = s.removeSuffix(".")
        if (s.isEmpty() || s.contains(" ") || !s.contains(".")) {
            return null
        }
        return s
    }

    private fun parseDomainFromLine(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
            return null
        }

        val noComment = trimmed.substringBefore('#').substringBefore('!').trim()
        if (noComment.isEmpty()) return null

        val tokens = noComment.split(Regex("\\s+"))
        val candidate = if (tokens.size >= 2 && (tokens[0] == "0.0.0.0" || tokens[0] == "127.0.0.1" || tokens[0] == "::1")) {
            tokens[1]
        } else {
            tokens[0]
        }

        var domain = candidate.lowercase().removeSuffix(".")
        if (domain.startsWith("*.")) domain = domain.substring(2)

        if (domain.isEmpty() ||
            domain == "localhost" ||
            domain == "localhost.localdomain" ||
            domain == "local" ||
            domain == "broadcasthost" ||
            domain == "ip6-localhost" ||
            domain == "ip6-loopback" ||
            domain == "0.0.0.0"
        ) {
            return null
        }

        if (!domain.contains('.')) return null
        return domain
    }

    companion object {
        private const val TAG = "BlockListManager"
    }
}
