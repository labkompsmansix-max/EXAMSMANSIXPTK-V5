package id.sch.sman6ptk.cbt

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.view.KeyEvent
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class MainActivity : AppCompatActivity() {

    // Kata kunci aplikasi. HARUS SAMA PERSIS dengan yang ada di file .htaccess di server.
    // Ganti dengan kata kunci buatan Anda sendiri (huruf/angka saja, tanpa spasi),
    // dan ganti setiap beberapa ujian supaya kata kunci lama yang bocor tidak berguna.
    private val APP_KEY = "SMANSIX-66F64F8CE3B53006"

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var homeView: LinearLayout
    private lateinit var webView: WebView
    private lateinit var lockView: LinearLayout
    private lateinit var lockMsg: TextView
    private lateinit var lockBtn: Button

    private var setupDone = false
    private var cameraAsked = false

    private val matchParent = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrapContent = ViewGroup.LayoutParams.WRAP_CONTENT

    // BARU: menyimpan apakah aplikasi pernah disematkan. Disimpan di penyimpanan
    // HP supaya tetap berlaku walaupun aplikasi dibuka ulang atau layar diputar.
    // Setelah pernah disematkan, melepas sematan harus memakai kata sandi pengawas.
    private val prefs by lazy { getSharedPreferences("exam_state", Context.MODE_PRIVATE) }
    private var wasPinned: Boolean
        get() = prefs.getBoolean("was_pinned", false)
        set(v) { prefs.edit().putBoolean("was_pinned", v).apply() }

    // BARU: pencatatan waktu siswa meninggalkan aplikasi saat ujian berlangsung.
    // ignorePause dipakai supaya membuka kamera (scan QR) tidak dihitung sebagai keluar.
    private var ignorePause = false
    private var leaveDialogShowing = false

    private fun examActive(): Boolean =
        ::webView.isInitialized && webView.visibility == View.VISIBLE

    private fun resetLeaveStats() {
        prefs.edit().putLong("left_at", 0L).putInt("leave_count", 0)
            .putLong("leave_total_ms", 0L).apply()
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return if (m > 0) "$m menit $s detik" else "$s detik"
    }

    // Dipanggil saat siswa kembali ke aplikasi: tampilkan berapa lama mereka pergi.
    private fun reportLeaveIfAny() {
        val leftAt = prefs.getLong("left_at", 0L)
        if (leftAt == 0L) return
        val dur = System.currentTimeMillis() - leftAt
        val count = prefs.getInt("leave_count", 0) + 1
        val total = prefs.getLong("leave_total_ms", 0L) + dur
        prefs.edit().putLong("left_at", 0L).putInt("leave_count", count)
            .putLong("leave_total_ms", total).apply()

        if (leaveDialogShowing) return
        leaveDialogShowing = true
        AlertDialog.Builder(this)
            .setTitle("Anda meninggalkan ujian")
            .setMessage(
                "Kali ini: ${formatDuration(dur)}\n" +
                    "Total keluar dari aplikasi: $count kali (${formatDuration(total)})\n\n" +
                    "Catat dan laporkan kepada pengawas."
            )
            .setCancelable(false)
            .setPositiveButton("Mengerti") { _, _ -> leaveDialogShowing = false }
            .show()
    }

    // ---------- BARU: alarm saat sematan dilepas + volume dikunci 100% ----------

    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    private fun maxVolume() {
        try {
            audioManager.setStreamVolume(
                AudioManager.STREAM_ALARM,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0
            )
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0
            )
        } catch (e: Exception) {
            // Abaikan.
        }
    }

    // Alarm dijalankan oleh ExamGuardService (layanan latar depan), sehingga tetap
    // berbunyi walaupun siswa menekan Home atau keluar dari aplikasi.
    private var guardStarted = false

    private fun startGuardService() {
        if (guardStarted) return
        guardStarted = true
        try {
            ContextCompat.startForegroundService(this, Intent(this, ExamGuardService::class.java))
        } catch (e: Exception) {
            guardStarted = false
        }
    }

    private fun stopGuardService() {
        guardStarted = false
        try {
            stopService(Intent(this, ExamGuardService::class.java))
        } catch (e: Exception) {
            // Abaikan.
        }
    }

    // Tombol volume tidak bisa dipakai selama ujian atau saat alarm berbunyi.
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val isVolumeKey = keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
        if (isVolumeKey && (examActive() || (wasPinned && !isPinned()))) {
            maxVolume()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // BARU: sesi ujian dianggap selesai kalau HP sudah lebih dari 15 menit tidak tersemat
    // (misalnya siswa sudah selesai, restart HP, lalu membukanya lagi 30 menit atau besok).
    // Kalau belum lewat 15 menit, status ujian dipertahankan sehingga alarm tetap berbunyi.
    // Batasnya diatur di ExamGuardService.SESSION_TIMEOUT_MS.
    private fun expireOldSession() {
        if (!wasPinned) return
        val last = prefs.getLong("last_pinned", 0L)
        if (System.currentTimeMillis() - last > ExamGuardService.SESSION_TIMEOUT_MS) {
            wasPinned = false
            resetLeaveStats()
            prefs.edit().putLong("alarm_mute_until", 0L).apply()
            stopGuardService()
        }
    }

    // Alamat yang boleh dibuka: jaringan lokal (IP privat) dan domain sekolah.
    private val privateIp = Regex(
        """^(192\.168\.\d{1,3}\.\d{1,3}|10\.\d{1,3}\.\d{1,3}\.\d{1,3}|172\.(1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3})$"""
    )

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        ignorePause = false
        val text = result.contents
        if (!text.isNullOrBlank()) openExam(text.trim())
    }

    private val poll = object : Runnable {
        override fun run() {
            updateLockState()
            if (examActive()) maxVolume()   // volume dikunci 100% selama ujian
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SECURE
        )
        root = FrameLayout(this)
        root.setBackgroundColor(Color.WHITE)
        setContentView(root)

        buildHome()
        buildWeb()
        buildLock()
        buildAdminButton()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Tombol kembali dinonaktifkan selama ujian.
            }
        })

        expireOldSession()
        ensureSetup()
    }

    // ---------- Pengaturan awal: izin kamera dan penyematan ----------

    private fun ensureSetup() {
        if (setupDone) return
        if (!cameraGranted() && !cameraAsked) {
            cameraAsked = true
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 10)
            return
        }
        setupDone = true
        // UBAH: menyematkan otomatis hanya jika belum pernah disematkan.
        // Kalau sudah pernah, siswa harus memasukkan kata sandi pengawas dulu.
        if (!isPinned() && !wasPinned) tryPin()
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        ensureSetup()
    }

    private fun cameraGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    // ---------- Penyematan layar (wajib) ----------

    private fun isPinned(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
    }

    private fun tryPin() {
        try {
            startLockTask()
        } catch (e: Exception) {
            // Abaikan; tampilan penyematan akan meminta ulang.
        }
    }

    // UBAH: kalau sematan lepas setelah pernah disematkan, layar penghalang
    // meminta kata sandi pengawas. Kalau belum pernah disematkan, tampilannya
    // sama seperti sebelumnya (tombol "Sematkan Aplikasi").
    private fun updateLockState() {
        if (!setupDone) return

        if (isPinned()) {
            if (!wasPinned) wasPinned = true
            val now = System.currentTimeMillis()
            if (now - prefs.getLong("last_pinned", 0L) > 5000) {
                prefs.edit().putLong("last_pinned", now).apply()
            }
            startGuardService()
            lockView.visibility = View.GONE
            return
        }

        if (wasPinned) {
            val beatAge = System.currentTimeMillis() - prefs.getLong("guard_beat", 0L)
            val diag = if (beatAge > 3000) "LAYANAN ALARM TIDAK BERJALAN"
                else prefs.getString("guard_status", "-")
            lockMsg.text = "Sematan layar dilepas.\n\n" +
                "Ujian dikunci. Minta pengawas memasukkan kata sandi untuk melanjutkan." +
                "\n\n[Diagnostik v4] Penjaga: $diag"
            lockBtn.text = "Masukkan Kata Sandi Pengawas"
        } else {
            lockMsg.text = "Aplikasi belum disematkan.\n\n" +
                "Tekan tombol di bawah, lalu pilih \"Mengerti\" (atau \"Mulai\") " +
                "pada kotak yang muncul. Ujian tidak dapat dimulai sebelum aplikasi disematkan."
            lockBtn.text = "Sematkan Aplikasi"
        }
        lockView.visibility = View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        reportLeaveIfAny()
        expireOldSession()
        if (setupDone && wasPinned) startGuardService()
        if (setupDone) {
            // UBAH: jangan menyematkan otomatis kalau sebelumnya sudah pernah disematkan.
            if (!isPinned() && !wasPinned) tryPin()
            handler.removeCallbacks(poll)
            handler.post(poll)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poll)
        // BARU: catat waktu mulai keluar dari aplikasi saat ujian sedang berjalan.
        if (!ignorePause && examActive() && prefs.getLong("left_at", 0L) == 0L) {
            prefs.edit().putLong("left_at", System.currentTimeMillis()).apply()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    private fun hideSystemUi() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    // ---------- Tampilan ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun buildHome() {
        homeView = LinearLayout(this)
        homeView.orientation = LinearLayout.VERTICAL
        homeView.gravity = Gravity.CENTER
        homeView.setPadding(dp(32), dp(32), dp(32), dp(32))

        val title = TextView(this)
        title.text = "EXAM SMANSIX PTK"
        title.textSize = 26f
        title.setTextColor(Color.BLACK)
        title.gravity = Gravity.CENTER

        val sub = TextView(this)
        sub.text = "Scan QR code yang diberikan pengawas untuk memulai ujian."
        sub.textSize = 16f
        sub.setTextColor(Color.DKGRAY)
        sub.gravity = Gravity.CENTER
        sub.setPadding(0, dp(12), 0, dp(32))

        val scan = Button(this)
        scan.text = "Scan QR Code"
        scan.setOnClickListener { startScan() }

        val logo = ImageView(this)
        logo.setImageResource(R.drawable.logo_sekolah)
        logo.adjustViewBounds = true
        val logoLp = LinearLayout.LayoutParams(dp(120), dp(120))
        logoLp.bottomMargin = dp(16)
        homeView.addView(logo, logoLp)
        homeView.addView(title)
        homeView.addView(sub)
        homeView.addView(scan)
        root.addView(homeView, FrameLayout.LayoutParams(matchParent, matchParent))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWeb() {
        webView = WebView(this)
        webView.visibility = View.GONE
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.allowFileAccess = false
        s.allowContentAccess = false
        // BARU: tanda pengenal rahasia di setiap permintaan ke server CBT.
        // Server (.htaccess) hanya menerima permintaan yang membawa kata kunci ini.
        s.userAgentString = s.userAgentString + " " + APP_KEY

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                if (isAllowed(request.url)) return false
                toast("Alamat ini tidak diizinkan.")
                return true
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    showHome()
                    toast("Gagal membuka halaman ujian: ${error.description}")
                }
            }

            override fun onReceivedSslError(
                view: WebView,
                h: SslErrorHandler,
                error: SslError
            ) {
                h.cancel()
            }
        }
        root.addView(webView, FrameLayout.LayoutParams(matchParent, matchParent))
    }

    private fun buildLock() {
        lockView = LinearLayout(this)
        lockView.orientation = LinearLayout.VERTICAL
        lockView.gravity = Gravity.CENTER
        lockView.setBackgroundColor(Color.parseColor("#0F172A"))
        lockView.setPadding(dp(32), dp(32), dp(32), dp(32))
        lockView.visibility = View.GONE
        lockView.isClickable = true

        lockMsg = TextView(this)
        lockMsg.textSize = 18f
        lockMsg.setTextColor(Color.WHITE)
        lockMsg.gravity = Gravity.CENTER
        lockMsg.setPadding(0, 0, 0, dp(24))

        lockBtn = Button(this)
        // UBAH: kalau sudah pernah disematkan, tombol meminta kata sandi pengawas
        // dulu, baru menyematkan lagi.
        lockBtn.setOnClickListener {
            if (wasPinned) {
                askPassword {
                    // Kata sandi benar: alarm dimatikan 20 detik supaya pengawas bisa menyematkan ulang.
                    prefs.edit().putLong("alarm_mute_until", System.currentTimeMillis() + 20000).apply()
                    tryPin()
                }
            } else {
                tryPin()
            }
        }

        lockView.addView(lockMsg)
        lockView.addView(lockBtn)
        root.addView(lockView, FrameLayout.LayoutParams(matchParent, matchParent))
    }

    private fun buildAdminButton() {
        val b = TextView(this)
        b.text = "Pengawas"
        b.textSize = 11f
        b.setTextColor(Color.WHITE)
        b.setBackgroundColor(Color.parseColor("#66000000"))
        b.setPadding(dp(10), dp(6), dp(10), dp(6))
        b.setOnClickListener { askPassword { showAdminMenu() } }
        root.addView(
            b,
            FrameLayout.LayoutParams(wrapContent, wrapContent, Gravity.BOTTOM or Gravity.START)
        )
    }

    // ---------- Scan dan membuka halaman ujian ----------

    private fun startScan() {
        if (!cameraGranted()) {
            toast("Izinkan akses kamera untuk scan QR code.")
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 10)
            return
        }
        val options = ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("Arahkan kamera ke QR code ujian")
            .setBeepEnabled(false)
            .setOrientationLocked(false)
        ignorePause = true
        scanLauncher.launch(options)
    }

    private fun isAllowed(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase() ?: return false
        if (host == "sman6ptk.sch.id" || host.endsWith(".sman6ptk.sch.id")) return true
        return privateIp.matches(host)
    }

    private fun openExam(raw: String) {
        val url = if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) {
            raw
        } else {
            "http://$raw"
        }
        if (!isAllowed(Uri.parse(url))) {
            toast("Alamat tidak diizinkan: $raw")
            return
        }
        resetLeaveStats()   // ujian baru dimulai: hitungan keluar dari nol
        homeView.visibility = View.GONE
        webView.visibility = View.VISIBLE
        webView.loadUrl(url)
    }

    private fun showHome() {
        webView.visibility = View.GONE
        webView.loadUrl("about:blank")
        homeView.visibility = View.VISIBLE
    }

    // ---------- Kata sandi pengawas ----------

    // Kata sandi pengawas ditentukan saat APK dibuat (rahasia ADMIN_PASSWORD di GitHub).
    // Di dalam APK hanya tersimpan hash-nya, bukan kata sandi aslinya.
    private fun checkPassword(pw: String): Boolean {
        val salt = BuildConfig.ADMIN_SALT.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val spec = PBEKeySpec(pw.toCharArray(), salt, 120000, 256)
        val h = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
            .generateSecret(spec).encoded
            .joinToString("") { "%02x".format(it) }
        return h == BuildConfig.ADMIN_HASH
    }

    private fun passwordField(hint: String): EditText {
        val e = EditText(this)
        e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        e.hint = hint
        return e
    }

    private fun askPassword(onOk: () -> Unit) {
        val input = passwordField("Kata sandi pengawas")
        AlertDialog.Builder(this)
            .setTitle("Khusus pengawas")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                if (checkPassword(input.text.toString())) onOk() else toast("Kata sandi salah.")
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showAdminMenu() {
        val items = arrayOf(
            "Keluar dari aplikasi",
            "Buka alamat server manual",
            "Kembali ke halaman awal",
            "Tes alarm (4 detik)"
        )
        AlertDialog.Builder(this)
            .setTitle("Menu pengawas")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> exitApp()
                    1 -> askUrl()
                    2 -> showHome()
                    3 -> testAlarm()
                }
            }
            .show()
    }

    // Tes alarm: menjalankan layanan penjaga dan membunyikan alarm 4 detik.
    private fun testAlarm() {
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, ExamGuardService::class.java).putExtra("test", true)
            )
            toast("Tes alarm dimulai. Dengarkan suara dari HP.")
        } catch (e: Exception) {
            toast("Layanan alarm gagal dijalankan: ${e.message}")
        }
    }

    private fun askUrl() {
        val input = EditText(this)
        input.hint = "192.168.0.101/smansixcbt"
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        AlertDialog.Builder(this)
            .setTitle("Alamat server")
            .setView(input)
            .setPositiveButton("Buka") { _, _ -> openExam(input.text.toString().trim()) }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun exitApp() {
        handler.removeCallbacks(poll)
        // BARU: reset status, supaya ujian berikutnya mulai dari awal tanpa meminta kata sandi.
        wasPinned = false
        stopGuardService()
        resetLeaveStats()
        try {
            stopLockTask()
        } catch (e: Exception) {
            // Abaikan.
        }
        finishAndRemoveTask()
    }
}
