package com.example.androtop

import android.os.Build
import android.system.Os
import android.system.OsConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

class ProcessMonitorUserService : IProcessMonitor.Stub() {

    private var previousProcJiffies = mutableMapOf<Int, Long>()
    private var previousTotalJiffies = 0L
    private var numCores = Runtime.getRuntime().availableProcessors()

    override fun destroy() {
        System.exit(0)
    }

    override fun getProcessSnapshot(): String {
        val totalJiffies = readTotalCpuJiffies()
        val deltaTotalJiffies = if (previousTotalJiffies > 0) {
            totalJiffies - previousTotalJiffies
        } else {
            0L
        }

        val totalMemKb = readTotalMemoryKb()
        val pageSize = try {
            Os.sysconf(OsConstants._SC_PAGESIZE)
        } catch (e: Exception) {
            4096L
        }

        val currentProcJiffies = mutableMapOf<Int, Long>()
        val processes = JSONArray()

        val procDir = File("/proc")
        val pidDirs = procDir.listFiles { file ->
            file.isDirectory && file.name.all { it.isDigit() }
        } ?: emptyArray()

        for (pidDir in pidDirs) {
            try {
                val pid = pidDir.name.toInt()
                val statLine = readFileFirstLine("/proc/$pid/stat") ?: continue
                val parsed = parseProcStat(statLine) ?: continue

                val procJiffies = parsed.utime + parsed.stime
                currentProcJiffies[pid] = procJiffies

                val cpuPercent = if (deltaTotalJiffies > 0) {
                    val prevJiffies = previousProcJiffies[pid] ?: procJiffies
                    val deltaProc = procJiffies - prevJiffies
                    if (deltaProc >= 0) {
                        (deltaProc.toDouble() / deltaTotalJiffies.toDouble()) * numCores * 100.0
                    } else {
                        0.0
                    }
                } else {
                    0.0
                }

                val statmLine = readFileFirstLine("/proc/$pid/statm")
                val rssPages = statmLine?.split(" ")?.getOrNull(1)?.toLongOrNull() ?: 0L
                val rssKb = (rssPages * pageSize) / 1024

                val memPercent = if (totalMemKb > 0) {
                    (rssKb.toDouble() / totalMemKb.toDouble()) * 100.0
                } else {
                    0.0
                }

                val obj = JSONObject().apply {
                    put("pid", pid)
                    put("name", parsed.name)
                    put("cpu", cpuPercent)
                    put("mem", memPercent)
                    put("rss", rssKb)
                    put("threads", parsed.threads)
                }
                processes.put(obj)
            } catch (e: Exception) {
                // Skip processes we can't read
            }
        }

        previousProcJiffies = currentProcJiffies
        previousTotalJiffies = totalJiffies

        return processes.toString()
    }

    override fun getSystemInfo(): String {
        val result = JSONObject()

        // CPU info
        val cpuInfo = readCpuInfo()
        result.put("coreCount", numCores)
        result.put("cores", cpuInfo)

        // Memory info
        val memInfo = readMemInfo()
        result.put("totalMemKb", memInfo["MemTotal"] ?: 0L)
        result.put("freeMemKb", memInfo["MemFree"] ?: 0L)
        result.put("availMemKb", memInfo["MemAvailable"] ?: 0L)
        result.put("buffersKb", memInfo["Buffers"] ?: 0L)
        result.put("cachedKb", memInfo["Cached"] ?: 0L)
        result.put("swapTotalKb", memInfo["SwapTotal"] ?: 0L)
        result.put("swapFreeKb", memInfo["SwapFree"] ?: 0L)

        return result.toString()
    }

    private data class ProcStat(
        val pid: Int,
        val name: String,
        val utime: Long,
        val stime: Long,
        val threads: Int
    )

    private fun parseProcStat(line: String): ProcStat? {
        try {
            // comm field is in parentheses and may contain spaces
            val openParen = line.indexOf('(')
            val closeParen = line.lastIndexOf(')')
            if (openParen < 0 || closeParen < 0) return null

            val pid = line.substring(0, openParen).trim().toInt()
            val name = line.substring(openParen + 1, closeParen)

            // Fields after the closing paren, split by whitespace
            val rest = line.substring(closeParen + 2).split(" ")
            // rest[0] = state (index 2 in full stat)
            // utime = index 13 in full stat = rest[11]
            // stime = index 14 in full stat = rest[12]
            // num_threads = index 19 in full stat = rest[17]
            val utime = rest.getOrNull(11)?.toLongOrNull() ?: return null
            val stime = rest.getOrNull(12)?.toLongOrNull() ?: return null
            val threads = rest.getOrNull(17)?.toIntOrNull() ?: 1

            return ProcStat(pid, name, utime, stime, threads)
        } catch (e: Exception) {
            return null
        }
    }

    private fun readTotalCpuJiffies(): Long {
        val line = readFileFirstLine("/proc/stat") ?: return 0L
        if (!line.startsWith("cpu ")) return 0L
        val parts = line.substring(4).trim().split("\\s+".toRegex())
        return parts.sumOf { it.toLongOrNull() ?: 0L }
    }

    private fun readTotalMemoryKb(): Long {
        val memInfo = readMemInfo()
        return memInfo["MemTotal"] ?: 0L
    }

    private fun readMemInfo(): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        try {
            BufferedReader(FileReader("/proc/meminfo")).use { reader ->
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

    private fun readCpuInfo(): JSONArray {
        val cores = JSONArray()
        try {
            var currentCore = JSONObject()
            var coreIndex = -1

            BufferedReader(FileReader("/proc/cpuinfo")).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val trimmed = line!!.trim()
                    if (trimmed.isEmpty()) {
                        if (coreIndex >= 0) {
                            currentCore.put("index", coreIndex)
                            // Try to read max frequency
                            val freqPath = "/sys/devices/system/cpu/cpu$coreIndex/cpufreq/cpuinfo_max_freq"
                            val freq = readFileFirstLine(freqPath)?.toLongOrNull() ?: 0L
                            currentCore.put("maxFreqKhz", freq)
                            cores.put(currentCore)
                            currentCore = JSONObject()
                        }
                        continue
                    }

                    val colonIdx = trimmed.indexOf(':')
                    if (colonIdx < 0) continue
                    val key = trimmed.substring(0, colonIdx).trim()
                    val value = trimmed.substring(colonIdx + 1).trim()

                    when (key) {
                        "processor" -> coreIndex = value.toIntOrNull() ?: -1
                        "CPU implementer" -> currentCore.put("implementer", value)
                        "CPU part" -> currentCore.put("part", value)
                        "model name" -> currentCore.put("modelName", value)
                        "Hardware" -> currentCore.put("hardware", value)
                        "BogoMIPS" -> currentCore.put("bogoMIPS", value)
                    }
                }
                // Handle last core if file doesn't end with empty line
                if (coreIndex >= 0 && !currentCore.has("index")) {
                    currentCore.put("index", coreIndex)
                    val freqPath = "/sys/devices/system/cpu/cpu$coreIndex/cpufreq/cpuinfo_max_freq"
                    val freq = readFileFirstLine(freqPath)?.toLongOrNull() ?: 0L
                    currentCore.put("maxFreqKhz", freq)
                    cores.put(currentCore)
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        return cores
    }

    private fun readFileFirstLine(path: String): String? {
        return try {
            BufferedReader(FileReader(path)).use { it.readLine() }
        } catch (e: Exception) {
            null
        }
    }
}
