package com.dreamhub.caster

import android.content.Context
import android.net.wifi.WifiManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

data class TvDevice(
    val id: String,
    val name: String,
    val manufacturer: String,
    val ipAddress: String,
    val locationUrl: String,
    val avTransportUrl: String,
    val renderingControlUrl: String? = null
)

object DlnaCaster {

    val devices = ConcurrentHashMap<String, TvDevice>()
    @Volatile
    var activeDevice: TvDevice? = null
    @Volatile
    var currentStreamTitle: String = ""
    @Volatile
    var currentStreamUrl: String = ""
    @Volatile
    var isPlaying: Boolean = false

    fun discoverDevices(context: Context, timeoutMs: Int = 3200): List<TvDevice> {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wm?.createMulticastLock("dreamhub_ssdp_lock")?.apply {
            setReferenceCounted(true)
            acquire()
        }

        val searchTargets = listOf(
            "urn:schemas-upnp-org:service:AVTransport:1",
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "ssdp:all"
        )

        val discoveredLocations = mutableSetOf<String>()

        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 750
                socket.broadcast = true
                val multicastAddr = InetAddress.getByName("239.255.255.250")

                for (st in searchTargets) {
                    val msg = buildString {
                        append("M-SEARCH * HTTP/1.1\r\n")
                        append("HOST: 239.255.255.250:1900\r\n")
                        append("MAN: \"ssdp:discover\"\r\n")
                        append("MX: 2\r\n")
                        append("ST: $st\r\n")
                        append("\r\n")
                    }
                    val data = msg.toByteArray()
                    val packet = DatagramPacket(data, data.size, multicastAddr, 1900)
                    socket.send(packet)
                }

                val start = System.currentTimeMillis()
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() - start < timeoutMs) {
                    try {
                        val recv = DatagramPacket(buf, buf.size)
                        socket.receive(recv)
                        val resp = String(recv.data, 0, recv.length)
                        val loc = extractHeader(resp, "LOCATION")
                        if (loc.isNotBlank() && (resp.contains("AVTransport", ignoreCase = true) ||
                                    resp.contains("MediaRenderer", ignoreCase = true) ||
                                    resp.contains("Samsung", ignoreCase = true) ||
                                    resp.contains("DLNA", ignoreCase = true) ||
                                    resp.contains("UPnP", ignoreCase = true))
                        ) {
                            discoveredLocations.add(loc)
                        }
                    } catch (e: Exception) {
                        // Socket timeout slice
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore network error
        } finally {
            try {
                if (lock?.isHeld == true) lock.release()
            } catch (e: Exception) {}
        }

        for (loc in discoveredLocations) {
            fetchDeviceDescription(loc)
        }

        return devices.values.toList()
    }

    fun fetchDeviceDescription(locationUrl: String): TvDevice? {
        return try {
            val req = Request.Builder()
                .url(locationUrl)
                .header("User-Agent", "DreamHubCaster/1.0 UPnP/1.1 DLNADOC/1.50")
                .build()

            DoHNetworkClient.client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return null
                val xml = res.body?.string() ?: return null

                if (!xml.contains("AVTransport", ignoreCase = true) && !xml.contains("MediaRenderer", ignoreCase = true)) {
                    return null
                }

                val friendlyName = extractXmlTag(xml, "friendlyName").ifBlank { "Smart TV" }
                val manufacturer = extractXmlTag(xml, "manufacturer").ifBlank { "DLNA TV" }
                val uri = URI(locationUrl)
                val baseUrl = "${uri.scheme}://${uri.host}:${uri.port}"

                // Extract AVTransport controlURL
                val avBlock = xml.substringAfter("AVTransport", "")
                val controlPath = extractXmlTag(avBlock, "controlURL")
                if (controlPath.isBlank()) return null

                val avUrl = resolveControlUrl(baseUrl, locationUrl, controlPath)

                // Extract RenderingControl controlURL (for Volume)
                val rcBlock = xml.substringAfter("RenderingControl", "")
                val rcPath = extractXmlTag(rcBlock, "controlURL")
                val rcUrl = if (rcPath.isNotBlank()) resolveControlUrl(baseUrl, locationUrl, rcPath) else null

                val dev = TvDevice(
                    id = "${uri.host}:${uri.port}",
                    name = friendlyName,
                    manufacturer = manufacturer,
                    ipAddress = uri.host ?: "",
                    locationUrl = locationUrl,
                    avTransportUrl = avUrl,
                    renderingControlUrl = rcUrl
                )
                devices[dev.id] = dev
                if (activeDevice == null) {
                    activeDevice = dev
                }
                dev
            }
        } catch (e: Exception) {
            null
        }
    }

    fun probeManualTvIp(ip: String): TvDevice? {
        val cleanIp = ip.trim()
        val commonLocations = listOf(
            "http://$cleanIp:9197/dmr",
            "http://$cleanIp:9197/upnp/dev/ag1/desc",
            "http://$cleanIp:7676/smp_14_",
            "http://$cleanIp:52235/dmr/SamsungMRDesc.xml",
            "http://$cleanIp:49152/description.xml",
            "http://$cleanIp:1443/description.xml"
        )
        for (loc in commonLocations) {
            val found = fetchDeviceDescription(loc)
            if (found != null) return found
        }
        return null
    }

    fun castToTv(device: TvDevice, streamUrl: String, title: String): Boolean {
        return try {
            activeDevice = device
            currentStreamUrl = streamUrl
            currentStreamTitle = title

            // 1. Stop current playback cleanly
            sendSoap(
                device.avTransportUrl,
                "urn:schemas-upnp-org:service:AVTransport:1",
                "Stop",
                "<InstanceID>0</InstanceID>"
            )

            val isHls = streamUrl.lowercase().contains(".m3u8")
            val mime = if (isHls) "application/x-mpegURL" else "video/mp4"
            val escapedUrl = escapeXml(streamUrl)
            val escapedTitle = escapeXml(title)

            val didl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" xmlns:dlna="urn:schemas-dlna-org:metadata-1-0/"><item id="0" parentID="-1" restricted="1"><dc:title>$escapedTitle</dc:title><upnp:class>object.item.videoItem.movie</upnp:class><res protocolInfo="http-get:*:$mime:DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000">$escapedUrl</res></item></DIDL-Lite>"""
            val escapedDidl = escapeXml(didl)

            // 2. SetAVTransportURI
            val setOk = sendSoap(
                device.avTransportUrl,
                "urn:schemas-upnp-org:service:AVTransport:1",
                "SetAVTransportURI",
                "<InstanceID>0</InstanceID><CurrentURI>$escapedUrl</CurrentURI><CurrentURIMetaData>$escapedDidl</CurrentURIMetaData>"
            )

            // 3. Play
            val playOk = sendSoap(
                device.avTransportUrl,
                "urn:schemas-upnp-org:service:AVTransport:1",
                "Play",
                "<InstanceID>0</InstanceID><Speed>1</Speed>"
            )

            isPlaying = setOk || playOk
            isPlaying
        } catch (e: Exception) {
            false
        }
    }

    fun playOrPause(play: Boolean): Boolean {
        val dev = activeDevice ?: return false
        return if (play) {
            isPlaying = true
            sendSoap(
                dev.avTransportUrl,
                "urn:schemas-upnp-org:service:AVTransport:1",
                "Play",
                "<InstanceID>0</InstanceID><Speed>1</Speed>"
            )
        } else {
            isPlaying = false
            sendSoap(
                dev.avTransportUrl,
                "urn:schemas-upnp-org:service:AVTransport:1",
                "Pause",
                "<InstanceID>0</InstanceID>"
            )
        }
    }

    fun stop(): Boolean {
        val dev = activeDevice ?: return false
        isPlaying = false
        return sendSoap(
            dev.avTransportUrl,
            "urn:schemas-upnp-org:service:AVTransport:1",
            "Stop",
            "<InstanceID>0</InstanceID>"
        )
    }

    fun seekToSeconds(seconds: Int): Boolean {
        val dev = activeDevice ?: return false
        val safeSec = seconds.coerceAtLeast(0)
        val hh = safeSec / 3600
        val mm = (safeSec % 3600) / 60
        val ss = safeSec % 60
        val target = String.format("%02d:%02d:%02d", hh, mm, ss)
        return sendSoap(
            dev.avTransportUrl,
            "urn:schemas-upnp-org:service:AVTransport:1",
            "Seek",
            "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>$target</Target>"
        )
    }

    fun setVolume(volumePercent: Int): Boolean {
        val dev = activeDevice ?: return false
        val rcUrl = dev.renderingControlUrl ?: return false
        val vol = volumePercent.coerceIn(0, 100)
        return sendSoap(
            rcUrl,
            "urn:schemas-upnp-org:service:RenderingControl:1",
            "SetVolume",
            "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>$vol</DesiredVolume>"
        )
    }

    private fun sendSoap(controlUrl: String, serviceType: String, action: String, bodyArgs: String): Boolean {
        return try {
            val envelope = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:$action xmlns:u="$serviceType">
      $bodyArgs
    </u:$action>
  </s:Body>
</s:Envelope>"""

            val req = Request.Builder()
                .url(controlUrl)
                .header("Content-Type", "text/xml; charset=\"utf-8\"")
                .header("SOAPAction", "\"$serviceType#$action\"")
                .header("User-Agent", "DreamHubCaster/1.0 DLNADOC/1.50")
                .post(envelope.toRequestBody("text/xml; charset=utf-8".toMediaType()))
                .build()

            DoHNetworkClient.client.newCall(req).execute().use { res ->
                res.isSuccessful
            }
        } catch (e: Exception) {
            false
        }
    }

    fun devicesToJson(): String {
        val arr = JSONArray()
        for (d in devices.values) {
            arr.put(JSONObject().apply {
                put("id", d.id)
                put("name", d.name)
                put("manufacturer", d.manufacturer)
                put("ipAddress", d.ipAddress)
                put("isActive", activeDevice?.id == d.id)
            })
        }
        return arr.toString()
    }

    private fun extractHeader(response: String, headerName: String): String {
        for (line in response.lines()) {
            if (line.startsWith("$headerName:", ignoreCase = true)) {
                return line.substringAfter(":").trim()
            }
        }
        return ""
    }

    private fun extractXmlTag(xml: String, tag: String): String {
        val regex = Regex("<$tag[^>]*>(.*?)</$tag>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        return regex.find(xml)?.groupValues?.getOrNull(1)?.trim() ?: ""
    }

    private fun resolveControlUrl(baseUrl: String, locationUrl: String, path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        if (path.startsWith("/")) return "$baseUrl$path"
        val folder = locationUrl.substringBeforeLast("/")
        return "$folder/$path"
    }

    private fun escapeXml(input: String): String {
        return input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
