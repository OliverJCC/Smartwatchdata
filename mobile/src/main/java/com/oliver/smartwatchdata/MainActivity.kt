package com.oliver.smartwatchdata.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.oliver.smartwatchdata.R

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnAsk: Button

    private val requestNotifPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            tvStatus.text = if (granted) {
                getString(R.string.notif_granted)
            } else {
                getString(R.string.notif_denied)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        btnAsk = findViewById(R.id.btnAskNotif)

        btnAsk.setOnClickListener {
            askNotifIfNeeded()
        }

        // Pídelo automáticamente al abrir en Android 13+
        askNotifIfNeeded()
    }

    private fun askNotifIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val perm = Manifest.permission.POST_NOTIFICATIONS
            val granted = ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestNotifPerm.launch(perm)
            }
        }
    }
}


