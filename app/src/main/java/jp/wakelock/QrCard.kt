package jp.wakelock

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.security.SecureRandom
import java.util.Base64

private val random = SecureRandom()
internal val Place.pngName get() = "$wire.png"

internal fun WakeState.qrPayload(place: Place): String {
    if (!registered(place)) throw InputError("この地点は未登録です。")
    return "wakelock:v1:${place.wire}:${token(place)}"
}

// Called inside the DataStore edit. A repeated or stale confirmation must not rotate the token.
internal fun WakeState.issueQr(place: Place, expectedToken: String): WakeState {
    if (phase.active) throw InputError("起床確認中はQRコードを変更できません。")
    if (token(place) != expectedToken) throw InputError("登録状態が変わりました。もう一度確認してください。")
    val bytes = ByteArray(16).also(random::nextBytes)
    val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    return register(place, "wakelock:v1:${place.wire}:$token")
}

internal class QrCard(place: Place, payload: String) {
    val title = place.title
    val filename = place.pngName
    val modules: BitMatrix
    init {
        readToken(payload, place)
        modules = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
    }
    val moduleSize = 24
    val qrOffset = 48
    val qrBottom = qrOffset + modules.height * moduleSize
    val width = qrBottom + qrOffset
    val titleBaseline = qrBottom + 52
    val filenameBaseline = titleBaseline + 54
    val height = filenameBaseline + 48
    // PDF coordinates are points (72 per inch). Exclude the eight quiet-zone modules.
    val printScale = (50f * 72 / 25.4f) / ((modules.width - 8) * moduleSize)
}
