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
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Gönderim. Dosya yöneticisinden "Paylaş" ile (ya da launcher'ın kendi seçicisinden) gelen dosyaları, aynı ağda şu an
 * paylaşımı AÇIK olan (mDNS ile "_qshare._tcp." duyuran: MyL, Plan, ATV Basic…) tüm cihazlara otomatik gönderir.
 *
 * Gönderimin kendisi Rust'tadır ([Native.sendStart] → `qshare_core` istemcisi; MyL ile aynı tel biçimi). Burada yalnızca
 * Android'e özgü işler kalır: dosya adresi → ad + açık tanıtıcı, NsdManager ile cihaz bulma, bildirim.
 */
class ShareSendService : Service() {

    companion object {
        private const val TYPE = "_qshare._tcp."
        private val scanTokens = java.util.concurrent.atomic.AtomicLong()
        private const val CH = "share_send"
        private const val NOTIF_ID = 2
        private const val DISCOVER_MS = 6_000L       // cihaz arama süresi
        private const val WATCHDOG_MS = 16_000L      // çözümleme takılırsa zorla bitir
        private const val MAX_FDS = 200              // açık dosya tanıtıcısı sınırı (aşan dosyalar önce kopyalanır)

        private const val EXTRA_PATHS = "paths"

        /** Dosya yolları (launcher'ın kendi seçicisi) ile gönderim başlatır. */
        fun startPaths(c: Context, paths: List<String>): Boolean {
            if (paths.isEmpty() || !Native.ok) return false
            return try {
                val i = Intent(c, ShareSendService::class.java)
                    .putStringArrayListExtra(EXTRA_PATHS, ArrayList(paths))
                c.startForegroundService(i)
                true
            } catch (e: Exception) {
                false
            }
        }

        /** Verilen dosyaları açık cihazlara göndermek için servisi başlatır. */
        fun start(c: Context, uris: List<Uri>): Boolean {
            if (uris.isEmpty() || !Native.ok) return false
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

    private val main = Handler(Looper.getMainLooper())
    private var jobs = 0

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
        intent?.getStringArrayListExtra(EXTRA_PATHS)?.forEach { uris.add(Uri.fromFile(File(it))) }
        if (uris.isEmpty() || !Native.ok) {
            if (!Native.ok) Notice.show(this, getString(R.string.notice_send_failed))
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
        private val seen = HashSet<String>()
        private val pending = java.util.ArrayDeque<NsdServiceInfo>()
        private val early = ArrayList<Pair<String, String>>() // dosyalar hazır olmadan bulunan cihazlar: (ad, "ip:port")
        private var listener: NsdManager.DiscoveryListener? = null

        private var send: Native.SendJob? = null   // dosyalar hazır olunca oluşur
        private var items: List<Native.SendItem> = emptyList()
        private var resolving = false
        private var discovering = true
        private var rustRunning = false              // Rust mDNS taraması sürüyor
        private val addrs = HashSet<String>()        // aynı cihaza (ip:port) iki kez gönderilmesin: NsdManager + Rust
        private val scanToken = scanTokens.incrementAndGet()
        private var closed = false
        private var finished = false

        private val ownId: String? =
            getSharedPreferences(Prefs.FILE, MODE_PRIVATE).getString(Prefs.SHARE_ID, null)
        private val ownName: String? = ownId?.let { "QS" + it.take(10) }

        fun run() {
            // Dosya bilgileri (ad, tanıtıcı) arka planda hazırlanır; cihaz arama bunu beklemez.
            thread(isDaemon = true, name = "send-prepare") { prepareAndStart() }
            startDiscovery()
            startRustScan()
            main.postDelayed({ stopDiscovery() }, DISCOVER_MS)
            main.postDelayed({
                synchronized(lock) {
                    discovering = false
                    rustRunning = false
                    pending.clear()
                    resolving = false
                }
                maybeClose()
            }, WATCHDOG_MS)
        }

        // -------------------------------------------------------------- files

        private fun prepareAndStart() {
            val list = ArrayList<Native.SendItem>()
            var fds = 0
            for (u in uris) {
                try {
                    val item = describe(u, fds < MAX_FDS) ?: continue
                    if (item.fd >= 0) fds++
                    list.add(item)
                } catch (e: Exception) {
                    // bu dosya atlanır
                }
            }
            items = list
            val job = Native.sendStart(list)
            if (job == null) {
                Native.closeFds(list)
                items = emptyList()
                finish(false)
                return
            }
            val queued: List<Pair<String, String>>
            synchronized(lock) {
                send = job
                queued = ArrayList(early)
                early.clear()
            }
            for ((n, a) in queued) Native.sendPeer(job, n, a)
            thread(isDaemon = true, name = "send-events") { eventLoop(job) }
            maybeClose()
        }

        /**
         * Bir adresi gönderilebilir hale getirir. Düz dosya (`file:` ya da düz bir dosyaya açılan `content:`) açık
         * tanıtıcı olarak Rust'a verilir; Rust `pread` ile okur. Boyutu bilinmeyen / aranamayan kaynaklar
         * (boru, gömülü ofset) önce geçici dosyaya kopyalanır.
         */
        private fun describe(u: Uri, allowFd: Boolean): Native.SendItem? {
            val name = displayName(u)
            if (u.scheme == "file") {
                val f = File(u.path ?: return null)
                return if (f.isFile) Native.SendItem(name, f.path, -1, f.length()) else null
            }
            if (allowFd) {
                try {
                    contentResolver.openAssetFileDescriptor(u, "r")?.let { afd ->
                        val pfd = afd.parcelFileDescriptor
                        val regular = try { OsConstants.S_ISREG(Os.fstat(pfd.fileDescriptor).st_mode) } catch (e: Exception) { false }
                        val len = afd.length
                        if (regular && afd.startOffset <= 0L && len >= 0L) {
                            return Native.SendItem(name, null, pfd.detachFd(), len)
                        }
                        afd.close()
                    }
                } catch (e: Exception) {
                    // aşağıda kopyalanır
                }
            }
            val dir = File(cacheDir, "send").apply { mkdirs() }
            val tmp = File(dir, UUID.randomUUID().toString())
            val input = contentResolver.openInputStream(u) ?: return null
            input.use { src -> FileOutputStream(tmp).use { src.copyTo(it, 1 shl 20) } }
            return Native.SendItem(name, tmp.path, -1, tmp.length())
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

        // -------------------------------------------------------------- Rust olayları

        private fun eventLoop(job: Native.SendJob) {
            var ok = 0
            while (true) {
                val ev = Native.sendPoll(job) ?: break
                if (ev.size < 4) continue
                when (ev[0]) {
                    Native.SEND_PROGRESS -> {
                        val pct = ev[3].toIntOrNull() ?: 0
                        progress(getString(R.string.send_sending, ev[1], ev[2]), pct) // Rust en çok ~4/sn bildirir
                    }
                    Native.SEND_DONE -> ok = ev[1].toIntOrNull() ?: 0
                }
            }
            finish(ok > 0 && items.isNotEmpty())
        }

        // -------------------------------------------------------------- discovery

        private fun startDiscovery() {
            val l = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(t: String?, e: Int) {
                    synchronized(lock) { discovering = false }
                    maybeClose()
                }
                override fun onStopDiscoveryFailed(t: String?, e: Int) {}
                override fun onDiscoveryStarted(t: String?) {}
                override fun onDiscoveryStopped(t: String?) {}
                override fun onServiceLost(s: NsdServiceInfo?) {}
                override fun onServiceFound(s: NsdServiceInfo?) {
                    if (s == null) return
                    if (ownName != null && s.serviceName == ownName) return
                    val fresh = synchronized(lock) {
                        if (closed || !discovering) false
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
                maybeClose()
            }
        }

        private fun stopDiscovery() {
            Native.mdnsCancel(scanToken)
            try { listener?.let { nsd.stopServiceDiscovery(it) } } catch (e: Exception) { }
            synchronized(lock) { discovering = false }
            maybeClose()
        }

        /**
         * Rust mDNS taraması (NsdManager'a EK): bulunan tüm cihazlar paralel çözülür. Hiçbir şey bulunamazsa
         * NsdManager sonuçları geçerli kalır. Tarama bitene kadar [maybeClose] bekler.
         */
        private fun startRustScan() {
            if (!Native.ok) return
            synchronized(lock) { rustRunning = true }
            thread(isDaemon = true, name = "send-mdns") {
                var ml: WifiManager.MulticastLock? = null
                try {
                    val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    ml = wm.createMulticastLock("atv-mdns").apply { setReferenceCounted(false); acquire() }
                    val hits = Native.mdnsBrowse(scanToken, localIpv4(), ownName, (DISCOVER_MS - 500).toInt())
                    for (h in hits.orEmpty()) {
                        if (h.port <= 0 || (h.id.isNotEmpty() && h.id == ownId)) continue
                        synchronized(lock) { seen.add(h.instance) }
                        deliver(h.name.ifEmpty { h.instance }, "${h.ip}:${h.port}")
                    }
                } catch (t: Throwable) {
                } finally {
                    try { ml?.release() } catch (e: Exception) { }
                    synchronized(lock) { rustRunning = false }
                    maybeClose()
                }
            }
        }

        /** NsdManager aynı anda tek çözümlemeye izin verir; sırayla çözülür. */
        private fun pump() {
            val next = synchronized(lock) {
                if (resolving) null else pending.poll()?.also { resolving = true }
            }
            if (next == null) {
                maybeClose()
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
            val addr = formatAddr(pickAddress(i) ?: return, i.port) ?: return
            deliver(name, addr)
        }

        /** Bulunan cihazı gönderime ekler; aynı adres ikinci kez (NsdManager + Rust) eklenmez. */
        private fun deliver(name: String, addr: String) {
            val job: Native.SendJob?
            synchronized(lock) {
                if (closed) return
                if (!addrs.add(addr)) return
                job = send
                if (job == null) early.add(name to addr)
            }
            if (job != null) Native.sendPeer(job, name, addr)
        }

        @Suppress("DEPRECATION")
        private fun pickAddress(i: NsdServiceInfo): InetAddress? {
            if (Build.VERSION.SDK_INT >= 34) {
                val all = i.hostAddresses
                return all.firstOrNull { it is Inet4Address } ?: all.firstOrNull()
            }
            return i.host
        }

        /** Rust'a verilen "ip:port". IPv6'da köşeli parantez gerekir; bağlantı-yerel (kapsam gerektiren) adresler atlanır. */
        private fun formatAddr(a: InetAddress, port: Int): String? {
            if (port <= 0) return null
            val host = a.hostAddress?.substringBefore('%') ?: return null
            if (a is Inet4Address) return "$host:$port"
            return if (a.isLinkLocalAddress) null else "[$host]:$port"
        }

        // -------------------------------------------------------------- end

        /** Arama ve çözümleme bitince Rust'a "başka cihaz eklenmeyecek" der; tüm gönderimler bitince "bitti" olayı gelir. */
        private fun maybeClose() {
            val job: Native.SendJob
            val flush: List<Pair<String, String>>
            synchronized(lock) {
                if (closed || discovering || rustRunning || resolving || pending.isNotEmpty()) return
                job = send ?: return // dosyalar hazır değil: hazır olunca [prepareAndStart] yeniden dener
                closed = true
                flush = ArrayList(early)
                early.clear()
            }
            for ((n, a) in flush) Native.sendPeer(job, n, a)
            Native.sendClose(job)
        }

        private fun finish(ok: Boolean) {
            synchronized(lock) {
                if (finished) return
                finished = true
            }
            Native.mdnsCancel(scanToken)
            try { File(cacheDir, "send").deleteRecursively() } catch (e: Exception) { }
            try { listener?.let { nsd.stopServiceDiscovery(it) } } catch (e: Exception) { }
            val msg = getString(if (ok) R.string.notice_sent else R.string.notice_send_failed)
            main.post {
                Notice.show(applicationContext, msg)
                if (--jobs <= 0) stopSelf()
            }
        }
    }

    // ------------------------------------------------------------------ notification

    private fun progress(text: String, pct: Int) {
        try {
            val n = nb.setContentText(text).setProgress(100, pct, false).build()
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
        } catch (e: Exception) {
            // bildirim güncellenemezse gönderim yine de sürer
        }
    }
}

/**
 * Wi-Fi / Ethernet arayüzünün özel (site-local) IPv4 adresi. Rust mDNS soketi bu adrese bağlanır; böylece çok noktaya
 * yayın doğru arayüzden çıkar. Bulunamazsa null: sistem seçer.
 */
private fun localIpv4(): String? = try {
    java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .filter { it.isUp && !it.isLoopback && it.supportsMulticast() }
        .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth") || it.name.startsWith("ap")) 0 else 1 }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
} catch (e: Exception) { null }
