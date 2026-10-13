package com.energiacontexto.core

object SharedConstants {
    const val HORIZON_MIN = 30
    const val QUALITY_M = 150.0
    const val MIN_DWELL_MIN = 3
    const val MIN_MS = 60_000L

    data class Level(val nombre: String, val gps: Int, val batch: Int, val alta: Boolean)

    val LEVELS = listOf(
        Level("Baja", gps = 120, batch = 300, alta = false),
        Level("Media", gps = 30, batch = 120, alta = false),
        Level("Alta", gps = 10, batch = 30, alta = true),
    )

    data class Profile(
        val nombre: String,
        val base: Int,
        val min: Int,
        val max: Int,
        val ayuda: String,
    )

    val PROFILES = mapOf(
        "precision" to Profile(
            nombre = "Mayor precisión",
            base = 2,
            min = 1,
            max = 2,
            ayuda = "Prioriza la calidad del seguimiento. Solo baja a frecuencia media si la batería está en riesgo.",
        ),
        "balance" to Profile(
            nombre = "Equilibrio",
            base = 1,
            min = 0,
            max = 2,
            ayuda = "Sube la frecuencia cerca de zonas relevantes o a gran velocidad, y la baja en reposo o con consumo alto.",
        ),
        "saving" to Profile(
            nombre = "Ahorro",
            base = 0,
            min = 0,
            max = 1,
            ayuda = "Usa frecuencia baja casi siempre y solo sube a media cerca de zonas relevantes.",
        ),
    )

    fun activityOf(speed: Double): String = when {
        speed < 0.5 -> "reposo"
        speed < 4 -> "caminata"
        else -> "desplazamiento"
    }

    val MODEL_NAMES = mapOf(
        "baseline" to "Referencia (extrapolación)",
        "linear" to "Regresión lineal",
        "tree" to "Árbol de decisión",
    )
}
