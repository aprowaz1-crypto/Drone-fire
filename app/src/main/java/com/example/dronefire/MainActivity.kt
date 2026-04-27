package com.example.dronefire

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.example.dronefire.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val logItems = ArrayList<String>()
    private lateinit var logAdapter: ArrayAdapter<String>

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != AudioMonitorService.ACTION_AUDIO_UPDATE) return
            val status = intent.getStringExtra(AudioMonitorService.EXTRA_STATUS)
            val spectrum = intent.getStringExtra(AudioMonitorService.EXTRA_SPECTRUM)
            val azimuth = intent.getFloatExtra(AudioMonitorService.EXTRA_AZIMUTH, 0f)
            val alarm = intent.getBooleanExtra(AudioMonitorService.EXTRA_ALARM, false)
            val log = intent.getStringExtra(AudioMonitorService.EXTRA_LOG)

            binding.statusTextView.text = status ?: "Waiting"
            binding.spectrumTextView.text = spectrum ?: "Spectrum unavailable"
            binding.azimuthTextView.text = "Азимут: ${azimuth.toInt()}°"
            if (!log.isNullOrEmpty()) {
                logItems.add(0, log)
                logAdapter.notifyDataSetChanged()
            }
            if (alarm) {
                binding.statusTextView.text = "ТРЕВОГА"
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            startMonitoringService()
        } else {
            Toast.makeText(this, "Permissions are required для моніторингу", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        logAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, logItems)
        binding.logListView.adapter = logAdapter

        binding.startButton.setOnClickListener {
            if (checkPermissions()) {
                startMonitoringService()
            } else {
                requestPermissions()
            }
        }

        binding.ignoreBatteryButton.setOnClickListener {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(broadcastReceiver, IntentFilter(AudioMonitorService.ACTION_AUDIO_UPDATE))
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(broadcastReceiver)
    }

    private fun checkPermissions(): Boolean {
        val needed = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.BODY_SENSORS
        )
        return needed.all { ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requestPermissions() {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.BODY_SENSORS
            )
        )
    }

    private fun startMonitoringService() {
        val intent = Intent(this, AudioMonitorService::class.java).apply {
            action = AudioMonitorService.ACTION_START_MONITORING
        }
        startForegroundService(intent)
        binding.statusTextView.text = "Моніторинг запущено"
        binding.logListView.smoothScrollToPosition(0)
    }
}
