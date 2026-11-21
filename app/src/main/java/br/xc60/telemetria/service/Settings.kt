package br.xc60.telemetria.service

import android.content.Context

enum class FuelType(val label: String, val afr: Double, val densityGPerL: Double, val mafValid: Boolean) {
    /** Gasolina comum brasileira (~27% etanol). Estequiometria ≈ 13,2:1 */
    GASOLINA_E27("Gasolina comum (E27)", 13.2, 750.0, true),
    GASOLINA_E0("Gasolina sem etanol", 14.7, 740.0, true),
    /** Diesel trabalha com mistura pobre: cálculo por fluxo de ar não vale, só o PID 5E. */
    DIESEL("Diesel (D5)", 14.5, 835.0, false),
}

class Settings(context: Context) {
    private val p = context.getSharedPreferences("xc60", Context.MODE_PRIVATE)

    var deviceAddress: String?
        get() = p.getString("device", null)
        set(v) = p.edit().putString("device", v).apply()

    var fuelType: FuelType
        get() = FuelType.valueOf(p.getString("fuel", FuelType.GASOLINA_E27.name)!!)
        set(v) = p.edit().putString("fuel", v.name).apply()

    /** Pausa entre requisições OBD. Aumente se o som engasgar. */
    var requestGapMs: Long
        get() = p.getLong("gap", 40L)
        set(v) = p.edit().putLong("gap", v).apply()

    /** Pedir várias PIDs numa requisição só. Desligue se o clone ELM327 não aceitar. */
    var multiPid: Boolean
        get() = p.getBoolean("multi", true)
        set(v) = p.edit().putBoolean("multi", v).apply()

    /** Usa um ELM327 simulado, sem Bluetooth, para ver o app funcionando fora do carro. */
    var demoMode: Boolean
        get() = p.getBoolean("demo", false)
        set(v) = p.edit().putBoolean("demo", v).apply()

    val canConnect: Boolean get() = demoMode || deviceAddress != null

    /** Pacote do app de música preferido (Spotify, YouTube Music, Deezer…). */
    var musicApp: String
        get() = p.getString("music", "com.spotify.music")!!
        set(v) = p.edit().putString("music", v).apply()

    /** Pacote do app de navegação preferido (Waze ou Google Maps). */
    var navApp: String
        get() = p.getString("nav", "com.waze")!!
        set(v) = p.edit().putString("nav", v).apply()

    var logCsv: Boolean
        get() = p.getBoolean("csv", false)
        set(v) = p.edit().putBoolean("csv", v).apply()
}
