package jp.wakelock

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlarmListDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun addFromHomeRestoreAndDeleteDraftThroughTheCard() {
        val store = WakeStore(ui.activity.applicationContext)
        fun saved() = runBlocking { store.states.first() }
        // The empty-install flow creates a disabled draft and can never arm a user's existing alarm.
        val initial = saved()
        assumeTrue(initial.alarms.isEmpty() && !initial.phase.active && Place.entries.none(initial::registered))
        ui.onNodeWithContentDescription("アラームを追加").performClick()
        ui.onNodeWithText("決定").performClick()
        ui.waitUntil(10_000) { saved().alarms.size == 1 }
        assertFalse(saved().alarms.single().enabled)
        ui.activityRule.scenario.recreate()
        ui.onNodeWithText("07:00").assertExists()
        if (ui.onAllNodesWithText("削除").fetchSemanticsNodes().isEmpty())
            ui.onNodeWithText("07:00").performScrollTo().performClick()
        ui.onNodeWithText("削除").performScrollTo().performClick()
        ui.onNodeWithText("削除する").performClick()
        ui.waitUntil(10_000) { saved().alarms.isEmpty() }
        assertNull(saved().clockEvent)
    }
}
