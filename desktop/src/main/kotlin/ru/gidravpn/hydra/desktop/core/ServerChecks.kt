package ru.gidravpn.hydra.desktop.core

import ru.gidravpn.hydra.data.model.Engine
import ru.gidravpn.hydra.data.model.ServerProfile

/**
 * Годится ли профиль для хранения и показа: у olcRTC (комната) и OpenFlux (адрес документа/токен MAX) порта нет —
 * для них порт 0 нормален; у остальных адрес и порт 1..65535 обязательны.
 */
val ServerProfile.hasValidEndpoint: Boolean
    get() = when (protocol?.engine) {
        Engine.OLCRTC -> address.isNotBlank()
        Engine.BYEDPI -> true   // локальный прокси: ни адреса, ни порта сервера нет
        Engine.OPENFLUX -> transport == "oneme" || transport == "cupsonline" || address.isNotBlank()
        else -> address.isNotBlank() && port in 1..65535
    }
