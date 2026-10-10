package com.charan.wifibar.shell

import android.os.IBinder

/**
 * Makes Android read the connected Wi-Fi signal more often than its default every 3 s, so "Walk to find"
 * gets more readings per metre walked. Apps can't do this; it needs MANAGE_WIFI_NETWORK_SELECTION, which
 * the adb shell user holds. It calls WifiManager.setLinkLayerStatsPollingInterval (system API, Android 13+)
 * and lasts until the device restarts or it's set back with 0.
 *
 *     adb shell 'CLASSPATH=$(pm path com.charan.wifibar | cut -d: -f2) app_process /system/bin com.charan.wifibar.shell.FastPoll 1000'
 *     adb shell 'CLASSPATH=$(pm path com.charan.wifibar | cut -d: -f2) app_process /system/bin com.charan.wifibar.shell.FastPoll 0'
 *
 * Check it took: adb shell dumpsys wifi | grep -i PollRssiInterval
 */
object FastPoll {
    @JvmStatic
    fun main(args: Array<String>) {
        val ms = args.firstOrNull()?.toIntOrNull()
        if (ms == null || ms < 0 || (ms in 1 until 250)) {
            System.err.println("Usage: FastPoll <milliseconds ≥ 250, or 0 for Android's default>")
            System.exit(2)
            return
        }
        try {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "wifi") as IBinder
            val wifi = Class.forName("android.net.wifi.IWifiManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            wifi.javaClass.getMethod("setLinkLayerStatsPollingInterval", Int::class.javaPrimitiveType).invoke(wifi, ms)
            println(if (ms == 0) "Wi-Fi signal polling is back to Android's default." else "Wi-Fi signal is now read every $ms ms, until restart (FastPoll 0 undoes it).")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            System.err.println("Refused: ${e.targetException}")
            System.exit(1)
        } catch (e: ReflectiveOperationException) {
            System.err.println("Not available on this Android version: $e")
            System.exit(1)
        }
    }
}
