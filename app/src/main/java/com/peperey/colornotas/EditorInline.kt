package com.peperey.colornotas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ImageSpan
import android.widget.EditText

private const val OBJETO = "\uFFFC"

// Imagen dentro del texto, con su archivo, su escala y el bitmap original para poder redimensionarla
class ImagenSpan(
    drawable: Drawable,
    val adjunto: Adjunto,
    val escala: Float,
    val original: Bitmap
) : ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM)

// Editor nativo: permite texto con imágenes en línea y avisa cuando cambia la selección
class EditorTexto(context: Context) : EditText(context) {
    var alCambiarSeleccion: ((Int, Int) -> Unit)? = null

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        alCambiarSeleccion?.invoke(selStart, selEnd)
    }
}

// Referencia al editor, para leerlo y modificarlo desde la pantalla
class EditorHolder {
    var view: EditorTexto? = null
}

fun anchoBasePx(context: Context): Int =
    (260 * context.resources.displayMetrics.density).toInt()

fun crearImagenSpan(context: Context, adjunto: Adjunto, original: Bitmap, escala: Float): ImagenSpan {
    val w = (anchoBasePx(context) * escala).toInt().coerceAtLeast(60)
    val h = (w * original.height / original.width.toFloat()).toInt().coerceAtLeast(60)
    val escalado = Bitmap.createScaledBitmap(original, w, h, true)
    val d = BitmapDrawable(context.resources, escalado)
    d.setBounds(0, 0, w, h)
    return ImagenSpan(d, adjunto, escala, original)
}

// Convierte los bloques de una nota en texto del editor, con las imágenes en su posición
fun bloquesAEditable(context: Context, bloques: List<Bloque>): Editable {
    val sb = SpannableStringBuilder()
    bloques.forEach { b ->
        when (b) {
            is Bloque.Texto -> sb.append(b.valor)
            is Bloque.Archivo -> {
                if (esImagen(b.adjunto)) {
                    val bmp = cargarMiniatura(context, b.adjunto)
                    if (bmp != null) {
                        val ini = sb.length
                        sb.append(OBJETO)
                        sb.setSpan(
                            crearImagenSpan(context, b.adjunto, bmp, b.escala),
                            ini,
                            ini + 1,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                }
            }
        }
    }
    return sb
}

// Convierte el texto del editor en bloques: texto, e imágenes en su posición
fun editableABloques(e: CharSequence): List<Bloque> {
    val spanned = e as? Spanned ?: return listOf(Bloque.Texto(e.toString()))
    val spans = spanned.getSpans(0, spanned.length, ImagenSpan::class.java)
        .sortedBy { spanned.getSpanStart(it) }
    val res = mutableListOf<Bloque>()
    var pos = 0
    spans.forEach { s ->
        val ini = spanned.getSpanStart(s)
        val fin = spanned.getSpanEnd(s)
        if (ini > pos) res.add(Bloque.Texto(spanned.subSequence(pos, ini).toString()))
        res.add(Bloque.Archivo(s.adjunto, s.escala))
        pos = fin
    }
    if (pos < spanned.length) {
        res.add(Bloque.Texto(spanned.subSequence(pos, spanned.length).toString()))
    }
    return res
}

fun cargarEnEditor(context: Context, view: EditText, bloques: List<Bloque>) {
    view.setText(bloquesAEditable(context, bloques))
}

// Inserta una imagen en la posición del cursor, en su propia línea
fun insertarImagenEnEditor(context: Context, view: EditText, a: Adjunto): Boolean {
    val bmp = cargarMiniatura(context, a) ?: return false
    val e = view.editableText
    val pos = view.selectionStart.coerceIn(0, e.length)
    e.insert(pos, "\n" + OBJETO + "\n")
    val ini = pos + 1
    e.setSpan(
        crearImagenSpan(context, a, bmp, 1f),
        ini,
        ini + 1,
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
    )
    view.setSelection(pos + 3)
    return true
}

// Cambia el tamaño de una imagen ya insertada
fun cambiarEscalaImagen(context: Context, view: EditText, span: ImagenSpan, nueva: Float): ImagenSpan? {
    val e = view.editableText
    val ini = e.getSpanStart(span)
    val fin = e.getSpanEnd(span)
    if (ini < 0) return null
    e.removeSpan(span)
    val nuevo = crearImagenSpan(context, span.adjunto, span.original, nueva)
    e.setSpan(nuevo, ini, fin, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    return nuevo
}

// Quita una imagen del texto
fun quitarImagenEnEditor(view: EditText, span: ImagenSpan) {
    val e = view.editableText
    val ini = e.getSpanStart(span)
    val fin = e.getSpanEnd(span)
    if (ini >= 0) e.delete(ini, fin)
}

// Devuelve la imagen que está justo en la selección o pegada a ella
fun imagenCerca(view: EditText, inicio: Int, fin: Int): ImagenSpan? {
    val texto = view.text
    val lo = (inicio - 1).coerceAtLeast(0)
    val hi = (fin + 1).coerceAtMost(texto.length)
    return texto.getSpans(lo, hi, ImagenSpan::class.java).firstOrNull()
}

fun EditText.alCambiarTexto(accion: () -> Unit) {
    addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            accion()
        }
    })
}
