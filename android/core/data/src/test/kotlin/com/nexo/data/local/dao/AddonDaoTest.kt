package com.nexo.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nexo.data.local.AppDatabase
import com.nexo.data.local.entity.AddonEntity
import com.nexo.data.remote.addons.AddonId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AddonDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: AddonDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = db.addonDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun urlA() = "https://a.example.com/m.json"

    private fun urlB() = "https://b.example.com/m.json"

    private fun entity(
        url: String,
        manifest: String = "{\"v\":1}",
        enabled: Boolean = true,
    ) = AddonEntity(
        id = AddonId.fromUrl(url),
        url = url,
        manifestJson = manifest,
        enabled = enabled,
        addedAt = url.hashCode().toLong(),
    )

    @Test
    fun `insert getById getAll roundtrip`() =
        runBlocking {
            dao.upsertPreservingEnabled(entity(urlA()))
            dao.upsertPreservingEnabled(entity(urlB()))

            val byId = dao.getById(AddonId.fromUrl(urlA()))
            assertEquals(urlA(), byId?.url)

            val all = dao.getAll()
            assertEquals(2, all.size)
            assertTrue(all[0].addedAt <= all[1].addedAt)
        }

    @Test
    fun `reinstall same url preserves enabled and updates manifest`() =
        runBlocking {
            dao.upsertPreservingEnabled(entity(urlA(), "{\"v\":1}", enabled = false))
            dao.upsertPreservingEnabled(entity(urlA(), "{\"v\":2}", enabled = true))

            val all = dao.getAll()
            assertEquals(1, all.size)
            assertEquals("{\"v\":2}", all[0].manifestJson)
            assertFalse(all[0].enabled)
        }

    @Test
    fun `setEnabled persists`() =
        runBlocking {
            dao.upsertPreservingEnabled(entity(urlA()))
            dao.upsertPreservingEnabled(entity(urlB()))

            dao.setEnabled(AddonId.fromUrl(urlA()), false)
            assertFalse(dao.getById(AddonId.fromUrl(urlA()))!!.enabled)
            dao.setEnabled(AddonId.fromUrl(urlA()), true)
            assertTrue(dao.getById(AddonId.fromUrl(urlA()))!!.enabled)
        }

    @Test
    fun `delete removes only target row`() =
        runBlocking {
            dao.upsertPreservingEnabled(entity(urlA()))
            dao.upsertPreservingEnabled(entity(urlB()))

            dao.delete(AddonId.fromUrl(urlA()))
            assertNull(dao.getById(AddonId.fromUrl(urlA())))
            assertEquals(1, dao.getAll().size)
        }
}
