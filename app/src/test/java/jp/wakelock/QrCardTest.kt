package jp.wakelock

import androidx.datastore.preferences.core.*
import com.google.zxing.qrcode.decoder.Decoder
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64

class QrCardTest {
    private val bath = Place.BATHROOM
    private val kitchen = Place.KITCHEN
    private val ready = WakeState().issueQr(bath, "").issueQr(kitchen, "")
    private fun reading(state: WakeState) = state.copy(phase = Phase.AWAITING_BATHROOM_QR)

    @Test fun issueUsesSixteenRandomBytesInPythonFormat() {
        val tokens = List(32) { WakeState().issueQr(bath, "").bathroom }
        assertEquals(tokens.size, tokens.distinct().size)
        assertTrue(tokens.all { it.matches(Regex("[A-Za-z0-9_-]{22}")) && Base64.getUrlDecoder().decode(it).size == 16 })
    }

    @Test fun creationOnlyRegistersSelectedPlace() {
        val state = WakeState(date = 4200, lastAbort = 12)
        val created = state.issueQr(kitchen, "")
        assertTrue(created.registered(kitchen))
        assertFalse(created.registered(bath))
        assertEquals(state, created.copy(kitchen = ""))
    }

    @Test fun absentRegistrationCannotBeDisplayed() {
        assertThrows(InputError::class.java) { WakeState().qrPayload(bath) }
    }

    @Test fun repeatedDisplayUsesExactLegacyPayload() {
        val pythonToken = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16) { it.toByte() })
        val legacy = mutablePreferencesOf(stringPreferencesKey("bathroom") to pythonToken)
        val state = WakeRecord.read(legacy)
        val payload = "wakelock:v1:bathroom:" + pythonToken
        repeat(3) { assertEquals(payload, state.qrPayload(bath)) }
        assertEquals(QrCard(bath, payload).modules, QrCard(bath, state.qrPayload(bath)).modules)
        assertEquals(Phase.AWAITING_KITCHEN_QR, reading(state).verify(reading(state).stepId, payload, 1).phase)
    }

    @Test fun staleCreateOrDuplicateConfirmationCannotOverwrite() {
        assertThrows(InputError::class.java) { ready.issueQr(bath, "") }
        val rotated = ready.issueQr(bath, ready.bathroom)
        assertThrows(InputError::class.java) { rotated.issueQr(bath, ready.bathroom) }
    }

    @Test fun confirmedReissueRevokesOnlySelectedPlace() {
        val rotated = ready.issueQr(bath, ready.bathroom)
        val step = reading(rotated)
        assertEquals(ready.kitchen, rotated.kitchen)
        assertThrows(InputError::class.java) { step.verify(step.stepId, ready.qrPayload(bath), 1) }
        assertEquals(Phase.AWAITING_KITCHEN_QR, step.verify(step.stepId, rotated.qrPayload(bath), 1).phase)
    }

    @Test fun allRunningPhasesRejectGenerationAndReplacement() {
        for (phase in Phase.entries.filter { it.active }) {
            assertThrows(InputError::class.java) { WakeState(phase = phase).issueQr(bath, "") }
            assertThrows(InputError::class.java) { ready.copy(phase = phase).issueQr(kitchen, ready.kitchen) }
        }
    }

    @Test fun generatedCodesUnlockOnlyTheExpectedLocation() {
        val step = reading(ready)
        assertThrows(InputError::class.java) { step.verify(step.stepId, ready.qrPayload(kitchen), 1) }
        val next = step.verify(step.stepId, ready.qrPayload(bath), 1)
        assertEquals(Phase.WAITING_FIRST_CHALLENGE, next.verify(next.stepId, ready.qrPayload(kitchen), 2).phase)
    }

    @Test fun generatedCodesRoundTripThroughQrEncoding() {
        for (place in Place.entries) {
            val payload = ready.qrPayload(place)
            val matrix = QrCard(place, payload).modules
            val body = Array(matrix.height - 8) { y -> BooleanArray(matrix.width - 8) { x -> matrix[x + 4, y + 4] } }
            assertEquals(payload, Decoder().decode(body).text)
        }
    }

    @Test fun layoutPreservesQuietZoneCaptionsAndPhysicalSize() {
        for (place in Place.entries) {
            val card = QrCard(place, ready.qrPayload(place))
            val m = card.modules
            for (y in 0 until m.height) for (x in 0 until m.width)
                if (x < 4 || y < 4 || x >= m.width - 4 || y >= m.height - 4) assertFalse(m[x, y])
            assertTrue(card.qrOffset > 12 && card.titleBaseline > card.qrBottom && card.filenameBaseline < card.height - 12)
            assertEquals(50f, (m.width - 8) * card.moduleSize * card.printScale * 25.4f / 72, .01f)
            assertEquals(place.wire + ".png", card.filename)
        }
    }

    @Test fun existingReservationAndSoundPreferenceSurviveStorageRoundTrip() {
        val state = ready.schedule(999, true, 1)
        val soundKey = stringPreferencesKey("alarmSoundUri")
        val prefs = mutablePreferencesOf(soundKey to "content://example/test")
        WakeRecord.write(prefs, state.issueQr(bath, state.bathroom))
        val restored = WakeRecord.read(prefs)
        assertEquals(state.event, restored.event)
        assertEquals(state.qrPayload(kitchen), restored.qrPayload(kitchen))
        assertNotEquals(state.bathroom, restored.bathroom)
        assertEquals("content://example/test", prefs[soundKey])
    }

    @Test fun unavailableDestinationAndFailedEncoderReportFailure() {
        assertThrows(IOException::class.java) { writePng({ null }) { true } }
        var closed = false
        val output = object : ByteArrayOutputStream() { override fun close() { closed = true } }
        assertThrows(IOException::class.java) { writePng({ output }) { false } }
        assertTrue(closed)
        assertThrows(IOException::class.java) { writePng({ output }) { throw IOException("full") } }
    }
}
