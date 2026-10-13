package com.energiacontexto.location

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

class LocationHelper(context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission")
    fun requestFix(highAccuracy: Boolean, onResult: (lat: Double, lon: Double, speed: Float?, accuracy: Float) -> Unit, onError: () -> Unit) {
        val priority = if (highAccuracy) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        val cancel = CancellationTokenSource()
        client.getCurrentLocation(priority, cancel.token)
            .addOnSuccessListener { loc ->
                if (loc == null) {
                    onError()
                    return@addOnSuccessListener
                }
                onResult(loc.latitude, loc.longitude, loc.speed, loc.accuracy)
            }
            .addOnFailureListener { onError() }
    }
}
