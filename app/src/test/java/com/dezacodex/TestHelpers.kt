package com.dezacodex

import android.app.Application
import android.os.Looper
import com.dezacodex.data.HeartDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.robolectric.Shadows

object TestHelpers {
    suspend fun resetDatabaseForTest(app: Application) {
        val db = HeartDatabase.getDatabase(app)
        withContext(Dispatchers.IO) {
            db.clearAllTables()
        }
    }

    fun idleMainLooper() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    inline fun waitUntil(timeoutMs: Long = 4000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idleMainLooper()
            if (condition()) return
            Thread.sleep(25)
        }
        idleMainLooper()
        if (!condition()) {
            throw AssertionError("Condition was not satisfied within ${timeoutMs}ms")
        }
    }
}
