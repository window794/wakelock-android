package jp.wakelock

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.graphics.Color
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.datastore.preferences.core.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class QrDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val bath = Place.BATHROOM
    private val state = WakeState().issueQr(bath, "")

    @Test fun renderedPngDecodesWithCaptionsAndCutLine() {
        val payload = state.qrPayload(bath)
        val card = QrCard(bath, payload)
        val png = ByteArrayOutputStream()
        card.bitmap().savePng { png }
        val data = png.toByteArray()
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)))
        assertEquals(payload, decoded.text)
        assertEquals(Color.BLACK, bitmap.getPixel(12, bitmap.height / 2))
        assertEquals(Color.WHITE, bitmap.getPixel(24, bitmap.height / 2))
        for (baseline in listOf(card.titleBaseline, card.filenameBaseline)) {
            assertTrue((baseline - 32 until baseline).any { y ->
                (40 until bitmap.width - 40).any { x -> bitmap.getPixel(x, y) == Color.BLACK }
            })
        }
    }

    @Test fun a4PrintPageDecodesAsTheSameQrAtFiveCentimeters() {
        val payload = state.qrPayload(bath)
        val card = QrCard(bath, payload)
        val settings = PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setResolution(PrintAttributes.Resolution("test", "test", 300, 300))
            .setMinMargins(PrintAttributes.Margins(250, 250, 250, 250)).build()
        val file = File.createTempFile("print-test", ".pdf", compose.activity.cacheDir)
        try {
            file.outputStream().use { card.writePage(compose.activity, settings, card.bitmap(), it) { false } }
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            PdfRenderer(descriptor).use { renderer ->
                assertEquals(1, renderer.pageCount)
                renderer.openPage(0).use { page ->
                    val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    val binary = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels)))
                    val decoded = QRCodeReader().decode(binary)
                    assertEquals(payload, decoded.text)
                    // Horizontal distance between finder centers is seven modules shorter than the QR body.
                    val modules = card.modules.width - 8
                    val finderDistance = decoded.resultPoints[2].x - decoded.resultPoints[1].x
                    assertEquals(50f, finderDistance * modules / (modules - 7) * 25.4f / 144, 1f)
                }
            }
        } finally {
            file.delete()
        }
    }

    @Test fun sharedPngCanBeOpenedWithReadOnlyUri() {
        val context = compose.activity
        val image = QrCard(bath, state.qrPayload(bath)).bitmap()
        val chooser = qrShareIntent(context, bath, image)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals("image/png", send.type)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, send.flags)
        @Suppress("DEPRECATION")
        val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals(uri, send.clipData!!.getItemAt(0).uri)
        assertEquals(context.packageName + ".qrshare", uri.authority)
        context.contentResolver.openInputStream(uri)!!.use { assertNotNull(BitmapFactory.decodeStream(it)) }
    }

    @Test fun cancellingReissueKeepsCurrentCodeAndList() {
        val activity = compose.activity
        val vm = QrViewModel(activity.application)
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                QrManagement(activity, state, vm, scan = {}, home = {})
            }
        }
        compose.onNodeWithText("洗面所のQRコードを再発行").performScrollTo().performClick()
        compose.onNodeWithText("キャンセル").performClick()
        compose.onNodeWithText("再発行する").assertDoesNotExist()
        compose.onNodeWithText("洗面所のQRコードを表示").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("洗面所に設置する印刷用QRコード").fetchSemanticsNodes().size == 1
        }
        assertFalse(vm.busy)
        assertEquals("", vm.notice)
    }

    @Test fun diskStoreRestoresTokenAfterFailedTransactionAndReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, UUID.randomUUID().toString() + ".preferences_pb")
        suspend fun withStore(action: suspend (androidx.datastore.core.DataStore<Preferences>) -> Unit) {
            val job = SupervisorJob()
            try {
                action(PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { file })
            } finally {
                job.cancelAndJoin()
            }
        }
        try {
            withStore { store ->
                store.edit { WakeRecord.write(it, state) }
                try {
                    store.edit {
                        WakeRecord.write(it, state.issueQr(bath, state.bathroom))
                        throw IOException("simulated transaction failure")
                    }
                    fail("Expected transaction failure")
                } catch (_: IOException) { }
            }
            withStore { store -> assertEquals(state.qrPayload(bath), WakeRecord.read(store.data.first()).qrPayload(bath)) }
        } finally {
            file.delete()
        }
    }
}
