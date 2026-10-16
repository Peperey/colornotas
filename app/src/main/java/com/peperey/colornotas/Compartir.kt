package com.peperey.colornotas

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Archivos recibidos desde el menú Compartir de otra app
fun uriCompartidas(intent: Intent?): List<Uri> {
    if (intent == null) return emptyList()
    return when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(uriUnica(intent))
        Intent.ACTION_SEND_MULTIPLE -> uriMultiples(intent)
        else -> emptyList()
    }
}

@Suppress("DEPRECATION")
private fun uriUnica(intent: Intent): Uri? =
    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)

@Suppress("DEPRECATION")
private fun uriMultiples(intent: Intent): List<Uri> =
    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.toList() ?: emptyList()

// Si la app se abrió desde Compartir, crea una nota nueva con las imágenes recibidas dentro
@Composable
fun AbrirDesdeCompartir(entrada: Intent?, onAbrir: (Nota) -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        val uris = uriCompartidas(entrada)
        if (uris.isEmpty()) return@LaunchedEffect
        // Quita la acción para no volver a abrir la misma nota si la actividad se recrea
        entrada?.action = null
        val adjuntos = withContext(Dispatchers.IO) {
            uris.mapNotNull { copiarAdjunto(context, it) }
        }
        if (adjuntos.isNotEmpty()) {
            val bloques = mutableListOf<Bloque>()
            adjuntos.forEach { bloques.add(Bloque.Archivo(it)) }
            bloques.add(Bloque.Texto(""))
            onAbrir(
                Nota(
                    id = System.currentTimeMillis(),
                    titulo = "",
                    color = 0,
                    fecha = 0L,
                    bloques = bloques
                )
            )
        }
    }
}
