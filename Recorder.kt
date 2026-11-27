package ir.obd.app

import android.app.*
import android.content.Intent
import android.os.*
import org.json.JSONObject
import java.io.File

class RecordingService : Service() {
    private var lock: PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("obd", "ضبط خودرو", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "obd") else Notification.Builder(this)
        startForeground(1, n.setSmallIcon(android.R.drawable.ic_menu_compass).setContentTitle("پایش خودرو فعال است").setContentText("برای توقف ضبط به برنامه بازگردید").setContentIntent(open).setOngoing(true).build())
        if (lock == null) lock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IranOBD:recording").apply { acquire() }
        return START_NOT_STICKY
    }
    override fun onDestroy() { lock?.let { if (it.isHeld) it.release() }; super.onDestroy() }
}

data class Frame(val time: Long, val values: Map<String, Double>)
class Recorder(private val dir: File) {
    private val frames = ArrayDeque<Frame>()
    val file = File(dir, "drive-${System.currentTimeMillis()}.jsonl")
    fun append(kind: String, data: JSONObject) { file.appendText(data.put("type", kind).put("time", System.currentTimeMillis()).toString() + "\n") }
    fun sample(values: Map<String, Double>) {
        val now = System.currentTimeMillis(); frames.addLast(Frame(now, values))
        while (frames.isNotEmpty() && now - frames.first().time > 60000) frames.removeFirst()
        append("sample", JSONObject(values))
    }
    fun event(code: String): String {
        val keys = frames.flatMap { it.values.keys }.distinct()
        val report = keys.map { k ->
            val v = frames.mapNotNull { it.values[k] }; val lo = v.minOrNull()!!; val hi = v.maxOrNull()!!
            val threshold = when(k) { "RPM" -> 50.0; "V" -> 0.2; else -> 1.0 }
            "$k: %.2f → %.2f | min %.2f / max %.2f | %s".format(v.first(), v.last(), lo, hi, if(v.size < 2) "داده ناکافی" else if(hi-lo <= threshold) "تقریباً ثابت" else "متغیر")
        }.joinToString("\n")
        val reason = when {
            code.startsWith("P030") -> "احتمال ایراد جرقه، انژکتور یا کمپرس؛ بررسی سیلندر لازم است."
            code == "P0171" -> "احتمال نشتی هوا، فشار سوخت کم یا خطای اندازه‌گیری هوا."
            code == "P0172" -> "احتمال سوخت اضافی، انژکتور نشتی‌دار یا خطای سنسور هوا."
            code.startsWith("P033") -> "بررسی سنسور میل‌لنگ، سیم‌کشی و سیگنال دور موتور."
            else -> "${dtcDesc(code)}؛ بررسی مدار و آزمون قطعه لازم است."
        }
        val result = "$code — $reason\nتغییرات ۶۰ ثانیه قبل از تشخیص (نه الزاماً لحظه وقوع):\n$report\nهمبستگی پارامترها اثبات علت نیست."
        append("fault", JSONObject().put("code", code).put("report", result))
        return result
    }
}
