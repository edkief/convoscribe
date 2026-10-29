package com.convoscribe.sync

import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPInputStream
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer

/**
 * The sync server's upload API (issue #68) kept in memory behind a
 * [MockWebServer], close enough to `server/` for [SyncEngine] tests:
 * blobs with resumable parts and a hash check, documents with
 * compare-and-swap, and tombstones. [intercept] answers a request
 * instead, to stage failures.
 */
class FakeSyncServer(val apiKey: String = "test-key") {
    class Document(var revision: Long, var manifest: String?, var hash: String?, var changedAt: Long)

    val blobs = mutableMapOf<String, ByteArray>()
    val documents = mutableMapOf<String, Document>()
    val uploads = mutableMapOf<String, MutableMap<Int, ByteArray>>()
    val requests = mutableListOf<String>()
    var intercept: (RecordedRequest) -> MockResponse? = { null }

    val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = synchronized(this@FakeSyncServer) {
                requests += "${request.method} ${request.path!!.removePrefix(PREFIX)}"
                intercept(request) ?: answer(request)
            }
        }
    }

    val url: String get() = server.url("/").toString().trimEnd('/')

    /** How many times part requests were sent, any upload. */
    val partsSent: Int get() = requests.count { it.startsWith("PUT blobs/") && "/parts/" in it }

    fun shutdown() = server.shutdown()

    private fun answer(request: RecordedRequest): MockResponse {
        if (request.getHeader("Authorization") != "Bearer $apiKey") return json(401, """{"error":"unauthorized"}""")
        val path = request.path!!.removePrefix(PREFIX).split('/')
        return when {
            path[0] == "blobs" -> blob(request, path)
            path[0] == "documents" -> document(request, path[2])
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun blob(request: RecordedRequest, path: List<String>): MockResponse {
        val sha = path[1]
        return when {
            path.size == 2 -> MockResponse().setResponseCode(if (sha in blobs) 200 else 404)
            path.size == 3 -> if (sha in blobs) {
                json(200, """{"exists":true}""")
            } else {
                val id = UUID.randomUUID().toString()
                uploads[id] = sortedMapOf()
                json(201, """{"id":"$id"}""")
            }
            path.size == 4 -> uploads[path[3]]?.let { parts ->
                val listed = parts.entries.joinToString(",") { """{"n":${it.key},"size":${it.value.size}}""" }
                json(200, """{"parts":[$listed]}""")
            } ?: json(404, """{"error":"unknown_upload"}""")
            path[4] == "parts" -> {
                val parts = uploads[path[3]] ?: return json(404, """{"error":"unknown_upload"}""")
                parts[path[5].toInt()] = request.body.readByteArray()
                MockResponse().setResponseCode(204)
            }
            else -> {
                val parts = uploads.remove(path[3]) ?: return json(404, """{"error":"unknown_upload"}""")
                val bytes = parts.toSortedMap().values.fold(ByteArray(0)) { acc, part -> acc + part }
                if (sha256(bytes) != sha) return json(400, """{"error":"hash_mismatch"}""")
                blobs[sha] = bytes
                json(200, """{"size":${bytes.size}}""")
            }
        }
    }

    private fun document(request: RecordedRequest, syncId: String): MockResponse {
        val base = request.getHeader("Sync-Base-Revision")!!.toLong()
        val current = documents[syncId]
        if (request.method == "DELETE") {
            current ?: return json(404, """{"error":"not_found"}""")
            if (current.manifest != null && current.revision != base) return conflict(syncId, current)
            if (current.manifest != null) {
                current.revision++
                current.manifest = null
                current.hash = null
            }
            return json(200, meta(syncId, current))
        }
        var body = request.body.readByteArray()
        if (request.getHeader("Content-Encoding") == "gzip") body = GZIPInputStream(body.inputStream()).readBytes()
        val text = body.toString(Charsets.UTF_8)
        val hash = request.getHeader("Sync-Manifest-Hash")!!
        if (sha256(body) != hash) return json(400, """{"error":"hash_mismatch"}""")
        val missing = Regex("\"sha256\":\"([0-9a-f]{64})\"").findAll(text).map {
            it.groupValues[1]
        }.filter { it !in blobs }.toList()
        if (missing.isNotEmpty()) {
            return json(
                422,
                """{"error":"missing_blobs","missing":[${missing.joinToString(",") { "\"$it\"" }}]}"""
            )
        }
        val changedAt = request.getHeader("Sync-Changed-At")!!.toLong()
        val doc = when {
            current == null -> Document(1, text, hash, changedAt).also { documents[syncId] = it }
            current.revision == base -> current.apply {
                revision++
                manifest = text
                this.hash = hash
                this.changedAt = changedAt
            }
            current.hash == hash -> current
            else -> return conflict(syncId, current)
        }
        return json(200, meta(syncId, doc))
    }

    private fun meta(syncId: String, doc: Document): String {
        val hash = doc.hash?.let { "\"$it\"" }
        return """{"kind":"session","syncId":"$syncId","revision":${doc.revision},"seq":1,""" +
            """"manifestHash":$hash,"deleted":${doc.manifest == null}}"""
    }

    private fun conflict(syncId: String, doc: Document) = json(
        409,
        """{"error":"conflict","current":${meta(syncId, doc)}}"""
    )

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(
        code
    ).setHeader("Content-Type", "application/json").setBody(Buffer().writeUtf8(body))

    companion object {
        const val PREFIX = "/api/sync/v1/"

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
