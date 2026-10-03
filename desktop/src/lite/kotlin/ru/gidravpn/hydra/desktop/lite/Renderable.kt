package ru.gidravpn.hydra.desktop.lite

import ru.gidravpn.hydra.desktop.UiState

/** Вкладка, которая умеет перерисоваться по новому состоянию. */
internal interface Renderable { fun render(ui: UiState) }
