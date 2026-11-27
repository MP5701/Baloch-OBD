@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ir.obd.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Vibrator
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { App() }
            }
        }
    }
}

/** تبدیل ارقام فارسی/عربی و جداکننده‌ها به عدد */
fun num(s: String): Double? = s.map {
    when (it) { in '۰'..'۹' -> '0' + (it - '۰'); in '٠'..'٩' -> '0' + (it - '٠'); '٫', '،', ',' -> '.'; else -> it }
}.joinToString("").toDoubleOrNull()

data class Trip(val t: Long, val km: Double, val l: Double, val max: Double)
data class Sample(val rpm: Float, val speed: Float, val cool: Float, val lph: Float)

class ObdVm(app: Application) : AndroidViewModel(app) {
    override fun onCleared() { disconnect(); super.onCleared() }
    private val c = ObdClient()
    private val prefs = app.getSharedPreferences("obd", 0)
    var status by mutableStateOf("متصل نیست"); var connected by mutableStateOf(false)
    var live by mutableStateOf(Live()); var lph by mutableStateOf<Double?>(null)
    var dtcs by mutableStateOf<List<String>?>(null); var busy by mutableStateOf(false)
    var proto by mutableStateOf(prefs.getString("proto", "0")!!)
    var disp by mutableStateOf(prefs.getString("disp", "1.6")!!)
    var coolMax by mutableStateOf(prefs.getString("coolMax", "105")!!)
    var rpmMax by mutableStateOf(prefs.getString("rpmMax", "6000")!!)
    var calib by mutableStateOf(prefs.getFloat("calib", 1f).toDouble())
    var calEst by mutableStateOf(prefs.getFloat("calEst", 0f).toDouble())
    var history by mutableStateOf(emptyList<Sample>())
    var alerts by mutableStateOf(emptyList<String>())
    var tripOn by mutableStateOf(false)
    var tKm by mutableStateOf(0.0); var tL by mutableStateOf(0.0); var tMax by mutableStateOf(0.0)
    var trips by mutableStateOf(load())
    private var job: Job? = null
    private var lastAlert = 0L
    var reports by mutableStateOf(emptyList<String>())
    var recording by mutableStateOf(false)
    var logName by mutableStateOf("")
    private var recorder: Recorder? = null
    private val serviceIntent = Intent(app, RecordingService::class.java)
    fun disconnect() { job?.cancel(); c.close(); connected = false; recording = false; status = "قطع اتصال"; getApplication<Application>().stopService(serviceIntent) }


    fun save() = prefs.edit().putString("proto", proto).putString("disp", disp)
        .putString("coolMax", coolMax).putString("rpmMax", rpmMax).apply()

    private fun load(): List<Trip> {
        val a = JSONArray(prefs.getString("trips", "[]"))
        return (0 until a.length()).map { a.getJSONObject(it).let { o -> Trip(o.getLong("t"), o.getDouble("km"), o.getDouble("l"), o.getDouble("max")) } }.reversed()
    }

    // مصرف خام (بدون ضریب کالیبراسیون): با MAF اگر موجود باشد، وگرنه تخمین از MAP/دور/دما
    private fun rawFuel(l: Live): Double? {
        val maf = l.maf ?: run {
            val r = l.rpm ?: return null; val m = l.map ?: return null
            val imap = r * m / ((l.iat ?: 25.0) + 273.15) / 2
            imap / 60 * 0.85 * (num(disp) ?: 1.6) * 28.97 / 8.314
        }
        return maf / (14.7 * 745) * 3600
    }

    private fun beep() {
        try { ToneGenerator(AudioManager.STREAM_ALARM, 100).startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 600) } catch (_: Exception) {}
        try { (getApplication<Application>().getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(500) } catch (_: Exception) {}
    }

    private fun checkAlerts(l: Live) {
        val a = mutableListOf<String>()
        val co = l.coolant ?: 0.0; val rp = l.rpm ?: 0.0
        if (co > (num(coolMax) ?: 105.0)) a += "🌡️ دمای موتور بالاست! (%.0f°C) — توقف کنید".format(co)
        if (rp > (num(rpmMax) ?: 6000.0)) a += "⚡ دور موتور بیش از حد مجاز"
        l.volt?.let { if (rp > 500 && it < 11.8) a += "🔋 ولتاژ پایین (%.1f V) — باتری/دینام".format(it) }
        alerts = a
        val now = System.currentTimeMillis()
        if (a.isNotEmpty() && now - lastAlert > 8000) { lastAlert = now; beep() }
    }

    @SuppressLint("MissingPermission")
    fun connect(d: BluetoothDevice) = connectWith(d.name ?: d.address) { c.connect(d, proto) }

    fun connectWifi(host: String, port: Int) = connectWith("$host:$port") { c.connectWifi(host, port, proto) }

    private fun connectWith(name: String, open: () -> Unit) {
        if (job?.isActive == true) return
        val app = getApplication<Application>()
        if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(serviceIntent) else app.startService(serviceIntent)
        connected = false; status = "در حال اتصال..."
        job = viewModelScope.launch(Dispatchers.IO) {
            try {
                open(); connected = true; status = "متصل: $name"
                val rec = Recorder(app.filesDir); recorder = rec; logName = rec.file.name; recording = true
                var previous = emptySet<String>(); var firstScan = true; var lastScan = 0L
                var last = System.nanoTime(); var lastSave = last
                while (isActive) {
                    val l = c.readLive(); val now = System.nanoTime(); val dt = (now - last) / 1e9; last = now
                    val raw = rawFuel(l); val f = raw?.times(calib)
                    live = l; lph = f
                    calEst += (raw ?: 0.0) * dt / 3600
                    if (tripOn) {
                        val v = l.speed ?: 0.0
                        tKm += v * dt / 3600; tL += (f ?: 0.0) * dt / 3600; tMax = maxOf(tMax, v)
                    }
                    history = (history + Sample(l.rpm?.toFloat() ?: 0f, l.speed?.toFloat() ?: 0f, l.coolant?.toFloat() ?: 0f, f?.toFloat() ?: 0f)).takeLast(200)
                    checkAlerts(l)
                    val values = mapOf("RPM" to l.rpm, "km/h" to l.speed, "coolant" to l.coolant, "IAT" to l.iat, "MAP" to l.map, "MAF" to l.maf, "load" to l.load, "throttle" to l.throttle, "V" to l.volt, "L/h" to f).mapNotNull { (k,v) -> v?.let { k to it } }.toMap() + c.extraLive()
                    rec.sample(values)
                    if (now - lastScan > 5_000_000_000L) {
                        lastScan = now
                        val codes = c.readDtc().toSet() + c.readDtc("07").toSet()
                        if (!firstScan) (codes - previous).forEach { code -> reports = (listOf(rec.event(code)) + reports).take(30) }
                        else rec.append("baseline", JSONObject().put("codes", JSONArray(codes.toList())))
                        firstScan = false; previous = codes; dtcs = codes.toList()
                    }
                    delay(100)
                    if (now - lastSave > 30_000_000_000L) { lastSave = now; prefs.edit().putFloat("calEst", calEst.toFloat()).apply() }
                }
            } catch (e: Exception) {
                if (isActive) { connected = false; status = "خطا: ${e.message}" }
            } finally { c.close(); recording = false; connected = false; app.stopService(serviceIntent) }
        }
    }

    fun readDtc() = viewModelScope.launch(Dispatchers.IO) {
        busy = true; try { dtcs = c.readDtc() } catch (e: Exception) { status = "خطا: ${e.message}" }; busy = false
    }
    fun clearDtc() = viewModelScope.launch(Dispatchers.IO) {
        busy = true; try { c.clearDtc(); dtcs = emptyList() } catch (e: Exception) { status = "خطا: ${e.message}" }; busy = false
    }
    fun startTrip() { tKm = 0.0; tL = 0.0; tMax = 0.0; tripOn = true }
    fun stopTrip() {
        tripOn = false
        if (tKm > 0.05) {
            val a = JSONArray(prefs.getString("trips", "[]"))
            a.put(JSONObject().put("t", System.currentTimeMillis()).put("km", tKm).put("l", tL).put("max", tMax))
            prefs.edit().putString("trips", a.toString()).apply(); trips = load()
        }
    }
    fun resetCal() { calEst = 0.0; prefs.edit().putFloat("calEst", 0f).apply() }
    fun applyCal(actual: Double) {
        if (calEst > 0.5 && actual > 0) {
            calib = (actual / calEst).coerceIn(0.5, 2.0)
            prefs.edit().putFloat("calib", calib.toFloat()).apply(); resetCal()
        }
    }
}

@Composable
fun App(vm: ObdVm = viewModel()) {
    var tab by remember { mutableIntStateOf(0) }
    val names = listOf("اتصال", "داشبورد", "خطاها", "سفر", "تنظیمات"); val icons = listOf("🔌", "📊", "⚠️", "🛣️", "⚙️")
    Scaffold(bottomBar = {
        NavigationBar { names.forEachIndexed { i, n -> NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(icons[i]) }, label = { Text(n, fontSize = 11.sp) }) } }
    }) { p ->
        Column(Modifier.padding(p).padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (tab) { 0 -> ConnectTab(vm); 1 -> DashTab(vm); 2 -> DtcTab(vm); 3 -> TripTab(vm); else -> SettingsTab(vm) }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun ConnectTab(vm: ObdVm) {
    val ctx = LocalContext.current
    val ad = remember { (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter }
    var devices by remember { mutableStateOf(listOf<BluetoothDevice>()) }
    fun refresh() { devices = try { ad?.bondedDevices?.toList() ?: emptyList() } catch (e: SecurityException) { emptyList() } }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 31) perm.launch(Manifest.permission.BLUETOOTH_CONNECT) else refresh() }

    Text("Baloch OBD", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF9800))
    Text(vm.status, style = MaterialTheme.typography.titleMedium)
    if (vm.recording) Text("● ضبط فعال: ${vm.logName}")
    OutlinedButton({ vm.disconnect() }) { Text("توقف ضبط و قطع اتصال") }
    Text("دانگل ELM327 را از تنظیمات بلوتوث گوشی جفت کنید (رمز: 1234 یا 0000)، سوئیچ را باز کنید و دستگاه را انتخاب کنید.")
    Text("پروتکل (برای خودروهای قدیمی‌تر ممکن است لازم باشد دستی انتخاب شود):")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("0" to "خودکار", "3" to "ISO 9141", "4" to "KWP آهسته", "5" to "KWP سریع", "6" to "CAN 11/500", "7" to "CAN 29/500", "8" to "CAN 11/250", "9" to "CAN 29/250", "1" to "J1850 PWM", "2" to "J1850 VPW")
            .forEach { (k, n) -> FilterChip(selected = vm.proto == k, onClick = { vm.proto = k; vm.save() }, label = { Text(n) }) }
    }
    var host by remember { mutableStateOf("192.168.0.10") }
    var port by remember { mutableStateOf("35000") }
    Text("اتصال Wi-Fi / TCP (ELM327 یا STN سازگار)")
    OutlinedTextField(host, { host = it }, label = { Text("IP / Host") })
    OutlinedTextField(port, { port = it }, label = { Text("Port") })
    val portNumber = port.toIntOrNull()?.takeIf { it in 1..65535 }
    Button({ portNumber?.let { vm.connectWifi(host.trim(), it) } }, enabled = !vm.connected && host.isNotBlank() && portNumber != null) { Text("اتصال Wi-Fi") }
    Text("بلوتوث کلاسیک / SPP")
    if (devices.isEmpty()) Text("هیچ دستگاه جفت‌شده‌ای پیدا نشد.")
    devices.forEach { d -> Button({ vm.connect(d) }, Modifier.fillMaxWidth()) { Text(d.name ?: d.address) } }
}

@Composable
fun Gauge(value: Double?, max: Float, label: String, unit: String, modifier: Modifier = Modifier, danger: Boolean = false) {
    val color = if (danger) Color(0xFFE53935) else MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val frac = ((value ?: 0.0).toFloat() / max).coerceIn(0f, 1f)
    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(6.dp)) {
            val st = Stroke(24f, cap = StrokeCap.Round); val o = Offset(12f, 12f); val sz = Size(size.width - 24f, size.height - 24f)
            drawArc(track, 135f, 270f, false, o, sz, style = st)
            drawArc(color, 135f, 270f * frac, false, o, sz, style = st)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value?.let { "%.0f".format(it) } ?: "—", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = if (danger) color else Color.Unspecified)
            Text("$label $unit", fontSize = 12.sp)
        }
    }
}

@Composable
fun LiveChart(h: List<Sample>) {
    var sel by remember { mutableIntStateOf(0) }
    val names = listOf("دور", "سرعت", "دما", "مصرف L/h")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        names.forEachIndexed { i, n -> FilterChip(selected = sel == i, onClick = { sel = i }, label = { Text(n) }) }
    }
    val vals = h.map { when (sel) { 0 -> it.rpm; 1 -> it.speed; 2 -> it.cool; else -> it.lph } }
    val color = MaterialTheme.colorScheme.primary; val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        val mx = maxOf(vals.maxOrNull() ?: 1f, 1f) * 1.1f
        for (i in 1..3) drawLine(grid, Offset(0f, size.height * i / 4), Offset(size.width, size.height * i / 4))
        if (vals.size > 1) {
            val path = Path()
            vals.forEachIndexed { i, v ->
                val x = size.width * i / (vals.size - 1); val y = size.height * (1 - v / mx)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(4f))
        }
    }
    Text("آخرین مقدار: %.1f   حداکثر: %.1f".format(vals.lastOrNull() ?: 0f, vals.maxOrNull() ?: 0f), fontSize = 12.sp)
}

@Composable
fun Info(k: String, v: String) = Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), Arrangement.SpaceBetween) {
    Text(k); Text(v, fontWeight = FontWeight.Bold)
}

@Composable
fun DashTab(vm: ObdVm) {
    val land = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val l = vm.live
    val rm = num(vm.rpmMax) ?: 6000.0; val cm = num(vm.coolMax) ?: 105.0
    vm.alerts.forEach {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFB71C1C))) {
            Text(it, Modifier.padding(12.dp), color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
    if (!vm.connected) Text("ابتدا در تب «اتصال» وصل شوید.")
    val rpm: @Composable (Modifier) -> Unit = { m -> Gauge(l.rpm, 8000f, "دور", "rpm", m, (l.rpm ?: 0.0) > rm) }
    val spd: @Composable (Modifier) -> Unit = { m -> Gauge(l.speed, 220f, "سرعت", "km/h", m) }
    val cool: @Composable (Modifier) -> Unit = { m -> Gauge(l.coolant, 130f, "دمای آب", "°C", m, (l.coolant ?: 0.0) > cm) }
    val load: @Composable (Modifier) -> Unit = { m -> Gauge(l.load, 100f, "بار موتور", "%", m) }
    if (land) Row(Modifier.fillMaxWidth()) { val m = Modifier.weight(1f); rpm(m); spd(m); cool(m); load(m) }
    else {
        Row(Modifier.fillMaxWidth()) { val m = Modifier.weight(1f); rpm(m); spd(m) }
        Row(Modifier.fillMaxWidth()) { val m = Modifier.weight(1f); cool(m); load(m) }
    }
    val f = vm.lph; val v = l.speed
    Info("مصرف لحظه‌ای", when { f == null -> "—"; v != null && v > 3 -> "%.1f L/100km".format(f / v * 100); else -> "%.1f L/h".format(f) })
    Info("دریچه گاز", l.throttle?.let { "%.0f %%".format(it) } ?: "—")
    Info("فشار مانیفولد (MAP)", l.map?.let { "%.0f kPa".format(it) } ?: "—")
    Info("دمای هوای ورودی", l.iat?.let { "%.0f °C".format(it) } ?: "—")
    Info("ولتاژ باتری", l.volt?.let { "%.1f V".format(it) } ?: "—")
    Text("نمودار زنده", style = MaterialTheme.typography.titleMedium)
    LiveChart(vm.history)
}

@Composable
fun DtcTab(vm: ObdVm) {
    val ctx = LocalContext.current
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching { ctx.contentResolver.openOutputStream(uri)?.use { out -> java.io.File(ctx.filesDir, vm.logName).inputStream().use { it.copyTo(out) } } }
    }
    OutlinedButton({ export.launch(vm.logName) }, enabled = vm.logName.isNotEmpty() && !vm.recording) { Text("خروجی فایل ضبط پس از توقف") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button({ vm.readDtc() }, enabled = vm.connected && !vm.busy) { Text("خواندن خطاها") }
        OutlinedButton({ vm.clearDtc() }, enabled = vm.connected && !vm.busy) { Text("پاک کردن") }
    }
    Text("توجه: پاک کردن کدها چراغ چک را خاموش می‌کند ولی مشکل را رفع نمی‌کند.", fontSize = 12.sp)
    Text("گزارش رخدادهای جدید", fontWeight = FontWeight.Bold)
    vm.reports.forEach { report -> Card(Modifier.fillMaxWidth()) { Text(report, Modifier.padding(12.dp)) } }
    val d = vm.dtcs
    if (d != null && d.isEmpty()) Text("هیچ خطایی ثبت نشده ✅")
    d?.forEach { code ->
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
            Text(code, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(dtcDesc(code))
            TextButton({ ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$code+OBD"))) }) { Text("جست‌وجو در اینترنت") }
        } }
    }
}

fun fa(t: Long): String = android.icu.text.SimpleDateFormat("yyyy/MM/dd HH:mm", android.icu.util.ULocale("fa_IR@calendar=persian")).format(java.util.Date(t))

@Composable
fun TripTab(vm: ObdVm) {
    Button({ if (vm.tripOn) vm.stopTrip() else vm.startTrip() }, Modifier.fillMaxWidth(), enabled = vm.connected) {
        Text(if (vm.tripOn) "پایان و ذخیره سفر" else "شروع سفر جدید")
    }
    if (vm.tripOn) {
        Info("مسافت", "%.2f km".format(vm.tKm)); Info("سوخت مصرفی", "%.2f L".format(vm.tL))
        Info("میانگین مصرف", if (vm.tKm > 0.1) "%.1f L/100km".format(vm.tL / vm.tKm * 100) else "—")
        Info("حداکثر سرعت", "%.0f km/h".format(vm.tMax))
        LiveChart(vm.history)
    }
    Text("سابقه سفرها", style = MaterialTheme.typography.titleMedium)
    vm.trips.forEach { t ->
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
            Text(fa(t.t), fontWeight = FontWeight.Bold)
            Text("%.1f km • %.1f L • %.1f L/100km • حداکثر %.0f km/h".format(t.km, t.l, if (t.km > 0) t.l / t.km * 100 else 0.0, t.max))
        } }
    }
}

@Composable
fun SettingsTab(vm: ObdVm) {
    val dec = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    Text("موتور و هشدارها", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(vm.disp, { vm.disp = it; vm.save() }, label = { Text("حجم موتور (لیتر)") }, keyboardOptions = dec, singleLine = true)
    OutlinedTextField(vm.coolMax, { vm.coolMax = it; vm.save() }, label = { Text("هشدار دمای موتور (°C)") }, keyboardOptions = dec, singleLine = true)
    OutlinedTextField(vm.rpmMax, { vm.rpmMax = it; vm.save() }, label = { Text("هشدار دور موتور (rpm)") }, keyboardOptions = dec, singleLine = true)
    HorizontalDivider()
    Text("کالیبراسیون مصرف سوخت", style = MaterialTheme.typography.titleMedium)
    Text("۱) باک را کامل پر کنید و «شروع شمارش» را بزنید.\n۲) رانندگی کنید (اپ وصل باشد).\n۳) دفعه بعد که باک را کامل پر کردید، لیتر واقعی را وارد و «اعمال» را بزنید.", fontSize = 13.sp)
    Info("تخمین اپ از شروع شمارش", "%.1f L".format(vm.calEst))
    Info("ضریب فعلی", "%.2f".format(vm.calib))
    var actual by remember { mutableStateOf("") }
    OutlinedTextField(actual, { actual = it }, label = { Text("لیتر واقعیِ بنزین‌زده‌شده") }, keyboardOptions = dec, singleLine = true)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ vm.resetCal() }) { Text("شروع شمارش") }
        Button({ vm.applyCal(num(actual) ?: 0.0) }) { Text("اعمال") }
    }
}
