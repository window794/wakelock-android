package jp.wakelock

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.*
import android.print.pdf.PrintedPdfDocument
import kotlinx.coroutines.*

internal fun printQr(activity: Activity, card: QrCard, image: Bitmap) {
    val manager = activity.getSystemService(PrintManager::class.java)
        ?: throw InputError("この端末では印刷を利用できません。")
    val defaults = PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build()
    manager.print("WakeLock ${card.title}", QrPrintAdapter(activity, card, image), defaults)
        ?: throw InputError("印刷サービスを利用できません。")
}

private class QrPrintAdapter(
    private val context: Context, private val card: QrCard, private val image: Bitmap
) : PrintDocumentAdapter() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var attributes: PrintAttributes

    override fun onLayout(old: PrintAttributes?, new: PrintAttributes, signal: CancellationSignal,
                          callback: LayoutResultCallback, extras: Bundle?) {
        if (signal.isCanceled) {
            callback.onLayoutCancelled()
            return
        }
        attributes = new
        val info = PrintDocumentInfo.Builder(card.filename.removeSuffix(".png") + ".pdf")
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(1).build()
        callback.onLayoutFinished(info, old != new)
    }

    override fun onWrite(ranges: Array<out PageRange>, target: ParcelFileDescriptor,
                         signal: CancellationSignal, callback: WriteResultCallback) {
        val settings = attributes
        scope.launch {
            try {
                val included = ranges.any { 0 in it.start..it.end }
                withContext(Dispatchers.IO) {
                    ParcelFileDescriptor.AutoCloseOutputStream(target).use { out ->
                        if (!included || signal.isCanceled) return@use
                        card.writePage(context, settings, image, out) { signal.isCanceled }
                    }
                }
                if (signal.isCanceled) callback.onWriteCancelled()
                else callback.onWriteFinished(if (included) arrayOf(PageRange(0, 0)) else emptyArray())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (signal.isCanceled) callback.onWriteCancelled()
                else callback.onWriteFailed("印刷データを作成できませんでした。")
            }
        }
    }

    override fun onFinish() {
        scope.cancel()
        super.onFinish()
    }
}

internal fun QrCard.writePage(context: Context, settings: PrintAttributes, image: Bitmap,
                              out: java.io.OutputStream, cancelled: () -> Boolean) {
    val pdf = PrintedPdfDocument(context, settings)
    try {
        val page = pdf.startPage(0)
        try {
            val area = pdf.pageContentRect
            val scale = minOf(printScale, area.width().toFloat() / image.width,
                area.height().toFloat() / image.height)
            val width = image.width * scale
            val height = image.height * scale
            val x = (area.width() - width) / 2
            val y = (area.height() - height) / 2
            page.canvas.drawBitmap(image, null, RectF(x, y, x + width, y + height), null)
        } finally {
            pdf.finishPage(page)
        }
        if (!cancelled()) pdf.writeTo(out)
    } finally {
        pdf.close()
    }
}
