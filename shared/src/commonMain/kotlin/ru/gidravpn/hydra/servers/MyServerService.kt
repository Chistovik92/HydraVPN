package ru.gidravpn.hydra.servers

import java.util.UUID

/** Итог операции над сервером. */
sealed interface DeployResult {
    data class Ok(val server: MyServer) : DeployResult
    data class Failed(val reason: String) : DeployResult
}

/**
 * Сценарии «Моих серверов» поверх SSH-сеанса: проверка, развёртывание AmneziaWG, клиенты, удаление.
 * Не знает ни об интерфейсе, ни о хранении — вызывающий передаёт сеанс и сохраняет результат.
 */
class MyServerService(private val bundleFor: (arch: String) -> AwgBundle) {

    fun preflight(ssh: SshSession, port: Int): Preflight = PreflightCheck.parse(ssh.exec(PreflightCheck.command(port)).out)

    /** Шаги, которые будут выполнены, — для показа до запуска. */
    fun planPreview(spec: AwgServerSpec, pre: Preflight, openFirewall: Boolean): List<Step> =
        AwgInstallPlan.steps(spec, emptyList(), AwgBundle(pre.arch, emptyMap<String, BundleFile>().withDummy()), openFirewall, pre.firewall)

    fun deployAwg(
        ssh: SshSession, target: SshTarget, fingerprint: String, name: String,
        generation: AwgGeneration, port: Int, openFirewall: Boolean, pre: Preflight,
        installer: (SshSession, Collection<String>) -> Installer,
    ): DeployResult {
        val spec = AwgServerSpec.create(target.host, port, generation)
        val first = AwgServerSpec.newClient("Hydra", spec.nextAddress(emptyList())!!)
        val bundle = try { bundleFor(pre.arch) } catch (e: Exception) { return DeployResult.Failed(e.message ?: "Не удалось получить серверный пакет") }
        val inst = installer(ssh, listOf(spec.privateKey, first.privateKey, first.presharedKey, target.password.orEmpty(), target.privateKey.orEmpty()))
        val ok = inst.run(AwgInstallPlan.steps(spec, listOf(first), bundle, openFirewall, pre.firewall))
        if (!ok) return DeployResult.Failed("Установка остановлена")
        return DeployResult.Ok(MyServer(UUID.randomUUID().toString(), name, target.host, target.port, target.user, fingerprint, spec, listOf(first)))
    }

    fun addClient(ssh: SshSession, s: MyServer, name: String, installer: Installer): Pair<MyServer, AwgClient>? {
        val spec = s.awg ?: return null
        val addr = spec.nextAddress(s.clients.map { it.address }) ?: return null
        val c = AwgServerSpec.newClient(name, addr)
        val all = s.clients + c
        if (!installer.run(AwgInstallPlan.applyClientsSteps(spec, all))) return null
        return s.copy(clients = all) to c
    }

    fun removeClient(s: MyServer, name: String, installer: Installer): MyServer? {
        val spec = s.awg ?: return null
        val all = s.clients.filterNot { it.name == name }
        if (!installer.run(AwgInstallPlan.applyClientsSteps(spec, all))) return null
        return s.copy(clients = all)
    }

    fun uninstall(s: MyServer, installer: Installer): Boolean = s.awg?.let { installer.run(AwgInstallPlan.uninstallSteps(it)) } ?: false

    private fun Map<String, BundleFile>.withDummy(): Map<String, BundleFile> =
        listOf("amneziawg-go", "awg", "awg-quick").associateWith { BundleFile(it, ByteArray(0), "") }
}
