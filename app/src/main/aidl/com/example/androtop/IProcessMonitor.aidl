package com.example.androtop;

interface IProcessMonitor {
    void destroy() = 16777114;
    String getProcessSnapshot();
    String getSystemInfo();
}
