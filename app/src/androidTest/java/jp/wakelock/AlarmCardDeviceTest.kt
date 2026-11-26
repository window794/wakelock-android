package jp.wakelock

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlarmCardDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private var card by mutableStateOf(ClockAlarm("test", minute = 420))
    private var removed = false

    private fun showEditor() {
        ui.setContent {
            AlarmEditor(card, false, Timing.NORMAL, {}, { minute, name, days, vibrate, _, sound ->
                card = card.copy(minute = minute, label = name, days = days, vibrate = vibrate, sound = sound)
            }, { removed = true })
        }
    }

    @Test fun editorAppliesNameDaysAndVibrationOnlyOnSave() {
        showEditor()
        ui.onNodeWithContentDescription("日曜日").performScrollTo().performClick()
        ui.onNodeWithContentDescription("土曜日").performClick()
        ui.onNodeWithContentDescription("バイブレーション").performScrollTo().performClick()
        ui.onNode(hasSetTextAction() and hasText("名前")).performScrollTo().performTextReplacement("休日")
        ui.runOnIdle { assertEquals(0, card.days); assertTrue(card.vibrate) }
        ui.onNodeWithText("保存").performClick()
        ui.runOnIdle {
            assertEquals(65, card.days)
            assertEquals("休日", card.label)
            assertFalse(card.vibrate)
        }
    }

    @Test fun timeInputSupportsDialAndDeletionNeedsConfirmation() {
        showEditor()
        ui.onNodeWithContentDescription("時計盤を使う").performClick()
        ui.onNodeWithContentDescription("数字で入力").performScrollTo().performClick()
        ui.onAllNodes(hasSetTextAction(), useUnmergedTree = true)[0].performTextReplacement("08")
        ui.onNodeWithText("保存").performSemanticsAction(SemanticsActions.OnClick)
        ui.runOnIdle { assertEquals(480, card.minute) }
        ui.onNodeWithText("このアラームを削除").performScrollTo().performClick()
        ui.onNodeWithText("キャンセル").performClick()
        ui.runOnIdle { assertFalse(removed) }
        ui.onNodeWithText("このアラームを削除").performClick()
        ui.onNodeWithText("削除する").performClick()
        ui.runOnIdle { assertTrue(removed) }
    }
}
