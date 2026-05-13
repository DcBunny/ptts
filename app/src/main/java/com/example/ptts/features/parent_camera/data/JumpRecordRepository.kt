package com.example.ptts.features.parent_camera.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.jumpRecordsDataStore by preferencesDataStore(name = "jump_records")

class JumpRecordRepository(context: Context) {
    private val appContext = context.applicationContext

    val bestRecord: Flow<Int> = appContext.jumpRecordsDataStore.data.map { preferences ->
        val value = preferences[BestRecordKey] ?: 0
        Log.i(TAG, "bestRecord loaded: $value")
        value
    }

    suspend fun saveBestRecordIfNeeded(count: Int) {
        appContext.jumpRecordsDataStore.edit { preferences ->
            val currentBest = preferences[BestRecordKey] ?: 0
            if (count > currentBest) {
                Log.i(TAG, "saveBestRecord: updated $currentBest -> $count")
                preferences[BestRecordKey] = count
            } else {
                Log.i(TAG, "saveBestRecord: not updated, count=$count currentBest=$currentBest")
            }
        }
    }

    private companion object {
        const val TAG = "JumpDebug"
        val BestRecordKey = intPreferencesKey("best_record")
    }
}
