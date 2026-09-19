package ru.gidravpn.hydra.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.gidravpn.hydra.data.model.EngineToggles

/**
 * Настройки движков (Настройки → Туннель).
 *
 *  - [preferXray] — для VLESS/VMess/Trojan/SS использовать Xray-core вместо sing-box
 *    (если Xray собран: `BuildConfig.XRAY_AVAILABLE`), ради его реализации XTLS;
 *  - [toggles] — включено ли каждое ядро (0.6.22). По умолчанию включены все; выключенное
 *    ядро не поднимает туннель, а его серверы помечаются в списке.
 */
class EngineRepository(private val context: Context) {

    private val KEY_PREFER_XRAY = booleanPreferencesKey("prefer_xray")
    private val KEY_SINGBOX = booleanPreferencesKey("engine_singbox")
    private val KEY_XRAY = booleanPreferencesKey("engine_xray")
    private val KEY_AWG = booleanPreferencesKey("engine_awg")
    private val KEY_PPP = booleanPreferencesKey("engine_ppp")
    private val KEY_OLCRTC = booleanPreferencesKey("engine_olcrtc")
    private val KEY_OPENFLUX = booleanPreferencesKey("engine_openflux")

    val preferXray: Flow<Boolean> = context.engineStore.data.map { it[KEY_PREFER_XRAY] == true }

    suspend fun setPreferXray(enabled: Boolean) {
        context.engineStore.edit { it[KEY_PREFER_XRAY] = enabled }
    }

    val toggles: Flow<EngineToggles> = context.engineStore.data.map { p ->
        EngineToggles(
            singBox = p[KEY_SINGBOX] != false,
            xray = p[KEY_XRAY] != false,
            amneziaWg = p[KEY_AWG] != false,
            ppp = p[KEY_PPP] != false,
            olcRtc = p[KEY_OLCRTC] != false,
            openFlux = p[KEY_OPENFLUX] != false,
            preferXray = p[KEY_PREFER_XRAY] == true,
        )
    }

    suspend fun setEnabled(engine: EngineToggles.Kind, enabled: Boolean) {
        val key = when (engine) {
            EngineToggles.Kind.SINGBOX -> KEY_SINGBOX
            EngineToggles.Kind.XRAY -> KEY_XRAY
            EngineToggles.Kind.AWG -> KEY_AWG
            EngineToggles.Kind.PPP -> KEY_PPP
            EngineToggles.Kind.OLCRTC -> KEY_OLCRTC
            EngineToggles.Kind.OPENFLUX -> KEY_OPENFLUX
        }
        context.engineStore.edit { it[key] = enabled }
    }
}
