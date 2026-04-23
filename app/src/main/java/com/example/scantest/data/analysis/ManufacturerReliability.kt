package com.example.scantest.data.analysis

/**
 * Clasificación empírica de fabricantes Android según su fiabilidad para
 * mantener un `foreground service` de tipo `dataSync` vivo durante sesiones
 * largas (≥ 8 h) con pantalla apagada.
 *
 * Por qué existe este archivo:
 *  - Un test corto de 30 min NO puede certificar que un dispositivo es apto
 *    para jornadas de 8 h. Razón fundamental: el **App Standby Bucket** de
 *    Android arranca en `ACTIVE` al tocar la app y tarda **horas** (no
 *    minutos) en degradarse a `RARE` / `RESTRICTED`. Los "killers agresivos"
 *    de OxygenOS, ColorOS, FuntouchOS, MIUI y EMUI sólo se disparan cuando
 *    la app ha caído a ese bucket. Por tanto, en los primeros 30 min la app
 *    está protegida, independientemente del fabricante.
 *  - La **única forma de certificar** un dispositivo como apto es una sesión
 *    real de ≥ 4 h validada con `validate_session.py` (completeness ≥ 0.99
 *    y `watchdog_resurrections == 0`).
 *  - Mientras esa sesión no exista, aplicamos un **techo** al veredicto del
 *    test basado en el fabricante. El techo se deriva de:
 *      · Comportamiento documentado del framework Android.
 *      · Rankings públicos de dontkillmyapp.com.
 *      · Observaciones directas de este proyecto.
 *  - El techo es **definitivo** mientras el único dato disponible sea el
 *    test corto. Puede levantarse cuando una sesión real demuestre lo
 *    contrario (ver `DEVICE_COMPATIBILITY.md`).
 */
enum class ManufacturerReliability {
    /**
     * Fabricantes que respetan la contract estándar de foreground services
     * (Google, Samsung modernas, Sony, Nokia/HMD, Fairphone). El test puede
     * emitir PASS sin restricciones: si los 30 min salen limpios, es
     * razonable creer que una sesión de 8 h también saldrá limpia.
     */
    RELIABLE,

    /**
     * Fabricantes con killers condicionales (Xiaomi/Redmi/POCO, Motorola,
     * ASUS). Funcionan bien **si** el usuario aplica toda la configuración
     * del fabricante (Autostart, candado en recientes, sin restricción de
     * batería). Incluso así, el test de 30 min no basta: el veredicto se
     * limita a WARN hasta que una sesión real lo confirme.
     */
    CONDITIONAL,

    /**
     * Fabricantes con killers agresivos que actúan **tras horas** de
     * reposo, no en minutos (OnePlus, OPPO, Realme, Vivo/iQOO, Huawei,
     * Honor). Son precisamente el caso para el que el test corto da
     * **falso PASS**: a los 30 min la app sigue en bucket `ACTIVE` y el
     * OEM no ha intervenido. El techo es FAIL: nuestra recomendación por
     * defecto es no usarlos para sesiones de producción.
     */
    HOSTILE,

    /**
     * Fabricante no reconocido. Techo de WARN: damos el beneficio de la
     * duda pero exigimos validación con sesión real antes de confiar.
     */
    UNKNOWN,
}

/**
 * Metadatos del fabricante incorporados en el [QualityReport] para
 * transparencia total: el usuario y los scripts de validación pueden ver
 * exactamente por qué el veredicto se limitó.
 */
data class ManufacturerInfo(
    val rawManufacturer: String,
    val displayName: String,
    val reliability: ManufacturerReliability,
    /** Razón legible presentada en UI cuando se aplica el techo. */
    val rationale: String,
)

/**
 * Devuelve la clasificación asociada a un string de `Build.MANUFACTURER`.
 *
 * Se normaliza en minúsculas y se quita espacio porque Android reporta
 * variaciones ("HMD Global", "Xiaomi", "HUAWEI" con distintas capitalizaciones).
 *
 * Si el fabricante no está en ninguna lista se devuelve [ManufacturerReliability.UNKNOWN].
 * Esto es intencionalmente conservador: preferimos un falso WARN en un móvil
 * bueno desconocido, a un falso PASS en un móvil malo desconocido.
 */
fun manufacturerInfoFor(rawManufacturer: String?): ManufacturerInfo {
    val raw = (rawManufacturer ?: "").trim()
    val m = raw.lowercase()

    // Los grupos cubren tanto la marca principal como sub-marcas y errores
    // tipográficos que Android reporta en ocasiones.
    return when {
        m.isEmpty() -> ManufacturerInfo(
            rawManufacturer = raw,
            displayName = "desconocido",
            reliability = ManufacturerReliability.UNKNOWN,
            rationale = "Sin información del fabricante. Veredicto limitado a WARN hasta validar con una sesión real de ≥ 4 h.",
        )

        m == "google" -> ManufacturerInfo(
            raw, "Google Pixel", ManufacturerReliability.RELIABLE,
            "Capa Android minimalista. Los foreground services se respetan. Referencia para evaluar al resto.",
        )
        m == "samsung" -> ManufacturerInfo(
            raw, "Samsung", ManufacturerReliability.RELIABLE,
            "One UI 6+ respeta los foreground services tras añadir la app a \"Apps que no se duermen\" y excluirla de \"Poner en reposo apps no usadas\". Se han reportado regresiones puntuales en One UI 7 (Android 15) que requieren aplicar los mismos ajustes tras cada update.",
        )
        m == "sony" -> ManufacturerInfo(
            raw, "Sony", ManufacturerReliability.RELIABLE,
            "Capa cercana a stock; los foreground services se respetan. Stamina mode debe estar desactivado para la app.",
        )
        m in listOf("nokia", "hmd", "hmd global") -> ManufacturerInfo(
            raw, "Nokia / HMD", ManufacturerReliability.RELIABLE,
            "Stock Android; sin killers adicionales del OEM.",
        )
        m in listOf("nothing", "nothing technology") -> ManufacturerInfo(
            raw, "Nothing", ManufacturerReliability.RELIABLE,
            "Nothing OS es near-stock Android; no aplica killers agresivos propios. Mismos ajustes estándar de Android.",
        )
        m == "fairphone" -> ManufacturerInfo(
            raw, "Fairphone", ManufacturerReliability.RELIABLE,
            "Stock Android. NOTA: en Android 15 se han reportado kills más agresivos del propio framework; aplicar también los ajustes estándar.",
        )

        m in listOf("xiaomi", "redmi", "poco") -> ManufacturerInfo(
            raw, "Xiaomi / Redmi / POCO", ManufacturerReliability.CONDITIONAL,
            "MIUI 13+ / HyperOS exige Autostart, candado en recientes y \"Sin restricciones\" en batería. El test de 30 min no llega a activar el killer; veredicto limitado a WARN hasta validar con una sesión real de ≥ 4 h.",
        )
        m == "motorola" -> ManufacturerInfo(
            raw, "Motorola", ManufacturerReliability.CONDITIONAL,
            "Comportamiento mixto según versión. Veredicto limitado a WARN hasta validar con una sesión real de ≥ 4 h.",
        )
        m in listOf("asus", "asustek") -> ManufacturerInfo(
            raw, "ASUS", ManufacturerReliability.CONDITIONAL,
            "PowerMaster puede matar servicios en segundo plano. Veredicto limitado a WARN hasta validar con una sesión real de ≥ 4 h.",
        )

        m == "oneplus" -> ManufacturerInfo(
            raw, "OnePlus", ManufacturerReliability.HOSTILE,
            "OxygenOS 12+ (ColorOS) mata foreground services tras horas de reposo en bucket RARE/RESTRICTED. Un test de 30 min NO puede detectarlo: la app sigue en bucket ACTIVE y el killer no se dispara todavía. Documentado en el caso de referencia de este proyecto (CPH2399: 17 h → 8.3% de completitud).",
        )
        m == "oppo" -> ManufacturerInfo(
            raw, "OPPO", ManufacturerReliability.HOSTILE,
            "ColorOS mata foreground services tras unas horas de reposo. No detectable en 30 min.",
        )
        m == "realme" -> ManufacturerInfo(
            raw, "Realme", ManufacturerReliability.HOSTILE,
            "ColorOS mata foreground services tras unas horas de reposo. No detectable en 30 min.",
        )
        m in listOf("vivo", "iqoo") -> ManufacturerInfo(
            raw, "Vivo / iQOO", ManufacturerReliability.HOSTILE,
            "FuntouchOS / OriginOS mata foreground services tras unas horas. No detectable en 30 min.",
        )
        m in listOf("huawei", "honor") -> ManufacturerInfo(
            raw, "Huawei / Honor", ManufacturerReliability.HOSTILE,
            "EMUI / MagicOS aplica kill agresivo de foreground services. No detectable en 30 min.",
        )
        m == "meizu" -> ManufacturerInfo(
            raw, "Meizu", ManufacturerReliability.HOSTILE,
            "Flyme mata foreground services. No detectable en 30 min.",
        )
        m == "lenovo" -> ManufacturerInfo(
            raw, "Lenovo", ManufacturerReliability.CONDITIONAL,
            "Comportamiento mixto según modelo. Veredicto limitado a WARN hasta validar con una sesión real de ≥ 4 h.",
        )

        else -> ManufacturerInfo(
            raw, raw.ifEmpty { "desconocido" }, ManufacturerReliability.UNKNOWN,
            "Fabricante no reconocido. Veredicto limitado a WARN hasta validar con una sesión real de ≥ 4 h.",
        )
    }
}

/**
 * Aplica el techo de fiabilidad a un veredicto "bruto" calculado únicamente
 * a partir de métricas.
 *
 * La tabla:
 *   RELIABLE    → sin cambios (puede quedarse en PASS).
 *   CONDITIONAL → PASS degradado a WARN; WARN/FAIL sin cambios.
 *   HOSTILE     → PASS y WARN degradados a FAIL; FAIL sin cambios.
 *   UNKNOWN     → PASS degradado a WARN; resto sin cambios.
 *
 * INSUFFICIENT_DATA queda intacto en todos los casos: no aplicamos techo
 * sobre algo que ni siquiera es un veredicto.
 */
fun applyManufacturerCap(raw: Verdict, reliability: ManufacturerReliability): Verdict {
    if (raw == Verdict.INSUFFICIENT_DATA) return raw
    return when (reliability) {
        ManufacturerReliability.RELIABLE -> raw
        ManufacturerReliability.CONDITIONAL -> if (raw == Verdict.PASS) Verdict.WARN else raw
        ManufacturerReliability.HOSTILE -> when (raw) {
            Verdict.PASS, Verdict.WARN -> Verdict.FAIL
            else -> raw
        }
        ManufacturerReliability.UNKNOWN -> if (raw == Verdict.PASS) Verdict.WARN else raw
    }
}
