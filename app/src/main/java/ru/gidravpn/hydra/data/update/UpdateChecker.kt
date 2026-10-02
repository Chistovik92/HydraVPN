package ru.gidravpn.hydra.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.gidravpn.hydra.update.ReleaseAsset
import ru.gidravpn.hydra.update.UpdateFeed

/**
 * Проверка обновлений — последний релиз на GitHub, основном канале распространения. С 0.7.1 приложение
 * само скачивает APK (SHA-256 сверяется) и отдаёт его системному установщику — см. [AppUpdater]; поиск релиза
 * и загрузка общие с ПК-версией (пакет `ru.gidravpn.hydra.update` из :shared).
 */
object UpdateChecker {
    const val REPO = UpdateFeed.REPO

    /** [apk] — файл под эту сборку (full или stub); null — в релизе его нет, тогда только страница. */
    data class Release(val version: String, val pageUrl: String, val apk: ReleaseAsset?) {
        val apkUrl: String? get() = apk?.url
    }

    suspend fun latest(): Release = withContext(Dispatchers.IO) {
        parse(UpdateFeed.latest("Hydra-VPN-update-check"), stub = ru.gidravpn.hydra.BuildConfig.FLAVOR == "stub")
    }

    internal fun parse(json: String, stub: Boolean = false, abis: List<String> = emptyList()): Release = parse(UpdateFeed.parse(json), stub, abis)

    private fun parse(info: ru.gidravpn.hydra.update.ReleaseInfo, stub: Boolean, abis: List<String> = android.os.Build.SUPPORTED_ABIS.toList()) =
        Release(info.version, info.pageUrl, UpdateFeed.pickAndroid(info, stub, abis))

    /** true, если [remote] новее [current] («0.6.23» > «0.6.22.2»); суффикс «-stub» не мешает. */
    fun isNewer(remote: String, current: String): Boolean = UpdateFeed.isNewer(remote, current)
}
