package io.github.frei0xff.readestwebview

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URLDecoder
import java.util.Locale

class LocalHttpServer(
    private val context: Context,
    port: Int
) : NanoHTTPD("127.0.0.1", port) {

    companion object {
        private const val API_HOST = "web.readest.com"
        private const val API_PREFIX = "/api/"
    }

    private val client = OkHttpClient()

    override fun serve(session: IHTTPSession): Response {

        val path = session.uri

        when {
            path.startsWith(API_PREFIX) -> {
                return proxyRequest(
                    session,
                    "https://$API_HOST${session.uri}${queryString(session)}",
                    API_HOST
                )
            }

            path.startsWith("/download/") -> {
                val encoded = path.removePrefix("/download/")
                val url = URLDecoder.decode(encoded, "UTF-8")
                return proxyRequest(session, url, null)
            }

            path.startsWith("/upload/") -> {
                val encoded = path.removePrefix("/upload/")
                val url = URLDecoder.decode(encoded, "UTF-8")
                return proxyRequest(session, url, null)
            }

            else -> {
                return serveAsset(path, session.method)
            }
        }
    }

    private fun queryString(session: IHTTPSession): String {
        val q = session.queryParameterString
        return if (q.isNullOrEmpty()) "" else "?$q"
    }

    private fun serveAsset(
        originalPath: String,
        method: Method
    ): Response {

        if (method != Method.GET && method != Method.HEAD) {
            return newFixedLengthResponse(
                Response.Status.METHOD_NOT_ALLOWED,
                MIME_PLAINTEXT,
                "Method Not Allowed"
            )
        }

        var path = originalPath

        if (path == "/")
            path = "/index.html"
        else {
            path = path.trimEnd('/')

            val candidate = path.removePrefix("/") + ".html"

            try {
                context.assets.open(candidate).close()
                path = "/$candidate"
            } catch (_: Exception) {
            }
        }

        val asset = path.removePrefix("/")

        return try {

            val input = context.assets.open(asset)
            val mime = getMimeType(asset)

            if (method == Method.HEAD) {
                input.close()

                return newFixedLengthResponse(
                    Response.Status.OK,
                    mime,
                    ""
                )
            }

            val response = newChunkedResponse(
                Response.Status.OK,
                mime,
                input
            )

            response.addHeader("Accept-Ranges", "bytes")

            response

        } catch (_: Exception) {

            newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                MIME_PLAINTEXT,
                "404 Not Found"
            )
        }
    }

    private fun proxyRequest(
        session: IHTTPSession,
        target: String,
        overrideHost: String?
    ): Response {

        return try {

            val bodyBytes = readBody(session)

            val headersBuilder = Headers.Builder()

            for ((k, v) in session.headers) {

                val lower = k.lowercase(Locale.US)

                if (
                    lower == "host" ||
                    lower == "connection" ||
                    lower == "content-length"
                ) {
                    continue
                }

                headersBuilder.add(k, v)
            }

            if (overrideHost != null)
                headersBuilder["Host"] = overrideHost

            val builder = Request.Builder()
                .url(target)
                .headers(headersBuilder.build())

            val body =
                if (bodyBytes != null)
                    bodyBytes.toRequestBody(
                        session.headers["content-type"]?.toMediaTypeOrNull()
                    )
                else
                    null

            when (session.method) {
                Method.GET -> builder.get()
                Method.HEAD -> builder.head()
                Method.POST -> builder.post(body ?: ByteArray(0).toRequestBody())
                Method.PUT -> builder.put(body ?: ByteArray(0).toRequestBody())
                Method.DELETE -> {
                    if (body == null)
                        builder.delete()
                    else
                        builder.delete(body)
                }
                Method.PATCH -> builder.patch(body ?: ByteArray(0).toRequestBody())
                else -> builder.method(session.method.name, body)
            }

            val upstream = client.newCall(builder.build()).execute()

            val responseBody = upstream.body

            val stream: InputStream =
                responseBody?.byteStream()
                    ?: ByteArrayInputStream(ByteArray(0))

            val response = newChunkedResponse(
                Response.Status.lookup(upstream.code)
                    ?: Response.Status.OK,
                responseBody?.contentType()?.toString(),
                stream
            )

            for ((name, value) in upstream.headers) {

                val lower = name.lowercase(Locale.US)

                if (
                    lower == "transfer-encoding" ||
                    lower == "connection"
                ) {
                    continue
                }

                response.addHeader(name, value)
            }

            response

        } catch (e: Exception) {

            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                "Proxy error: ${e.message}"
            )
        }
    }

    private fun readBody(session: IHTTPSession): ByteArray? {

        val len =
            session.headers["content-length"]?.toIntOrNull()
                ?: return null

        val buffer = ByteArray(len)

        var total = 0

        while (total < len) {

            val read = session.inputStream.read(
                buffer,
                total,
                len - total
            )

            if (read <= 0)
                break

            total += read
        }

        return buffer
    }

    private fun getMimeType(file: String): String {

        val ext = file.substringAfterLast('.', "")

        return when (ext.lowercase(Locale.US)) {

            "html", "htm" -> "text/html"

            "js" -> "application/javascript"

            "css" -> "text/css"

            "json" -> "application/json"

            "svg" -> "image/svg+xml"

            "png" -> "image/png"

            "jpg", "jpeg" -> "image/jpeg"

            "gif" -> "image/gif"

            "woff" -> "font/woff"

            "woff2" -> "font/woff2"

            "ttf" -> "font/ttf"

            "otf" -> "font/otf"

            "ico" -> "image/x-icon"

            "wasm" -> "application/wasm"

            "xml" -> "application/xml"

            "txt" -> "text/plain"

            else -> "application/octet-stream"
        }
    }
}
