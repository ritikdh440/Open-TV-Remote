package com.opentvremote

import android.content.Context
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

// ---------- ADB client (fallback mode) ----------
class TvClient(private val dir: File) {
    private var dadb: Dadb? = null

    private fun keyPair(): AdbKeyPair {
        val priv = File(dir, "adbkey")
        val pub = File(dir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        return AdbKeyPair.read(priv, pub)
    }

    suspend fun connect(host: String) = withContext(Dispatchers.IO) {
        try { dadb?.close() } catch (e: Exception) {}
        val d = Dadb.create(host, 5555, keyPair())
        d.shell("echo ok")
        dadb = d
    }

    fun close() {
        try { dadb?.close() } catch (e: Exception) {}
        dadb = null
    }

    suspend fun shell(cmd: String): String = withContext(Dispatchers.IO) {
        val d = dadb ?: error("Not connected")
        d.shell(cmd).allOutput
    }

    // Key presses are queued and sent in batches through ONE adb call at a time.
    private val keyQueue = java.util.concurrent.ConcurrentLinkedQueue<Int>()
    private val sending = java.util.concurrent.atomic.AtomicBoolean(false)

    suspend fun pressKey(code: Int) {
        keyQueue.add(code)
        while (keyQueue.isNotEmpty() && sending.compareAndSet(false, true)) {
            try {
                while (true) {
                    val batch = ArrayList<Int>()
                    while (true) {
                        val k = keyQueue.poll() ?: break
                        batch.add(k)
                    }
                    if (batch.isEmpty()) break
                    shell("input keyevent " + batch.joinToString(" "))
                }
            } finally {
                sending.set(false)
            }
        }
    }

    suspend fun togglePower() {
        val out = shell("dumpsys power | grep mWakefulness=")
        shell("input keyevent " + if (out.contains("Awake")) 223 else 224)
    }
}

enum class Mode { ATV, ADB }

// ---- About / signature 
private const val AUTHOR_NAME = "Ritik Dhiman"
private const val REPO_URL = "https://github.com/ritikdh440/Open-TV-Remote"

// ---------- Network scan (auto-detect TVs) ----------
data class Found(val ip: String, val remote: Boolean, val adb: Boolean)

object NetScan {
    private fun localPrefix(): String? {
        try {
            val all = java.util.Collections.list(NetworkInterface.getNetworkInterfaces() ?: return null)
            for (ni in all) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in java.util.Collections.list(ni.inetAddresses)) {
                    if (a is Inet4Address && a.isSiteLocalAddress) {
                        val parts = (a.hostAddress ?: continue).split(".")
                        if (parts.size == 4) return parts[0] + "." + parts[1] + "." + parts[2]
                    }
                }
            }
        } catch (e: Exception) {
        }
        return null
    }

    private fun isOpen(ip: String, port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), timeoutMs) }
        true
    } catch (e: Exception) {
        false
    }

    // Returns null when the phone has no local Wi-Fi network.
    suspend fun scan(): List<Found>? = withContext(Dispatchers.IO) {
        val prefix = localPrefix() ?: return@withContext null
        val gate = Semaphore(80)
        coroutineScope {
            (1..254).map { n ->
                async {
                    gate.withPermit {
                        val ip = "$prefix.$n"
                        val r = isOpen(ip, 6466, 400)
                        val a = isOpen(ip, 5555, 400)
                        if (r || a) Found(ip, r, a) else null
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }
}

// ---------- Theme ----------
private val Bg = Color(0xFF0F1115)
private val Panel = Color(0xFF1B1E25)
private val KeyBg = Color(0xFF262A33)
private val Accent = Color(0xFF4C8DFF)
private val Danger = Color(0xFFE5484D)
private val Good = Color(0xFF3FB950)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val client = TvClient(filesDir)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent, background = Bg, surface = Panel,
                    onSurface = Color.White, onBackground = Color.White
                )
            ) { App(client) }
        }
    }
}

private data class AppEntry(val name: String, val pkg: String, val link: String)

private val APPS = listOf(
    AppEntry("YouTube", "com.google.android.youtube.tv", "https://www.youtube.com"),
    AppEntry("Netflix", "com.netflix.ninja", "https://www.netflix.com"),
    AppEntry("Prime", "com.amazon.amazonvideo.livingroom", "https://app.primevideo.com"),
    AppEntry("Hotstar", "in.startv.hotstar", "https://www.hotstar.com"),
    AppEntry("Play Store", "com.android.vending", "https://play.google.com/store"),
)

// ---------- Screen ----------
private val Muted = Color(0xFFAAB0BC)

@Composable
fun App(client: TvClient) {
    val ctx = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val prefs = remember { ctx.getSharedPreferences("opentvremote", Context.MODE_PRIVATE) }
    val hub = remember { AtvHub(ctx.filesDir) }
    var ip by remember { mutableStateOf(prefs.getString("ip", "") ?: "") }
    var mode by remember {
        mutableStateOf(if (prefs.getString("mode", "atv") == "adb") Mode.ADB else Mode.ATV)
    }
    var status by remember { mutableStateOf("") }
    var connected by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var awaitingCode by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var scanned by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<Found>>(emptyList()) }
    var manual by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val view = LocalView.current

    fun pairedKey() = "paired_" + ip.trim()

    fun startScan() {
        if (scanning) return
        scanning = true
        status = ""
        scope.launch {
            val res = NetScan.scan()
            if (res == null) {
                status = "Connect your phone to Wi-Fi to find TVs."
            } else {
                found = res
            }
            scanned = true
            scanning = false
        }
    }

    fun connectAdb() {
        scope.launch {
            try {
                client.connect(ip.trim())
                connected = true
                status = ""
            } catch (e: Exception) {
                connected = false
                status = "Failed: " + (e.message ?: "check IP and network debugging")
            }
            busy = false
        }
    }

    fun connectAtv() {
        scope.launch {
            try {
                status = "Preparing…"
                hub.init()
                val remote = hub.remote!!
                remote.onClosed = {
                    connected = false
                    status = "Disconnected. Tap your TV to reconnect."
                }
                val host = ip.trim()
                var ok = false
                if (prefs.getBoolean(pairedKey(), false)) {
                    status = "Connecting…"
                    try {
                        remote.connect(host)
                        ok = true
                    } catch (e: NeedPairing) {
                        prefs.edit().putBoolean(pairedKey(), false).apply()
                    }
                }
                if (ok) {
                    connected = true
                    status = ""
                } else {
                    status = "Starting pairing…"
                    hub.pairing!!.start(host)
                    awaitingCode = true
                    status = "Enter the code shown on your TV"
                }
            } catch (e: Unreachable) {
                connected = false
                status = (e.message ?: "Can't reach TV") +
                    ". Check Wi-Fi, or try ADB mode if your TV doesn't support this."
            } catch (e: Exception) {
                connected = false
                status = "Failed: " + (e.message ?: "unknown error")
            }
            busy = false
        }
    }

    fun connect() {
        if (ip.isBlank() || busy) return
        busy = true
        awaitingCode = false
        connected = false
        prefs.edit().putString("ip", ip.trim()).apply()
        if (mode == Mode.ATV) {
            connectAtv()
        } else {
            status = "Connecting… accept the prompt on your TV"
            connectAdb()
        }
    }

    fun submitCode() {
        if (busy || code.length != 6) return
        busy = true
        scope.launch {
            try {
                hub.pairing!!.finish(code)
                prefs.edit().putBoolean(pairedKey(), true).apply()
                awaitingCode = false
                code = ""
                status = "Paired. Connecting…"
                delay(1000)
                hub.remote!!.connect(ip.trim())
                connected = true
                status = ""
            } catch (e: Exception) {
                awaitingCode = false
                status = "Pairing failed: " + (e.message ?: "unknown error") + ". Tap your TV to try again."
            }
            busy = false
        }
    }

    fun switchMode(m: Mode) {
        if (m == mode) return
        mode = m
        prefs.edit().putString("mode", if (m == Mode.ADB) "adb" else "atv").apply()
        hub.remote?.close()
        connected = false
        awaitingCode = false
        status = ""
    }

    fun disconnect() {
        hub.remote?.close()
        client.close()
        connected = false
        awaitingCode = false
        status = ""
    }

    fun exec(block: suspend () -> Unit) {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                connected = false
                status = "Connection lost. Tap your TV to reconnect."
                if (mode == Mode.ATV) hub.remote?.close()
            }
        }
    }

    suspend fun sendKey(k: Int) {
        if (mode == Mode.ATV) hub.remote!!.key(k) else client.pressKey(k)
    }

    fun key(k: Int) = exec { sendKey(k) }

    LaunchedEffect(Unit) {
        if (ip.isBlank()) {
            startScan()
        } else {
            if (mode == Mode.ADB || prefs.getBoolean(pairedKey(), false)) connect()
            startScan()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // ---- Header
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Open TV Remote", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.clip(RoundedCornerShape(20.dp)).background(Panel).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (connected) Good else Danger))
                Spacer(Modifier.width(8.dp))
                Text(if (connected) "Connected" else "Offline", color = Muted, fontSize = 12.sp)
            }
        }

        if (!connected) {
            // ---- Mode selector (segmented)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Key(
                    "Wi-Fi remote", Modifier.weight(1f).height(42.dp),
                    if (mode == Mode.ATV) Accent else Color.Transparent, RoundedCornerShape(11.dp), 14
                ) { switchMode(Mode.ATV) }
                Key(
                    "ADB", Modifier.weight(1f).height(42.dp),
                    if (mode == Mode.ADB) Accent else Color.Transparent, RoundedCornerShape(11.dp), 14
                ) { switchMode(Mode.ADB) }
            }

            // ---- Find your TV
            val visible = found.filter { if (mode == Mode.ATV) it.remote else it.adb }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Panel).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Your TV", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    if (scanning) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Accent)
                        Spacer(Modifier.width(8.dp))
                        Text("Scanning…", color = Muted, fontSize = 13.sp)
                    } else {
                        TextButton(onClick = { startScan() }) { Text("Scan again") }
                    }
                }
                visible.forEach { f ->
                    DeviceRow(f, ip == f.ip && busy) {
                        if (!busy) {
                            ip = f.ip
                            connect()
                        }
                    }
                }
                if (!scanning && scanned && visible.isEmpty()) {
                    Text(
                        "No TVs found on this Wi-Fi. Make sure the TV is on, or try the other mode, or enter the IP below.",
                        color = Muted, fontSize = 13.sp
                    )
                }
                if (manual || (scanned && visible.isEmpty()) || ip.isNotBlank() && visible.none { it.ip == ip }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = ip, onValueChange = { ip = it },
                            label = { Text("TV IP address") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Button(
                            onClick = { connect() }, enabled = !busy && ip.isNotBlank(),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.height(56.dp)
                        ) { Text(if (busy) "…" else "Connect") }
                    }
                } else {
                    TextButton(onClick = { manual = true }) { Text("Enter IP manually") }
                }
            }

            if (status.isNotBlank()) {
                Text(status, color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
            }

            if (awaitingCode) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Panel).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Pair with your TV", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("A 6-character code is showing on your TV screen.", color = Muted, fontSize = 13.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it.filter { c -> c.isLetterOrDigit() }.take(6).uppercase() },
                            label = { Text("Code") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Button(
                            onClick = { submitCode() }, enabled = !busy && code.length == 6,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.height(56.dp)
                        ) { Text("Pair") }
                    }
                }
            }

            if (mode == Mode.ATV && !awaitingCode && !busy && ip.isNotBlank()) {
                TextButton(onClick = {
                    prefs.edit().putBoolean(pairedKey(), false).apply()
                    connect()
                }) { Text("Pair again") }
            }
        }

        if (connected) {
            // ---- Device bar
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(ip, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(if (mode == Mode.ATV) "Wi-Fi remote" else "ADB", color = Muted, fontSize = 12.sp)
                }
                TextButton(onClick = { disconnect() }) { Text("Disconnect") }
            }

            // ---- Power row
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Key("⏻", Modifier.size(60.dp), Danger, CircleShape) {
                    exec { if (mode == Mode.ATV) sendKey(26) else client.togglePower() }
                }
                Key("Input", Modifier.size(60.dp), KeyBg, CircleShape, 13) { key(178) }
                Key("Mute", Modifier.size(60.dp), KeyBg, CircleShape, 13) { key(164) }
            }

            // ---- D-pad
            Box(
                Modifier.size(240.dp).clip(CircleShape).background(Panel),
                contentAlignment = Alignment.Center
            ) {
                Key("▲", Modifier.align(Alignment.TopCenter).padding(top = 10.dp).size(64.dp), Color.Transparent, CircleShape, 22) { key(19) }
                Key("▼", Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).size(64.dp), Color.Transparent, CircleShape, 22) { key(20) }
                Key("◀", Modifier.align(Alignment.CenterStart).padding(start = 10.dp).size(64.dp), Color.Transparent, CircleShape, 22) { key(21) }
                Key("▶", Modifier.align(Alignment.CenterEnd).padding(end = 10.dp).size(64.dp), Color.Transparent, CircleShape, 22) { key(22) }
                Key("OK", Modifier.size(88.dp), Accent, CircleShape, 20) { key(23) }
            }

            // ---- Back / Home / Menu
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Key("Back", Modifier.weight(1f).height(52.dp)) { key(4) }
                Key("Home", Modifier.weight(1f).height(52.dp)) { key(3) }
                Key("Menu", Modifier.weight(1f).height(52.dp)) { key(82) }
            }

            // ---- Volume / Channel
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stepper("VOL", Modifier.weight(1f), { key(25) }, { key(24) })
                Stepper("CH", Modifier.weight(1f), { key(167) }, { key(166) })
            }

            // ---- Media
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Key("⏪", Modifier.weight(1f).height(48.dp)) { key(89) }
                Key("⏯", Modifier.weight(1f).height(48.dp)) { key(85) }
                Key("⏩", Modifier.weight(1f).height(48.dp)) { key(90) }
            }

            // ---- Apps
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                APPS.forEach { app ->
                    Key(app.name, Modifier.width(104.dp).height(48.dp), Panel, RoundedCornerShape(14.dp), 14) {
                        exec {
                            if (mode == Mode.ATV) {
                                hub.remote!!.launch(app.link)
                            } else {
                                client.shell("monkey -p ${app.pkg} -c android.intent.category.LEANBACK_LAUNCHER 1")
                            }
                        }
                    }
                }
            }

            // ---- Text input
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("Type to TV") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                )
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = {
                        val t = text
                        if (t.isNotEmpty()) {
                            exec {
                                if (mode == Mode.ATV) {
                                    hub.remote!!.typeText(t)
                                } else {
                                    val safe = t.replace(" ", "%s").replace("'", "'\\''")
                                    client.shell("input text '$safe'")
                                }
                            }
                            text = ""
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.height(56.dp)
                ) { Text("Send") }
            }
        }

        // ---- Signature / star
        Column(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Made with ♥ by $AUTHOR_NAME", color = Muted, fontSize = 13.sp)
            Text("Find it useful? Give it a star on GitHub.", color = Muted, fontSize = 12.sp)
            Key(
                "★  Star on GitHub", Modifier.width(200.dp).height(44.dp),
                Panel, RoundedCornerShape(22.dp), 14
            ) { uriHandler.openUri(REPO_URL) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun DeviceRow(f: Found, loading: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(KeyBg)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (f.remote) "Android TV" else "ADB device",
                color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold
            )
            Text(f.ip, color = Muted, fontSize = 13.sp)
        }
        if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Accent)
        } else {
            if (f.remote) Chip("Wi-Fi")
            if (f.adb) {
                Spacer(Modifier.width(6.dp))
                Chip("ADB")
            }
        }
    }
}

@Composable
fun Chip(label: String) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).background(Accent.copy(alpha = 0.2f)).padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(label, color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun Key(
    label: String,
    modifier: Modifier = Modifier,
    bg: Color = KeyBg,
    shape: Shape = RoundedCornerShape(16.dp),
    size: Int = 16,
    onClick: () -> Unit
) {
    Box(
        modifier.clip(shape).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontSize = size.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun Stepper(label: String, modifier: Modifier, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(
        modifier.height(56.dp).clip(RoundedCornerShape(28.dp)).background(Panel),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Key("−", Modifier.weight(1f).fillMaxHeight(), Color.Transparent, RoundedCornerShape(28.dp), 24, onMinus)
        Text(label, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Key("+", Modifier.weight(1f).fillMaxHeight(), Color.Transparent, RoundedCornerShape(28.dp), 24, onPlus)
    }
}
