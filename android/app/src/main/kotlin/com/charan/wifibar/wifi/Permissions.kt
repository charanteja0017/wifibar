package com.charan.wifibar.wifi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build

/** What Android will let the app see. */
enum class LocationAccess { DENIED, APPROXIMATE, PRECISE }

data class PermissionState(
    val location: LocationAccess,
    /** Android hides Wi-Fi results unless Location Services (the system switch) is on. */
    val locationServicesOn: Boolean,
) {
    val canSeeNetworks: Boolean get() = location == LocationAccess.PRECISE && locationServicesOn

    companion object {
        /** Ask for FINE and COARSE together: since Android 12 a FINE-only request is ignored. */
        val requested: Array<String> = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }.toTypedArray()

        fun read(context: Context): PermissionState {
            fun granted(permission: String) =
                context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            val location = when {
                granted(Manifest.permission.ACCESS_FINE_LOCATION) -> LocationAccess.PRECISE
                granted(Manifest.permission.ACCESS_COARSE_LOCATION) -> LocationAccess.APPROXIMATE
                else -> LocationAccess.DENIED
            }
            val services = context.getSystemService(LocationManager::class.java)?.isLocationEnabled ?: false
            return PermissionState(location, services)
        }
    }
}
