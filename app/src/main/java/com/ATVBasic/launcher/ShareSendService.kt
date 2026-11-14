package com.ATVBasic.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * Gönderim. Dosya yöneticisinden "Paylaş" ile gelen dosyaları, aynı ağda şu an paylaşımı AÇIK olan
 * (mDNS ile "_qshare._tcp." duyuran) tüm cihazlara otomatik gönderir. Cihaz seçme ekranı yoktur.
 * Protokol [ShareService] ile aynıdır: POST + Content-Length + X-Name başlığı.
 */
class ShareSendService : Service() {

    companion object {
        private const val TYPE = "_qshare._tcp."
        private const val CH = "share_send"
        private const val NOTIF_ID = 2
        private const val DISCOVER_MS = 6_000L       // cihaz arama süresi
        private const val WATCHDOG_MS = 16_000L      // çözümleme takılırsa zorla bitir
        private const val BUF = 1 shl 20          // MyL ile aynı: 1 MB okuma/yazma tamponu

        /** Verilen dosyaları açık cihazlara göndermek için servisi başlatır. */
        fun start(c: Context, uris: List<Uri>): Boolean {
            if (uris.isEmpty()) return false
            return try {
                val i = Intent(c, ShareSendService::class.java)
                val clip = ClipData.newRawUri("files", uris[0])
                for (k in 1 until uris.size) clip.addItem(ClipData.Item(uris[k]))
                i.clipData = clip
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                c.startForegroundService(i)
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    private class Item(val name: String, val size: Long, val uri: Uri?, val file: File?)

    private val main = Handler(Looper.getMainLooper())
    private var jobs = 0
    private var lastProgress = 0L

    private val nb by lazy {
        Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_share)
            .setContentTitle(getString(R.string.app_name))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CH, getString(R.string.send_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = nb.setContentText(getString(R.string.send_searching)).setProgress(0, 0, true).build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, n)
        }

        val uris = ArrayList<Uri>()
        val clip = intent?.clipData
        if (clip != null) {
            for (k in 0 until clip.itemCount) clip.getItemAt(k).uri?.let { uris.add(it) }
        }
        if (uris.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        jobs++
        Job(uris).run()
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ job

    private inner class Job(private val uris: List<Uri>) {
        private val lock = Any()
        private val nsd = getSystemService(Context.NSD_SERVICE) as NsdManager
        private val ready = CountDownLatch(1)
        private val items = ArrayList<Item>()
        private val seen = HashSet<String>()
        private val pending = java.util.ArrayDeque<NsdServiceInfo>()
        private var listener: NsdManager.DiscoveryListener? = null

        private var resolving = false
        private var discovering = true
        private var finished = false
        private var active = 0       // yürüyen cihaz gönderimleri
        private var devOk = 0
        private var devFail = 0

        private val ownId: String? =
            getSharedPreferences(Prefs.FILE, MODE_PRIVATE).getString(Prefs.SHARE_ID, null)
        private val ownName: String? = ownId?.let { "QS" + it.take(10) }

        fun run() {
            // Dosya bilgileri (ad, boyut) arka planda hazırlanır; cihaz arama bunu beklemez.
            thread(isDaemon = true, name = "send-prepare") {
                prepare()
                ready.countDown()
                if (items.isEmpty()) finish()
            }
            startDiscovery()
            main.postDelayed({ stopDiscovery() }, DISCOVER_MS)
            main.postDelayed({
                synchronized(lock) {
                    discovering = false
                    pending.clear()
                    resolving = false
                }
                maybeFinish()
            }, WATCHDOG_MS)
        }

        // -------------------------------------------------------------- files

        private fun prepare() {
            for (u in uris) {
                try {
                    val name = displayName(u)
                    val size = lengthOf(u)
                    if (size >= 0) {
                        items.add(Item(name, size, u, null))
                    } else {
                        // Boyut bilinmiyorsa Content-Length için önce geçici dosyaya kopyalanır.
                        val dir = File(cacheDir, "send").apply { mkdirs() }
                        val tmp = File(dir, UUID.randomUUID().toString())
                        val input = contentResolver.openInputStream(u) ?: continue
                        input.use { src -> FileOutputStream(tmp).use { src.copyTo(it, BUF) } }
                        items.add(Item(name, tmp.length(), null, tmp))
                    }
                } catch (e: Exception) {
                    // bu dosya atlanır
                }
            }
        }

        private fun displayName(u: Uri): String {
            var name: String? = null
            try {
                contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) name = it.getString(0)
                }
            } catch (e: Exception) { }
            if (name.isNullOrBlank()) name = u.lastPathSegment?.substringAfterLast('/')
            var n = if (name.isNullOrBlank()) "file" else name!!
            if (!n.contains('.')) {
                val mime = try { contentResolver.getType(u) } catch (e: Exception) { null }
                val ext = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
                if (ext != null) n = "$n.$ext"
            }
            return n
        }

        private fun lengthOf(u: Uri): Long {
            try {
                contentResolver.openAssetFileDescriptor(u, "r")?.use {
                    if (it.length >= 0) return it.length
                }
            } catch (e: Exception) { }
            try {
                contentResolver.query(u, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                    if (it.moveToFirst() && !it.isNull(0)) {
                        val s = it.getLong(0)
                        if (s >= 0) return s
                    }
                }
            } catch (e: Exception) { }
            return -1L
        }

        private fun open(item: Item): InputStream? =
            if (item.file != null) FileInputStream(item.file) else contentResolver.openInputStream(item.uri!!)

        // -------------------------------------------------------------- discovery

        private fun startDiscovery() {
            val l = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(t: String?, e: Int) {
                    synchronized(lock) { discovering = false }
                    maybeFinish()
                }
                override fun onStopDiscoveryFailed(t: String?, e: Int) {}
                override fun onDiscoveryStarted(t: String?) {}
                override fun onDiscoveryStopped(t: String?) {}
                override fun onServiceLost(s: NsdServiceInfo?) {}
                override fun onServiceFound(s: NsdServiceInfo?) {
                    if (s == null) return
                    if (ownName != null && s.serviceName == ownName) return
                    val fresh = synchronized(lock) {
                        if (finished || !discovering) false
                        else if (seen.add(s.serviceName)) { pending.add(s); true } else false
                    }
                    if (fresh) pump()
                }
            }
            listener = l
            try {
                nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, l)
            } catch (e: Exception) {
                synchronized(lock) { discovering = false }
                maybeFinish()
            }
        }

        private fun stopDiscovery() {
            try { listener?.let { nsd.stopServiceDiscovery(it) } } catch (e: Exception) { }
            synchronized(lock) { discovering = false }
            maybeFinish()
        }

        /** NsdManager aynı anda tek çözümlemeye izin verir; sırayla çözülür. */
        private fun pump() {
            val next = synchronized(lock) {
                if (resolving) null else pending.poll()?.also { resolving = true }
            }
            if (next == null) {
                maybeFinish()
                return
            }
            try {
                @Suppress("DEPRECATION")
                nsd.resolveService(next, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo?, e: Int) { resolved(null) }
                    override fun onServiceResolved(i: NsdServiceInfo?) { resolved(i) }
                })
            } catch (e: Exception) {
                resolved(null)
            }
        }

        private fun resolved(info: NsdServiceInfo?) {
            if (info != null) onDevice(info)
            synchronized(lock) { resolving = false }
            pump()
        }

        private fun attr(i: NsdServiceInfo, k: String): String? =
            i.attributes[k]?.let { String(it, Charsets.UTF_8) }

        private fun onDevice(i: NsdServiceInfo) {
            val id = attr(i, "i")
            if (id != null && id == ownId) return
            val name = attr(i, "n") ?: i.serviceName
            val addr = pickAddress(i) ?: return
            val port = i.port
            if (port <= 0) return

            synchronized(lock) {
                if (finished) return
                active++
            }
            thread(isDaemon = true, name = "send-$name") {
                val ok = try { sendAll(addr, port, name) } catch (e: Exception) { false }
                synchronized(lock) {
                    active--
                    if (ok) devOk++ else devFail++
                }
                maybeFinish()
            }
        }

        @Suppress("DEPRECATION")
        private fun pickAddress(i: NsdServiceInfo): InetAddress? {
            if (Build.VERSION.SDK_INT >= 34) {
                val all = i.hostAddresses
                return all.firstOrNull { it is Inet4Address } ?: all.firstOrNull()
            }
            return i.host
        }

        // -------------------------------------------------------------- sending

        private fun sendAll(addr: InetAddress, port: Int, dev: String): Boolean {
            ready.await()
            if (items.isEmpty()) return false
            var all = true
            for (item in items) {
                val ok = try { sendOne(addr, port, item, dev) } catch (e: Exception) { false }
                if (!ok) all = false
            }
            return all
        }

        private fun sendOne(addr: InetAddress, port: Int, item: Item, dev: String): Boolean {
            val src = open(item) ?: return false
            return src.use { input ->
                Socket().use { s ->
                    s.sendBufferSize = 1 shl 20
                    s.tcpNoDelay = true
                    s.connect(InetSocketAddress(addr, port), 5_000)
                    s.soTimeout = 30_000
                    val out = s.getOutputStream()
                    val head = "POST /u HTTP/1.1\r\n" +
                        "Host: ${addr.hostAddress}:$port\r\n" +
                        "Content-Type: application/octet-stream\r\n" +
                        "Content-Length: ${item.size}\r\n" +
                        "X-Name: ${URLEncoder.encode(item.name, "UTF-8")}\r\n" +
                        "Connection: close\r\n\r\n"
                    out.write(head.toByteArray(Charsets.US_ASCII))

                    val buf = ByteArray(BUF)
                    var sent = 0L
                    var last = -1
                    while (sent < item.size) {
                        val want = minOf(buf.size.toLong(), item.size - sent).toInt()
                        val n = input.read(buf, 0, want)
                        if (n < 0) return false
                        out.write(buf, 0, n)
                        sent += n
                        val p = if (item.size > 0) (sent * 100 / item.size).toInt() else 100
                        if (p != last) {
                            last = p
                            progress(getString(R.string.send_sending, item.name, dev), p)
                        }
                    }
                    out.flush()

                    // Alıcı, dosya diske tamamen yazılınca "200 OK" döner.
                    val status = readLine(s.getInputStream())
                    status.startsWith("HTTP/") && status.contains(" 200")
                }
            }
        }

        private fun readLine(i: InputStream): String {
            val sb = StringBuilder()
            while (sb.length < 8192) {
                val c = i.read()
                if (c < 0 || c == 10) break
                if (c != 13) sb.append(c.toChar())
            }
            return sb.toString()
        }

        // -------------------------------------------------------------- end

        private fun maybeFinish() {
            val fin = synchronized(lock) {
                if (finished || discovering || resolving || pending.isNotEmpty() || active > 0) {
                    false
                } else {
                    finished = true
                    true
                }
            }
            if (fin) finish()
        }

        private fun finish() {
            synchronized(lock) {
                if (!finished) finished = true
            }
            try { File(cacheDir, "send").deleteRecursively() } catch (e: Exception) { }
            try { listener?.let { nsd.stopServiceDiscovery(it) } } catch (e: Exception) { }

            val msg = when {
                items.isEmpty() -> getString(R.string.send_no_files)
                devOk == 0 && devFail == 0 -> getString(R.string.send_no_device)
                devFail == 0 -> getString(R.string.send_done, devOk)
                devOk == 0 -> getString(R.string.send_failed)
                else -> getString(R.string.send_partial, devOk, devFail)
            }
            main.post {
                Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
                if (--jobs <= 0) stopSelf()
            }
        }
    }

    // ------------------------------------------------------------------ notification

    private fun progress(text: String, pct: Int) {
        val now = SystemClock.elapsedRealtime()
        if (pct < 100 && now - lastProgress < 250) return
        lastProgress = now
        try {
            val n = nb.setContentText(text).setProgress(100, pct, false).build()
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
        } catch (e: Exception) {
            // bildirim güncellenemezse gönderim yine de sürer
        }
    }
}
