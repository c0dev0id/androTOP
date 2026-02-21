package com.example.androtop

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku

class ShizukuHelper(private val listener: Listener) {

    companion object {
        private const val TAG = "ShizukuHelper"
        private const val REQUEST_CODE = 1001
    }

    interface Listener {
        fun onShizukuAvailable()
        fun onShizukuUnavailable()
        fun onServiceConnected(service: IProcessMonitor)
        fun onServiceDisconnected()
        fun onPermissionResult(granted: Boolean)
    }

    private var isBound = false
    private var service: IProcessMonitor? = null

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.d(TAG, "Shizuku binder received")
        listener.onShizukuAvailable()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.d(TAG, "Shizuku binder dead")
        service = null
        isBound = false
        listener.onShizukuUnavailable()
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQUEST_CODE) {
                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                Log.d(TAG, "Permission result: $granted")
                listener.onPermissionResult(granted)
            }
        }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.d(TAG, "UserService connected")
            if (binder != null && binder.pingBinder()) {
                service = IProcessMonitor.Stub.asInterface(binder)
                listener.onServiceConnected(service!!)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "UserService disconnected")
            service = null
            listener.onServiceDisconnected()
        }
    }

    fun register() {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
    }

    fun unregister() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
    }

    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    fun hasPermission(): Boolean {
        return try {
            if (!isShizukuAvailable()) return false
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    fun requestPermission() {
        try {
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                listener.onPermissionResult(false)
                return
            }
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request permission", e)
            listener.onPermissionResult(false)
        }
    }

    fun bindService() {
        if (isBound) return
        try {
            val args = Shizuku.UserServiceArgs(
                ComponentName(
                    BuildConfig.APPLICATION_ID,
                    ProcessMonitorUserService::class.java.name
                )
            )
                .tag("process_monitor")
                .processNameSuffix("monitor")
                .debuggable(BuildConfig.DEBUG)
                .version(BuildConfig.VERSION_CODE)

            Shizuku.bindUserService(args, serviceConnection)
            isBound = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind UserService", e)
        }
    }

    fun unbindService() {
        if (!isBound) return
        try {
            val args = Shizuku.UserServiceArgs(
                ComponentName(
                    BuildConfig.APPLICATION_ID,
                    ProcessMonitorUserService::class.java.name
                )
            )
                .tag("process_monitor")
                .processNameSuffix("monitor")
                .debuggable(BuildConfig.DEBUG)
                .version(BuildConfig.VERSION_CODE)

            Shizuku.unbindUserService(args, serviceConnection, true)
            isBound = false
            service = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unbind UserService", e)
        }
    }

    fun getService(): IProcessMonitor? = service
}
