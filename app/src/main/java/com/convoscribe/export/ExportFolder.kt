package com.convoscribe.export

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.FileNotFoundException
import java.io.IOException

/** The chosen export folder is gone, or the app may no longer write to it. */
class FolderUnavailableException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * The folder exports are saved to (issue #29), picked once by the user
 * through the system picker and remembered by its tree URI.
 */
interface ExportFolder {
    /**
     * Keep access to the folder [treeUri] the user just picked, across
     * restarts, and return its name to show; `null` when it cannot be
     * kept. [previous], the folder it replaces, is let go.
     */
    fun remember(treeUri: String, previous: String?): String?

    /** Let go of the folder [treeUri]. */
    fun forget(treeUri: String)

    /**
     * Write [bytes] as the file [name] in the folder [treeUri], replacing
     * a file of that name.
     *
     * @throws FolderUnavailableException when the folder is gone or no
     *   longer writable.
     * @throws IOException when writing fails otherwise.
     */
    fun write(treeUri: String, name: String, mime: String, bytes: ByteArray)
}

/** [ExportFolder] through the Storage Access Framework. */
class SafExportFolder(private val resolver: ContentResolver) : ExportFolder {
    private val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    override fun remember(treeUri: String, previous: String?): String? {
        val uri = Uri.parse(treeUri)
        return try {
            resolver.takePersistableUriPermission(uri, flags)
            if (previous != null && previous != treeUri) forget(previous)
            displayName(uri) ?: uri.lastPathSegment
        } catch (_: SecurityException) {
            null
        }
    }

    override fun forget(treeUri: String) {
        runCatching { resolver.releasePersistableUriPermission(Uri.parse(treeUri), flags) }
    }

    override fun write(treeUri: String, name: String, mime: String, bytes: ByteArray) {
        val tree = Uri.parse(treeUri)
        val granted = resolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }
        if (!granted) throw FolderUnavailableException("No access to the export folder")
        try {
            val parentId = DocumentsContract.getTreeDocumentId(tree)
            val target = findChild(tree, parentId, name)
                ?: DocumentsContract.createDocument(
                    resolver,
                    DocumentsContract.buildDocumentUriUsingTree(tree, parentId),
                    mime,
                    name
                )
                ?: throw IOException("Could not create $name")
            val out = resolver.openOutputStream(target, "wt") ?: throw IOException("Could not open $name")
            out.use { it.write(bytes) }
        } catch (e: FolderUnavailableException) {
            throw e
        } catch (e: FileNotFoundException) {
            throw FolderUnavailableException("The export folder is gone", e)
        } catch (e: SecurityException) {
            throw FolderUnavailableException("No access to the export folder", e)
        } catch (e: IllegalArgumentException) {
            throw FolderUnavailableException("The export folder is gone", e)
        }
    }

    /** The document named [name] directly in the folder, or `null`; throws when the folder is gone. */
    private fun findChild(tree: Uri, parentId: String, name: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val columns =
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val cursor = resolver.query(children, columns, null, null, null)
            ?: throw FolderUnavailableException("The export folder is gone")
        cursor.use {
            while (it.moveToNext()) {
                if (it.getString(1) == name) return DocumentsContract.buildDocumentUriUsingTree(tree, it.getString(0))
            }
        }
        return null
    }

    private fun displayName(tree: Uri): String? {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return resolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }
}
