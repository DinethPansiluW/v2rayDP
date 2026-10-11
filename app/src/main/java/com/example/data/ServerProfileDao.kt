package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.model.ServerProfile
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerProfileDao {

    @Query("SELECT * FROM server_profiles ORDER BY id DESC")
    fun getAllProfilesFlow(): Flow<List<ServerProfile>>

    @Query("SELECT * FROM server_profiles ORDER BY id DESC")
    suspend fun getAllProfiles(): List<ServerProfile>

    @Query("SELECT * FROM server_profiles WHERE id = :id LIMIT 1")
    suspend fun getProfileById(id: Long): ServerProfile?

    @Query("SELECT * FROM server_profiles WHERE isSelected = 1 LIMIT 1")
    fun getActiveProfileFlow(): Flow<ServerProfile?>

    @Query("SELECT * FROM server_profiles WHERE isSelected = 1 LIMIT 1")
    suspend fun getActiveProfile(): ServerProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: ServerProfile): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfiles(profiles: List<ServerProfile>): List<Long>

    @Update
    suspend fun updateProfile(profile: ServerProfile)

    @Delete
    suspend fun deleteProfile(profile: ServerProfile)

    @Query("UPDATE server_profiles SET isSelected = 0")
    suspend fun deselectAll()

    @Query("UPDATE server_profiles SET isSelected = (id = :profileId)")
    suspend fun setSelected(profileId: Long)

    @Query("UPDATE server_profiles SET pingLatencyMs = :latencyMs, lastPingTimestamp = :timestamp WHERE id = :profileId")
    suspend fun updateLatency(profileId: Long, latencyMs: Int, timestamp: Long)

    @Query("DELETE FROM server_profiles")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM server_profiles")
    suspend fun getCount(): Int
}
