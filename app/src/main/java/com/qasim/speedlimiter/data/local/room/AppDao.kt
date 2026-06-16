package com.qasim.speedlimiter.data.local.room

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {
    @Query("SELECT * FROM app_limits")
    fun getAllApps(): Flow<List<AppEntity>>

    @Query("SELECT * FROM app_limits WHERE packageName = :packageName LIMIT 1")
    suspend fun getAppByPackage(packageName: String): AppEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateApp(app: AppEntity)

    @Delete
    suspend fun deleteApp(app: AppEntity)
}
