package ru.gidravpn.hydra.desktop.core

import ru.gidravpn.hydra.servers.CredentialVault
import ru.gidravpn.hydra.servers.FileVault
import ru.gidravpn.hydra.servers.KeyFileCipher
import ru.gidravpn.hydra.servers.VaultCipher
import java.io.File

/**
 * Данные входа «Моих серверов» на ПК (0.8.0-rc4). Windows — DPAPI учётной записи (расшифровать может только этот пользователь
 * на этом компьютере); Linux/macOS — AES-GCM ключом из файла профиля с правами 0600.
 */
object DesktopVault {
    fun create(dir: File): CredentialVault {
        val cipher: VaultCipher = if (System.getProperty("os.name").orEmpty().startsWith("Windows") && dpapiWorks()) Dpapi
            else KeyFileCipher(File(dir, "servers-login.key"))
        return FileVault(File(dir, "servers-login.dat"), cipher)
    }

    private object Dpapi : VaultCipher {
        private val entropy = "HydraVPN/my-servers".toByteArray()
        override fun encrypt(plain: ByteArray): ByteArray =
            com.sun.jna.platform.win32.Crypt32Util.cryptProtectData(plain, entropy, 0, "Hydra", null)
        override fun decrypt(data: ByteArray): ByteArray =
            com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(data, entropy, 0, null)
    }

    private fun dpapiWorks() = runCatching { Dpapi.decrypt(Dpapi.encrypt(byteArrayOf(1, 2, 3))).contentEquals(byteArrayOf(1, 2, 3)) }.getOrDefault(false)
}
