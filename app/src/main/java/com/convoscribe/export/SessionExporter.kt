package com.convoscribe.export

import android.util.Log
import com.convoscribe.TAG
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Exports a session (issue #29): renders it ([ExportRenderer]) and hands
 * it to the share sheet, through a file in [cacheDir], or saves it in the
 * chosen [folder], replacing an earlier export of the same name.
 */
class SessionExporter(
    private val loader: SessionExportLoader,
    private val folder: ExportFolder,
    private val cacheDir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO
) {
    /** How an export ended. */
    sealed interface Result {
        /** Ready to share: [file] typed [mime], or [text] itself for plain text. */
        data class Shared(val file: File?, val mime: String, val title: String, val text: String? = null) : Result

        /** Saved as [fileName] in the folder [folderName]. */
        data class Saved(val fileName: String, val folderName: String) : Result

        /** The folder is gone or no longer writable: it needs picking again. */
        data object FolderUnavailable : Result

        /** The session no longer exists. */
        data object NotFound : Result

        /** Writing failed: [message] says why. */
        data class Failed(val message: String) : Result
    }

    /** [sessionId] rendered as [options] asks, ready to share. */
    suspend fun share(sessionId: Long, options: ExportOptions): Result = withContext(io) {
        val session = loader.load(sessionId) ?: return@withContext Result.NotFound
        val bytes = ExportRenderer.render(session, options)
        if (options.format == ExportFormat.TEXT) {
            return@withContext Result.Shared(null, options.format.mime, session.title, bytes.toString(Charsets.UTF_8))
        }
        try {
            val dir = File(cacheDir, sessionId.toString())
            dir.deleteRecursively()
            if (!dir.mkdirs()) throw IOException("Cannot create $dir")
            val file = File(dir, ExportNames.fileName(session, options.format))
            file.writeBytes(bytes)
            Result.Shared(file, options.format.mime, session.title)
        } catch (e: IOException) {
            Log.w(TAG, "export: could not write the file to share", e)
            Result.Failed(e.message ?: e.toString())
        }
    }

    /** [sessionId] rendered as [options] asks, saved in the folder [treeUri] named [folderName]. */
    suspend fun save(sessionId: Long, options: ExportOptions, treeUri: String, folderName: String): Result =
        withContext(io) {
            val session = loader.load(sessionId) ?: return@withContext Result.NotFound
            val name = ExportNames.fileName(session, options.format)
            try {
                folder.write(treeUri, name, options.format.documentMime, ExportRenderer.render(session, options))
                Log.i(TAG, "export saved session=$sessionId format=${options.format.key}")
                Result.Saved(name, folderName)
            } catch (e: FolderUnavailableException) {
                Log.w(TAG, "export: folder unavailable", e)
                Result.FolderUnavailable
            } catch (e: IOException) {
                Log.w(TAG, "export: could not save", e)
                Result.Failed(e.message ?: e.toString())
            }
        }
}
