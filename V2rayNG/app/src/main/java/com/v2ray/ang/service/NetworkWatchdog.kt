package com.v2ray.ang.service

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.LogUtil
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Detects a tunnel that the OS still reports as connected while the core's sockets to the server
 * are dead.
 *
 * NetworkMonitor only reacts to a new usable network being announced. On cellular the carrier
 * keeps handing out a fresh IP/interface on the same Network object, so no callback fires, the
 * Xray connection is left pointing at an address that no longer exists, and the UI keeps showing
 * "Connected". Probing the local SOCKS inbound is the only signal that reflects that.
 *
 * Only used where a local SOCKS inbound exists (all modes except VPN). Started and stopped by
 * CoreServiceManager next to NetworkMonitor.
 */
class NetworkWatchdog(
    private val context: Context,
    private val socksPort: () -> Int,
    private val onDead: () -> Unit,
) {
    private companion object {
        // The event-driven NetworkMonitor covers network changes in about a second, so this is
        // only the safety net for what no callback reports: a dead core, a hung server, or DNS
        // that fails while Android still reports a working network. A minute between probes
        // keeps that net cheap.
        const val CHECK_INTERVAL_MS = 60_000L
        const val QUICK_RETRY_MS = 10_000L
        const val TIMEOUT_MS = 10_000

        // Waits back off while reloads keep failing, so a long outage (airplane mode, no signal)
        // does not restart the core over and over. A working probe resets it.
        const val MAX_RELOAD_DELAY_MS = 300_000L

        const val SETTING_INTERVAL_MS = "v2rayng_watchdog_interval_ms"

        // Plain android.util.Log rather than LogUtil: LogUtil can be filtered by the app's own
        // log level, and this line is the only trace that a reload was triggered.
        const val LOG_TAG = "NetworkWatchdog"

        // Probe through the tunnel, not on it: a domain the core has to resolve and an address
        // that is never served locally, so a working probe means DNS and egress both work.
        const val PROBE_HOST = "connect.rom.miui.com"
        const val PROBE_PORT = 80

        val PROBE_REQUEST =
            ("GET /generate_204 HTTP/1.1\r\n" +
                "Host: $PROBE_HOST\r\n" +
                "Connection: close\r\n\r\n").toByteArray()
    }

    @Volatile
    private var running = false

    private var worker: Thread? = null

    /** Starts watching. Safe to call more than once, only the first call spawns the thread. */
    @Synchronized
    fun start() {
        if (running) return
        running = true
        worker = thread(start = true, isDaemon = true, name = "v2rayng-network-watchdog") { loop() }
    }

    /** Stops watching. Safe to call more than once. */
    @Synchronized
    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
    }

    private fun loop() {
        // No direct-connectivity gate here: in root mode every socket of this uid is marked into
        // the tunnel by CORE_OUT, so a direct probe can never succeed and would disable the whole
        // watchdog. Backoff bounds the cost of reloading while the upstream is really down.
        var reloadDelay = checkInterval()
        try {
            while (running) {
                Thread.sleep(reloadDelay)
                if (!running) return
                if (probeTunnel()) {
                    reloadDelay = checkInterval()
                    continue
                }
                Thread.sleep(QUICK_RETRY_MS)
                if (!running) return
                if (probeTunnel()) {
                    reloadDelay = checkInterval()
                    continue
                }
                Log.i(LOG_TAG, "tunnel unresponsive (probe failed twice), reloading core")
                onDead()
                reloadDelay = (reloadDelay * 2).coerceAtMost(MAX_RELOAD_DELAY_MS)
            }
        } catch (e: InterruptedException) {
            // stop() during a sleep: nothing to do, the flag is already cleared.
        }
    }

    /**
     * Interval between probes. Read from Settings.Global so a check can be shortened for testing
     * with `adb shell settings put global v2rayng_watchdog_interval_ms 5000` without a rebuild.
     */
    private fun checkInterval(): Long {
        val value = Settings.Global.getLong(
            context.contentResolver,
            SETTING_INTERVAL_MS,
            CHECK_INTERVAL_MS,
        )
        return value.coerceIn(1_000L, MAX_RELOAD_DELAY_MS)
    }

    private fun probeTunnel(): Boolean = try {
        Socket().use { socket ->
            socket.soTimeout = TIMEOUT_MS
            socket.connect(InetSocketAddress(LOOPBACK, socksPort()), TIMEOUT_MS)
            val out = socket.getOutputStream()
            // SOCKS5 greeting: ver 5, one method, no auth.
            out.write(byteArrayOf(0x05, 0x01, 0x00))
            out.flush()
            val input = socket.getInputStream()
            val greeting = ByteArray(2)
            if (readFully(input, greeting) && greeting[0] == 0x05.toByte()) {
                val host = PROBE_HOST.toByteArray()
                val request = ByteArray(4 + 1 + host.size + 2)
                request[0] = 0x05
                request[1] = 0x01
                request[2] = 0x00
                request[3] = 0x03 // domain name, resolved by the core
                request[4] = host.size.toByte()
                host.copyInto(request, 5)
                request[request.size - 2] = ((PROBE_PORT shr 8) and 0xff).toByte()
                request[request.size - 1] = (PROBE_PORT and 0xff).toByte()
                out.write(request)
                out.flush()
                if (!readSocksReply(input)) return false

                // A SOCKS CONNECT reply only says the core accepted the command; it is sent
                // before the outbound connection exists. Only a real response proves the tunnel
                // carries traffic, so fetch the captive-portal endpoint and look at the status.
                out.write(PROBE_REQUEST)
                out.flush()
                return readHttpStatus(input)
            }
            false
        }
    } catch (e: Exception) {
        false
    }

    /** Reads the response head and accepts only a 2xx/3xx status line. */
    private fun readHttpStatus(input: InputStream): Boolean {
        val line = StringBuilder()
        while (line.length < 128) {
            val b = input.read()
            if (b < 0) return false
            if (b == '\n'.code) break
            if (b != '\r'.code) line.append(b.toChar())
        }
        val parts = line.toString().split(' ')
        if (parts.size < 2 || !parts[0].startsWith("HTTP/")) return false
        val status = parts[1].toIntOrNull() ?: return false
        return status in 200..399
    }

    private fun readSocksReply(input: InputStream): Boolean {
        val header = ByteArray(4)
        if (!readFully(input, header) || header[1] != 0x00.toByte()) return false
        val addressLength = when (header[3].toInt()) {
            0x01 -> 4
            0x04 -> 16
            0x03 -> {
                val length = ByteArray(1)
                if (!readFully(input, length)) return false
                length[0].toInt() and 0xff
            }

            else -> return false
        }
        return readFully(input, ByteArray(addressLength)) && readFully(input, ByteArray(2))
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var read = 0
        while (read < buffer.size) {
            val count = input.read(buffer, read, buffer.size - read)
            if (count <= 0) return false
            read += count
        }
        return true
    }
}

private const val LOOPBACK = "127.0.0.1"
