package com.example.androtop

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    private var monitorService: MonitorService? = null
    private var isBound = false
    private var isMonitoring = false

    private lateinit var statusBanner: TextView
    private lateinit var cpuInfoText: TextView
    private lateinit var memInfoText: TextView
    private lateinit var swapInfoText: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var sortCpuButton: MaterialButton
    private lateinit var sortMemButton: MaterialButton
    private lateinit var refreshButton: MaterialButton
    private lateinit var startStopButton: MaterialButton
    private lateinit var processAdapter: ProcessAdapter

    private var sortByCpu = true
    private var currentProcessList: List<ProcessInfo> = emptyList()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as MonitorService.LocalBinder
            monitorService = localBinder.getService()
            isBound = true
            observeServiceData()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            monitorService = null
            isBound = false
        }
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, "Notification permission denied", Toast.LENGTH_SHORT).show()
            }
            checkOverlayPermission()
        }

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) {
                startMonitoringService()
            } else {
                Toast.makeText(this, "Overlay permission denied", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupRecyclerView()
        setupButtons()
        checkPermissions()
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }

    private fun initViews() {
        statusBanner = findViewById(R.id.statusBanner)
        cpuInfoText = findViewById(R.id.cpuInfoText)
        memInfoText = findViewById(R.id.memInfoText)
        swapInfoText = findViewById(R.id.swapInfoText)
        recyclerView = findViewById(R.id.processRecyclerView)
        sortCpuButton = findViewById(R.id.sortCpuButton)
        sortMemButton = findViewById(R.id.sortMemButton)
        refreshButton = findViewById(R.id.refreshButton)
        startStopButton = findViewById(R.id.startStopButton)
    }

    private fun setupRecyclerView() {
        processAdapter = ProcessAdapter()
        recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = processAdapter
            itemAnimator = null // Disable animations for fast updates
        }
    }

    private fun setupButtons() {
        sortCpuButton.setOnClickListener {
            sortByCpu = true
            updateSortButtons()
            applySortAndDisplay()
        }

        sortMemButton.setOnClickListener {
            sortByCpu = false
            updateSortButtons()
            applySortAndDisplay()
        }

        refreshButton.setOnClickListener {
            monitorService?.refreshNow()
        }

        startStopButton.setOnClickListener {
            if (isMonitoring) {
                stopMonitoringService()
            } else {
                checkOverlayPermission()
            }
        }
    }

    private fun updateSortButtons() {
        if (sortByCpu) {
            sortCpuButton.setStrokeColorResource(R.color.primary)
            sortMemButton.setStrokeColorResource(R.color.surface_variant)
        } else {
            sortCpuButton.setStrokeColorResource(R.color.surface_variant)
            sortMemButton.setStrokeColorResource(R.color.primary)
        }
    }

    private fun checkPermissions() {
        // Check notification permission (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        checkOverlayPermission()
    }

    private fun checkOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            showBanner(getString(R.string.overlay_permission_needed))
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        } else {
            startMonitoringService()
        }
    }

    private fun startMonitoringService() {
        val intent = Intent(this, MonitorService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        isMonitoring = true
        startStopButton.text = getString(R.string.stop_monitoring)
        hideBanner()
    }

    private fun stopMonitoringService() {
        monitorService?.stopMonitoring()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        val intent = Intent(this, MonitorService::class.java)
        stopService(intent)
        monitorService = null
        isMonitoring = false
        startStopButton.text = getString(R.string.start_monitoring)
    }

    private fun observeServiceData() {
        monitorService?.processListData?.observe(this) { processes ->
            currentProcessList = processes
            applySortAndDisplay()
        }

        monitorService?.systemInfoData?.observe(this) { sysInfo ->
            sysInfo?.let { displaySystemInfo(it) }
        }

        monitorService?.statusMessage?.observe(this) { message ->
            showBanner(message)
        }
    }

    private fun applySortAndDisplay() {
        val sorted = if (sortByCpu) {
            currentProcessList.sortedByDescending { it.cpuPercent }
        } else {
            currentProcessList.sortedByDescending { it.memPercent }
        }
        processAdapter.submitList(sorted)
    }

    private fun displaySystemInfo(info: SystemInfoData) {
        // CPU info
        val cpuText = buildString {
            append("CPU: ${info.coreCount} cores")
            if (info.coreTypes.isNotEmpty()) {
                val grouped = info.coreTypes.groupBy { it.part }
                for ((part, cores) in grouped) {
                    val freqMhz = cores.maxOf { it.maxFreqKhz } / 1000
                    val label = mapCpuPart(part)
                    if (freqMhz > 0) {
                        append("\n  ${cores.size}x $label @ ${freqMhz}MHz")
                    } else {
                        append("\n  ${cores.size}x $label")
                    }
                }
            }
        }
        cpuInfoText.text = cpuText

        // Memory info
        val totalMb = info.totalMemKb / 1024
        val usedMb = (info.totalMemKb - info.availMemKb) / 1024
        val freeMb = info.availMemKb / 1024
        val buffersMb = info.buffersKb / 1024
        val cachedMb = info.cachedKb / 1024
        memInfoText.text = "Mem: ${totalMb}MB total, ${usedMb}MB used, ${freeMb}MB avail, ${buffersMb}MB buf, ${cachedMb}MB cache"

        // Swap info
        val swapTotalMb = info.swapTotalKb / 1024
        val swapUsedMb = (info.swapTotalKb - info.swapFreeKb) / 1024
        val swapFreeMb = info.swapFreeKb / 1024
        swapInfoText.text = if (swapTotalMb > 0) {
            "Swap: ${swapTotalMb}MB total, ${swapUsedMb}MB used, ${swapFreeMb}MB free"
        } else {
            "Swap: none"
        }
    }

    private fun mapCpuPart(part: String): String {
        // Common ARM Cortex part numbers
        return when (part.lowercase()) {
            "0xd03" -> "Cortex-A53"
            "0xd04" -> "Cortex-A35"
            "0xd05" -> "Cortex-A55"
            "0xd06" -> "Cortex-A65"
            "0xd07" -> "Cortex-A57"
            "0xd08" -> "Cortex-A72"
            "0xd09" -> "Cortex-A73"
            "0xd0a" -> "Cortex-A75"
            "0xd0b" -> "Cortex-A76"
            "0xd0c" -> "Neoverse-N1"
            "0xd0d" -> "Cortex-A77"
            "0xd0e" -> "Cortex-A76AE"
            "0xd40" -> "Neoverse-V1"
            "0xd41" -> "Cortex-A78"
            "0xd42" -> "Cortex-A78AE"
            "0xd43" -> "Cortex-A65AE"
            "0xd44" -> "Cortex-X1"
            "0xd46" -> "Cortex-A510"
            "0xd47" -> "Cortex-A710"
            "0xd48" -> "Cortex-X2"
            "0xd49" -> "Neoverse-N2"
            "0xd4a" -> "Neoverse-E1"
            "0xd4b" -> "Cortex-A78C"
            "0xd4d" -> "Cortex-A715"
            "0xd4e" -> "Cortex-X3"
            "0xd80" -> "Cortex-A520"
            "0xd81" -> "Cortex-A720"
            "0xd82" -> "Cortex-X4"
            else -> part
        }
    }

    private fun showBanner(message: String) {
        statusBanner.text = message
        statusBanner.visibility = android.view.View.VISIBLE
    }

    private fun hideBanner() {
        statusBanner.visibility = android.view.View.GONE
    }
}
