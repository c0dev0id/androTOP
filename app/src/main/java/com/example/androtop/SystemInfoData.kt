package com.example.androtop

data class SystemInfoData(
    val coreCount: Int,
    val coreTypes: List<CoreInfo>,
    val totalMemKb: Long,
    val freeMemKb: Long,
    val availMemKb: Long,
    val buffersKb: Long,
    val cachedKb: Long,
    val swapTotalKb: Long,
    val swapFreeKb: Long
)

data class CoreInfo(
    val index: Int,
    val implementer: String,
    val part: String,
    val maxFreqKhz: Long
)
