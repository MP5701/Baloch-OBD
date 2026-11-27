package ir.obd.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

data class Live(
    val rpm: Double? = null, val speed: Double? = null, val coolant: Double? = null,
    val iat: Double? = null, val map: Double? = null, val maf: Double? = null,
    val load: Double? = null, val throttle: Double? = null, val volt: Double? = null
)

class ObdClient {
    private var sock: BluetoothSocket? = null
    private var tcp: java.net.Socket? = null
    private var inp: InputStream? = null
    private var out: OutputStream? = null
    private var isCan = false
    private val fails = HashMap<Int, Int>()

    @SuppressLint("MissingPermission")
    fun connect(dev: BluetoothDevice, proto: String) {
        close(); fails.clear()
        val s = dev.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"))
        s.connect(); sock = s; inp = s.inputStream; out = s.outputStream
        initialize(proto)
    }

    fun connectWifi(host: String, port: Int, proto: String) {
        close(); fails.clear()
        val s = java.net.Socket(); tcp = s
        s.connect(java.net.InetSocketAddress(host, port), 5000)
        inp = s.getInputStream(); out = s.getOutputStream()
        initialize(proto)
    }

    private fun initialize(proto: String) {
        send("ATZ")
        for (c in listOf("ATE0", "ATL0", "ATS1", "ATH0", "ATSP$proto")) send(c)
        val r = send("0100")
        if (r.contains("UNABLE") || r.contains("ERROR") || r.contains("NO DATA"))
            throw IOException("ECU جواب نداد (سوئیچ باز است؟ پروتکل را عوض کنید)")
        val p = send("ATDPN").filter { it.isLetterOrDigit() }.removePrefix("A")
        isCan = p.firstOrNull()?.let { it in '6'..'9' } ?: false
    }

    fun close() { try { sock?.close() } catch (_: Exception) {}; sock = null; try { tcp?.close() } catch (_: Exception) {}; tcp = null; out = null; inp = null }

    @Synchronized
    fun send(cmd: String): String {
        val o = out ?: throw IOException("اتصال قطع است"); val i = inp!!
        while (i.available() > 0) i.read()
        o.write((cmd + "\r").toByteArray()); o.flush()
        val sb = StringBuilder(); val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 8000) {
            if (i.available() > 0) { val c = i.read().toChar(); if (c == '>') break; sb.append(c) }
            else Thread.sleep(5)
        }
        return sb.toString().replace('\r', ' ').replace('\n', ' ').trim()
    }

    private fun bytes(r: String) = r.split(' ')
        .filter { it.length == 2 && it.all { c -> c in '0'..'9' || c in 'A'..'F' } }.map { it.toInt(16) }

    private fun query(pid: Int): List<Int>? {
        if ((fails[pid] ?: 0) >= 3) return null
        val b = bytes(send("01%02X".format(pid)))
        val k = (0 until b.size - 1).firstOrNull { b[it] == 0x41 && b[it + 1] == pid }
        if (k == null) { fails[pid] = (fails[pid] ?: 0) + 1; return null }
        fails[pid] = 0
        return b.drop(k + 2)
    }

    private fun a(pid: Int) = query(pid)?.firstOrNull()?.toDouble()
    private fun w(pid: Int) = query(pid)?.takeIf { it.size >= 2 }?.let { it[0] * 256.0 + it[1] }

    fun readLive() = Live(
        rpm = w(0x0C)?.let { it / 4 }, speed = a(0x0D), coolant = a(0x05)?.let { it - 40 },
        iat = a(0x0F)?.let { it - 40 }, map = a(0x0B), maf = w(0x10)?.let { it / 100 },
        load = a(0x04)?.let { it * 100 / 255 }, throttle = a(0x11)?.let { it * 100 / 255 },
        volt = w(0x42)?.let { it / 1000 }
    )

    fun readDtc(mode: String = "03"): List<String> {
        val b = bytes(send(mode))
        val k = b.indexOf(0x40 + mode.toInt(16)); if (k < 0) return emptyList()
        var r = b.drop(k + 1); if (isCan) r = r.drop(1)
        return r.chunked(2).filter { it.size == 2 && (it[0] != 0 || it[1] != 0) }
            .map { "PCBU"[it[0] shr 6].toString() + ((it[0] shr 4) and 3) + "%X%02X".format(it[0] and 15, it[1]) }
            .distinct()
    }

    fun extraLive(): Map<String, Double> {
        val result = mutableMapOf<String, Double>()
        for ((pid, name) in listOf(6 to "STFT1", 7 to "LTFT1", 8 to "STFT2", 9 to "LTFT2")) a(pid)?.let { result[name] = (it-128)*100/128 }
        a(0x0E)?.let { result["timing"] = it/2-64 }
        a(0x2F)?.let { result["fuel level"] = it*100/255 }
        a(0x33)?.let { result["barometric"] = it }
        a(0x46)?.let { result["ambient"] = it-40 }
        a(0x14)?.let { result["O2 B1S1"] = it/200 }
        a(0x15)?.let { result["O2 B1S2"] = it/200 }
        return result
    }
    fun clearDtc() = send("04")
}
