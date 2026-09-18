package ru.gidravpn.hydra.vpn.core

import android.net.DnsResolver
import android.net.Network
import android.os.Build
import android.os.CancellationSignal
import android.system.ErrnoException
import androidx.annotation.RequiresApi
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.LocalDNSTransport
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * DNS-сервер `type: local` sing-box на Android резолвит ТОЛЬКО через этот
 * интерфейс платформы («there is no other way to obtain upstream DNS servers»,
 * sing-box docs) — с `localDNSTransport() = null` пресет «Системный резолвер»
 * просто не отвечал бы. Повторяет LocalResolver из SagerNet/sing-box-for-android.
 *
 * [network] — реальная подложенная сеть от монитора интерфейса; null — сеть
 * процесса по умолчанию, что тоже не туннель (наш пакет исключён из VPN в
 * HydraVpnService.establishTun).
 */
internal class HydraLocalDns(private val network: () -> Network?) : LocalDNSTransport {

    private val executor = Executors.newCachedThreadPool()

    override fun raw(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun exchange(ctx: ExchangeContext, message: ByteArray) {
        val done = CountDownLatch(1)
        var failure: Exception? = null
        val signal = CancellationSignal()
        ctx.onCancel { signal.cancel() }
        DnsResolver.getInstance().rawQuery(
            network(), message, DnsResolver.FLAG_NO_RETRY, executor, signal,
            object : DnsResolver.Callback<ByteArray> {
                override fun onAnswer(answer: ByteArray, rcode: Int) {
                    if (rcode == 0) ctx.rawSuccess(answer) else ctx.errorCode(rcode)
                    done.countDown()
                }

                override fun onError(error: DnsResolver.DnsException) {
                    val cause = error.cause
                    if (cause is ErrnoException) ctx.errnoCode(cause.errno) else failure = error
                    done.countDown()
                }
            },
        )
        awaitOrThrow(done, signal)
        failure?.let { throw it }
    }

    override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
        // Вызывается только при raw() == false, т.е. на Android 8–9.
        val addresses = InetAddress.getAllByName(domain).filter {
            when {
                network.endsWith("4") -> it is Inet4Address
                network.endsWith("6") -> it is Inet6Address
                else -> true
            }
        }
        if (addresses.isEmpty()) ctx.errorCode(RCODE_NXDOMAIN)
        else ctx.success(addresses.mapNotNull { it.hostAddress }.joinToString("\n"))
    }

    private fun awaitOrThrow(done: CountDownLatch, signal: CancellationSignal) {
        if (!done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            signal.cancel()
            throw java.io.IOException("local DNS: нет ответа за ${TIMEOUT_SECONDS}с")
        }
    }

    private companion object {
        const val RCODE_NXDOMAIN = 3
        const val TIMEOUT_SECONDS = 10L
    }
}
