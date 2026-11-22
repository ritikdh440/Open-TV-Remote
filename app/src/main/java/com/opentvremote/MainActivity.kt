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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
@Composable
fun App(client: TvClient) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("opentvremote", Context.MODE_PRIVATE) }
    val hub = remember { AtvHub(ctx.filesDir) }
    var ip by remember { mutableStateOf(prefs.getString("ip", "") ?: "") }
    var mode by remember {
        mutableStateOf(if (prefs.getString("mode", "atv") == "adb") Mode.ADB else Mode.ATV)
    }
    var status by remember { mutableStateOf("Not connected") }
    var connected by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var awaitingCode by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val view = LocalView.current

    fun pairedKey() = "paired_" + ip.trim()

    fun connectAdb() {
        scope.launch {
            try {
                client.connect(ip.trim())
                connected = true
                status = "Connected"
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
                    status = "Disconnected. Tap Connect."
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
                    status = "Connected"
                } else {
                    status = "Starting pairing…"
                    hub.pairing!!.start(host)
                    awaitingCode = true
                    status = "Enter the code shown on your TV"
                }
            } catch (e: Unreachable) {
                connected = false
                status = (e.message ?: "Can't reach TV") +
                    ". Check the IP and Wi-Fi, or try ADB mode if your TV doesn't support this."
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
                status = "Connected"
            } catch (e: Exception) {
                awaitingCode = false
                status = "Pairing failed: " + (e.message ?: "unknown error") + ". Tap Connect to try again."
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
        status = "Not connected"
    }

    fun exec(block: suspend () -> Unit) {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                connected = false
                status = "Connection lost. Tap Connect."
                if (mode == Mode.ATV) hub.remote?.close()
            }
        }
    }

    suspend fun sendKey(k: Int) {
        if (mode == Mode.ATV) hub.remote!!.key(k) else client.pressKey(k)
    }

    fun key(k: Int) = exec { sendKey(k) }

    LaunchedEffect(Unit) {
        if (ip.isNotBlank() && (mode == Mode.ADB || prefs.getBoolean(pairedKey(), false))) connect()
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
        // Header
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Open TV Remote", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (connected) Good else Danger))
            Spacer(Modifier.width(8.dp))
            Text(if (connected) "Connected" else "Offline", color = Color(0xFFAAB0BC), fontSize = 13.sp)
        }

        // Mode selector
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Key(
                "Wi-Fi remote", Modifier.weight(1f).height(44.dp),
                if (mode == Mode.ATV) Accent else KeyBg, RoundedCornerShape(12.dp), 14
            ) { switchMode(Mode.ATV) }
            Key(
                "ADB", Modifier.weight(1f).height(44.dp),
                if (mode == Mode.ADB) Accent else KeyBg, RoundedCornerShape(12.dp), 14
            ) { switchMode(Mode.ADB) }
        }

        // Connection
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = ip, onValueChange = { ip = it },
                label = { Text("TV IP address") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp)
            )
            Spacer(Modifier.width(10.dp))
            Button(
                onClick = { connect() }, enabled = !busy,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.height(56.dp)
            ) { Text(if (busy) "…" else "Connect") }
        }

        if (!connected) {
            Text(status, color = Color(0xFFAAB0BC), fontSize = 13.sp, textAlign = TextAlign.Center)
        }

        if (awaitingCode) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter { c -> c.isLetterOrDigit() }.take(6).uppercase() },
                    label = { Text("Code on TV (6 characters)") },
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

        if (!connected && mode == Mode.ATV && !awaitingCode && !busy && ip.isNotBlank()) {
            TextButton(onClick = {
                prefs.edit().putBoolean(pairedKey(), false).apply()
                connect()
            }) { Text("Pair again") }
        }

        if (connected) {
            // Power row
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Key("⏻", Modifier.size(60.dp), Danger, CircleShape) {
                    exec { if (mode == Mode.ATV) sendKey(26) else client.togglePower() }
                }
                Key("Input", Modifier.size(60.dp), KeyBg, CircleShape, 13) { key(178) }
                Key("Mute", Modifier.size(60.dp), KeyBg, CircleShape, 13) { key(164) }
            }

            // D-pad
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

            // Back / Home / Menu
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Key("Back", Modifier.weight(1f).height(52.dp)) { key(4) }
                Key("Home", Modifier.weight(1f).height(52.dp)) { key(3) }
                Key("Menu", Modifier.weight(1f).height(52.dp)) { key(82) }
            }

            // Volume / Channel
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stepper("VOL", Modifier.weight(1f), { key(25) }, { key(24) })
                Stepper("CH", Modifier.weight(1f), { key(167) }, { key(166) })
            }

            // Media
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Key("⏪", Modifier.weight(1f).height(48.dp)) { key(89) }
                Key("⏯", Modifier.weight(1f).height(48.dp)) { key(85) }
                Key("⏩", Modifier.weight(1f).height(48.dp)) { key(90) }
            }

            // Apps
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

            // Text input
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
        Spacer(Modifier.height(8.dp))
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
        Text(label, color = Color(0xFFAAB0BC), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Key("+", Modifier.weight(1f).fillMaxHeight(), Color.Transparent, RoundedCornerShape(28.dp), 24, onPlus)
    }
}
