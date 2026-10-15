package com.peperey.colornotas

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

enum class TipoCambio { IGUAL, BORRADO, ANADIDO }

data class Segmento(val texto: String, val tipo: TipoCambio)

// Compara dos textos palabra por palabra y devuelve qué se conserva, qué se borra y qué se añade
fun diferenciar(original: String, corregido: String): List<Segmento> {
    val a = original.split(Regex("\\s+")).filter { it.isNotEmpty() }
    val b = corregido.split(Regex("\\s+")).filter { it.isNotEmpty() }
    val n = a.size
    val m = b.size

    // Textos demasiado largos: se muestran enteros, sin marcar palabra a palabra
    if (n.toLong() * m > 4_000_000L) {
        return listOf(
            Segmento(original, TipoCambio.BORRADO),
            Segmento(corregido, TipoCambio.ANADIDO)
        )
    }

    val tabla = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) {
        for (j in m - 1 downTo 0) {
            tabla[i][j] = if (a[i] == b[j]) {
                tabla[i + 1][j + 1] + 1
            } else {
                maxOf(tabla[i + 1][j], tabla[i][j + 1])
            }
        }
    }

    val pasos = mutableListOf<Segmento>()
    var i = 0
    var j = 0
    while (i < n && j < m) {
        when {
            a[i] == b[j] -> {
                pasos += Segmento(a[i], TipoCambio.IGUAL)
                i++
                j++
            }
            tabla[i + 1][j] >= tabla[i][j + 1] -> {
                pasos += Segmento(a[i], TipoCambio.BORRADO)
                i++
            }
            else -> {
                pasos += Segmento(b[j], TipoCambio.ANADIDO)
                j++
            }
        }
    }
    while (i < n) {
        pasos += Segmento(a[i], TipoCambio.BORRADO)
        i++
    }
    while (j < m) {
        pasos += Segmento(b[j], TipoCambio.ANADIDO)
        j++
    }

    // Une palabras seguidas del mismo tipo en un solo bloque
    val agrupados = mutableListOf<Segmento>()
    for (p in pasos) {
        val ultimo = agrupados.lastOrNull()
        if (ultimo != null && ultimo.tipo == p.tipo) {
            agrupados[agrupados.size - 1] = Segmento(ultimo.texto + " " + p.texto, p.tipo)
        } else {
            agrupados += p
        }
    }
    return agrupados
}

// Muestra el texto con los cambios marcados
@Composable
fun VistaDiferencias(original: String, corregido: String) {
    val segmentos = remember(original, corregido) { diferenciar(original, corregido) }
    val texto = buildAnnotatedString {
        segmentos.forEachIndexed { k, s ->
            if (k > 0) append(" ")
            when (s.tipo) {
                TipoCambio.IGUAL -> append(s.texto)
                TipoCambio.BORRADO -> withStyle(
                    SpanStyle(color = Color(0xFFD32F2F), textDecoration = TextDecoration.LineThrough)
                ) {
                    append(s.texto)
                }
                TipoCambio.ANADIDO -> withStyle(
                    SpanStyle(color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                ) {
                    append(s.texto)
                }
            }
        }
    }
    Text(texto, style = MaterialTheme.typography.bodyMedium)
}
