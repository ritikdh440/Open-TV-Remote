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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ---------- ADB client ----------
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

    suspend fun togglePower() {
        val out = shell("dumpsys power | grep mWakefulness=")
        shell("input keyevent " + if (out.contains("Awake")) 223 else 224)
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

private val APPS = listOf(
    "YouTube" to "com.google.android.youtube.tv",
    "Netflix" to "com.netflix.ninja",
    "Prime" to "com.amazon.amazonvideo.livingroom",
    "Hotstar" to "in.startv.hotstar",
    "Play Store" to "com.android.vending",
)

// ---------- Screen ----------
@Composable
fun App(client: TvClient) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("opentvremote", Context.MODE_PRIVATE) }
    var ip by remember { mutableStateOf(prefs.getString("ip", "") ?: "") }
    var status by remember { mutableStateOf("Not connected") }
    var connected by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val view = LocalView.current

    fun connect() {
        if (ip.isBlank() || busy) return
        busy = true
        status = "Connecting… accept the prompt on your TV"
        prefs.edit().putString("ip", ip.trim()).apply()
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

    fun exec(block: suspend () -> Unit) {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        scope.launch {
            try { block() } catch (e: Exception) {
                connected = false
                status = "Connection lost"
            }
        }
    }
    fun key(code: Int) = exec { client.shell("input keyevent $code") }

    LaunchedEffect(Unit) { if (ip.isNotBlank()) connect() }

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

        if (connected) {
            // Power row
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Key("⏻", Modifier.size(60.dp), Danger, CircleShape) { exec { client.togglePower() } }
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
                APPS.forEach { (name, pkg) ->
                    Key(name, Modifier.width(104.dp).height(48.dp), Panel, RoundedCornerShape(14.dp), 14) {
                        exec { client.shell("monkey -p $pkg -c android.intent.category.LEANBACK_LAUNCHER 1") }
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
                            val safe = t.replace(" ", "%s").replace("'", "'\\''")
                            exec { client.shell("input text '$safe'") }
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
