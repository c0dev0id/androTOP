package com.example.androtop

data class ProcessInfo(
    val pid: Int,
    val name: String,
    val cpuPercent: Double,
    val memPercent: Double,
    val memRssKb: Long,
    val threads: Int
)
