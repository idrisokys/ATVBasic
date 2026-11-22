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
import android.provider.MediaStore
import android.provider.Settings
import android.webkit.MimeTypeMap
import java.io.File
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Paylaşım. Açıkken bu cihaz ağda QuickShare cihazı olarak görünür (mDNS) ve gelen dosyaları
 * İndirilenler (Download) klasörüne kaydeder. Arayüzü yoktur: ana ekrandaki paylaşım simgesi servisi açar / kapatır.
 *
 * Alma sunucusu Rust'tadır ([Native.serverStart]; MyL ve Plan ile ORTAK `qshare_core`): HTTP ayrıştırma, dosyayı
 * `.part` olarak yazma, ad temizleme, aynı ad için `ad_1.uzantı`. Burada yalnızca Android'e özgü işler kalır:
 * mDNS duyurusu (NsdManager), bildirimler, Wi-Fi/CPU kilitleri, depolama izni ve MediaStore.
 */
class ShareService : Service() {

    companion object {
        @Volatile var running = false

        /** Servis açıldı / kapandı: ana ekran simge rengini günceller (ana iş parçacığında çağrılır). */
        @Volatile var onChange: (() -> Unit)? = null

        private const val TYPE = "_qshare._tcp."
        private const val CH_RUN = "share_run"
        private const val CH_RECV = "share_recv"
        private const val PORT_KEY = "share_port"

        private val main = Handler(Looper.getMainLooper())

        private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)

        fun isOn(c: Context): Boolean = prefs(c).getBoolean(Prefs.SHARE_ON, false)

        fun setOn(c: Context, on: Boolean) {
            prefs(c).edit().putBoolean(Prefs.SHARE_ON, on).apply()
        }

        /** Servisi başlatır; yerel kütüphane yoksa ya da arka plan kısıtı vb. yüzünden başlatılamazsa false döner. */
        fun start(c: Context): Boolean {
            if (!Native.ok) return false
            return try {
                c.startForegroundService(Intent(c, ShareService::class.java))
                true
            } catch (e: Exception) {
                false
            }
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, ShareService::class.java))
        }

        private fun changed() {
            main.post { onChange?.invoke() }
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    private val backToWaiting = Runnable { show(getString(R.string.qs_notif_waiting), -1) }
    private var server: Native.Server? = null
    private var nsd: NsdManager? = null
    private var reg: NsdManager.RegistrationListener? = null

    /** Android 10 ve izin yokken Rust'un yazdığı geçici klasör; dosyalar buradan MediaStore'a taşınır. */
    private var inbox: File? = null

    private var holds = 0
    private var wifiLock: WifiManager.WifiLock? = null
    private var wifiLock2: WifiManager.WifiLock? = null
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

        val srv = startServer()
        if (srv == null) {
            setOn(this, false)
            stopSelf()
            return
        }
        server = srv
        try {
            announce(srv.port)
        } catch (e: Exception) {
            android.util.Log.w("ShareService", "mDNS duyurusu başlatılamadı", e)
        }
        thread(isDaemon = true, name = "share-events") { eventLoop(srv) }
        running = true
        changed()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        running = false
        ui.removeCallbacksAndMessages(null)
        server?.let { Native.serverStop(it) } // olay döngüsü null alıp kendiliğinden biter
        server = null
        try { reg?.let { nsd?.unregisterService(it) } } catch (e: Exception) { }
        releaseAll()
        changed()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ Rust sunucusu

    /**
     * Rust alma sunucusunu başlatır. Önceki port yeniden denenir: servis yeniden başlayınca port değişmez,
     * karşı cihazların önbelleği geçerli kalır; olmazsa boş port seçilir.
     */
    private fun startServer(): Native.Server? {
        Native.setLang()
        val recv = recvDir() ?: return null
        val sp = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
        val saved = sp.getInt(PORT_KEY, 0)
        val s = (if (saved > 0) Native.serverStart(recv, null, null, null, saved) else null)
            ?: Native.serverStart(recv, null, null, null, 0)
        if (s != null) sp.edit().putInt(PORT_KEY, s.port).apply()
        return s
    }

    /** Gelen dosyaların yazılacağı klasör: izin varsa İndirilenler, yoksa uygulamanın geçici gelen kutusu. */
    @Suppress("DEPRECATION")
    private fun recvDir(): File? {
        if (canWriteRoot()) {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (dir.isDirectory || dir.mkdirs()) return dir
        }
        // İzin yoksa: Android 10+ dosyayı sonradan MediaStore ile İndirilenler'e taşırız; eski sürümlerde vazgeç.
        if (Build.VERSION.SDK_INT < 29) return null
        val box = File(filesDir, "inbox")
        if (!box.isDirectory && !box.mkdirs()) return null
        inbox = box
        return box
    }

    /** Rust sunucusunun olaylarını bekler; sunucu kapanınca (null) biter. */
    private fun eventLoop(srv: Native.Server) {
        while (true) {
            val ev = Native.serverPoll(srv) ?: break
            if (ev.size < 3) continue
            val name = ev[1]
            when (ev[0]) {
                Native.EV_BUSY_ON -> acquire()
                Native.EV_BUSY_OFF -> release()
                Native.EV_RECEIVING -> busy(getString(R.string.qs_receiving, name))
                Native.EV_RECEIVED -> {
                    val moved = inbox?.let { drainInbox(it) } ?: true
                    if (inbox == null) scanSaved(ev[2], name)
                    if (moved) {
                        status(getString(if (inbox == null) R.string.qs_saved else R.string.qs_saved_fallback, name), -1)
                        notifyReceived(name)
                    } else {
                        status(getString(R.string.qs_error), -1)
                    }
                }
                Native.EV_FAILED -> status(getString(R.string.qs_partial), -1)
            }
        }
    }

    // ------------------------------------------------------------------ mDNS

    /** Cihazı ağda duyurur; diğer QuickShare cihazları (MyL, Plan, Android, Mac) bunu görüp dosya gönderir. */
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
            override fun onRegistrationFailed(s: NsdServiceInfo?, e: Int) { android.util.Log.w("ShareService", "mDNS kaydı başarısız: $e") }
            override fun onUnregistrationFailed(s: NsdServiceInfo?, e: Int) {}
            override fun onServiceRegistered(s: NsdServiceInfo?) {}
            override fun onServiceUnregistered(s: NsdServiceInfo?) {}
        }
        nsd = m
        reg = l
        m.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
    }

    // ------------------------------------------------------------------ storage: Download (İndirilenler)

    /**
     * Rust aynı adlı dosya için `ad_1.uzantı` üretebilir; olayda yalnızca özgün ad vardır. Klasörde bu adla ya da
     * `ad_N.uzantı` biçimiyle eşleşen en yeni dosya medya tarayıcısına bildirilir (galeri/dosya yöneticisi görsün).
     */
    private fun scanSaved(folder: String, name: String) {
        try {
            val dir = File(folder)
            val base = name.substringBeforeLast('.', name)
            val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
            val numbered = Regex(Regex.escape(base) + "_\\d+" + Regex.escape(ext))
            val newest = dir.listFiles { f -> f.isFile && (f.name == name || numbered.matches(f.name)) }
                ?.maxByOrNull { it.lastModified() } ?: return
            MediaScannerConnection.scanFile(this, arrayOf(newest.path), null, null)
        } catch (e: Exception) {
            // tarama başarısız olsa da dosya yerinde
        }
    }

    /** İndirilenler klasörüne File API ile doğrudan yazılabilir mi? */
    @Suppress("DEPRECATION")
    private fun canWriteRoot(): Boolean = when {
        Build.VERSION.SDK_INT >= 30 -> Environment.isExternalStorageManager()
        Build.VERSION.SDK_INT >= 29 -> false // Android 10: izin maxSdk=28 ile sınırlı, MediaStore kullanılır
        else -> checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Gelen kutusundaki TAMAMLANMIŞ dosyaları (Rust yarımları `.part` olarak yazar) MediaStore ile İndirilenler'e taşır.
     * Hepsi taşındıysa true.
     */
    @SuppressLint("NewApi")
    @Synchronized
    private fun drainInbox(box: File): Boolean {
        var all = true
        val files = box.listFiles { f -> f.isFile && !f.name.endsWith(".part") } ?: return true
        for (f in files) {
            if (!moveToDownloads(f)) all = false
        }
        return all
    }

    @SuppressLint("NewApi")
    private fun moveToDownloads(f: File): Boolean {
        val r = contentResolver
        val ext = f.name.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val v = ContentValues()
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime)
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        v.put(MediaStore.MediaColumns.IS_PENDING, 1)
        val uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return false
        return try {
            val os = r.openOutputStream(uri) ?: throw java.io.IOException("no stream")
            os.use { out -> f.inputStream().use { it.copyTo(out, 256 * 1024) } }
            v.clear()
            v.put(MediaStore.MediaColumns.IS_PENDING, 0)
            r.update(uri, v, null, null)
            f.delete()
            true
        } catch (e: Exception) {
            try { r.delete(uri, null, null) } catch (e2: Exception) { }
            false
        }
    }

    // ------------------------------------------------------------------ notifications

    private fun show(text: String, pct: Int) {
        try {
            val b = nb.setContentText(text)
            if (pct >= 0) b.setProgress(100, pct, false) else b.setProgress(0, 0, false)
            getSystemService(NotificationManager::class.java).notify(1, b.build())
        } catch (e: Exception) {
            // bildirim izni yoksa servis yine de çalışır
        }
    }

    /** Alma sürüyor: belirsiz ilerleme çubuğu (Rust yüzde bildirmez); sonuç gelene kadar "bekliyor"a dönülmez. */
    private fun busy(text: String) {
        ui.post {
            ui.removeCallbacks(backToWaiting)
            try {
                val n = nb.setContentText(text).setProgress(0, 0, true).build()
                getSystemService(NotificationManager::class.java).notify(1, n)
            } catch (e: Exception) { }
        }
    }

    /** Sonuç metni; 4 sn sonra "bekliyor"a döner. */
    private fun status(text: String, pct: Int) {
        ui.post {
            ui.removeCallbacks(backToWaiting)
            show(text, pct)
            if (pct < 0) ui.postDelayed(backToWaiting, 4000)
        }
    }

    private fun notifyReceived(name: String) {
        Notice.show(this, getString(R.string.notice_received))
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
            // Android 14 öncesinde HIGH_PERF de tutulur: düşük gecikme kilidi yalnızca ön plan + ekran açıkken etkindir,
            // arka planda alırken Wi-Fi güç tasarrufu hızı düşürmesin.
            if (Build.VERSION.SDK_INT in 29..33) {
                wifiLock2 = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "share2").apply {
                    setReferenceCounted(false)
                    acquire()
                }
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
        try { wifiLock2?.release() } catch (e: Exception) { }
        wifiLock2 = null
        try { cpuLock?.release() } catch (e: Exception) { }
        wifiLock = null
        cpuLock = null
    }
}
