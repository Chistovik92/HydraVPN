package ru.gidravpn.hydra.vpn.core.xray;

import android.os.ParcelFileDescriptor;

/**
 * Реализуется в главном процессе (HydraVpnService держит активный VpnService)
 * и передаётся в процесс :xray — Xray-core (libXray.aar) не может protect()
 * сам, у него нет доступа к VpnService. См. docs/PROTOCOLS.md, раздел «Xray».
 */
interface IXraySocketProtector {
    boolean protect(in ParcelFileDescriptor pfd);
}
