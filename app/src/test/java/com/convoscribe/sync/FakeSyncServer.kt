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
 * The sync server's API (issues #68, #69) kept in memory behind a
 * [MockWebServer], close enough to `server/` for [SyncEngine] tests:
 * blobs with resumable parts, a hash check and Range downloads,
 * documents with compare-and-swap and the conflict rule
 * (`server/src/lib/documents.ts`), tombstones and the change feed.
 * [intercept] answers a request instead, to stage failures.
 *
 * Sessions are in [documents]; other kinds (#70) in [library], by
 * kind. A kind not in [kinds] is refused as an older server would.
 */
class FakeSyncServer(val apiKey: String = "test-key") {
    class Document(
        var revision: Long,
        var manifest: String?,
        var hash: String?,
        var changedAt: Long,
        var durationMs: Long = 0,
        var seq: Long = 0
    )

    private var seq = 0L

    val blobs = mutableMapOf<String, ByteArray>()
    val documents = mutableMapOf<String, Document>()
    val library = mutableMapOf<String, MutableMap<String, Document>>()
    var kinds = setOf("session", "voice", "preset", "template", "vectors")
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
            path[0].startsWith("changes") -> changes(request)
            path[0] == "documents" && path[1] !in kinds -> json(404, """{"error":"unknown_kind"}""")
            path[0] == "documents" && request.method == "GET" -> getDocument(path[1], path[2])
            path[0] == "documents" -> document(request, path[1], path[2])
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun blob(request: RecordedRequest, path: List<String>): MockResponse {
        val sha = path[1]
        return when {
            path.size == 2 && request.method == "GET" -> download(request, sha)
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

    private fun download(request: RecordedRequest, sha: String): MockResponse {
        val bytes = blobs[sha] ?: return MockResponse().setResponseCode(404)
        val from = request.getHeader("Range")?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
        val body = Buffer().write(bytes.copyOfRange(from, bytes.size))
        return if (from > 0) {
            MockResponse().setResponseCode(
                206
            ).setHeader("Content-Range", "bytes $from-${bytes.size - 1}/${bytes.size}")
                .setBody(body)
        } else {
            MockResponse().setResponseCode(200).setBody(body)
        }
    }

    private fun changes(request: RecordedRequest): MockResponse {
        val since = request.requestUrl!!.queryParameter("since")!!.toLong()
        val limit = request.requestUrl!!.queryParameter("limit")!!.toInt()
        val all = documents.map { Triple("session", it.key, it.value) } +
            library.flatMap { (kind, docs) -> docs.map { Triple(kind, it.key, it.value) } }
        val after = all.filter { it.third.seq > since }.sortedBy { it.third.seq }
        val page = after.take(limit)
        val next = page.lastOrNull()?.third?.seq ?: since
        return json(
            200,
            """{"changes":[${page.joinToString(
                ","
            ) { meta(it.second, it.third, it.first) }}],"next":$next,"more":${after.size > limit}}"""
        )
    }

    /** The documents of [kind]. */
    fun docs(kind: String): MutableMap<String, Document> =
        if (kind == "session") documents else library.getOrPut(kind) { mutableMapOf() }

    private fun getDocument(kind: String, syncId: String): MockResponse {
        val doc = docs(kind)[syncId] ?: return json(404, """{"error":"not_found"}""")
        if (doc.manifest == null) return json(410, """{"error":"deleted","current":${meta(syncId, doc, kind)}}""")
        return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
            .setHeader("Sync-Revision", doc.revision).setHeader("Sync-Changed-At", doc.changedAt)
            .setBody(Buffer().writeUtf8(doc.manifest!!))
    }

    /** The server's conflict rule, as `pushWins` in `server/src/lib/documents.ts`. */
    private fun pushWins(durationMs: Long, changedAt: Long, hash: String, current: Document): Boolean = when {
        current.manifest == null -> durationMs > current.durationMs
        durationMs != current.durationMs -> durationMs > current.durationMs
        changedAt != current.changedAt -> changedAt > current.changedAt
        else -> hash > (current.hash ?: "")
    }

    private fun document(request: RecordedRequest, kind: String, syncId: String): MockResponse {
        val documents = docs(kind)
        val base = request.getHeader("Sync-Base-Revision")!!.toLong()
        val current = documents[syncId]
        if (request.method == "DELETE") {
            current ?: return json(404, """{"error":"not_found"}""")
            val deleted = request.getHeader("Sync-Duration-Ms")?.toLong()
            if (current.manifest != null && current.revision != base &&
                (deleted == null || current.durationMs > deleted)
            ) {
                return conflict(syncId, current, kind)
            }
            if (current.manifest != null) {
                current.revision++
                current.manifest = null
                current.hash = null
                current.seq = ++seq
            }
            return json(200, meta(syncId, current, kind))
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
        val durationMs = request.getHeader("Sync-Duration-Ms")!!.toLong()
        val doc = when {
            current == null -> Document(1, text, hash, changedAt, durationMs, ++seq).also { documents[syncId] = it }
            current.manifest != null && current.hash == hash -> current
            current.revision == base || pushWins(durationMs, changedAt, hash, current) -> current.apply {
                revision++
                manifest = text
                this.hash = hash
                this.changedAt = changedAt
                this.durationMs = durationMs
                this.seq = ++this@FakeSyncServer.seq
            }
            else -> return conflict(syncId, current, kind)
        }
        return json(200, meta(syncId, doc, kind))
    }

    private fun meta(syncId: String, doc: Document, kind: String = "session"): String {
        val hash = doc.hash?.let { "\"$it\"" }
        return """{"kind":"$kind","syncId":"$syncId","revision":${doc.revision},"seq":${doc.seq},""" +
            """"durationMs":${doc.durationMs},"changedAt":${doc.changedAt},""" +
            """"manifestHash":$hash,"deleted":${doc.manifest == null}}"""
    }

    private fun conflict(syncId: String, doc: Document, kind: String = "session") = json(
        409,
        """{"error":"conflict","current":${meta(syncId, doc, kind)}}"""
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
