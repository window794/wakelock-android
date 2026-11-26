package jp.wakelock

import androidx.activity.ComponentActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.wakelock.ui.theme.WakeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RedesignDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()

    @Test fun allFourDestinationsKeepScanAvailableAtLargeType() {
        var phase by mutableStateOf(Phase.INITIAL_ALARM)
        var scanned = 0
        var emergency = false
        ui.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                WakeTheme { Surface { RingScreen(WakeState(phase = phase, date = 1L),
                    { scanned++ }, {}, { emergency = true }, {}) } }
            }
        }
        val phases = listOf(Phase.INITIAL_ALARM, Phase.AWAITING_KITCHEN_QR, Phase.FIRST_CHALLENGE_ACTIVE, Phase.FINAL_CHALLENGE_ACTIVE)
        phases.forEachIndexed { index, current ->
            ui.runOnIdle { phase = current }
            ui.onNodeWithText("${current.place!!.title}へ").assertExists()
            ui.onNodeWithText("QRを読み取る").assertIsDisplayed().performClick()
            if (index == 0) ui.onNodeWithText("スヌーズ 5分", substring = true).assertIsDisplayed()
            else ui.onNodeWithText("スヌーズ 5分", substring = true).assertDoesNotExist()
        }
        ui.onNodeWithText("読み取れないとき（緊急停止）").performClick()
        ui.runOnIdle { assertEquals(4, scanned); assertTrue(emergency) }
    }

    @Test fun waitingScreensDescribeCorrectDestinationAndCompletedSteps() {
        var finalWait by mutableStateOf(false)
        val now = System.currentTimeMillis()
        ui.setContent {
            val phase = if (finalWait) Phase.WAITING_FINAL_CHALLENGE else Phase.WAITING_FIRST_CHALLENGE
            WakeTheme { Surface { WaitScreen(WakeState(phase = phase, timing = Timing.SHORT,
                deadline = now + if (finalWait) 20_000 else 30_000), true, {}, {}) } }
        }
        ui.onNodeWithText("次は キッチン").assertIsDisplayed()
        ui.onNodeWithContentDescription("起床プロトコル 2/4 完了。次はキッチン").assertExists()
        ui.runOnIdle { finalWait = true }
        ui.onNodeWithText("次は 洗面所").assertIsDisplayed()
        ui.onNodeWithContentDescription("起床プロトコル 3/4 完了。次は洗面所").assertExists()
    }

    @Test fun readinessRoutesToUnregisteredPlaceThenMissingPermission() {
        var registered by mutableStateOf(false)
        var target = ""
        ui.setContent {
            val state = WakeState(bathroom = "A".repeat(22), kitchen = if (registered) "B".repeat(22) else "")
            val perms = listOf(PermissionItem(Icons.Filled.PhotoCamera, "カメラ", false, "QRを読み取れません") {})
            WakeTheme { Surface { AlarmHome(state, perms, { target = it }, {}, { _, _ -> }, {}) } }
        }
        ui.onNodeWithText("キッチンのQRが未登録").performClick()
        ui.runOnIdle { assertEquals("qr", target); registered = true }
        ui.onNodeWithText("カメラが未許可").performClick()
        ui.runOnIdle { assertEquals("settings", target) }
    }
}
