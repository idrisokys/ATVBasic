package com.ATVBasic.launcher

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/**
 * Arayüzsüz paylaşım hedefi. Dosya yöneticisinde bir ya da birden çok dosya seçilip
 * "Paylaş → Açık cihazlara gönder" denince açılır, dosyaları [ShareSendService]'e verir ve kapanır.
 * Gönderim servis içinde, arka planda yürür.
 */
class ShareSendActivity : Activity() {

    private var uris: List<Uri> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        uris = collectUris(intent)
        if (uris.isEmpty()) {
            Toast.makeText(this, R.string.send_no_files, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Android 17+: yerel ağ erişimi çalışma zamanı izni (cihaz keşfi + gönderim için gerekli).
        if (Build.VERSION.SDK_INT >= 37 &&
            checkSelfPermission(PERM_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(PERM_LOCAL_NETWORK), REQ_NET)
            return
        }
        go()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NET) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                go()
            } else {
                Toast.makeText(this, R.string.toast_share_permission, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun go() {
        if (!ShareSendService.start(this, uris)) {
            Toast.makeText(this, R.string.send_start_failed, Toast.LENGTH_LONG).show()
        }
        finish()
    }

    private companion object {
        const val REQ_NET = 7
        const val PERM_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}

/** ACTION_SEND / ACTION_SEND_MULTIPLE içindeki tüm dosya adreslerini toplar. */
@Suppress("DEPRECATION")
private fun collectUris(i: Intent?): List<Uri> {
    if (i == null) return emptyList()
    val out = LinkedHashSet<Uri>()

    if (i.action == Intent.ACTION_SEND_MULTIPLE) {
        val list: ArrayList<Uri>? = if (Build.VERSION.SDK_INT >= 33) {
            i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        }
        if (list != null) out.addAll(list)
    } else {
        val one: Uri? = if (Build.VERSION.SDK_INT >= 33) {
            i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            i.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        if (one != null) out.add(one)
    }

    val clip = i.clipData
    if (clip != null) {
        for (k in 0 until clip.itemCount) {
            val u = clip.getItemAt(k).uri
            if (u != null) out.add(u)
        }
    }
    i.data?.let { out.add(it) }

    return out.filter { it.scheme == "content" || it.scheme == "file" }
}
