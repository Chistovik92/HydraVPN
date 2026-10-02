package ru.gidravpn.hydra.data.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.gidravpn.hydra.update.UpdateFeed
import ru.gidravpn.hydra.vpn.VpnState
import java.io.File

/**
 * Обновление приложения напрямую (0.7.1): APK скачивается с GitHub со сверкой SHA-256 и передаётся системному
 * установщику (PackageInstaller). Система сама проверит, что подпись та же, что у установленной Hydra, и спросит
 * подтверждение. Нужно разрешение «Установка неизвестных приложений» для Hydra — без него открывается его экран.
 */
object AppUpdater {
    enum class Result { INSTALLER_OPENED, NEEDS_PERMISSION, NO_FILE }

    private const val ACTION = "ru.gidravpn.hydra.UPDATE_INSTALL_RESULT"

    suspend fun downloadAndInstall(context: Context, release: UpdateChecker.Release, onProgress: (Float) -> Unit): Result {
        val apk = release.apk ?: return Result.NO_FILE
        val app = context.applicationContext
        if (!app.packageManager.canRequestPackageInstalls()) {
            app.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return Result.NEEDS_PERMISSION
        }
        val file = withContext(Dispatchers.IO) {
            val dir = File(app.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
            UpdateFeed.download(apk, File(dir, "Hydra-update.apk")) { done, total ->
                if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f)); true
            }
        }
        withContext(Dispatchers.IO) { commit(app, file) }
        return Result.INSTALLER_OPENED
    }

    private fun commit(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(context.packageName) }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("hydra.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(context, InstallResultReceiver::class.java).setAction(ACTION).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
    }

    /** Результат установки: системный диалог подтверждения открываем сами, ошибку пишем в журнал. */
    class InstallResultReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { context.startActivity(it) } }
                }
                PackageInstaller.STATUS_SUCCESS -> File(context.cacheDir, "updates").deleteRecursively()
                else -> VpnState.log("Ошибка: обновление не установлено (код $status): ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()}")
            }
        }
    }
}
