package org.jaagruk.safety.testing

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.jaagruk.safety.data.db.JaagrukDatabase
import java.util.concurrent.Executor

/**
 * An in-memory [JaagrukDatabase] that runs every query on the calling thread.
 *
 * The direct executors are the important part. Room's suspend DAO functions normally hand work to
 * its own query and transaction executors, so a view model whose `init` reads the database finishes
 * *some time after* the constructor returns. A test that then asserts on `state.value` is racing,
 * and it will pass or fail depending on how many suspension points happened to run first — which is
 * exactly the kind of test that passes on a laptop and fails in CI.
 *
 * Pointing both executors at the caller, with `Dispatchers.setMain(UnconfinedTestDispatcher())` for
 * `viewModelScope`, makes the whole chain run inline: by the time a view model constructor returns,
 * its initial load has completed.
 */
object TestDatabase {

    private val directExecutor = Executor { command -> command.run() }

    fun create(
        context: Context = ApplicationProvider.getApplicationContext(),
    ): JaagrukDatabase = Room.inMemoryDatabaseBuilder(context, JaagrukDatabase::class.java)
        .allowMainThreadQueries()
        .setQueryExecutor(directExecutor)
        .setTransactionExecutor(directExecutor)
        .build()
}
