package com.example.musicplugin

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * DLNA 投屏助手 v1.0.4
 *
 * 支持：
 * - SSDP M-SEARCH 设备发现（云视听小电视、小米电视、海信电视等 DLNA 设备）
 * - 解析设备描述 XML
 * - SetAVTransportURI + Play 控制
 *
 * 注意：仅支持在线歌曲投屏（本地文件需要 HTTP 服务器，暂不支持）
 */
object DlnaHelper {

    private const val TAG = "DlnaHelper"
    private const val SSDP_HOST = "239.255.255.250"
    private const val SSDP_PORT = 1900

    data class DlnaDevice(
        val name: String,
        val location: String,
        val controlUrl: String,    // AVTransport 控制地址（完整 URL）
        val friendlyName: String
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /**
     * 搜索 DLNA 设备（阻塞 4 秒）
     */
    fun discover(context: Context, callback: (List<DlnaDevice>) -> Unit) {
        Thread {
            val devices = mutableListOf<DlnaDevice>()
            val locations = mutableSetOf<String>()

            // 获取 WiFi 多播锁
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = wifi.createMulticastLock("musicplugin_dlna").apply {
                acquire()
            }

            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                socket.soTimeout = 4000
                socket.broadcast = true

                val msearch = (
                    "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: $SSDP_HOST:$SSDP_PORT\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 3\r\n" +
                    "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
                    "\r\n"
                ).toByteArray()

                val addr = InetAddress.getByName(SSDP_HOST)
                socket.send(DatagramPacket(msearch, msearch.size, addr, SSDP_PORT))

                // 第二个搜索（一些设备响应 ssdp:all）
                val msearch2 = (
                    "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: $SSDP_HOST:$SSDP_PORT\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 3\r\n" +
                    "ST: ssdp:all\r\n" +
                    "\r\n"
                ).toByteArray()
                socket.send(DatagramPacket(msearch2, msearch2.size, addr, SSDP_PORT))

                val buf = ByteArray(8192)
                val deadline = System.currentTimeMillis() + 4000
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        socket.receive(packet)
                        val resp = String(packet.data, 0, packet.length)
                        // 提取 LOCATION
                        val loc = Regex("(?i)^LOCATION:\\s*(.+)$", RegexOption.MULTILINE)
                            .find(resp)?.groupValues?.get(1)?.trim() ?: continue
                        if (locations.add(loc)) {
                            // 解析设备描述
                            val device = parseDevice(loc)
                            if (device != null) {
                                devices.add(device)
                                Log.d(TAG, "发现设备: ${device.name} @ ${device.controlUrl}")
                            }
                        }
                    } catch (e: SocketTimeoutException) {
                        break
                    } catch (e: Exception) {
                        Log.w(TAG, "接收失败: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "搜索失败: ${e.message}")
            } finally {
                socket?.close()
                if (lock.isHeld) lock.release()
            }
            callback(devices)
        }.start()
    }

    /**
     * 解析设备描述 XML，找 AVTransport 服务
     */
    private fun parseDevice(location: String): DlnaDevice? {
        return try {
            val req = Request.Builder().url(location).build()
            val resp = client.newCall(req).execute()
            val xml = resp.body?.string() ?: return null

            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var friendlyName = ""
            var currentServiceType = ""
            var avTransportControlUrl = ""

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "friendlyName" -> {
                            friendlyName = parser.nextText()
                        }
                        "serviceType" -> {
                            currentServiceType = parser.nextText()
                        }
                        "controlURL" -> {
                            // 仅记录 AVTransport 服务的 controlURL
                            if (currentServiceType.contains("AVTransport")) {
                                avTransportControlUrl = parser.nextText()
                            }
                        }
                    }
                }
                event = parser.next()
            }

            if (friendlyName.isEmpty() || avTransportControlUrl.isEmpty()) return null

            // 拼接完整控制 URL
            val fullControlUrl = if (avTransportControlUrl.startsWith("http")) {
                avTransportControlUrl
            } else {
                // 从 location 提取 origin
                val base = Regex("^(https?://[^/]+)").find(location)?.groupValues?.get(1) ?: return null
                base + (if (avTransportControlUrl.startsWith("/")) "" else "/") + avTransportControlUrl
            }

            DlnaDevice(
                name = friendlyName,
                location = location,
                controlUrl = fullControlUrl,
                friendlyName = friendlyName
            )
        } catch (e: Exception) {
            Log.w(TAG, "解析设备失败 $location: ${e.message}")
            null
        }
    }

    /**
     * XML 转义（用于 SOAP / DIDL-Lite 文本内容）
     */
    private fun xmlEscape(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    /**
     * 根据 URL 推断 MIME 类型
     */
    private fun mimeOf(url: String): String {
        val lower = url.substringBefore("?").lowercase()
        return when {
            lower.endsWith(".flac") -> "audio/flac"
            lower.endsWith(".wav") -> "audio/wav"
            lower.endsWith(".m4a") || lower.endsWith(".mp4") || lower.endsWith(".aac") -> "audio/mp4"
            lower.endsWith(".ogg") -> "audio/ogg"
            lower.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
            else -> "audio/mpeg" // mp3 / 默认
        }
    }

    /**
     * 投屏播放（SetAVTransportURI + Play）
     *
     * v1.0.6 修复 HTTP 500：
     * - DIDL-Lite 增加 <res protocolInfo="..."> 元素（设备需要据此识别媒体格式才能 Play）
     * - CurrentURI / title 用 XML 转义替代 CDATA（部分设备对 CDATA 解析有问题）
     * - SetAVTransportURI 失败时记录响应体，便于排查
     *
     * @param mediaUrl 媒体 URL（在线歌曲的播放链接）
     * @param title 标题（元数据）
     */
    fun cast(device: DlnaDevice, mediaUrl: String, title: String, callback: (Boolean, String) -> Unit) {
        Thread {
            try {
                val escapedUrl = xmlEscape(mediaUrl)
                val escapedTitle = xmlEscape(title)
                val mime = mimeOf(mediaUrl)
                val protocolInfo = "http-get:*:$mime:*"

                // 1. SetAVTransportURI
                val meta = (
                    "&lt;DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
                    "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
                    "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"&gt;" +
                    "&lt;item id=\"1\" parentID=\"0\" restricted=\"1\"&gt;" +
                    "&lt;dc:title&gt;$escapedTitle&lt;/dc:title&gt;" +
                    "&lt;upnp:class&gt;object.item.audioItem.musicTrack&lt;/upnp:class&gt;" +
                    "&lt;res protocolInfo=\"$protocolInfo\"&gt;$escapedUrl&lt;/res&gt;" +
                    "&lt;/item&gt;&lt;/DIDL-Lite&gt;"
                )

                val setUriSoapBody = (
                    "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                    "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                    "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                    "<s:Body>" +
                    "<u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
                    "<InstanceID>0</InstanceID>" +
                    "<CurrentURI>$escapedUrl</CurrentURI>" +
                    "<CurrentURIMetaData>$meta</CurrentURIMetaData>" +
                    "</u:SetAVTransportURI>" +
                    "</s:Body>" +
                    "</s:Envelope>"
                )

                val setUriReq = Request.Builder()
                    .url(device.controlUrl)
                    .post(setUriSoapBody.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
                    .addHeader("SOAPAction", "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"")
                    .build()
                val setUriResp = client.newCall(setUriReq).execute()
                val setUriCode = setUriResp.code
                val setUriRespBody = setUriResp.body?.string() ?: ""
                if (setUriCode !in 200..299) {
                    callback(false, "SetAVTransportURI 失败 HTTP $setUriCode: ${setUriRespBody.take(200)}")
                    return@Thread
                }
                Log.d(TAG, "SetAVTransportURI 成功，准备 Play")

                // 2. Play
                Thread.sleep(500)
                val playSoapBody = (
                    "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                    "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                    "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                    "<s:Body>" +
                    "<u:Play xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
                    "<InstanceID>0</InstanceID>" +
                    "<Speed>1</Speed>" +
                    "</u:Play>" +
                    "</s:Body>" +
                    "</s:Envelope>"
                )
                val playReq = Request.Builder()
                    .url(device.controlUrl)
                    .post(playSoapBody.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
                    .addHeader("SOAPAction", "\"urn:schemas-upnp-org:service:AVTransport:1#Play\"")
                    .build()
                val playResp = client.newCall(playReq).execute()
                val playCode = playResp.code
                if (playCode in 200..299) {
                    callback(true, "已投屏到 ${device.name}")
                } else {
                    val playRespBodyStr = playResp.body?.string() ?: ""
                    Log.w(TAG, "Play 失败 HTTP $playCode: $playRespBodyStr")
                    callback(false, "Play 失败 HTTP $playCode: ${playRespBodyStr.take(200)}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "投屏失败: ${e.message}")
                callback(false, "投屏失败: ${e.message}")
            }
        }.start()
    }

    /**
     * 停止投屏
     */
    fun stop(device: DlnaDevice, callback: (Boolean) -> Unit) {
        Thread {
            try {
                val stopBody = """
                    <?xml version="1.0" encoding="utf-8"?>
                    <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                      <s:Body>
                        <u:Stop xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                          <InstanceID>0</InstanceID>
                        </u:Stop>
                      </s:Body>
                    </s:Envelope>
                """.trimIndent()
                val req = Request.Builder()
                    .url(device.controlUrl)
                    .post(stopBody.toRequestBody("text/xml; charset=utf-8".toMediaType()))
                    .addHeader("SOAPAction", "\"urn:schemas-upnp-org:service:AVTransport:1#Stop\"")
                    .build()
                val resp = client.newCall(req).execute()
                callback(resp.code in 200..299)
            } catch (e: Exception) {
                callback(false)
            }
        }.start()
    }
}
