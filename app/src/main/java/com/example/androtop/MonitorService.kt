package com.example.androtop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import org.json.JSONArray
import org.json.JSONObject

class MonitorService : Service(), ShizukuHelper.Listener {

    companion object {
        private const val TAG = "MonitorService"
        private const val NOTIFICATION_CHANNEL_ID = "process_monitor"
        private const val NOTIFICATION_ID = 1
        private const val POLL_INTERVAL_MS = 1000L
    }

    inner class LocalBinder : Binder() {
        fun getService(): MonitorService = this@MonitorService
    }

    private val binder = LocalBinder()
    private lateinit var shizukuHelper: ShizukuHelper
    private var overlayManager: OverlayManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isMonitoring = false

    private val _processListData = MutableLiveData<List<ProcessInfo>>()
    val processListData: LiveData<List<ProcessInfo>> = _processListData

    private val _systemInfoData = MutableLiveData<SystemInfoData?>()
    val systemInfoData: LiveData<SystemInfoData?> = _systemInfoData

    private val _statusMessage = MutableLiveData<String>()
    val statusMessage: LiveData<String> = _statusMessage

    private var processMonitor: IProcessMonitor? = null
    private var systemInfoFetched = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (isMonitoring) {
                fetchProcessData()
                handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        shizukuHelper = ShizukuHelper(this)
        shizukuHelper.register()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.waiting_for_data)))
        overlayManager = OverlayManager(this)
        overlayManager?.show()
        startMonitoring()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        stopMonitoring()
        overlayManager?.destroy()
        shizukuHelper.unbindService()
        shizukuHelper.unregister()
        super.onDestroy()
    }

    fun startMonitoring() {
        if (isMonitoring) return
        isMonitoring = true

        if (shizukuHelper.isShizukuAvailable()) {
            if (shizukuHelper.hasPermission()) {
                shizukuHelper.bindService()
                _statusMessage.postValue("Connecting to Shizuku...")
            } else {
                shizukuHelper.requestPermission()
                _statusMessage.postValue("Requesting Shizuku permission...")
            }
        } else {
            _statusMessage.postValue("Shizuku not available. Trying fallback...")
            startFallbackMonitoring()
        }
    }

    fun stopMonitoring() {
        isMonitoring = false
        handler.removeCallbacks(pollRunnable)
    }

    fun refreshNow() {
        fetchProcessData()
    }

    private fun startPolling() {
        handler.removeCallbacks(pollRunnable)
        handler.post(pollRunnable)
    }

    private fun fetchProcessData() {
        Thread {
            try {
                val monitor = processMonitor
                if (monitor != null) {
                    // Fetch system info once
                    if (!systemInfoFetched) {
                        try {
                            val sysInfoJson = monitor.getSystemInfo()
                            val sysInfo = parseSystemInfo(sysInfoJson)
                            _systemInfoData.postValue(sysInfo)
                            systemInfoFetched = true
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to fetch system info", e)
                        }
                    }

                    // Fetch process snapshot
                    val json = monitor.getProcessSnapshot()
                    val processes = parseProcessList(json)
                    _processListData.postValue(processes)

                    // Update overlay with top CPU process
                    val top = processes.maxByOrNull { it.cpuPercent }
                    if (top != null) {
                        val text = "${top.name} (${formatCpu(top.cpuPercent)} CPU, ${formatMem(top.memPercent)} MEM)"
                        updateOverlay(text)
                        updateNotification(text)
                    }
                    _statusMessage.postValue("Monitoring active")
                } else {
                    fetchFallbackData()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching process data", e)
                _statusMessage.postValue("Error: ${e.message}")
            }
        }.start()
    }

    private fun fetchFallbackData() {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("top", "-bn1", "-m", "30"))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()

            val processes = parseFallbackTop(output)
            _processListData.postValue(processes)

            val top = processes.maxByOrNull { it.cpuPercent }
            if (top != null) {
                val text = "${top.name} (${formatCpu(top.cpuPercent)} CPU, ${formatMem(top.memPercent)} MEM)"
                updateOverlay(text)
                updateNotification(text)
            }

            // Try to read system info from /proc directly (no Shizuku needed)
            if (!systemInfoFetched) {
                fetchLocalSystemInfo()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fallback top command failed", e)
            _statusMessage.postValue("Fallback failed: ${e.message}")
        }
    }

    private fun fetchLocalSystemInfo() {
        try {
            val coreCount = Runtime.getRuntime().availableProcessors()
            // /proc/meminfo is readable without privileges
            val memInfo = readProcMemInfo()
            val sysInfo = SystemInfoData(
                coreCount = coreCount,
                coreTypes = emptyList(),
                totalMemKb = memInfo["MemTotal"] ?: 0L,
                freeMemKb = memInfo["MemFree"] ?: 0L,
                availMemKb = memInfo["MemAvailable"] ?: 0L,
                buffersKb = memInfo["Buffers"] ?: 0L,
                cachedKb = memInfo["Cached"] ?: 0L,
                swapTotalKb = memInfo["SwapTotal"] ?: 0L,
                swapFreeKb = memInfo["SwapFree"] ?: 0L
            )
            _systemInfoData.postValue(sysInfo)
            systemInfoFetched = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read local system info", e)
        }
    }

    private fun readProcMemInfo(): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        try {
            java.io.BufferedReader(java.io.FileReader("/proc/meminfo")).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val parts = line!!.split("\\s+".toRegex())
                    if (parts.size >= 2) {
                        val key = parts[0].trimEnd(':')
                        val value = parts[1].toLongOrNull() ?: continue
                        result[key] = value
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        return result
    }

    private fun startFallbackMonitoring() {
        startPolling()
    }

    private fun parseFallbackTop(output: String): List<ProcessInfo> {
        val processes = mutableListOf<ProcessInfo>()
        val lines = output.lines()

        // Find the header line to determine column positions
        var headerIdx = -1
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.startsWith("PID") || line.contains("PID") && line.contains("CPU") && line.contains("NAME")) {
                headerIdx = i
                break
            }
        }
        if (headerIdx < 0) return processes

        // Parse data lines after header
        for (i in (headerIdx + 1) until lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty()) continue
            val parts = line.split("\\s+".toRegex())
            if (parts.size < 5) continue

            try {
                val pid = parts[0].toIntOrNull() ?: continue
                // Toybox top format varies, but typically:
                // PID USER PR NI VIRT RES SHR S %CPU %MEM TIME+ ARGS
                // Try to find CPU% and name
                var cpuStr = ""
                var memStr = ""
                var name = ""

                // Look for % values
                for (j in parts.indices) {
                    val p = parts[j]
                    if (p.endsWith("%") || p.contains(".")) {
                        val num = p.replace("%", "").toDoubleOrNull()
                        if (num != null) {
                            if (cpuStr.isEmpty()) cpuStr = p
                            else if (memStr.isEmpty()) memStr = p
                        }
                    }
                }
                // Name is typically the last field
                name = parts.last()
                if (name.contains("/")) {
                    name = name.substringBefore("/")
                }

                val cpu = cpuStr.replace("%", "").toDoubleOrNull() ?: 0.0
                val mem = memStr.replace("%", "").toDoubleOrNull() ?: 0.0

                processes.add(ProcessInfo(pid, name, cpu, mem, 0L, 1))
            } catch (e: Exception) {
                continue
            }
        }
        return processes
    }

    private fun parseProcessList(json: String): List<ProcessInfo> {
        val list = mutableListOf<ProcessInfo>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    ProcessInfo(
                        pid = obj.getInt("pid"),
                        name = obj.getString("name"),
                        cpuPercent = obj.getDouble("cpu"),
                        memPercent = obj.getDouble("mem"),
                        memRssKb = obj.getLong("rss"),
                        threads = obj.getInt("threads")
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse process list", e)
        }
        return list
    }

    private fun parseSystemInfo(json: String): SystemInfoData {
        val obj = JSONObject(json)
        val coresArray = obj.optJSONArray("cores") ?: JSONArray()
        val cores = mutableListOf<CoreInfo>()
        for (i in 0 until coresArray.length()) {
            val coreObj = coresArray.getJSONObject(i)
            cores.add(
                CoreInfo(
                    index = coreObj.optInt("index", i),
                    implementer = coreObj.optString("implementer", ""),
                    part = coreObj.optString("part", ""),
                    maxFreqKhz = coreObj.optLong("maxFreqKhz", 0L)
                )
            )
        }
        return SystemInfoData(
            coreCount = obj.optInt("coreCount", 0),
            coreTypes = cores,
            totalMemKb = obj.optLong("totalMemKb", 0L),
            freeMemKb = obj.optLong("freeMemKb", 0L),
            availMemKb = obj.optLong("availMemKb", 0L),
            buffersKb = obj.optLong("buffersKb", 0L),
            cachedKb = obj.optLong("cachedKb", 0L),
            swapTotalKb = obj.optLong("swapTotalKb", 0L),
            swapFreeKb = obj.optLong("swapFreeKb", 0L)
        )
    }

    private fun formatCpu(cpu: Double): String {
        return if (cpu >= 10.0) "${cpu.toInt()}%" else "%.1f%%".format(cpu)
    }

    private fun formatMem(mem: Double): String {
        return if (mem >= 10.0) "${mem.toInt()}%" else "%.1f%%".format(mem)
    }

    private fun updateOverlay(text: String) {
        overlayManager?.updateText(text)
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("androTOP")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    // ShizukuHelper.Listener callbacks

    override fun onShizukuAvailable() {
        if (shizukuHelper.hasPermission()) {
            shizukuHelper.bindService()
        } else {
            _statusMessage.postValue("Shizuku available. Permission needed.")
        }
    }

    override fun onShizukuUnavailable() {
        processMonitor = null
        _statusMessage.postValue("Shizuku disconnected. Using fallback.")
        if (isMonitoring) {
            startFallbackMonitoring()
        }
    }

    override fun onServiceConnected(service: IProcessMonitor) {
        processMonitor = service
        _statusMessage.postValue("Connected to Shizuku. Monitoring...")
        if (isMonitoring) {
            startPolling()
        }
    }

    override fun onServiceDisconnected() {
        processMonitor = null
        _statusMessage.postValue("Shizuku service disconnected.")
    }

    override fun onPermissionResult(granted: Boolean) {
        if (granted) {
            shizukuHelper.bindService()
            _statusMessage.postValue("Shizuku permission granted. Connecting...")
        } else {
            _statusMessage.postValue("Shizuku permission denied. Using fallback.")
            if (isMonitoring) {
                startFallbackMonitoring()
            }
        }
    }
}
