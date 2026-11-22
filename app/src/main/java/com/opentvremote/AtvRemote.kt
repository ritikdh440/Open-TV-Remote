package com.opentvremote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

// ============================================================
// Minimal protobuf helpers (enough for the Android TV remote protocol)
// ============================================================
object Pb {
    fun varint(value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var v = value
        while (true) {
            if ((v and 0x7FL.inv()) == 0L) {
                out.write(v.toInt())
                break
            }
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        return out.toByteArray()
    }

    fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (p in parts) out.write(p)
        return out.toByteArray()
    }

    fun int(field: Int, v: Int): ByteArray =
        concat(varint((field shl 3).toLong()), varint(v.toLong()))

    fun bytes(field: Int, b: ByteArray): ByteArray =
        concat(varint(((field shl 3) or 2).toLong()), varint(b.size.toLong()), b)

    fun str(field: Int, s: String): ByteArray = bytes(field, s.toByteArray(Charsets.UTF_8))

    fun msg(field: Int, vararg parts: ByteArray): ByteArray = bytes(field, concat(*parts))

    private fun readVarint(inp: InputStream): Long? {
        var result = 0L
        var shift = 0
        while (true) {
            val b = inp.read()
            if (b < 0) {
                if (shift == 0) return null
                throw IOException("Unexpected end of stream")
            }
            result = result or ((b and 0x7F).toLong() shl shift)
            if ((b and 0x80) == 0) return result
            shift += 7
            if (shift > 63) throw IOException("Bad varint")
        }
    }

    fun readDelimited(inp: InputStream): ByteArray? {
        val len = readVarint(inp) ?: return null
        if (len < 0 || len > 1_000_000) throw IOException("Bad message length")
        val buf = ByteArray(len.toInt())
        var off = 0
        while (off < buf.size) {
            val n = inp.read(buf, off, buf.size - off)
            if (n < 0) throw IOException("Unexpected end of stream")
            off += n
        }
        return buf
    }

    fun writeDelimited(out: OutputStream, data: ByteArray) {
        out.write(concat(varint(data.size.toLong()), data))
        out.flush()
    }

    fun parse(data: ByteArray): Map<Int, List<Any>> {
        val res = HashMap<Int, MutableList<Any>>()
        var i = 0
        fun readV(): Long {
            var r = 0L
            var s = 0
            while (true) {
                val b = data[i++].toInt() and 0xFF
                r = r or ((b and 0x7F).toLong() shl s)
                if ((b and 0x80) == 0) return r
                s += 7
            }
        }
        try {
            while (i < data.size) {
                val key = readV()
                val field = (key ushr 3).toInt()
                val wire = (key and 7L).toInt()
                val value: Any = when (wire) {
                    0 -> readV()
                    2 -> {
                        val l = readV().toInt()
                        val b = data.copyOfRange(i, i + l)
                        i += l
                        b
                    }
                    1 -> { i += 8; 0L }
                    5 -> { i += 4; 0L }
                    else -> return res
                }
                res.getOrPut(field) { mutableListOf() }.add(value)
            }
        } catch (e: Exception) {
            // malformed tail: return what we have
        }
        return res
    }
}

fun Map<Int, List<Any>>.long(field: Int): Long? = this[field]?.firstOrNull() as? Long

fun Map<Int, List<Any>>.sub(field: Int): Map<Int, List<Any>>? =
    (this[field]?.firstOrNull() as? ByteArray)?.let { Pb.parse(it) }

// ============================================================
// Client identity (self-signed certificate the TV remembers after pairing)
// ============================================================
class Identity(val key: PrivateKey, val cert: X509Certificate) {
    companion object {
        fun loadOrCreate(dir: File): Identity {
            val kf = File(dir, "atv_client.key")
            val cf = File(dir, "atv_client.crt")
            if (kf.exists() && cf.exists()) {
                try {
                    val key = KeyFactory.getInstance("RSA")
                        .generatePrivate(PKCS8EncodedKeySpec(kf.readBytes()))
                    val cert = CertificateFactory.getInstance("X.509")
                        .generateCertificate(ByteArrayInputStream(cf.readBytes())) as X509Certificate
                    return Identity(key, cert)
                } catch (e: Exception) {
                    // fall through and regenerate
                }
            }
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048)
            val kp = kpg.generateKeyPair()
            val name = X500Name("CN=OpenTVRemote")
            val now = System.currentTimeMillis()
            val holder = JcaX509v3CertificateBuilder(
                name,
                BigInteger.valueOf(now),
                Date(now - 86_400_000L),
                Date(now + 3_650L * 86_400_000L),
                name,
                kp.public
            ).build(JcaContentSignerBuilder("SHA256withRSA").build(kp.private))
            val cert = JcaX509CertificateConverter().getCertificate(holder)
            kf.writeBytes(kp.private.encoded)
            cf.writeBytes(cert.encoded)
            return Identity(kp.private, cert)
        }
    }
}

private fun BigInteger.unsignedBytes(): ByteArray {
    val b = toByteArray()
    return if (b.size > 1 && b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b
}

// ============================================================
// TLS helpers
// ============================================================
class NeedPairing : Exception("Pairing required")
class Unreachable(msg: String) : IOException(msg)

private class FixedKeyManager(private val id: Identity) : X509ExtendedKeyManager() {
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = arrayOf("client")
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String = "client"
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(id.cert)
    override fun getPrivateKey(alias: String?): PrivateKey = id.key
}

private object TrustAll : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
}

private object Tls {
    fun connect(host: String, port: Int, id: Identity, timeoutMs: Int = 8000): SSLSocket {
        val ctx = SSLContext.getInstance("TLSv1.2")
        ctx.init(arrayOf<KeyManager>(FixedKeyManager(id)), arrayOf<TrustManager>(TrustAll), null)
        val s = ctx.socketFactory.createSocket() as SSLSocket
        try {
            s.connect(InetSocketAddress(host, port), timeoutMs)
        } catch (e: IOException) {
            try { s.close() } catch (x: Exception) {}
            throw Unreachable("Can't reach $host:$port")
        }
        s.soTimeout = timeoutMs
        try {
            s.startHandshake()
        } catch (e: IOException) {
            try { s.close() } catch (x: Exception) {}
            throw e
        }
        return s
    }
}

// ============================================================
// Pairing (port 6467): TV shows a 6-character code, we prove we know it
// ============================================================
class AtvPairing(private val id: Identity) {
    @Volatile private var sock: SSLSocket? = null

    private fun pmsg(vararg inner: ByteArray): ByteArray =
        Pb.concat(Pb.int(1, 2), Pb.int(2, 200), *inner)

    private fun expect(inp: InputStream): Map<Int, List<Any>> {
        val data = Pb.readDelimited(inp) ?: throw IOException("TV closed the connection")
        val m = Pb.parse(data)
        val st = m.long(2)
        if (st != null && st != 200L) throw IOException("TV rejected pairing (status $st)")
        return m
    }

    suspend fun start(host: String) {
        withContext(Dispatchers.IO) {
            close()
            val s = Tls.connect(host, 6467, id, 15000)
            sock = s
            val inp = s.inputStream
            val out = s.outputStream
            // 1. pairing request
            Pb.writeDelimited(out, pmsg(Pb.msg(10, Pb.str(1, "androidtv-remote"), Pb.str(2, "Open TV Remote"))))
            expect(inp)
            // 2. options: hexadecimal code, 6 symbols, we act as INPUT
            Pb.writeDelimited(out, pmsg(Pb.msg(20, Pb.msg(1, Pb.int(1, 3), Pb.int(2, 6)), Pb.int(3, 1))))
            expect(inp)
            // 3. configuration -> TV now shows the code on screen
            Pb.writeDelimited(out, pmsg(Pb.msg(30, Pb.msg(1, Pb.int(1, 3), Pb.int(2, 6)), Pb.int(2, 1))))
            expect(inp)
        }
    }

    suspend fun finish(codeRaw: String) {
        withContext(Dispatchers.IO) {
            val s = sock ?: throw IOException("Pairing was not started")
            val code = codeRaw.trim().replace(" ", "")
            if (code.length != 6 || !code.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                throw IOException("Code must be 6 characters (0-9, A-F)")
            }
            val serverKey = s.session.peerCertificates[0].publicKey as RSAPublicKey
            val clientKey = id.cert.publicKey as RSAPublicKey
            val codeBytes = ByteArray(3) { code.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            val md = MessageDigest.getInstance("SHA-256")
            md.update(clientKey.modulus.unsignedBytes())
            md.update(clientKey.publicExponent.unsignedBytes())
            md.update(serverKey.modulus.unsignedBytes())
            md.update(serverKey.publicExponent.unsignedBytes())
            md.update(codeBytes.copyOfRange(1, codeBytes.size))
            val hash = md.digest()
            Pb.writeDelimited(s.outputStream, pmsg(Pb.msg(40, Pb.bytes(1, hash))))
            expect(s.inputStream)
            close()
        }
    }

    fun close() {
        try { sock?.close() } catch (e: Exception) {}
        sock = null
    }
}

// ============================================================
// Remote session (port 6466): persistent connection, near-instant key presses
// ============================================================
class AtvRemote(private val id: Identity) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()
    @Volatile private var sock: SSLSocket? = null
    @Volatile var onClosed: (() -> Unit)? = null

    suspend fun connect(host: String) {
        withContext(Dispatchers.IO) {
            close()
            val s = try {
                Tls.connect(host, 6466, id)
            } catch (e: Unreachable) {
                throw e
            } catch (e: IOException) {
                throw NeedPairing()
            }
            val first = try { Pb.readDelimited(s.inputStream) } catch (e: IOException) { null }
            if (first == null) {
                try { s.close() } catch (e: Exception) {}
                throw NeedPairing()
            }
            sock = s
            s.soTimeout = 0
            val ready = CompletableDeferred<Unit>()
            handle(s, Pb.parse(first), ready)
            scope.launch { readLoop(s, ready) }
            withTimeoutOrNull(6000) { ready.await() }
        }
    }

    private suspend fun readLoop(s: SSLSocket, ready: CompletableDeferred<Unit>) {
        try {
            val inp = s.inputStream
            while (true) {
                val data = Pb.readDelimited(inp) ?: break
                handle(s, Pb.parse(data), ready)
            }
        } catch (e: Exception) {
            // connection dropped
        }
        if (sock === s) {
            close()
            onClosed?.invoke()
        }
    }

    private suspend fun handle(s: SSLSocket, m: Map<Int, List<Any>>, ready: CompletableDeferred<Unit>) {
        when {
            m.containsKey(1) -> send(
                s,
                Pb.msg(
                    1,
                    Pb.int(1, 622),
                    Pb.msg(
                        2,
                        Pb.str(1, "Open TV Remote"),
                        Pb.str(2, "OpenTVRemote"),
                        Pb.int(3, 1),
                        Pb.str(4, "1"),
                        Pb.str(5, "com.opentvremote"),
                        Pb.str(6, "1.0.0")
                    )
                )
            )
            m.containsKey(2) -> {
                send(s, Pb.msg(2, Pb.int(1, 622)))
                ready.complete(Unit)
            }
            m.containsKey(8) -> {
                val v = m.sub(8)?.long(1) ?: 0L
                send(s, Pb.msg(9, Pb.int(1, v.toInt())))
            }
        }
    }

    private suspend fun send(s: SSLSocket, payload: ByteArray) {
        writeLock.withLock {
            withContext(Dispatchers.IO) { Pb.writeDelimited(s.outputStream, payload) }
        }
    }

    suspend fun key(code: Int) {
        val s = sock ?: throw IOException("Not connected")
        send(s, Pb.msg(10, Pb.int(1, code), Pb.int(2, 3)))
    }

    suspend fun launch(link: String) {
        val s = sock ?: throw IOException("Not connected")
        send(s, Pb.msg(90, Pb.str(1, link)))
    }

    private fun charKey(c: Char): Int? = when (c) {
        in 'a'..'z' -> 29 + (c - 'a')
        in '0'..'9' -> 7 + (c - '0')
        ' ' -> 62
        '.' -> 56
        ',' -> 55
        '-' -> 69
        '/' -> 76
        '@' -> 77
        else -> null
    }

    suspend fun typeText(t: String) {
        for (c in t.lowercase()) {
            val k = charKey(c) ?: continue
            key(k)
        }
    }

    fun close() {
        val s = sock
        sock = null
        try { s?.close() } catch (e: Exception) {}
    }
}

// Holds the identity + sessions; the identity (RSA key) is created on first use.
class AtvHub(private val dir: File) {
    var remote: AtvRemote? = null
        private set
    var pairing: AtvPairing? = null
        private set

    suspend fun init() {
        if (remote == null) {
            val id = withContext(Dispatchers.IO) { Identity.loadOrCreate(dir) }
            remote = AtvRemote(id)
            pairing = AtvPairing(id)
        }
    }
}
