package com.example.core.block

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class BlockUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.i(TAG, "Starting periodic weekly blocklist update...")
        return try {
            val manager = BlockListManager(applicationContext)
            manager.downloadAll()
            Log.i(TAG, "Weekly blocklist update finished successfully")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Weekly blocklist update failed: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "BlockUpdateWorker"
        private const val WORK_NAME = "weekly_blocklist_update"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresCharging(true)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<BlockUpdateWorker>(7, TimeUnit.DAYS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )
            Log.d(TAG, "Scheduled weekly blocklist auto-update (requires unmetered + charging)")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Cancelled weekly blocklist auto-update")
        }
    }
}
