package com.example.androtop;

interface IProcessMonitor {
    void destroy();
    String getProcessSnapshot();
    String getSystemInfo();
}
