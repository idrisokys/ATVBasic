package com.ATVBasic.launcher

import java.io.File

/**
 * Rust ile yazılmış yerel kütüphane köprüsü (`native/` → libatvnative.so).
 *
 * Arayüz dışındaki iş mantığı Rust'tadır: paylaşım (MyL ile ORTAK `qshare_core` protokolü), dosya listeleme,
 * duvar kağıdı. Burası yalnızca ince bir JNI sarmalayıcısıdır; yedek Kotlin kodu YOKTUR.
 * Kütüphane yüklenemezse (ör. desteklenmeyen işlemci) her sarmalayıcı null döner; launcher'ın kendisi
 * (ana ekran, uygulamalar) çalışmaya devam eder, yalnızca paylaşım ve duvar kağıdı özellikleri devre dışı kalır.
 */
internal object Native {

    /** Kütüphane yüklendi ve çalışıyor mu? */
    val ok: Boolean by lazy {
        try {
            System.loadLibrary("atvnative")
            nVersion() > 0
        } catch (t: Throwable) {
            false
        }
    }

    @JvmStatic private external fun nVersion(): Int
    @JvmStatic private external fun nSetLang(english: Boolean)
    @JvmStatic private external fun nServerStart(recvDir: String, linkRoot: String?, dlRoot: String?, trash: String?, port: Int): Long
    @JvmStatic private external fun nServerPoll(id: Long): Array<String>?
    @JvmStatic private external fun nServerStop(id: Long)
    @JvmStatic private external fun nSendStart(names: Array<String>, paths: Array<String>, fds: IntArray, lens: LongArray): Long
    @JvmStatic private external fun nSendPeer(id: Long, dev: String, addr: String)
    @JvmStatic private external fun nSendClose(id: Long)
    @JvmStatic private external fun nSendPoll(id: Long): Array<String>?
    @JvmStatic private external fun nListDir(dir: String): Array<String>?
    @JvmStatic private external fun nResolveInside(root: String, path: String): String?
    @JvmStatic private external fun nWpListingUrl(): String?
    @JvmStatic private external fun nWpFallbackUrl(): String?
    @JvmStatic private external fun nWpPoolLoad(body: String): Int
    @JvmStatic private external fun nWpNext(): String?
    @JvmStatic private external fun nWpImport(src: String, dest: String, displayW: Int, displayH: Int, fit: Boolean): Boolean
    @JvmStatic private external fun nWpSystemImage(src: String, out: String, displayW: Int, displayH: Int, tv: Boolean): Boolean
    @JvmStatic private external fun nWpStamp(path: String): Long

    private inline fun <T> safe(block: () -> T?): T? =
        if (!ok) null else try { block() } catch (t: Throwable) { null }

    // ------------------------------------------------------------ paylaşım: alma sunucusu

    /** Alma sunucusu: [id] tutamaç, [port] dinlenen port. */
    class Server(val id: Long, val port: Int)

    const val EV_RECEIVING = "1"   // ad
    const val EV_RECEIVED = "2"    // ad, klasör
    const val EV_FAILED = "3"      // ad
    const val EV_SYNC = "4"        // (eşitleme dosyaları değiştirdi; ATV Basic eşitlemeyi açmaz)
    const val EV_BUSY_ON = "5"     // bağlantı başladı: Wi-Fi/CPU kilidi tut
    const val EV_BUSY_OFF = "6"    // bağlantı bitti

    /** Rust'tan dönen hata/özet metinlerinin dili (cihaz dili Türkçe değilse İngilizce). */
    fun setLang() {
        safe { nSetLang(java.util.Locale.getDefault().language != "tr"); true }
    }

    /**
     * Alma sunucusunu başlatır (port 0: boş port). Gelen dosyalar [recvDir]'e yazılır; aynı adlı dosya `ad_1.uzantı` olur
     * (MyL ile aynı). [linkRoot] / [dlRoot] null ise MyL'nin Link / Download eşitlemesi kapalıdır.
     * Başarısızsa ya da kütüphane yoksa null.
     */
    fun serverStart(recvDir: File, linkRoot: File?, dlRoot: File?, trash: File?, port: Int): Server? = safe {
        val v = nServerStart(recvDir.path, linkRoot?.path, dlRoot?.path, trash?.path, port)
        if (v < 0) null else Server(v ushr 16, (v and 0xFFFF).toInt())
    }

    /** Sunucu olayını bekler (en çok ~1,5 sn): [tür, ad, klasör]; zaman aşımında boş dizi; sunucu kapandıysa null. */
    fun serverPoll(s: Server): Array<String>? = safe { nServerPoll(s.id) }

    fun serverStop(s: Server) {
        safe { nServerStop(s.id); true }
    }

    // ------------------------------------------------------------ paylaşım: gönderme

    /** Gönderilecek bir dosya: [path] doluysa yol, değilse [fd] (açık tanıtıcı, [len] bayt; sahipliği Rust'a geçer). */
    class SendItem(val name: String, val path: String?, val fd: Int, val len: Long)

    class SendJob(val id: Long)

    const val SEND_PROGRESS = "1"  // ad, cihaz, yüzde
    const val SEND_DONE = "2"      // tamamlanan cihaz sayısı, hatalı cihaz sayısı

    /**
     * Gönderim işi başlatır. Tanıtıcıların sahipliği bu çağrıdan sonra Rust'tadır (başarısız olsa bile);
     * çağıran onları kapatmamalıdır. Kütüphane yoksa null (o durumda tanıtıcıları çağıran kapatmalıdır: [closeFds]).
     */
    fun sendStart(items: List<SendItem>): SendJob? = safe {
        val id = nSendStart(
            Array(items.size) { items[it].name },
            Array(items.size) { items[it].path.orEmpty() },
            IntArray(items.size) { items[it].fd },
            LongArray(items.size) { items[it].len },
        )
        if (id < 0) null else SendJob(id)
    }

    /** Bulunan cihazı ([addr]: "ip:port") işe ekler; gönderim hemen başlar. */
    fun sendPeer(j: SendJob, dev: String, addr: String) {
        safe { nSendPeer(j.id, dev, addr); true }
    }

    /** Başka cihaz eklenmeyecek; tüm cihazlar bitince [SEND_DONE] olayı gelir. */
    fun sendClose(j: SendJob) {
        safe { nSendClose(j.id); true }
    }

    /** Gönderim olayını bekler (en çok ~1,5 sn): boş dizi = zaman aşımı; null = iş bitti ve olaylar okundu. */
    fun sendPoll(j: SendJob): Array<String>? = safe { nSendPoll(j.id) }

    /** Kütüphane yokken Rust'a devredilemeyen tanıtıcıları kapatır (yalnızca [sendStart] null dönerse çağrılır). */
    fun closeFds(items: List<SendItem>) {
        for (i in items) {
            if (i.fd >= 0) try { android.os.ParcelFileDescriptor.adoptFd(i.fd).close() } catch (e: Exception) { }
        }
    }

    // ------------------------------------------------------------ dosyalar

    class Entry(val name: String, val isDir: Boolean, val size: Long)

    /** Klasör içeriği (gizliler hariç; önce klasörler, sonra dosyalar, ada göre). Okunamazsa ya da kütüphane yoksa null. */
    fun listDir(dir: File): List<Entry>? = safe {
        val a = nListDir(dir.path) ?: return@safe null
        val out = ArrayList<Entry>(a.size / 3)
        var i = 0
        while (i + 2 < a.size) {
            out.add(Entry(a[i], a[i + 1] == "1", a[i + 2].toLongOrNull() ?: 0L))
            i += 3
        }
        out
    }

    /** [path] gerçek yolu [root] içinde kalan düz bir dosyaysa o dosya; değilse null. */
    fun resolveInside(root: File, path: String): File? = safe { nResolveInside(root.path, path)?.let { File(it) } }

    // ------------------------------------------------------------ duvar kağıdı

    fun wpListingUrl(): String? = safe { nWpListingUrl() }
    fun wpFallbackUrl(): String? = safe { nWpFallbackUrl() }

    /** Arama yanıtıyla adres havuzunu yeniler; havuza giren adres sayısı. */
    fun wpPoolLoad(json: String): Int? = safe { nWpPoolLoad(json) }

    /** Havuzdan bir resim adresi; havuz boş/eskiyse null (yeni arama yanıtıyla [wpPoolLoad] gerekir). */
    fun wpNext(): String? = safe { nWpNext() }

    /** [src] resmini ekran boyutlu JPEG duvar kağıdı [dest]'e çevirir; [src] her durumda silinir. */
    fun wpImport(src: File, dest: File, displayW: Int, displayH: Int, fit: Boolean): Boolean? =
        safe { nWpImport(src.path, dest.path, displayW, displayH, fit) }

    /** Sistem duvar kağıdı için kırpmadan sığdırılmış JPEG üretir; [src] silinmez. */
    fun wpSystemImage(src: File, out: File, displayW: Int, displayH: Int, tv: Boolean): Boolean? =
        safe { nWpSystemImage(src.path, out.path, displayW, displayH, tv) }

    /** Duvar kağıdı dosyası değişince değişen değer; dosya yoksa 0. */
    fun wpStamp(f: File): Long? = safe { nWpStamp(f.path) }
}
