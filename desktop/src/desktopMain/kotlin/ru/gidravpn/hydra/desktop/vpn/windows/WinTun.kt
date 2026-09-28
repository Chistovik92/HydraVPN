package ru.gidravpn.hydra.desktop.vpn.windows

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.WinDef.DWORD
import com.sun.jna.platform.win32.WinDef.LONG
import com.sun.jna.platform.win32.WinNT.HANDLE
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.io.File

/**
 * JNA interface for WinTun (Windows TUN driver).
 * WinTun docs: https://www.wintun.net/
 * Requires wintun.dll from https://gitlab.com/wintun/wintun
 */
interface WinTunLibrary : com.sun.jna.Library {
    fun WintunCreateAdapter(adapterName: WString, tunnelType: WString, requestedGUID: Pointer?): HANDLE?
    fun WintunDeleteAdapter(adapter: HANDLE?): Void
    fun WintunStartSession(adapter: HANDLE?, capacity: DWORD): HANDLE?
    fun WintunEndSession(session: HANDLE?): Void
    fun WintunAllocateSendPacket(session: HANDLE?, size: IntByReference): Pointer?
    fun WintunSendPacket(session: HANDLE?, packet: Pointer?): Void
    fun WintunReceivePacket(session: HANDLE?, size: IntByReference, waitForPacketEvent: HANDLE?): Pointer?
    fun WintunReleaseReceivePacket(session: HANDLE?, packet: Pointer?): Void
    fun WintunGetAdapterLUID(adapter: HANDLE?): LONG
    fun WintunGetLastError(): DWORD
    fun WintunSetLogger(logger: Pointer?): Void
    fun WintunGetRunningDriverVersion(): DWORD
    fun WintunCreateAdapterEx(adapterName: WString, tunnelType: WString, requestedGUID: PointerByReference?): HANDLE?

    companion object {
        val INSTANCE: WinTunLibrary = Native.load("wintun", WinTunLibrary::class.java)
    }
}

/**
 * Windows IP Helper API for route management
 */
interface IpHelperLibrary : com.sun.jna.Library {
    fun CreateIpForwardEntry(pRoute: Pointer): Int
    fun DeleteIpForwardEntry(pRoute: Pointer): Int
    fun SetIpForwardEntry(pRoute: Pointer): Int
    fun GetIpForwardTable(pIpForwardTable: Pointer, pdwSize: IntByReference, bOrder: Boolean): Int
    fun GetBestInterface(dwDestAddr: Int, pdwBestIfIndex: IntByReference): Int
    fun GetBestRoute(dwDestAddr: Int, dwSourceAddr: Int, pBestRoute: Pointer): Int
    fun GetAdaptersInfo(pAdapterInfo: Pointer, pOutBufLen: IntByReference): Int
    fun GetAdaptersAddresses(Family: Int, Flags: Int, Reserved: Pointer, AdapterAddresses: Pointer, SizePointer: IntByReference): Int

    companion object {
        val INSTANCE: IpHelperLibrary = Native.load("iphlpapi", IpHelperLibrary::class.java)
    }
}

/**
 * MIB_IPFORWARDROW structure for IPv4 route table
 */
@Structure.FieldOrder("dwForwardDest", "dwForwardMask", "dwForwardPolicy", "dwForwardNextHop", "dwForwardIfIndex", "dwForwardType", "dwForwardProto", "dwForwardAge", "dwForwardNextHopAS", "dwForwardMetric1", "dwForwardMetric2", "dwForwardMetric3", "dwForwardMetric4", "dwForwardMetric5")
class MIB_IPFORWARDROW : Structure() {
    @JvmField var dwForwardDest: Int = 0
    @JvmField var dwForwardMask: Int = 0
    @JvmField var dwForwardPolicy: Int = 0
    @JvmField var dwForwardNextHop: Int = 0
    @JvmField var dwForwardIfIndex: Int = 0
    @JvmField var dwForwardType: Int = 0
    @JvmField var dwForwardProto: Int = 0
    @JvmField var dwForwardAge: Int = 0
    @JvmField var dwForwardNextHopAS: Int = 0
    @JvmField var dwForwardMetric1: Int = 0
    @JvmField var dwForwardMetric2: Int = 0
    @JvmField var dwForwardMetric3: Int = 0
    @JvmField var dwForwardMetric4: Int = 0
    @JvmField var dwForwardMetric5: Int = 0
}

/**
 * WinTun GUIDs
 */
object WinTunGuids {
    const val WINTUN_TUNNEL_TYPE = "B69C3308-0D7F-4E0B-8A3B-9C8F6E4D2A1B"
}

/**
 * WinTun loader with automatic DLL discovery
 */
object WinTunLoader {
    private var loaded = false
    private var driverVersion: Int = 0

    fun load(): Boolean {
        if (loaded) return true
        
        val appDir = File(".").absoluteFile.parentFile
        val arch = System.getProperty("os.arch").lowercase()
        val dllName = "wintun.dll"
        
        val searchPaths = mutableListOf<File>()
        searchPaths.add(File(appDir, dllName))
        searchPaths.add(File(appDir, "runtimes/win-${if (arch.contains("64")) "x64" else "x86"}/native/$dllName"))
        
        System.getenv("PATH")?.split(";")?.forEach { searchPaths.add(File(it, dllName)) }
        
        for (dll in searchPaths) {
            if (dll.exists()) {
                try {
                    System.load(dll.absolutePath)
                    loaded = true
                    driverVersion = WinTunLibrary.INSTANCE.WintunGetRunningDriverVersion().toInt()
                    println("[WinTun] Loaded from: ${dll.absolutePath}, driver version: $driverVersion")
                    return true
                } catch (e: Exception) {
                    println("[WinTun] Failed to load ${dll.absolutePath}: ${e.message}")
                }
            }
        }
        
        try {
            System.loadLibrary("wintun")
            loaded = true
            driverVersion = WinTunLibrary.INSTANCE.WintunGetRunningDriverVersion().toInt()
            println("[WinTun] Loaded from system PATH, driver version: $driverVersion")
            return true
        } catch (e: Exception) {
            println("[WinTun] Failed to load from system: ${e.message}")
        }
        
        return false
    }
    
    fun isLoaded(): Boolean = loaded
    fun getDriverVersion(): Int = driverVersion
}