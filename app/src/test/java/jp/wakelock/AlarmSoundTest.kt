package jp.wakelock

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AlarmSoundTest {
    private val selected = AlarmSound("content://media/internal/audio/media/12", "Morning Alarm")
    private val local = AlarmSound("content://example.documents/document/alarm", "alarm.wav", true)
    private val system = "content://settings/system/alarm_alert"

    @Test fun oldInstallDefaultsAndBothPickerTypesRoundTrip() {
        val p = mutablePreferencesOf()
        assertEquals(AlarmSound(), SoundRecord.read(p))
        for (sound in listOf(selected, local, AlarmSound())) {
            SoundRecord.write(p, sound)
            assertEquals(sound, SoundRecord.read(p))
        }
    }

    @Test fun invalidSavedUriUsesSystemDefault() {
        val p = mutablePreferencesOf(stringPreferencesKey("alarmSoundUri") to "unusable")
        assertNull(SoundRecord.read(p).uri)
    }

    @Test fun soundSelectionPreservesProtocolAndSurvivesAllRingingPhases() {
        val p = mutablePreferencesOf()
        val bathroom = "A".repeat(22)
        val kitchen = "B".repeat(22)
        val initial = WakeState(bathroom = bathroom, kitchen = kitchen).schedule(1000, true, 1)
        WakeRecord.write(p, initial)
        SoundRecord.write(p, local)
        assertEquals(initial, WakeRecord.read(p))
        fun persist(s: WakeState): WakeState {
            WakeRecord.write(p, s)
            assertEquals(local, SoundRecord.read(p))
            return WakeRecord.read(p)
        }
        val ring = persist(initial.fire(initial.event, 1000))
        val scanning = ring.beginScan()
        val second = scanning.verify(scanning.stepId, "wakelock:v1:bathroom:$bathroom", 1100)
        val wait = persist(second.verify(second.stepId, "wakelock:v1:kitchen:$kitchen", 1200))
        val first = persist(wait.fire(wait.event, wait.deadline))
        val next = persist(first.verify(first.stepId, "wakelock:v1:kitchen:$kitchen", 602000))
        val final = persist(next.fire(next.event, next.deadline))
        val done = persist(final.verify(final.stepId, "wakelock:v1:bathroom:$bathroom", 903000))
        assertTrue(listOf(ring, first, final).all { it.phase.ringing })
        assertTrue(listOf(wait, next, done, persist(first.abort(602000))).none { it.phase.ringing })
        assertEquals(Phase.COMPLETED, done.phase)
    }

    @Test fun previewCanStopAndRestartWithoutStackingOrFallingBackOnStop() = runBlocking {
        val events = mutableListOf<String>()
        val sound = AlarmSoundPlayer(this, system) { uri ->
            events += "start $uri"
            try { awaitCancellation() } finally { events += "stop $uri" }
        }
        sound.start(selected.uri) { fail() }
        sound.start(selected.uri) { fail() }
        yield()
        sound.stop()
        yield()
        sound.start(local.uri) { fail() }
        yield()
        sound.stop()
        yield()
        assertEquals(listOf("start ${selected.uri}", "stop ${selected.uri}", "start ${local.uri}", "stop ${local.uri}"), events)
    }

    @Test fun deniedOrBrokenSelectionFallsBackToDeviceThenBundledAudio() = runBlocking {
        val attempts = mutableListOf<String?>()
        var fallbacks = 0
        val player = AlarmSoundPlayer(this, system) { uri ->
            attempts += uri
            yield() // A failure may arrive after the source was opened.
            when (uri) {
                local.uri -> throw SecurityException()
                system -> throw IOException()
            }
        }
        player.start(local.uri, { fallbacks++ }) { fail() }
        coroutineContext.job.children.toList().joinAll()
        assertEquals(listOf(local.uri, system, null), attempts)
        assertEquals(2, fallbacks)
    }

    @Test fun missingSettingStartsAtDefaultAndReportsExhaustedSourcesOnce() = runBlocking {
        val attempts = mutableListOf<String?>()
        var failures = 0
        val player = AlarmSoundPlayer(this, system) { uri ->
            attempts += uri
            throw IOException()
        }
        player.start(null) { failures++ }
        yield()
        assertEquals(listOf(system, null), attempts)
        assertEquals(1, failures)
    }

    @Test fun sliderReadsOsAndMapsEndpointsAndRounding() {
        var level = AlarmVolume(1, 15, 8, false, false)
        val writes = mutableListOf<Int>()
        val sound = AlarmVolumeControl({ level }) { writes += it; level = level.copy(current = it) }
        assertEquals(0.5f, sound.current().fraction, 0.001f)
        assertTrue(writes.isEmpty())
        assertEquals(1, sound.change(0f).current)
        assertTrue(sound.current().low)
        assertEquals(15, sound.change(1f).current)
        assertEquals(10, sound.change(.65f).current)
        level = level.copy(current = 4)
        assertEquals(4, sound.current().current)
        assertEquals(listOf(1, 15, 10), writes)
    }

    @Test fun blockedOrIgnoredVolumeWriteDoesNotReportSuccess() {
        val level = AlarmVolume(0, 10, 0, false, true)
        assertTrue(level.low)
        assertThrows(SecurityException::class.java) {
            AlarmVolumeControl({ level }) { throw SecurityException() }.change(1f)
        }
        assertThrows(IllegalStateException::class.java) { AlarmVolumeControl({ level }) {}.change(1f) }
        assertThrows(IllegalStateException::class.java) {
            AlarmVolumeControl({ level.copy(fixed = true) }) { fail() }.change(1f)
        }
    }
}
