package ru.gidravpn.hydra.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.ManagedActivityResultLauncher
import ru.gidravpn.hydra.R

/**
 * Запуск системного окна (выбор файла, фото) там, где его может не быть.
 *
 * Android TV и урезанные прошивки приставок не содержат DocumentsUI и фото-пикера: прямой
 * `launch` падал бы с ActivityNotFoundException и закрывал приложение. Вместо падения —
 * пояснение, что делать (вставить ссылку из буфера обмена).
 */
fun <I> ManagedActivityResultLauncher<I, *>.launchSafe(ctx: Context, input: I) {
    try {
        launch(input)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(ctx, R.string.no_system_picker, Toast.LENGTH_LONG).show()
    } catch (_: SecurityException) {
        Toast.makeText(ctx, R.string.no_system_picker, Toast.LENGTH_LONG).show()
    }
}

/** Сканер QR: на приставках без камеры (большинство Android TV) вместо пустого экрана — подсказка. */
fun <I> ManagedActivityResultLauncher<I, *>.launchScan(ctx: Context, input: I) {
    val pm = ctx.packageManager
    val hasCamera = pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) || pm.hasSystemFeature(PackageManager.FEATURE_CAMERA)
    if (!hasCamera) {
        Toast.makeText(ctx, R.string.no_camera, Toast.LENGTH_LONG).show()
        return
    }
    launchSafe(ctx, input)
}
