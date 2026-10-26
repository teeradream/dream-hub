package com.dreamhub.caster

import android.content.Context
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

data class IptvChannel(
    val id: String,
    val name: String,
    val group: String,
    val logo: String,
    val url: String
)

object M3uParser {

    fun parseM3uContent(rawContent: String, defaultGroup: String = "ทั่วไป"): List<IptvChannel> {
        val channels = mutableListOf<IptvChannel>()
        val lines = rawContent.lines()

        var currentName = ""
        var currentGroup = defaultGroup
        var currentLogo = ""
        var index = 0

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                currentGroup = extractAttr(line, "group-title").ifBlank { defaultGroup }
                currentLogo = extractAttr(line, "tvg-logo")
                val tvgName = extractAttr(line, "tvg-name")
                val commaName = line.substringAfterLast(",", "").trim()
                currentName = commaName.ifBlank { tvgName }.ifBlank { "Channel #${index + 1}" }
            } else if (!line.startsWith("#") && (line.startsWith("http://") || line.startsWith("https://"))) {
                index++
                val name = currentName.ifBlank { "Channel #$index" }
                channels.add(
                    IptvChannel(
                        id = "iptv_$index",
                        name = name,
                        group = currentGroup,
                        logo = currentLogo,
                        url = line
                    )
                )
                currentName = ""
                currentLogo = ""
            }
        }
        return channels
    }

    private fun extractAttr(line: String, attr: String): String {
        val pattern = Regex("""$attr\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
        return pattern.find(line)?.groupValues?.getOrNull(1)?.trim() ?: ""
    }

    fun loadAssetPlaylist(context: Context, assetFileName: String, defaultGroup: String): List<IptvChannel> {
        return try {
            val content = context.assets.open(assetFileName).bufferedReader().use { it.readText() }
            parseM3uContent(content, defaultGroup)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun fetchRemotePlaylist(url: String, defaultGroup: String): List<IptvChannel> {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Android 14; Mobile) DreamHubCaster/1.0")
                .build()
            DoHNetworkClient.client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return emptyList()
                val body = res.body?.string() ?: return emptyList()
                parseM3uContent(body, defaultGroup)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun channelsToJson(channels: List<IptvChannel>): String {
        val arr = JSONArray()
        for (ch in channels) {
            arr.put(JSONObject().apply {
                put("id", ch.id)
                put("name", ch.name)
                put("group", ch.group)
                put("logo", ch.logo)
                put("url", ch.url)
            })
        }
        return arr.toString()
    }
}
