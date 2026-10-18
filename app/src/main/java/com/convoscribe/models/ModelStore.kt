package com.convoscribe.models

import java.io.File

/**
 * On-disk model store under `filesDir/models/<bundleId>/`.
 *
 * The downloader writes here, the recognizer/diarizer reads absolute
 * paths from here. We never trust an installed bundle without checking
 * every file exists and matches its declared [ModelFile.sizeBytes]: a
 * truncated download would otherwise produce a recognizer that fails
 * mid-session in a way that's hard to reproduce.
 */
class ModelStore(private val root: File) {

    /**
     * Directory that holds every file for [bundle]. Created lazily by
     * the downloader; this class does not create it.
     */
    fun bundleDir(bundle: ModelBundle): File = File(root, bundle.id)

    /**
     * Absolute path to a single file inside [bundle]'s directory.
     * `fileName` may contain a sub-directory (e.g. `test_wavs/0.wav`).
     */
    fun fileFor(bundle: ModelBundle, fileName: String): File = File(bundleDir(bundle), fileName)

    /**
     * True iff every file declared in [bundle] is present on disk and
     * has exactly the expected [ModelFile.sizeBytes]. A zero-byte file
     * (interrupted download) is *not* considered installed.
     */
    fun isInstalled(bundle: ModelBundle): Boolean = bundle.files.all { file ->
        val onDisk = fileFor(bundle, file.fileName)
        onDisk.exists() && onDisk.length() == file.sizeBytes
    }

    /**
     * Bundles in [bundles] that are not fully installed, in input order.
     * The downloader uses this to decide what to fetch.
     */
    fun missing(bundles: List<ModelBundle>): List<ModelBundle> = bundles.filterNot(::isInstalled)

    /**
     * Remove every file of [bundle] from disk. Missing bundles are a
     * no-op. Used by the Settings screen to free space.
     */
    fun delete(bundle: ModelBundle) {
        val dir = bundleDir(bundle)
        if (dir.exists()) {
            dir.deleteRecursively()
        }
        archivePartFor(bundle).delete()
    }

    /**
     * Where [bundle]'s [ModelArchive] is downloaded before it is
     * extracted (issue #10): next to the bundle directory, so a resumed
     * download finds it and extraction never sees it as a model file.
     */
    fun archivePartFor(bundle: ModelBundle): File = File(root, "${bundle.id}.archive.part")

    /**
     * Delete what an interrupted download of [bundle] kept to resume
     * from: its archive and every `.part` file in its directory.
     * Installed files stay.
     */
    fun discardPartial(bundle: ModelBundle) {
        archivePartFor(bundle).delete()
        bundleDir(bundle).walkBottomUp()
            .filter { it.isFile && it.name.endsWith(".part") }
            .forEach { it.delete() }
    }

    /** Bytes free for new files on the volume holding the store. */
    fun usableSpace(): Long = root.apply { mkdirs() }.usableSpace
}
