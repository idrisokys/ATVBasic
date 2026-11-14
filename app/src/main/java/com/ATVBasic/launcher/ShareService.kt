package com.ATVBasic.launcher

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.webkit.MimeTypeMap
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Paylaşım. Açıkken bu cihaz ağda QuickShare cihazı olarak görünür (mDNS) ve gelen dosyaları
 * İndirilenler/Link klasörüne kaydeder. Arayüzü yoktur: ana ekrandaki paylaşım simgesi
 * servisi açar / kapatır.
 */
class ShareService : Service() {

    companion object {
        @Volatile var running = false

        /** Servis açıldı / kapandı: ana ekran simge rengini günceller (ana iş parçacığında çağrılır). */
        @Volatile var onChange: (() -> Unit)? = null

        private const val TYPE = "_qshare._tcp."
        private const val CH_RUN = "share_run"
        private const val CH_RECV = "share_recv"
        private const val BUF = 1 shl 20
        private const val CH = 512 * 1024      // ağ okuma ile disk yazmayı ayıran tampon parçası
        private const val SLOTS = 8            // 8 x 512 KB = 4 MB tampon havuzu

        private val main = Handler(Looper.getMainLooper())

        private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)

        fun isOn(c: Context): Boolean = prefs(c).getBoolean(Prefs.SHARE_ON, false)

        fun setOn(c: Context, on: Boolean) {
            prefs(c).edit().putBoolean(Prefs.SHARE_ON, on).apply()
        }

        /** Servisi başlatır; arka plan kısıtı vb. yüzünden başlatılamazsa false döner. */
        fun start(c: Context): Boolean = try {
            c.startForegroundService(Intent(c, ShareService::class.java))
            true
        } catch (e: Exception) {
            false
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, ShareService::class.java))
        }

        private fun changed() {
            main.post { onChange?.invoke() }
        }
    }

    private class Chunk(val b: ByteArray, val n: Int) // n < 0: bitti işareti

    private class Sink(val out: OutputStream, val commit: () -> Boolean, val abort: () -> Unit)

    private val ui = Handler(Looper.getMainLooper())
    private val backToWaiting = Runnable { show(getString(R.string.qs_notif_waiting), -1) }
    private var server: ServerSocket? = null
    private var nsd: NsdManager? = null
    private var reg: NsdManager.RegistrationListener? = null
    private var lastProgress = 0L

    private var holds = 0
    private var wifiLock: WifiManager.WifiLock? = null
    private var cpuLock: PowerManager.WakeLock? = null

    private val nb by lazy {
        Notification.Builder(this, CH_RUN)
            .setSmallIcon(R.drawable.ic_share)
            .setContentTitle(getString(R.string.app_name))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_RUN, getString(R.string.qs_channel_run), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_RECV, getString(R.string.qs_channel_recv), NotificationManager.IMPORTANCE_HIGH),
        )
        val n = nb.setContentText(getString(R.string.qs_notif_waiting)).build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1, n)
        }

        try {
            // Tampon, bind'dan ÖNCE ayarlanmalı; yoksa büyük TCP penceresi (hız) devreye girmez.
            val ss = ServerSocket().apply {
                reuseAddress = true
                receiveBufferSize = BUF
                bind(InetSocketAddress(0))
            }
            server = ss
            thread(isDaemon = true, name = "share-accept") {
                while (true) {
                    val c = try { ss.accept() } catch (e: Exception) { break }
                    thread(isDaemon = true, name = "share-rx") { handle(c) }
                }
            }
            announce(ss.localPort)
        } catch (e: Exception) {
            setOn(this, false)
            stopSelf()
            return
        }
        running = true
        changed()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        running = false
        ui.removeCallbacksAndMessages(null)
        try { server?.close() } catch (e: Exception) { }
        try { reg?.let { nsd?.unregisterService(it) } } catch (e: Exception) { }
        releaseAll()
        changed()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ mDNS

    /** Cihazı ağda duyurur; diğer QuickShare cihazları (Android, Mac) bunu görüp dosya gönderir. */
    private fun announce(port: Int) {
        val sp = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
        val devId = sp.getString(Prefs.SHARE_ID, null)
            ?: UUID.randomUUID().toString().replace("-", "").also { sp.edit().putString(Prefs.SHARE_ID, it).apply() }
        val devName = Settings.Global.getString(contentResolver, "device_name") ?: Build.MODEL

        val info = NsdServiceInfo()
        info.serviceName = "QS" + devId.take(10)
        info.serviceType = TYPE
        info.port = port
        info.setAttribute("n", devName)
        info.setAttribute("i", devId)

        val m = getSystemService(Context.NSD_SERVICE) as NsdManager
        val l = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(s: NsdServiceInfo?, e: Int) {}
            override fun onUnregistrationFailed(s: NsdServiceInfo?, e: Int) {}
            override fun onServiceRegistered(s: NsdServiceInfo?) {}
            override fun onServiceUnregistered(s: NsdServiceInfo?) {}
        }
        nsd = m
        reg = l
        m.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
    }

    // ------------------------------------------------------------------ receiving

    private fun line(i: InputStream): String {
        val sb = StringBuilder()
        while (sb.length < 8192) {
            val c = i.read()
            if (c < 0 || c == 10) break
            if (c != 13) sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun reply(s: Socket, st: String) {
        s.outputStream.write("HTTP/1.1 $st\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        s.outputStream.flush()
    }

    private fun handle(s: Socket) {
        acquire()
        try {
            s.use {
                s.soTimeout = 30000
                s.tcpNoDelay = true
                val ins = BufferedInputStream(s.inputStream, 64 * 1024)
                line(ins)
                var len = -1L
                var name = "file"
                while (true) {
                    val l = line(ins)
                    if (l.isEmpty()) break
                    val k = l.substringBefore(':').lowercase()
                    val v = l.substringAfter(':').trim()
                    if (k == "content-length") len = v.toLongOrNull() ?: -1L
                    else if (k == "x-name") name = URLDecoder.decode(v, "UTF-8")
                }
                if (len < 0) {
                    reply(s, "400 Bad Request")
                    return
                }
                val clean = name.substringAfterLast('/').substringAfterLast('\\')
                    .replace(Regex("[\\u0000-\\u001f:*?\"<>|]"), "_").trimStart('.').take(120).ifBlank { "file" }

                val sink = openSink(clean)
                if (sink == null) {
                    reply(s, "500 Error")
                    status(getString(R.string.qs_error), -1)
                    return
                }

                var ok = false
                try {
                    sink.out.use { out ->
                        // Okuyucu ağdan okur, yazıcı iş parçacığı diske yazar: yavaş TV deposu ağı bekletmez.
                        val free = ArrayBlockingQueue<ByteArray>(SLOTS).also { q -> repeat(SLOTS) { q.add(ByteArray(CH)) } }
                        val full = ArrayBlockingQueue<Chunk>(SLOTS + 1)
                        val werr = AtomicBoolean(false)
                        val wr = thread(isDaemon = true, name = "share-write") {
                            while (true) {
                                val c = try { full.take() } catch (e: InterruptedException) { break }
                                if (c.n < 0) break
                                if (!werr.get()) try { out.write(c.b, 0, c.n) } catch (e: Exception) { werr.set(true) }
                                free.add(c.b)
                            }
                        }
                        var left = len
                        var last = -1
                        try {
                            while (left > 0 && !werr.get()) {
                                val b = free.poll(30, TimeUnit.SECONDS) ?: break
                                val want = minOf(CH.toLong(), left).toInt()
                                var fill = 0
                                while (fill < want) {
                                    val n = ins.read(b, fill, want - fill)
                                    if (n < 0) break
                                    fill += n
                                }
                                if (fill > 0) full.put(Chunk(b, fill)) else free.add(b)
                                left -= fill
                                val p = ((len - left) * 100 / len).toInt()
                                if (p != last) {
                                    last = p
                                    progress(getString(R.string.qs_receiving, clean), p)
                                }
                                if (fill < want) break
                            }
                        } finally {
                            full.put(Chunk(ByteArray(0), -1))
                            wr.join() // bütün yazmalar bitmeden dosya kapanmaz
                        }
                        ok = left == 0L && !werr.get()
                    }
                } finally {
                    ok = try { if (ok) sink.commit() else { sink.abort(); false } } catch (e: Exception) { false }
                }
                reply(s, if (ok) "200 OK" else "500 Error")
                status(if (ok) getString(R.string.qs_saved, clean) else getString(R.string.qs_partial), -1)
                if (ok) notifyReceived(clean)
            }
        } catch (e: Exception) {
            status(getString(R.string.qs_error), -1)
        } finally {
            release()
        }
    }

    // ------------------------------------------------------------------ storage: Download/Link

    private fun openSink(name: String): Sink? =
        if (Build.VERSION.SDK_INT >= 29) mediaSink(name) else legacySink(name)

    /** Android 10+: izin gerekmez; dosya İndirilenler/Link altına MediaStore ile yazılır. */
    @SuppressLint("NewApi")
    private fun mediaSink(name: String): Sink? {
        val r = contentResolver
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val v = ContentValues()
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime)
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Link")
        v.put(MediaStore.MediaColumns.IS_PENDING, 1)
        val uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return null
        val os = try { r.openOutputStream(uri) } catch (e: Exception) { null }
        if (os == null) {
            r.delete(uri, null, null)
            return null
        }
        return Sink(
            os,
            {
                v.clear()
                v.put(MediaStore.MediaColumns.IS_PENDING, 0)
                r.update(uri, v, null, null) > 0
            },
            { r.delete(uri, null, null) },
        )
    }

    /** Android 8/9: depolama izniyle İndirilenler/Link klasörüne yazılır. */
    @Suppress("DEPRECATION")
    private fun legacySink(name: String): Sink? {
        if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Link")
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val tmp = File(dir, "$name.${UUID.randomUUID().toString().take(8)}.part")
        val os = try { FileOutputStream(tmp) } catch (e: Exception) { return null }
        return Sink(
            os,
            {
                val dest = File(dir, name)
                dest.delete()
                val moved = tmp.renameTo(dest)
                if (moved) MediaScannerConnection.scanFile(this, arrayOf(dest.path), null, null) else tmp.delete()
                moved
            },
            { tmp.delete() },
        )
    }

    // ------------------------------------------------------------------ notifications

    private fun show(text: String, pct: Int) {
        try {
            val n = nb.setContentText(text).setProgress(if (pct >= 0) 100 else 0, pct.coerceAtLeast(0), false).build()
            getSystemService(NotificationManager::class.java).notify(1, n)
        } catch (e: Exception) {
            // bildirim izni yoksa servis yine de çalışır
        }
    }

    private fun status(text: String, pct: Int) {
        ui.post {
            ui.removeCallbacks(backToWaiting)
            show(text, pct)
            if (pct < 0) ui.postDelayed(backToWaiting, 4000)
        }
    }

    /** İlerleme güncellemesi: en fazla ~4/sn, %100 her zaman geçer. */
    private fun progress(text: String, pct: Int) {
        val now = SystemClock.elapsedRealtime()
        if (pct < 100 && now - lastProgress < 250) return
        lastProgress = now
        status(text, pct)
    }

    private fun notifyReceived(name: String) {
        try {
            val n = Notification.Builder(this, CH_RECV)
                .setSmallIcon(R.drawable.ic_share)
                .setContentTitle(getString(R.string.qs_received))
                .setContentText(name)
                .setAutoCancel(true)
                .build()
            val id = 1000 + (System.nanoTime() % 100000).toInt().let { if (it < 0) -it else it }
            getSystemService(NotificationManager::class.java).notify(id, n)
        } catch (e: Exception) {
            // yoksay
        }
    }

    // ------------------------------------------------------------------ Wi-Fi / CPU locks while receiving

    @Suppress("DEPRECATION")
    @Synchronized
    private fun acquire() {
        if (holds++ > 0) return
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm.createWifiLock(mode, "share").apply {
                setReferenceCounted(false)
                acquire()
            }
            cpuLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "atvbasic:share").apply {
                    setReferenceCounted(false)
                    acquire(30 * 60 * 1000L)
                }
        } catch (e: Exception) {
            // kilitler alınamazsa aktarım yine de sürer
        }
    }

    @Synchronized
    private fun release() {
        if (holds == 0 || --holds > 0) return
        dropLocks()
    }

    @Synchronized
    private fun releaseAll() {
        holds = 0
        dropLocks()
    }

    private fun dropLocks() {
        try { wifiLock?.release() } catch (e: Exception) { }
        try { cpuLock?.release() } catch (e: Exception) { }
        wifiLock = null
        cpuLock = null
    }
}
