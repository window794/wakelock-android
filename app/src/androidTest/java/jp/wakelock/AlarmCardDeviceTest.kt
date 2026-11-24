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

    private fun showCard() {
        ui.setContent {
            var expanded by remember { mutableStateOf(false) }
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    AlarmCard(card, expanded, { expanded = !expanded }, { card = it(card) }, {}, { removed = true })
                }
            }
        }
        ui.onNodeWithText("07:00").performClick()
    }

    @Test fun cardEditsDaysNameEnabledAndVibration() {
        showCard()
        ui.onNodeWithContentDescription("日曜日").performClick()
        ui.onNodeWithContentDescription("土曜日").performClick()
        ui.onNodeWithContentDescription("バイブレーション").performScrollTo().performClick()
        ui.onNodeWithText("名前　未設定").performScrollTo().performClick()
        ui.onNode(hasSetTextAction()).performTextReplacement("休日")
        ui.onNodeWithText("保存").performClick()
        ui.onNodeWithContentDescription("休日のアラーム").performScrollTo().performClick()
        ui.runOnIdle {
            assertEquals(65, card.days)
            assertEquals("休日", card.label)
            assertFalse(card.enabled)
            assertFalse(card.vibrate)
        }
    }

    @Test fun timeDialogSupportsKeyboardAndCancelAndDeletionNeedsConfirmation() {
        showCard()
        ui.onNodeWithText("時刻を変更　07:00").performClick()
        ui.onNodeWithText("数字で入力").performSemanticsAction(SemanticsActions.OnClick)
        ui.onNodeWithText("時計盤を使う").assertExists()
        ui.onAllNodes(hasSetTextAction(), useUnmergedTree = true)[0].performTextReplacement("08")
        ui.onNodeWithText("決定").performSemanticsAction(SemanticsActions.OnClick)
        ui.runOnIdle { assertEquals(480, card.minute) }
        ui.onNodeWithText("時刻を変更　08:00").performClick()
        ui.onNodeWithText("キャンセル").performClick()
        ui.onNodeWithText("削除").performScrollTo().performClick()
        ui.onNodeWithText("キャンセル").performClick()
        ui.runOnIdle { assertFalse(removed) }
        ui.onNodeWithText("削除").performClick()
        ui.onNodeWithText("削除する").performClick()
        ui.runOnIdle { assertTrue(removed) }
    }
}
