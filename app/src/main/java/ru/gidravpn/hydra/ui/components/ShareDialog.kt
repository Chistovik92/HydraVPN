package ru.gidravpn.hydra.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import ru.gidravpn.hydra.R
import ru.gidravpn.hydra.data.subscription.QrEncoder
import ru.gidravpn.hydra.ui.theme.AccentCyan
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import ru.gidravpn.hydra.ui.theme.Danger
import ru.gidravpn.hydra.ui.theme.Surface
import ru.gidravpn.hydra.ui.theme.TextMuted
import ru.gidravpn.hydra.ui.theme.TextPrimary
import ru.gidravpn.hydra.ui.theme.TextSecondary
import java.io.File

/**
 * «Поделиться» сервером или подпиской: QR-код и ссылка (0.6.18+). Отправка идёт
 * одним действием — картинка с QR и ссылка текстом, чтобы получатель мог и
 * отсканировать, и просто нажать.
 */
@Composable
fun ShareDialog(title: String, link: String, hideSecrets: Boolean = true, onDismiss: () -> Unit) {
    val context = LocalContext.current
    // «Скрывать ключи» (Фаза 8): на экране — ссылка без UUID/паролей/токенов, пока не нажать
    // «Показать». QR, копирование и отправка — всегда полная ссылка.
    var reveal by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(!hideSecrets) }
    val shown = if (reveal) link else ru.gidravpn.hydra.data.model.SecretMask.mask(link)
    val qr = remember(link) { QrEncoder.encode(link) }
    val copiedMsg = stringResource(R.string.hotspot_copied)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text(title, color = TextPrimary, fontSize = 16.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (qr != null) {
                    Image(
                        bitmap = qr.asImageBitmap(),
                        contentDescription = stringResource(R.string.share_qr_desc),
                        modifier = Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(12.dp))
                            .background(androidx.compose.ui.graphics.Color.White).padding(6.dp)
                    )
                } else {
                    Text(stringResource(R.string.share_too_long), color = Danger, fontSize = 12.sp)
                }
                Text(
                    shown.take(160) + if (shown.length > 160) "…" else "",
                    color = TextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().clickableNoRipple {
                        copy(context, link); Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
                    }
                )
                if (hideSecrets) Text(
                    stringResource(if (reveal) R.string.secret_hide else R.string.secret_show),
                    color = AccentCyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickableNoRipple { reveal = !reveal }.padding(vertical = 2.dp),
                )
                Text(stringResource(R.string.share_warning), color = TextMuted, fontSize = 11.sp)
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { copy(context, link); Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show() }) {
                    Text(stringResource(R.string.hotspot_copy), color = TextMuted)
                }
                TextButton(onClick = { share(context, title, link, qr); onDismiss() }) {
                    Text(stringResource(R.string.share_send), color = AccentCyan, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = TextMuted) } },
    )
}

private fun copy(context: Context, link: String) {
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText("hydra", link))
}

/** Один ACTION_SEND: картинка QR (если получилась) + ссылка текстом. */
private fun share(context: Context, title: String, link: String, qr: android.graphics.Bitmap?) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        putExtra(Intent.EXTRA_TEXT, link)
        putExtra(Intent.EXTRA_SUBJECT, title)
        type = "text/plain"
    }
    if (qr != null) {
        runCatching {
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            val file = File(dir, "hydra-qr.png")
            file.outputStream().use { qr.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            intent.type = "image/png"
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
