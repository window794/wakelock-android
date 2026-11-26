package jp.wakelock

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryNavigationDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun finalReceiptIsTransientWithOrWithoutHomeButtonAndHistoryRemains() {
        val store = WakeStore(ui.activity.applicationContext)
        val before = runBlocking { store.states.first() }
        assumeTrue(before.alarms.isEmpty() && !before.phase.active && Place.entries.none(before::registered))
        try {
            for (pressHome in listOf(false, true)) {
                val now = System.currentTimeMillis()
                val card = ClockAlarm(minute = 420, label = "画面遷移テスト", enabled = false)
                val final = before.copy(alarms = listOf(card), ownerId = card.id, generation = now,
                    bathroom = "A".repeat(22), kitchen = "B".repeat(22), phase = Phase.FINAL_CHALLENGE_ACTIVE,
                    date = now - 1000_000, started = now - 1000_000, qrDone = now - 950_000,
                    firstDone = now - 310_000, deadline = now + 60_000)
                runBlocking { store.change { final } }
                ui.waitUntil(10_000) { ui.onAllNodesWithText("QRを読み取る").fetchSemanticsNodes().isNotEmpty() }
                ui.runOnIdle {
                    ViewModelProvider(ui.activity)[WakeViewModel::class.java].verify(final.stepId, final.qrPayload(Place.BATHROOM))
                }
                ui.waitUntil(10_000) { ui.onAllNodesWithText("起床完了").fetchSemanticsNodes().isNotEmpty() }
                if (pressHome) ui.onNodeWithText("ホームへ").performClick()
                ui.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
                ui.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                ui.onNodeWithContentDescription("アラームを追加").assertExists()
                ui.onNodeWithText("起床完了").assertDoesNotExist()
                assertNull(runBlocking { store.states.first() }.clockEvent)
                ui.onNodeWithText("履歴").performClick()
                ui.waitUntil(10_000) { ui.onAllNodesWithText(card.label, substring = true).fetchSemanticsNodes().isNotEmpty() }
                ui.onAllNodesWithText(card.label, substring = true).onFirst().performClick()
                ui.onNodeWithText("④ 洗面所（最終確認）").assertExists()
                ui.onNodeWithContentDescription("戻る").performClick()
                ui.onNodeWithContentDescription("CSVエクスポート").performClick()
                ui.onNodeWithText("日付範囲").performClick()
                ui.onNodeWithText("終了日（YYYY-MM-DD）").assertExists()
                ui.onNodeWithContentDescription("戻る").performClick()
                ui.onAllNodesWithText("アラーム").onLast().performClick()
            }
            runBlocking { store.change { it.copy(phase = Phase.EMERGENCY_STOPPED, lastAbort = System.currentTimeMillis()) } }
            ui.activityRule.scenario.recreate()
            ui.onNodeWithContentDescription("アラームを追加").assertExists()
        } finally {
            runBlocking { (ui.activity.application as WakeApp).controller.change { before } }
        }
    }
}
