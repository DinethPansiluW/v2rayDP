package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.model.AppLog
import kotlinx.coroutines.flow.Flow

@Dao
interface AppLogDao {

    @Query("SELECT * FROM app_logs ORDER BY id DESC LIMIT :limit")
    fun getRecentLogsFlow(limit: Int = 150): Flow<List<AppLog>>

    @Insert
    suspend fun insertLog(log: AppLog): Long

    @Query("DELETE FROM app_logs")
    suspend fun clearLogs()

    @Query("DELETE FROM app_logs WHERE id NOT IN (SELECT id FROM app_logs ORDER BY id DESC LIMIT 200)")
    suspend fun pruneOldLogs()
}
