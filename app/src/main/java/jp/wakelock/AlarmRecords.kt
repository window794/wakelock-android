package jp.wakelock

import androidx.datastore.preferences.core.*
import java.time.*

internal object AlarmRecords {
    private val ids = stringSetPreferencesKey("clockAlarmIds")
    private val owner = stringPreferencesKey("clockOwner")
    private val snooze = booleanPreferencesKey("clockSnoozeUsed")

    fun read(p: Preferences): WakeState {
        val protocol = WakeRecord.read(p)
        val saved = p[ids] ?: return migrate(protocol, SoundRecord.read(p), ZoneId.systemDefault())
        val alarms = saved.sorted().map { id ->
            val key = "clock.$id."
            ClockAlarm(id, p[intPreferencesKey(key + "minute")] ?: 0, p[intPreferencesKey(key + "days")] ?: 0,
                p[stringPreferencesKey(key + "label")].orEmpty(), AlarmSound(p[stringPreferencesKey(key + "uri")],
                    p[stringPreferencesKey(key + "sound")] ?: AlarmSound().name, p[booleanPreferencesKey(key + "document")] == true),
                p[booleanPreferencesKey(key + "vibrate")] != false, p[booleanPreferencesKey(key + "enabled")] == true,
                p[longPreferencesKey(key + "due")] ?: 0, p[stringPreferencesKey(key + "zone")] ?: ZoneId.systemDefault().id,
                p[longPreferencesKey(key + "skipAt")] ?: 0)
        }
        return protocol.copy(alarms = alarms, ownerId = p[owner], snoozeUsed = p[snooze] == true)
    }

    internal fun migrate(old: WakeState, sound: AlarmSound, zone: ZoneId): WakeState {
        val at = old.date.takeIf { it > 0 } ?: old.started.takeIf { old.phase.active && it > 0 } ?: return old
        val time = Instant.ofEpochMilli(at).atZone(zone)
        val card = ClockAlarm(id = "legacy", minute = time.hour * 60 + time.minute, sound = sound,
            enabled = old.enabled, due = if (old.enabled) at else 0, zoneId = zone.id)
        return old.copy(enabled = false, alarms = listOf(card), ownerId = card.id.takeIf { old.phase.active })
    }

    fun write(p: MutablePreferences, s: WakeState) {
        WakeRecord.write(p, s)
        val nextIds = s.alarms.mapTo(mutableSetOf()) { it.id }
        for (removed in p[ids].orEmpty() - nextIds) {
            p.asMap().keys.filter { it.name.startsWith("clock.$removed.") }.forEach { p.remove(it) }
        }
        p[ids] = nextIds
        if (s.ownerId == null) p.remove(owner) else p[owner] = s.ownerId
        p[snooze] = s.snoozeUsed
        for (card in s.alarms) {
            val key = "clock.${card.id}."
            p[intPreferencesKey(key + "minute")] = card.minute
            p[intPreferencesKey(key + "days")] = card.days
            p[stringPreferencesKey(key + "label")] = card.label
            p[stringPreferencesKey(key + "sound")] = card.sound.name
            val uriKey = stringPreferencesKey(key + "uri")
            if (card.sound.uri == null) p.remove(uriKey) else p[uriKey] = card.sound.uri
            p[booleanPreferencesKey(key + "document")] = card.sound.document
            p[booleanPreferencesKey(key + "vibrate")] = card.vibrate
            p[booleanPreferencesKey(key + "enabled")] = card.enabled
            p[longPreferencesKey(key + "due")] = card.due
            p[stringPreferencesKey(key + "zone")] = card.zoneId
            p[longPreferencesKey(key + "skipAt")] = card.skipAt
        }
    }
}
