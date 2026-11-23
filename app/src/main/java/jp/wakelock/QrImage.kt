package jp.wakelock

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.*
import androidx.core.graphics.createBitmap
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.io.OutputStream

internal fun QrCard.bitmap(): Bitmap {
    val bitmap = createBitmap(width, height)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)
    val ink = Paint().apply { color = Color.BLACK }
    for (y in 0 until modules.height) {
        for (x in 0 until modules.width) {
            if (!modules[x, y]) continue
            val left = (qrOffset + x * moduleSize).toFloat()
            val top = (qrOffset + y * moduleSize).toFloat()
            canvas.drawRect(left, top, left + moduleSize, top + moduleSize, ink)
        }
    }
    ink.style = Paint.Style.STROKE
    ink.strokeWidth = 2f
    canvas.drawRect(12f, 12f, width - 12f, height - 12f, ink)
    ink.style = Paint.Style.FILL
    ink.isAntiAlias = true
    ink.textAlign = Paint.Align.CENTER
    ink.textSize = 52f
    canvas.drawText(title, width / 2f, titleBaseline.toFloat(), ink)
    ink.textSize = 32f
    canvas.drawText(filename, width / 2f, filenameBaseline.toFloat(), ink)
    return bitmap
}

internal fun writePng(open: () -> OutputStream?, encode: (OutputStream) -> Boolean) {
    val out = open() ?: throw IOException("保存先を開けませんでした。")
    out.use { if (!encode(it)) throw IOException("PNGを保存できませんでした。") }
}

internal fun Bitmap.savePng(open: () -> OutputStream?) =
    writePng(open) { compress(Bitmap.CompressFormat.PNG, 100, it) }

class QrShareProvider : FileProvider()

internal fun clearExpiredQrShares(context: Context) {
    val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
    File(context.cacheDir, "qr-share").listFiles()?.forEach {
        if (it.isFile && it.lastModified() < cutoff) it.delete()
    }
}

internal fun qrShareIntent(context: Context, place: Place, image: Bitmap): Intent {
    clearExpiredQrShares(context)
    val directory = File(context.cacheDir, "qr-share")
    if (!directory.isDirectory && !directory.mkdirs()) throw IOException()
    val file = File.createTempFile("qr-", ".png", directory)
    try {
        image.savePng { file.outputStream() }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.qrshare", file, place.pngName)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, place.pngName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "${place.title}のQRコードを共有")
    } catch (e: Exception) {
        file.delete()
        throw e
    }
}
