package ru.gidravpn.hydra.vpn.core.xray;

import ru.gidravpn.hydra.vpn.core.xray.IXraySocketProtector;

/**
 * Контракт с процессом :xray (XrayEngineService, app/src/nativeXrayReal/).
 * Только Android-типы через границу (String/ParcelFileDescriptor/boolean) —
 * без Go/gomobile-типов, т.к. libbox (главный процесс) и libXray (:xray)
 * не могут делить один процесс (см. docs/BUILD.md, раздел 2.2).
 */
interface IXrayEngine {
    void setProtector(IXraySocketProtector protector);
    /** Возвращает сырой JSON-ответ LibXray.invoke() ({"success":..,"error":..}). */
    String runXray(String xrayJson);
    String stopXray();
}
