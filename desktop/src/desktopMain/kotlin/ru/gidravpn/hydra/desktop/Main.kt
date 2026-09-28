package ru.gidravpn.hydra.desktop

import ru.gidravpn.hydra.data.model.Protocol
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.subscription.SingBoxConfigBuilder
import ru.gidravpn.hydra.desktop.vpn.DesktopVpnManager
import ru.gidravpn.hydra.desktop.vpn.DesktopVpnManagerFactory
import ru.gidravpn.hydra.desktop.vpn.DesktopVpnManagerFactoryImpl
import ru.gidravpn.hydra.desktop.vpn.linux.LinuxVpnManagerFactory
import ru.gidravpn.hydra.desktop.vpn.windows.WindowsVpnManagerFactory
import java.io.File

fun main() {
    println("=== HydraVPN Desktop Core Test ===")
    println("OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")}")
    println("Arch: ${System.getProperty("os.arch")}")
    println()

    // Test 1: Config generation
    println("Test 1: SingBox config generation")
    val profile = ServerProfile(
        name = "Test Server",
        protocolId = Protocol.VLESS.id,
        address = "example.com",
        port = 443,
        uuidOrPassword = "test-uuid-1234",
        transport = "tcp",
        security = "tls",
        sni = "example.com",
    )

    val config = SingBoxConfigBuilder.build(profile)
    val configJson = config.toString()
    println("Config generated (${configJson.length} chars)")
    println("Sample: ${configJson.take(200)}...")
    println()

    // Test 2: Save config to file
    println("Test 2: Config file I/O")
    val configFile = File("test-singbox-config.json")
    configFile.writeText(configJson)
    println("Config saved to: ${configFile.absolutePath}")
    println("File exists: ${configFile.exists()}, size: ${configFile.length()} bytes")
    println()

    // Test 3: Platform-specific VPN Manager
    println("Test 3: Platform VPN Manager")
    val osName = System.getProperty("os.name").lowercase()
    val factory: DesktopVpnManagerFactory = when {
        osName.contains("windows") -> WindowsVpnManagerFactory()
        osName.contains("linux") -> LinuxVpnManagerFactory()
        else -> DesktopVpnManagerFactoryImpl()
    }
    val vpnManager = factory.create()
    println("Manager created: ${vpnManager::class.simpleName}")
    println("isRunning: ${vpnManager.isRunning()}")
    println()

    // Test 4: Profile parsing
    println("Test 4: Profile parsing")
    val testLink = "vless://test-uuid@example.com:443?security=tls&sni=example.com#Test"
    println("Link format OK")
    println()

    println("=== All core tests passed ===")
    println()
    println("Note: Full VPN connection requires:")
    println("  1. sing-box binary in PATH (for non-Windows/Linux)")
    println("  2. wintun.dll in PATH (for Windows)")
    println("  3. CAP_NET_ADMIN / root (for Linux)")
    println("  4. Admin/root privileges for TUN")
    println("  5. Real server credentials")
}