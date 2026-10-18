package io.github.willianchavez.winali

import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class DownloadQueueTest {
    @Test fun cancellingOneQueuedAppLeavesOtherRequestsAndDuplicateRequestsAreIgnored() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val manager = WorkManager.getInstance(context)
        val release = Release(1,"1",26,listOf("universal"),"https://example.invalid/app.apk",100,"0".repeat(64),"0".repeat(64),"")
        val first = CatalogApp("first","test.first","First","","","Test","https://github.com/owner/first",emptyList(),emptyList(),release)
        val second = first.copy(id="second",packageName="test.second")
        context.enqueueDownload(first).result.get(10,TimeUnit.SECONDS)
        context.enqueueDownload(second).result.get(10,TimeUnit.SECONDS)
        context.enqueueDownload(second).result.get(10,TimeUnit.SECONDS)
        val firstInfo = manager.getWorkInfosForUniqueWork("download-test.first").get().single()
        manager.cancelWorkById(firstInfo.id).result.get(10,TimeUnit.SECONDS)
        val secondInfo = manager.getWorkInfosForUniqueWork("download-test.second").get()
        assertEquals(1,secondInfo.size)
        assertEquals(WorkInfo.State.ENQUEUED,secondInfo.single().state)
        assertEquals(WorkInfo.State.CANCELLED,manager.getWorkInfoById(firstInfo.id).get()!!.state)
        manager.cancelAllWork().result.get(10,TimeUnit.SECONDS)
    }
}
