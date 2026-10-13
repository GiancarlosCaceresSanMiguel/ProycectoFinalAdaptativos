package com.energiacontexto.client

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

object GeoUtils {
    fun dist(a: LatLon, b: LatLon): Double {
        val r = 6371000.0
        val rad = Math.PI / 180
        val dLat = (b.lat - a.lat) * rad
        val dLon = (b.lon - a.lon) * rad
        val x = sin(dLat / 2).let { it * it } +
            cos(a.lat * rad) * cos(b.lat * rad) * sin(dLon / 2).let { it * it }
        return 2 * r * asin(sqrt(x))
    }
}
