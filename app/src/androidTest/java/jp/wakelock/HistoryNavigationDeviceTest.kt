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
                ui.waitUntil(10_000) { ui.onAllNodesWithText("洗面所のQRコードを読む").fetchSemanticsNodes().isNotEmpty() }
                ui.runOnIdle {
                    ViewModelProvider(ui.activity)[WakeViewModel::class.java].verify(final.stepId, final.qrPayload(Place.BATHROOM))
                }
                ui.waitUntil(10_000) { ui.onAllNodesWithText("起床プロトコル完了").fetchSemanticsNodes().isNotEmpty() }
                if (pressHome) ui.onNodeWithText("ホームへ").performClick()
                ui.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
                ui.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                ui.onNodeWithContentDescription("アラームを追加").assertExists()
                ui.onNodeWithText("起床プロトコル完了").assertDoesNotExist()
                assertNull(runBlocking { store.states.first() }.clockEvent)
                ui.onNodeWithText("起床履歴").performScrollTo().performClick()
                ui.waitUntil(10_000) { ui.onAllNodesWithText(card.label).fetchSemanticsNodes().isNotEmpty() }
                ui.onAllNodesWithText(card.label).onFirst().performClick()
                ui.onNodeWithText("洗面所QR最終認証").assertExists()
                ui.onNodeWithText("履歴一覧に戻る").performClick()
                ui.onNodeWithText("CSVエクスポート").performClick()
                ui.onNodeWithText("日付範囲").performClick()
                ui.onNodeWithText("終了日（YYYY-MM-DD）").assertExists()
                ui.onNodeWithText("履歴一覧に戻る").performClick()
                ui.onNodeWithText("ホームへ").performClick()
            }
            runBlocking { store.change { it.copy(phase = Phase.EMERGENCY_STOPPED, lastAbort = System.currentTimeMillis()) } }
            ui.activityRule.scenario.recreate()
            ui.onNodeWithContentDescription("アラームを追加").assertExists()
        } finally {
            runBlocking { (ui.activity.application as WakeApp).controller.change { before } }
        }
    }
}
