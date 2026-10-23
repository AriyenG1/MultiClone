package dev.multiclone.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.preferences by preferencesDataStore("multiclone-preferences")
class AppPreferences(private val context: Context) {
    private val lastSource = stringPreferencesKey("last_source")
    val selectedSource = context.preferences.data.map { it[lastSource].orEmpty() }
    suspend fun selectSource(id: String) { context.preferences.edit { it[lastSource] = id } }
}
