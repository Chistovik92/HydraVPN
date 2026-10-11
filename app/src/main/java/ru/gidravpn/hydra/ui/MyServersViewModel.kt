package ru.gidravpn.hydra.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import ru.gidravpn.hydra.BuildConfig
import ru.gidravpn.hydra.servers.AwgBundleLoader
import ru.gidravpn.hydra.servers.MyServer
import ru.gidravpn.hydra.servers.MyServersManager
import java.io.File

/**
 * «Мои серверы» (0.8.0): мастер развёртывания и управление по SSH. Вся логика — в общем [MyServersManager] (:shared),
 * тот же, что на ПК; здесь только файл со списком (приватный каталог приложения) и добавление клиентского .conf в «Серверы».
 * Пароль/ключ SSH на диск не попадают; в файле — отпечаток хоста и ключи AmneziaWG (без них нельзя добавить клиента).
 */
class MyServersViewModel(app: Application) : AndroidViewModel(app) {
    private val file = File(app.filesDir, "my_servers.json")

    private val _servers = MutableStateFlow(
        runBlocking(Dispatchers.IO) { if (file.isFile) MyServer.listFromJson(file.readText()) else emptyList() },
    )
    val servers: StateFlow<List<MyServer>> = _servers

    /** Выставляется экраном: добавить текст .conf как обычный сервер. */
    @Volatile var importConf: (name: String, conf: String) -> Unit = { _, _ -> }

    val manager = MyServersManager(
        viewModelScope,
        servers = { _servers.value },
        save = { list ->
            _servers.value = list
            viewModelScope.launch(Dispatchers.IO) {
                runCatching {
                    val tmp = File(file.parentFile, file.name + ".tmp")
                    tmp.writeText(MyServer.listToJson(list))
                    if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
                }
            }
        },
        bundleFor = { arch ->
            // Только в debug-сборке: пакет из каталога приложения (проверка без опубликованного релиза).
            val dev = File(app.filesDir, "awg-bundle/$arch")
            if (BuildConfig.DEBUG && dev.isDirectory) AwgBundleLoader.fromDir(arch, dev)
            else AwgBundleLoader.download(BuildConfig.VERSION_NAME.substringBefore('-'), arch)
        },
        importConf = { name, conf -> importConf(name, conf) },
    )
}
