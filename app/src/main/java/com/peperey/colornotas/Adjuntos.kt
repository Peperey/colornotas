package com.peperey.colornotas

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.view.DragAndDropPermissions
import android.view.DragEvent
import android.view.Gravity
import android.webkit.MimeTypeMap
import android.widget.TextView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import java.util.UUID

// Un archivo adjunto a una nota: nombre guardado en la app y nombre original
data class Adjunto(val archivo: String, val nombre: String)

private val EXTENSIONES_IMAGEN = setOf("jpg", "jpeg", "png", "webp", "gif")

fun esImagen(a: Adjunto): Boolean =
    a.archivo.substringAfterLast('.', "").lowercase() in EXTENSIONES_IMAGEN

fun carpetaAdjuntos(context: Context): File =
    File(context.filesDir, "adjuntos").apply { mkdirs() }

fun nombreDe(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

// Copia un archivo recibido (del selector o arrastrado) a la carpeta privada de la app
fun copiarAdjunto(context: Context, uri: Uri): Adjunto? = runCatching {
    val mime = context.contentResolver.getType(uri)
    val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
    val nombre = nombreDe(context, uri) ?: "archivo.$ext"
    val archivo = "${UUID.randomUUID()}.$ext"
    val entrada = context.contentResolver.openInputStream(uri) ?: error("sin acceso")
    entrada.use { e ->
        File(carpetaAdjuntos(context), archivo).outputStream().use { s -> e.copyTo(s) }
    }
    Adjunto(archivo, nombre)
}.getOrNull()

fun borrarAdjunto(context: Context, a: Adjunto) {
    File(carpetaAdjuntos(context), a.archivo).delete()
}

// Reduce la imagen para la miniatura y evita cargar fotos enormes en memoria
fun cargarMiniatura(context: Context, a: Adjunto): Bitmap? {
    val f = File(carpetaAdjuntos(context), a.archivo)
    if (!f.exists()) return null
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.path, o)
    var muestra = 1
    while (o.outWidth / muestra > 800 || o.outHeight / muestra > 800) muestra *= 2
    return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = muestra })
}

// Zona donde se pueden soltar imágenes o archivos arrastrados desde otra app
@Composable
fun ZonaArrastre(onSoltar: (List<Uri>, DragAndDropPermissions?) -> Unit) {
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(90.dp),
        factory = { ctx ->
            TextView(ctx).apply {
                text = "Arrastra aquí imágenes o archivos desde otra app"
                gravity = Gravity.CENTER
                setPadding(24, 24, 24, 24)
                setBackgroundColor(android.graphics.Color.parseColor("#EEEEEE"))
                setOnDragListener { v, evento ->
                    when (evento.action) {
                        DragEvent.ACTION_DRAG_STARTED -> evento.clipDescription != null
                        DragEvent.ACTION_DRAG_ENTERED -> {
                            setBackgroundColor(android.graphics.Color.parseColor("#C5CAE9"))
                            true
                        }
                        DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> {
                            setBackgroundColor(android.graphics.Color.parseColor("#EEEEEE"))
                            true
                        }
                        DragEvent.ACTION_DROP -> {
                            // Pide permiso temporal para leer los archivos que se han soltado
                            val permisos = (ctx as? Activity)?.requestDragAndDropPermissions(evento)
                            val uris = evento.clipData?.let { cd ->
                                (0 until cd.itemCount).mapNotNull { cd.getItemAt(it).uri }
                            } ?: emptyList()
                            setBackgroundColor(android.graphics.Color.parseColor("#EEEEEE"))
                            onSoltar(uris, permisos)
                            true
                        }
                        else -> true
                    }
                }
            }
        }
    )
}

// Una fila por adjunto: miniatura si es imagen, icono si es otro archivo
@Composable
fun AdjuntoFila(context: Context, a: Adjunto, onQuitar: () -> Unit) {
    val miniatura = remember(a.archivo) {
        if (esImagen(a)) cargarMiniatura(context, a)?.asImageBitmap() else null
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        if (miniatura != null) {
            Image(
                bitmap = miniatura,
                contentDescription = a.nombre,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp))
            )
        } else {
            Text("📄", style = MaterialTheme.typography.headlineSmall)
        }
        Text(
            a.nombre,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        OutlinedButton(onClick = onQuitar) {
            Text("Quitar")
        }
    }
}
