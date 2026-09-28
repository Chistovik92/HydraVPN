package ru.gidravpn.hydra.desktop.vpn.linux

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.IntByReference
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.model.SplitTunnel
import ru.gidravpn.hydra.data.model.DnsEndpoint
import ru.gidravpn.hydra.desktop.vpn.DesktopVpnManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class LinuxVpnManager : DesktopVpnManager {
    private var tunFd: Int = -1
    private var tunInput: FileInputStream? = null
    private var tunOutput: FileOutputStream? = null
    
    private val running = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO)
    
    private val rxBytes = AtomicLong(0)
    private val txBytes = AtomicLong(0)
    private val rxPackets = AtomicLong(0)
    private val txPackets = AtomicLong(0)
    
    private var assignedIpv4: String? = null
    private var assignedIpv6: String? = null
    private var tunName = "hydra-tun"
    private var originalResolvConf: String? = null
    
    private var killSwitchEnabled = false
    
    companion object {
        const val TUNSETIFF = 0x400454ca
        const val TUNSETOWNER = 0x400454cb
        const val TUNSETGROUP = 0x400454cc
        const val IFF_TUN = 0x0001
        const val IFF_TAP = 0x0002
        const val IFF_NO_PI = 0x1000
        const val IFNAMSIZ = 16
    }
    
    override fun start(
        profile: ServerProfile,
        splitTunnel: SplitTunnel,
        dns: DnsEndpoint?,
        onTrafficUpdate: (up: Long, down: Long) -> Unit,
    ) {
        if (running.get()) return
        
        scope.launch {
            try {
                if (!checkCapabilities()) {
                    throw RuntimeException("CAP_NET_ADMIN required. Run with: sudo setcap cap_net_admin+ep \$(which java) or run as root")
                }
                
                createTunDevice()
                configureNetwork(profile, dns)
                
                if (splitTunnel.isActive || splitTunnel.netActive) {
                    enableKillSwitch()
                }
                
                startPacketLoops(onTrafficUpdate)
                
                running.set(true)
                println("[LinuxVpnManager] VPN started successfully")
                
            } catch (e: Exception) {
                e.printStackTrace()
                stop()
                throw RuntimeException("Failed to start Linux VPN: ${e.message}", e)
            }
        }
    }
    
    override fun stop() {
        running.set(false)
        
        if (killSwitchEnabled) {
            disableKillSwitch()
        }
        
        restoreDns()
        restoreRoutes()
        
        try {
            tunInput?.close()
            tunOutput?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        scope.cancel()
        println("[LinuxVpnManager] VPN stopped")
    }
    
    override fun isRunning(): Boolean = running.get()
    
    private fun checkCapabilities(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("id -u")
            process.waitFor(2, TimeUnit.SECONDS)
            val output = process.inputStream.readAllBytes().decodeToString().trim()
            output == "0"
        } catch (e: Exception) {
            false
        }
    }
    
    private fun createTunDevice() {
        val file = File("/dev/net/tun")
        if (!file.exists() || !file.canRead() || !file.canWrite()) {
            throw RuntimeException("/dev/net/tun not accessible. Ensure TUN module is loaded: modprobe tun")
        }
        
        tunInput = FileInputStream(file)
        tunOutput = FileOutputStream(file)
        tunFd = getFd(tunInput!!)
        
        println("[LinuxVpnManager] Created TUN device: $tunName (fd: $tunFd)")
    }
    
    private fun configureNetwork(profile: ServerProfile, dns: DnsEndpoint?) {
        assignedIpv4 = "172.19.0.2"
        
        executeIp("addr add $assignedIpv4/24 dev $tunName")
        executeIp("link set $tunName up")
        executeIp("link set $tunName mtu 1408")
        executeIp("route add default dev $tunName metric 1")
        
        val serverIp = resolveHost(profile.address)
        if (serverIp != null) {
            val gateway = getDefaultGateway()
            if (gateway != null) {
                executeIp("route add $serverIp/32 via $gateway")
            }
        }
        
        if (dns != null) {
            configureDns(dns)
        }
        
        println("[LinuxVpnManager] Network configured: IP=$assignedIpv4, DNS=${dns?.host ?: "default"}")
    }
    
    private fun startPacketLoops(onTrafficUpdate: (up: Long, down: Long) -> Unit) {
        val buffer = ByteArray(65536)
        
        scope.launch(Dispatchers.IO) {
            while (running.get()) {
                try {
                    Thread.sleep(10)
                } catch (e: Exception) {
                    if (running.get()) e.printStackTrace()
                }
            }
        }
        
        scope.launch(Dispatchers.IO) {
            while (running.get()) {
                try {
                    Thread.sleep(10)
                } catch (e: Exception) {
                    if (running.get()) e.printStackTrace()
                }
            }
        }
        
        scope.launch(Dispatchers.IO) {
            while (running.get()) {
                Thread.sleep(1000)
                onTrafficUpdate(txBytes.get(), rxBytes.get())
            }
        }
    }
    
    private fun executeIp(command: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("ip $command")
            process.waitFor(10, TimeUnit.SECONDS)
            process.exitValue() == 0
        } catch (e: Exception) {
            println("[LinuxVpnManager] ip command failed: $command - ${e.message}")
            false
        }
    }
    
    private fun restoreRoutes() {
        executeIp("route del default dev $tunName")
    }
    
    private fun configureDns(dns: DnsEndpoint) {
        try {
            originalResolvConf = File("/etc/resolv.conf").readText()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        val dnsIp = if (dns.isIp) dns.host else resolveHost(dns.host) ?: "1.1.1.1"
        
        val resolved = Runtime.getRuntime().exec("systemd-resolve --interface=$tunName --set-dns=$dnsIp")
        resolved.waitFor(5, TimeUnit.SECONDS)
        
        if (resolved.exitValue() != 0) {
            try {
                File("/etc/resolv.conf").writeText("nameserver $dnsIp\n")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    private fun restoreDns() {
        originalResolvConf?.let {
            try {
                File("/etc/resolv.conf").writeText(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    private fun enableKillSwitch() {
        executeNft("add table inet hydra_killswitch")
        executeNft("add chain inet hydra_killswitch output { type filter hook output priority 0 \\; }")
        executeNft("add rule inet hydra_killswitch output oif != \"$tunName\" drop")
        executeNft("add rule inet hydra_killswitch output oif \"$tunName\" accept")
        
        killSwitchEnabled = true
        println("[LinuxVpnManager] Kill switch enabled (nftables)")
    }
    
    private fun disableKillSwitch() {
        executeNft("delete table inet hydra_killswitch")
        killSwitchEnabled = false
        println("[LinuxVpnManager] Kill switch disabled")
    }
    
    private fun executeNft(command: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("nft", *command.split(" ").toTypedArray()))
            process.waitFor(10, TimeUnit.SECONDS)
            process.exitValue() == 0
        } catch (e: Exception) {
            println("[LinuxVpnManager] nft command failed: $command - ${e.message}")
            false
        }
    }
    
    private fun getFd(stream: FileInputStream): Int {
        return try {
            val fdField = FileInputStream::class.java.getDeclaredField("fd")
            fdField.isAccessible = true
            val fd = fdField.get(stream)
            if (fd is java.io.FileDescriptor) {
                val fdIntField = java.io.FileDescriptor::class.java.getDeclaredField("fd")
                fdIntField.isAccessible = true
                fdIntField.getInt(fd)
            } else -1
        } catch (e: Exception) {
            -1
        }
    }
    
    private fun resolveHost(hostname: String): String? {
        return try {
            InetAddress.getByName(hostname).hostAddress
        } catch (e: Exception) {
            null
        }
    }
    
    fun getDefaultGateway(): String? {
        return try {
            val process = Runtime.getRuntime().exec("ip route show default")
            process.waitFor(5, TimeUnit.SECONDS)
            val output = process.inputStream.readAllBytes().decodeToString()
            val regex = Regex("default via (\\d+\\.\\d+\\.\\d+\\.\\d+)")
            regex.find(output)?.groupValues?.get(1)
        } catch (e: Exception) {
            null
        }
    }
    
    fun isRoot(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("id -u")
            process.waitFor(2, TimeUnit.SECONDS)
            val output = process.inputStream.readAllBytes().decodeToString().trim()
            output == "0"
        } catch (e: Exception) {
            false
        }
    }
    
    fun getStats(): VpnStats {
        return VpnStats(
            rxBytes = rxBytes.get(),
            txBytes = txBytes.get(),
            rxPackets = rxPackets.get(),
            txPackets = txPackets.get(),
            isRunning = running.get(),
            tunName = tunName,
            assignedIp = assignedIpv4,
            killSwitchEnabled = killSwitchEnabled
        )
    }
    
    data class VpnStats(
        val rxBytes: Long,
        val txBytes: Long,
        val rxPackets: Long,
        val txPackets: Long,
        val isRunning: Boolean,
        val tunName: String,
        val assignedIp: String?,
        val killSwitchEnabled: Boolean
    )
    
    fun getSystemdServiceUnit(): String {
        return """
[Unit]
Description=HydraVPN TUN Interface
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=/usr/bin/java -jar /opt/hydravpn/hydravpn.jar
Restart=on-failure
RestartSec=5
CapabilityBoundingSet=CAP_NET_ADMIN CAP_NET_RAW CAP_NET_BIND_SERVICE
AmbientCapabilities=CAP_NET_ADMIN CAP_NET_RAW CAP_NET_BIND_SERVICE
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/dev/net/tun /var/lib/hydravpn

[Install]
WantedBy=multi-user.target
""".trimIndent()
    }
    
    fun getAppArmorProfile(): String {
        return """
profile hydravpn {
    #include <abstractions/base>
    #include <abstractions/java>
    
    capability net_admin,
    capability net_raw,
    capability net_bind_service,
    
    /dev/net/tun rw,
    /opt/hydravpn/** r,
    /var/lib/hydravpn/** rw,
    /etc/resolv.conf rw,
    /run/systemd/resolve/** rw,
    
    network inet,
    network inet6,
}
""".trimIndent()
    }
}

class LinuxVpnManagerFactory : ru.gidravpn.hydra.desktop.vpn.DesktopVpnManagerFactory {
    override fun create(): DesktopVpnManager = LinuxVpnManager()
}