package io.legado.app.web.mcp

import fi.iki.elonen.NanoHTTPD
import io.legado.app.service.McpService
import java.net.InetAddress

class McpHttpServer(
    hostname: String?,
    port: Int
) : NanoHTTPD(hostname, port) {

    constructor(port: Int) : this(null, port)

    override fun serve(session: IHTTPSession): Response {
        val remoteIp = session.remoteIpAddress
        if (!isAllowedClientIp(remoteIp)) {
            return newFixedLengthResponse(
                Response.Status.FORBIDDEN,
                "application/json; charset=utf-8",
                """{"error":"MCP server only allows localhost or private LAN clients"}"""
            )
        }
        McpService.serve()
        val contentType = ContentType(session.headers["content-type"]).tryUTF8()
        session.headers["content-type"] = contentType.contentTypeHeader
        val files = HashMap<String, String>()
        val postData = if (session.method == Method.POST) {
            session.parseBody(files)
            files["postData"]
        } else {
            null
        }
        return McpServer.serve(session.method, session.uri, postData)
    }

    companion object {
        internal fun isAllowedClientIp(remoteIp: String?): Boolean {
            val cleaned = remoteIp?.trim()?.substringBefore('%').orEmpty()
            if (cleaned.isEmpty()) return false
            if (cleaned == "127.0.0.1" || cleaned == "::1" || cleaned == "0:0:0:0:0:0:0:1") {
                return true
            }
            return runCatching {
                val address = InetAddress.getByName(cleaned)
                address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress
            }.getOrDefault(false)
        }
    }
}
