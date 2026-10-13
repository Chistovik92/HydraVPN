package ru.gidravpn.hydra.servers

/**
 * Локальный прокси ядра, через который идёт SSH к «Моим серверам» (0.8.0-rc3). 0 — напрямую.
 * ПК в режиме «прокси» выставляет порт, пока ядро поднято; на Android и в режиме TUN трафик Hydra и так идёт в туннель, порт не нужен.
 */
object AppProxy {
    @Volatile var port: Int = 0
}
