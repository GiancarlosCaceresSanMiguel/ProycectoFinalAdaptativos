package com.energiacontexto.core

import com.energiacontexto.core.SharedConstants.LEVELS
import com.energiacontexto.core.SharedConstants.PROFILES
import com.energiacontexto.core.SharedConstants.QUALITY_M
import kotlin.math.max
import kotlin.math.min

data class PolicyContext(
    val profile: String,
    val mode: String,
    val battery: Double,
    val predictedDrop: Double?,
    val speed: Double,
    val nearZone: Boolean,
    val charging: Boolean,
)

data class PolicyChoice(val level: Int, val reason: String)

object PolicyEngine {
    fun choosePolicy(c: PolicyContext): PolicyChoice {
        val p = PROFILES[c.profile] ?: PROFILES["balance"]!!
        var lvl = p.base
        fun clamp(v: Int) = max(p.min, min(p.max, v))

        if (c.mode == "fixed") {
            return PolicyChoice(
                lvl,
                "Modo fijo: se mantiene siempre el nivel base del perfil (${LEVELS[lvl].nombre}).",
            )
        }
        if (c.charging) {
            return PolicyChoice(
                lvl,
                "El teléfono está cargando: se usa el nivel base del perfil (${LEVELS[lvl].nombre}).",
            )
        }

        val why = mutableListOf<String>()
        val drop = c.predictedDrop ?: 0.0
        if (c.predictedDrop == null) why.add("aún no hay predicción")

        if (c.nearZone) {
            lvl = clamp(lvl + 1)
            why.add("cerca de una zona relevante")
        } else if (c.speed < 0.5) {
            lvl = clamp(lvl - 1)
            why.add("sin movimiento")
        }

        val left = c.battery - drop
        if (left < 20 || drop > 12) {
            lvl = clamp(lvl - 1)
            why.add("consumo previsto alto (${"%.1f".format(drop)}% en 30 min)")
        }

        if (left >= 10) {
            var raised = false
            while (lvl < p.max && c.speed * LEVELS[lvl].gps > QUALITY_M) {
                lvl++
                raised = true
            }
            if (raised) why.add("la velocidad exige más muestreo")
        }

        return PolicyChoice(
            lvl,
            "Frecuencia ${LEVELS[lvl].nombre.lowercase()}: ${if (why.isEmpty()) "condiciones normales" else why.joinToString("; ")}.",
        )
    }
}
