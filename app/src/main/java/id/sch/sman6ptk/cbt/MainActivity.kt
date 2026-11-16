package id.sch.sman6ptk.cbt

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var homeView: LinearLayout
    private lateinit var webView: WebView
    private lateinit var lockView: LinearLayout

    private var setupDone = false
    private var cameraAsked = false

    private val matchParent = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrapContent = ViewGroup.LayoutParams.WRAP_CONTENT

    // Alamat yang boleh dibuka: jaringan lokal (IP privat) dan domain sekolah.
    private val privateIp = Regex(
        """^(192\.168\.\d{1,3}\.\d{1,3}|10\.\d{1,3}\.\d{1,3}\.\d{1,3}|172\.(1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3})$"""
    )

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val text = result.contents
        if (!text.isNullOrBlank()) openExam(text.trim())
    }

    private val poll = object : Runnable {
        override fun run() {
            updateLockState()
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
        if (!isPinned()) tryPin()
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

    private fun updateLockState() {
        if (!setupDone) return
        lockView.visibility = if (isPinned()) View.GONE else View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        if (setupDone) {
            if (!isPinned()) tryPin()
            handler.removeCallbacks(poll)
            handler.post(poll)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poll)
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

        val msg = TextView(this)
        msg.text = "Aplikasi belum disematkan.\n\n" +
            "Tekan tombol di bawah, lalu pilih \"Mengerti\" (atau \"Mulai\") " +
            "pada kotak yang muncul. Ujian tidak dapat dimulai sebelum aplikasi disematkan."
        msg.textSize = 18f
        msg.setTextColor(Color.WHITE)
        msg.gravity = Gravity.CENTER
        msg.setPadding(0, 0, 0, dp(24))

        val btn = Button(this)
        btn.text = "Sematkan Aplikasi"
        btn.setOnClickListener { tryPin() }

        lockView.addView(msg)
        lockView.addView(btn)
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
            "Kembali ke halaman awal"
        )
        AlertDialog.Builder(this)
            .setTitle("Menu pengawas")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> exitApp()
                    1 -> askUrl()
                    2 -> showHome()
                }
            }
            .show()
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
        try {
            stopLockTask()
        } catch (e: Exception) {
            // Abaikan.
        }
        finishAndRemoveTask()
    }
}
