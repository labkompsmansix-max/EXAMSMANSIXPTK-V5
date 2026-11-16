package id.sch.sman6ptk.cbt

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioFormat
import android.media.AudioTrack
import android.widget.Toast
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

/**
 * Layanan latar depan yang menjaga ujian.
 * Selama ujian berjalan, layanan ini mengecek status sematan setiap 0,5 detik.
 * Kalau sematan dilepas (dan kata sandi pengawas belum dimasukkan), alarm berbunyi
 * dengan volume penuh walaupun aplikasi sedang tidak tampil di layar.
 */
class ExamGuardService : Service() {

    companion object {
        // Lewat batas ini sejak terakhir tersemat, sesi ujian dianggap selesai
        // dan alarm tidak berbunyi lagi. Ubah angka 15 kalau ingin batas lain.
        const val SESSION_TIMEOUT_MS = 20 * 60 * 1000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var track: AudioTrack? = null
    private var errorShown = false
    private var testUntil = 0L
    private var lastStatus = ""
    private var lastError = ""
    private var beatCount = 0

    private val tick = object : Runnable {
        override fun run() {
            check()
            handler.postDelayed(this, 500)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra("test", false) == true) {
            testUntil = System.currentTimeMillis() + 4000   // tes alarm dari menu pengawas
        }
        try {
            startAsForeground()
        } catch (e: Exception) {
            setStatus("startForeground GAGAL: ${e.message}")
        }
        setStatus("layanan berjalan")
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        stopAlarm()
        super.onDestroy()
    }

    // ---------- Notifikasi wajib untuk layanan latar depan ----------

    private fun startAsForeground() {
        val channelId = "exam_guard"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Penjaga ujian", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Ujian sedang berlangsung")
            .setContentText("Jangan keluar dari aplikasi ujian.")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1001, notification)
        }
    }

    // ---------- Pengecekan sematan ----------

    private fun isPinned(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
    }

    private fun check() {
        val prefs = getSharedPreferences("exam_state", Context.MODE_PRIVATE)

        // Penanda "layanan hidup", dibaca layar penghalang untuk diagnosis.
        beatCount++
        if (beatCount % 4 == 1) {
            prefs.edit().putLong("guard_beat", System.currentTimeMillis()).apply()
        }

        // Tes alarm dari menu pengawas: bunyi 4 detik apa pun status sematannya.
        if (System.currentTimeMillis() < testUntil) {
            startAlarm()
            maxVolume()
            setStatus(if (track != null) "tes alarm: berbunyi" else "tes alarm GAGAL: $lastError")
            return
        }

        // Ujian sudah diakhiri pengawas (keluar dari aplikasi): layanan berhenti sendiri.
        if (!prefs.getBoolean("was_pinned", false)) {
            stopAlarm()
            stopSelf()
            return
        }

        val now = System.currentTimeMillis()
        val pinned = isPinned()
        val muted = now < prefs.getLong("alarm_mute_until", 0L)

        if (pinned) {
            // Catat kapan terakhir tersemat (tiap 5 detik saja supaya hemat).
            if (now - prefs.getLong("last_pinned", 0L) > 5000) {
                prefs.edit().putLong("last_pinned", now).apply()
            }
            stopAlarm()
            setStatus("tersemat (aman)")
            return
        }

        // Sematan lepas lebih lama dari batas waktu: sesi dianggap sudah selesai.
        if (now - prefs.getLong("last_pinned", 0L) > SESSION_TIMEOUT_MS) {
            prefs.edit().putBoolean("was_pinned", false).apply()
            stopAlarm()
            setStatus("sesi berakhir (lewat batas waktu)")
            stopSelf()
            return
        }

        if (muted) {
            stopAlarm()
            setStatus("alarm dibisukan sementara")
        } else {
            startAlarm()
            maxVolume()
            setStatus(if (track != null) "alarm BERBUNYI" else "alarm GAGAL: $lastError")
        }
    }

    private fun setStatus(s: String) {
        if (s == lastStatus) return
        lastStatus = s
        getSharedPreferences("exam_state", Context.MODE_PRIVATE)
            .edit().putString("guard_status", s).apply()
    }

    // ---------- Alarm dan volume ----------

    private fun maxVolume() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(
                AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0
            )
            am.setStreamVolume(
                AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0
            )
        } catch (e: Exception) {
            // Abaikan.
        }
    }

    // Sirene dibuat langsung oleh aplikasi (bergantian 900 Hz dan 1500 Hz),
    // jadi tidak bergantung pada file nada dering di HP.
    private fun startAlarm() {
        if (track != null) return
        try {
            val sampleRate = 22050
            val samples = ShortArray(sampleRate)   // 1 detik, diulang terus
            var phase = 0.0
            for (i in samples.indices) {
                val freq = if ((i / (sampleRate / 4)) % 2 == 0) 900.0 else 1500.0
                phase += 2.0 * Math.PI * freq / sampleRate
                val v = if (Math.sin(phase) >= 0) 1.0 else -1.0   // gelombang kotak: nyaring
                samples[i] = (v * 30000).toInt().toShort()
            }
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val t = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(samples.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            t.write(samples, 0, samples.size)
            t.setLoopPoints(0, samples.size, -1)
            maxVolume()
            t.play()
            track = t
        } catch (e: Exception) {
            track = null
            lastError = e.message ?: e.javaClass.simpleName
            if (!errorShown) {   // tampilkan pesan sekali saja, jangan berulang tiap 0,5 detik
                errorShown = true
                Toast.makeText(this, "Alarm gagal: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun stopAlarm() {
        track?.let {
            try { it.stop() } catch (e: Exception) { }
            it.release()
        }
        track = null
    }
}
