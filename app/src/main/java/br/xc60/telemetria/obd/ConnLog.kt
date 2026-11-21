package br.xc60.telemetria.obd

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Registro curto da conexão com o ELM327 (mostrado em Ajustes) para diagnosticar adaptadores. */
object ConnLog {
    private const val MAX = 40
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    fun add(text: String) {
        val line = "${fmt.format(Date())}  ${text.replace("\r", " ").replace("\n", " ").trim()}"
        _lines.value = (_lines.value + line).takeLast(MAX)
    }

    fun clear() { _lines.value = emptyList() }
}
