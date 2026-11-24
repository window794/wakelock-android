package jp.wakelock

import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class AlarmSound(val uri: String? = null, val name: String = "端末のデフォルト", val document: Boolean = false)

internal object SoundRecord {
    private val uri = stringPreferencesKey("alarmSoundUri")
    private val name = stringPreferencesKey("alarmSoundName")
    private val document = booleanPreferencesKey("alarmSoundDocument")

    fun read(p: Preferences): AlarmSound {
        val value = p[uri]?.takeIf { it.startsWith("content://") } ?: return AlarmSound()
        return AlarmSound(value, p[name] ?: "選択した音声", p[document] == true)
    }

    fun write(p: MutablePreferences, sound: AlarmSound) {
        if (sound.uri == null) {
            p.remove(uri)
            p.remove(name)
            p.remove(document)
        } else {
            p[uri] = sound.uri
            p[name] = sound.name
            p[document] = sound.document
        }
    }
}

internal class AlarmSoundStore(private val context: Context) {
    val sounds = context.wakeData.data.map(SoundRecord::read).distinctUntilChanged()
    private val resolver = context.contentResolver
    private val lock = Mutex()
    private val readFlag = Intent.FLAG_GRANT_READ_URI_PERMISSION

    suspend fun select(uri: Uri?, document: Boolean, alarmId: String? = null) = lock.withLock {
        // Finish the grant and its DataStore write together, even if the settings page is closed.
        withContext(NonCancellable + Dispatchers.IO) {
            val selected = if (uri == null || (!document && RingtoneManager.isDefault(uri))) AlarmSound() else {
                require(uri.scheme == "content" && !uri.authority.isNullOrBlank()) { "この音源には対応していません。" }
                AlarmSound(uri.toString(), soundTitle(context, uri, document), document)
            }
            val needsGrant = selected.document && uri != null &&
                resolver.persistedUriPermissions.none { it.uri == uri && it.isReadPermission }
            if (needsGrant) resolver.takePersistableUriPermission(requireNotNull(uri), readFlag)
            try {
                context.wakeData.edit { prefs ->
                    val before = AlarmRecords.read(prefs)
                    val after = if (alarmId == null) before else before.modifyCard(alarmId) { it.copy(sound = selected) }
                    AlarmRecords.write(prefs, after)
                    if (alarmId == null) SoundRecord.write(prefs, selected)
                }
            } catch (failure: Exception) {
                if (needsGrant) releaseGrant(uri.toString())
                throw failure
            }
            pruneUnusedAudioGrants()
        }
    }

    suspend fun afterDeletion() = lock.withLock {
        withContext(Dispatchers.IO) { pruneUnusedAudioGrants() }
    }

    // Audio documents are the only persistable permissions the app requests.
    private suspend fun pruneUnusedAudioGrants() {
        val prefs = context.wakeData.data.first()
        val retained = (AlarmRecords.read(prefs).alarms.map { it.sound } + SoundRecord.read(prefs))
            .mapNotNullTo(mutableSetOf()) { it.uri }
        resolver.persistedUriPermissions.filter { it.isReadPermission && it.uri.toString() !in retained }
            .forEach { releaseGrant(it.uri.toString()) }
    }

    private fun releaseGrant(value: String) {
        try {
            resolver.releasePersistableUriPermission(value.toUri(), readFlag)
        } catch (_: SecurityException) {
            // Deleting a document or removing its provider can already revoke its grant.
        }
    }
}

internal fun soundTitle(context: Context, uri: Uri, document: Boolean): String {
    val title = runCatching {
        if (document) context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } else RingtoneManager.getRingtone(context, uri)?.let { ringtone ->
            try { ringtone.getTitle(context) } finally { ringtone.stop() }
        }
    }.getOrNull()
    return title?.takeIf { it.isNotBlank() } ?: "選択した音声"
}
