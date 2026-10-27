package com.convoscribe.engine.sherpa

import com.convoscribe.models.ModelCatalog
import com.convoscribe.models.ModelStore
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DiarizationEmbeddingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(): ModelStore = ModelStore(tmp.newFolder("models"))

    private fun ModelStore.write(sizeBytes: Long) {
        val bundle = ModelCatalog.EMBEDDING_CAMPPLUS
        val file = fileFor(bundle, bundle.files.single().fileName)
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { it.setLength(sizeBytes) }
    }

    @Test
    fun default_isResNet34() {
        assertEquals(ModelCatalog.EMBEDDING, diarizationEmbedding(store(), "resnet34"))
    }

    @Test
    fun campplus_whenDownloaded() {
        val store = store()
        store.write(ModelCatalog.EMBEDDING_CAMPPLUS.totalBytes)
        assertEquals(ModelCatalog.EMBEDDING_CAMPPLUS, diarizationEmbedding(store, "campplus"))
    }

    @Test
    fun campplus_notDownloaded_fallsBackToResNet34() {
        assertEquals(ModelCatalog.EMBEDDING, diarizationEmbedding(store(), "campplus"))
    }

    @Test
    fun campplus_halfDownloaded_fallsBackToResNet34() {
        val store = store()
        store.write(ModelCatalog.EMBEDDING_CAMPPLUS.totalBytes / 2)
        assertEquals(ModelCatalog.EMBEDDING, diarizationEmbedding(store, "campplus"))
    }

    @Test
    fun unknownKey_isResNet34() {
        assertEquals(ModelCatalog.EMBEDDING, diarizationEmbedding(store(), "nope"))
    }
}
