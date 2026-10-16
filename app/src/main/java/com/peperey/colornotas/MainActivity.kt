package com.peperey.colornotas

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.DragAndDropPermissions
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                ColorNotasApp(intent)
            }
        }
    }
}

// Colores de fondo disponibles para las notas
val PALETA = listOf(
    Color(0xFFFFF59D),
    Color(0xFFA5D6A7),
    Color(0xFF90CAF9),
    Color(0xFFF48FB1),
    Color(0xFFFFCC80),
    Color(0xFFE0E0E0)
)

fun colorDe(indice: Int): Color = PALETA.getOrElse(indice) { PALETA[0] }

enum class Orden(val etiqueta: String) {
    FECHA("Fecha"),
    TITULO("Título"),
    COLOR("Color")
}

// Cada parte de una nota: texto, o un archivo (imagen en su posición, o documento)
sealed class Bloque {
    data class Texto(val valor: String) : Bloque()
    data class Archivo(val adjunto: Adjunto, val escala: Float = 1f) : Bloque()
}

data class Nota(
    val id: Long,
    val titulo: String,
    val color: Int,
    val fecha: Long,
    val fijada: Boolean = false,
    val bloques: List<Bloque> = listOf(Bloque.Texto(""))
)

// Texto de la nota sin imágenes, para buscar, mostrar en la lista y copiar
fun textoPlano(bloques: List<Bloque>): String =
    bloques.filterIsInstance<Bloque.Texto>()
        .map { it.valor }
        .filter { it.isNotBlank() }
        .joinToString("\n")

// Quita los bloques de texto vacíos (se conservan saltos de línea para mantener la posición de las imágenes)
fun limpiarBloques(bloques: List<Bloque>): List<Bloque> =
    bloques.filter { !(it is Bloque.Texto && it.valor.isEmpty()) }

// Corrige cada bloque de texto por separado, para que las imágenes conserven su posición
fun corregirBloques(clave: String, titulo: String, bloques: List<Bloque>): Pair<String, List<Bloque>> {
    var tituloRes = titulo
    var primero = true
    val res = bloques.map { b: Bloque ->
        if (b is Bloque.Texto && b.valor.isNotBlank()) {
            val c = corregirConGroq(clave, if (primero) titulo else "", b.valor)
            if (primero) tituloRes = c.titulo
            primero = false
            Bloque.Texto(c.texto) as Bloque
        } else {
            b
        }
    }
    return Pair(tituloRes, res)
}

private const val PREFS = "colornotas_prefs"

// Convierte los bloques de una nota a JSON
fun bloquesAJson(bloques: List<Bloque>): JSONArray {
    val bl = JSONArray()
    bloques.forEach { b ->
        val o = JSONObject()
        when (b) {
            is Bloque.Texto -> o.put("tipo", "texto").put("valor", b.valor)
            is Bloque.Archivo -> o.put("tipo", "archivo")
                .put("archivo", b.adjunto.archivo)
                .put("nombre", b.adjunto.nombre)
                .put("escala", b.escala.toDouble())
        }
        bl.put(o)
    }
    return bl
}

fun guardarNotas(context: Context, notas: List<Nota>) {
    val array = JSONArray()
    notas.forEach { n ->
        array.put(
            JSONObject()
                .put("id", n.id)
                .put("titulo", n.titulo)
                .put("color", n.color)
                .put("fecha", n.fecha)
                .put("fijada", n.fijada)
                .put("bloques", bloquesAJson(n.bloques))
        )
    }
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putString("notas", array.toString())
        .apply()
}

// Lee los bloques de una nota o de un borrador. Las notas anteriores se convierten aquí.
fun leerBloques(o: JSONObject): List<Bloque> {
    val lista = mutableListOf<Bloque>()
    val arr = o.optJSONArray("bloques")
    if (arr != null) {
        for (k in 0 until arr.length()) {
            val b = arr.getJSONObject(k)
            if (b.optString("tipo") == "archivo") {
                lista.add(
                    Bloque.Archivo(
                        Adjunto(b.getString("archivo"), b.getString("nombre")),
                        b.optDouble("escala", 1.0).toFloat()
                    )
                )
            } else {
                lista.add(Bloque.Texto(b.optString("valor", "")))
            }
        }
    } else {
        lista.add(Bloque.Texto(o.optString("texto", "")))
        o.optJSONArray("adjuntos")?.let { ja ->
            for (k in 0 until ja.length()) {
                val ad = ja.getJSONObject(k)
                lista.add(Bloque.Archivo(Adjunto(ad.getString("archivo"), ad.getString("nombre"))))
            }
        }
    }
    return lista
}

fun cargarNotas(context: Context): List<Nota> {
    return runCatching {
        val texto = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("notas", null) ?: return@runCatching emptyList<Nota>()
        val array = JSONArray(texto)
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Nota(
                id = o.getLong("id"),
                titulo = o.getString("titulo"),
                color = o.getInt("color"),
                fecha = o.getLong("fecha"),
                fijada = o.optBoolean("fijada", false),
                bloques = leerBloques(o)
            )
        }
    }.getOrElse { emptyList() }
}

// Borrador: copia de la nota mientras se edita, para no perder nada si la app se cierra
private fun claveBorrador(id: Long) = "borrador_$id"

fun guardarBorrador(
    context: Context,
    id: Long,
    titulo: String,
    color: Int,
    fijada: Boolean,
    bloques: List<Bloque>
) {
    val o = JSONObject()
        .put("titulo", titulo)
        .put("color", color)
        .put("fijada", fijada)
        .put("bloques", bloquesAJson(bloques))
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putString(claveBorrador(id), o.toString())
        .apply()
}

fun cargarBorrador(context: Context, id: Long): JSONObject? = runCatching {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(claveBorrador(id), null)
        ?.let { JSONObject(it) }
}.getOrNull()

fun borrarBorrador(context: Context, id: Long) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .remove(claveBorrador(id))
        .apply()
}

// Recuerda qué nota está abierta, para volver a abrirla si la app se cierra
private const val CLAVE_ABIERTA = "nota_abierta"

fun marcarAbierta(context: Context, id: Long?) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putLong(CLAVE_ABIERTA, id ?: -1L)
        .apply()
}

fun abiertaAlIniciar(context: Context): Long? =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getLong(CLAVE_ABIERTA, -1L)
        .takeIf { it != -1L }

// La clave de Groq se guarda solo en el teléfono
fun guardarClave(context: Context, clave: String) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putString("groq_key", clave)
        .apply()
}

fun cargarClave(context: Context): String =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("groq_key", "") ?: ""

@Composable
fun ColorNotasApp(entrada: Intent? = null) {
    val context = LocalContext.current
    val notas = remember { mutableStateListOf<Nota>().apply { addAll(cargarNotas(context)) } }
    val abiertaInicial = remember { abiertaAlIniciar(context) }
    var editando by remember {
        mutableStateOf<Nota?>(
            abiertaInicial?.takeIf { cargarBorrador(context, it) != null }?.let { id ->
                notas.firstOrNull { it.id == id } ?: Nota(id, "", 0, 0L)
            }
        )
    }
    var esNueva by remember {
        mutableStateOf(abiertaInicial != null && notas.none { it.id == abiertaInicial })
    }
    var busqueda by remember { mutableStateOf("") }
    var clave by remember { mutableStateOf(cargarClave(context)) }
    var orden by remember { mutableStateOf(Orden.FECHA) }

    fun guardarTodo() {
        guardarNotas(context, notas.toList())
    }

    fun guardar(nota: Nota) {
        val idx = notas.indexOfFirst { it.id == nota.id }
        if (idx >= 0) notas[idx] = nota else notas.add(nota)
        guardarTodo()
    }

    fun borrar(id: Long) {
        // Al borrar la nota también se borran sus archivos
        notas.firstOrNull { it.id == id }?.bloques?.forEach { b ->
            if (b is Bloque.Archivo) borrarAdjunto(context, b.adjunto)
        }
        notas.removeAll { it.id == id }
        guardarTodo()
    }

    fun alternarFijada(id: Long) {
        val idx = notas.indexOfFirst { it.id == id }
        if (idx >= 0) notas[idx] = notas[idx].copy(fijada = !notas[idx].fijada)
        guardarTodo()
    }

    fun abrir(nota: Nota, nueva: Boolean) {
        editando = nota
        esNueva = nueva
        marcarAbierta(context, nota.id)
    }

    fun cerrar() {
        editando = null
        marcarAbierta(context, null)
    }

    AbrirDesdeCompartir(entrada) { n -> abrir(n, true) }

    val actual = editando
    if (actual != null) {
        key(actual.id) {
            EditorNota(
                nota = actual,
                esNueva = esNueva,
                clave = clave,
                onGuardar = {
                    guardar(it)
                    borrarBorrador(context, actual.id)
                    cerrar()
                },
                onBorrar = {
                    borrar(actual.id)
                    borrarBorrador(context, actual.id)
                    cerrar()
                },
                onCancelar = {
                    borrarBorrador(context, actual.id)
                    cerrar()
                }
            )
        }
    } else {
        val filtradas = notas.filter { n ->
            busqueda.isBlank() ||
                n.titulo.contains(busqueda, ignoreCase = true) ||
                textoPlano(n.bloques).contains(busqueda, ignoreCase = true)
        }
        val ordenadas = when (orden) {
            Orden.FECHA -> filtradas.sortedByDescending { it.fecha }
            Orden.TITULO -> filtradas.sortedBy { it.titulo.lowercase() }
            Orden.COLOR -> filtradas.sortedBy { it.color }
        }
        // Las fijadas van primero; sortedByDescending es estable, así que conserva el orden elegido
        val visibles = ordenadas.sortedByDescending { it.fijada }

        ListaNotas(
            visibles = visibles,
            busqueda = busqueda,
            onBusqueda = { busqueda = it },
            orden = orden,
            onOrden = { orden = it },
            clave = clave,
            onClave = { clave = it },
            onGuardarClave = { guardarClave(context, clave.trim()) },
            onNueva = { abrir(Nota(System.currentTimeMillis(), "", 0, 0L), true) },
            onAbrir = { n -> abrir(n, false) },
            onFijar = { alternarFijada(it) }
        )
    }
}

@Composable
fun ListaNotas(
    visibles: List<Nota>,
    busqueda: String,
    onBusqueda: (String) -> Unit,
    orden: Orden,
    onOrden: (Orden) -> Unit,
    clave: String,
    onClave: (String) -> Unit,
    onGuardarClave: () -> Unit,
    onNueva: () -> Unit,
    onAbrir: (Nota) -> Unit,
    onFijar: (Long) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Color Notas", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = busqueda,
            onValueChange = onBusqueda,
            label = { Text("Buscar") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Text("Ordenar por", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Orden.entries.forEach { o ->
                FilterChip(
                    selected = o == orden,
                    onClick = { onOrden(o) },
                    label = { Text(o.etiqueta) }
                )
            }
        }
        OutlinedTextField(
            value = clave,
            onValueChange = onClave,
            label = { Text("Clave de Groq (para corregir)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedButton(
            onClick = onGuardarClave,
            enabled = clave.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Guardar clave")
        }
        Button(onClick = onNueva, modifier = Modifier.fillMaxWidth()) {
            Text("Nueva nota")
        }
        if (visibles.isEmpty()) {
            Text(
                if (busqueda.isBlank()) "Aún no tienes notas. Pulsa Nueva nota."
                else "No hay notas que coincidan.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(visibles, key = { it.id }) { n ->
                val texto = textoPlano(n.bloques)
                val numArchivos = n.bloques.count { it is Bloque.Archivo }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colorDe(n.color))
                        .clickable { onAbrir(n) }
                        .padding(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                (if (n.fijada) "📌 " else "") + n.titulo.ifBlank { "Sin título" },
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedButton(onClick = { onFijar(n.id) }) {
                                Text(if (n.fijada) "Quitar" else "Fijar")
                            }
                        }
                        if (texto.isNotBlank()) {
                            Text(
                                texto,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (numArchivos > 0) {
                            Text(
                                "📎 $numArchivos",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EditorNota(
    nota: Nota,
    esNueva: Boolean,
    clave: String,
    onGuardar: (Nota) -> Unit,
    onBorrar: () -> Unit,
    onCancelar: () -> Unit
) {
    // El botón atrás del teléfono cancela la edición
    BackHandler { onCancelar() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()

    // Si hay un borrador guardado (la app se cerró a mitad de edición), se recupera
    val borrador = remember(nota.id) { cargarBorrador(context, nota.id) }
    val bloquesIniciales = remember(nota.id) {
        (borrador?.let { leerBloques(it) } ?: nota.bloques).ifEmpty { listOf(Bloque.Texto("")) }
    }

    var titulo by remember(nota.id) {
        mutableStateOf(borrador?.optString("titulo", nota.titulo) ?: nota.titulo)
    }
    var color by remember(nota.id) {
        mutableStateOf(borrador?.optInt("color", nota.color) ?: nota.color)
    }
    var fijada by remember(nota.id) {
        mutableStateOf(borrador?.optBoolean("fijada", nota.fijada) ?: nota.fijada)
    }
    // Documentos que no son imágenes: se muestran en una lista debajo del texto
    var docs by remember(nota.id) {
        mutableStateOf(
            bloquesIniciales.filterIsInstance<Bloque.Archivo>()
                .filter { !esImagen(it.adjunto) }
                .map { it.adjunto }
        )
    }
    val holder = remember(nota.id) { EditorHolder() }
    var versionTexto by remember(nota.id) { mutableIntStateOf(0) }
    // Imagen seleccionada en el editor, para cambiar su tamaño
    var seleccion by remember(nota.id) { mutableStateOf<ImagenSpan?>(null) }

    var corrigiendo by remember(nota.id) { mutableStateOf(false) }
    var originalTitulo by remember(nota.id) { mutableStateOf("") }
    var originalBloques by remember(nota.id) { mutableStateOf<List<Bloque>>(emptyList()) }
    var propuestaTitulo by remember(nota.id) { mutableStateOf<String?>(null) }
    var propuestaBloques by remember(nota.id) { mutableStateOf<List<Bloque>?>(null) }
    var mensaje by remember(nota.id) { mutableStateOf("") }

    // Contenido actual de la nota: lo que hay en el editor más los documentos
    fun contenido(): List<Bloque> {
        val v = holder.view
        val cuerpo = if (v != null) {
            editableABloques(v.text)
        } else {
            bloquesIniciales.filter { it !is Bloque.Archivo || esImagen(it.adjunto) }
        }
        return cuerpo + docs.map { Bloque.Archivo(it) }
    }

    // Cada cambio se guarda como borrador
    LaunchedEffect(titulo, color, fijada, versionTexto, docs) {
        guardarBorrador(context, nota.id, titulo, color, fijada, contenido())
    }

    fun escalar(delta: Float) {
        val s = seleccion ?: return
        val v = holder.view ?: return
        val nuevo = (s.escala + delta).coerceIn(0.2f, 2f)
        seleccion = cambiarEscalaImagen(context, v, s, nuevo)
    }

    fun quitarImagen() {
        val s = seleccion ?: return
        val v = holder.view ?: return
        quitarImagenEnEditor(v, s)
        seleccion = null
    }

    // Copia los archivos a la carpeta privada y los inserta: imágenes en el texto, documentos debajo
    fun adjuntarAsync(uris: List<Uri>, permisos: DragAndDropPermissions?) {
        if (uris.isEmpty()) return
        scope.launch {
            val nuevos = withContext(Dispatchers.IO) {
                uris.mapNotNull { copiarAdjunto(context, it) }
            }
            // Libera el permiso del arrastre cuando ya se han copiado los archivos
            permisos?.release()
            val v = holder.view
            nuevos.forEach { a ->
                val insertada = v != null && esImagen(a) && insertarImagenEnEditor(context, v, a)
                if (!insertada) docs = docs + a
            }
            mensaje = if (nuevos.isEmpty()) "No se pudo adjuntar" else "Adjuntado: ${nuevos.size}"
        }
    }

    // Recibe el texto dictado y lo escribe en la posición del cursor
    val lanzarDictado = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { resultado ->
        val dicho = resultado.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!dicho.isNullOrBlank()) {
            holder.view?.let { v ->
                val pos = v.selectionStart.coerceIn(0, v.editableText.length)
                v.editableText.insert(pos, "$dicho ")
            }
            mensaje = "Dictado añadido"
        }
    }

    val lanzarArchivos = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> adjuntarAsync(uris, null) }

    fun dictar() {
        val intento = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Dicta la nota")
        }
        try {
            lanzarDictado.launch(intento)
        } catch (e: ActivityNotFoundException) {
            mensaje = "Este teléfono no tiene reconocimiento de voz"
        }
    }

    fun copiarNota() {
        val portapapeles = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        portapapeles.setPrimaryClip(
            ClipData.newPlainText("nota", "${titulo.trim()}\n\n${textoPlano(contenido())}")
        )
        Toast.makeText(context, "Nota copiada", Toast.LENGTH_SHORT).show()
    }

    fun compartirNota() {
        val envio = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, titulo.trim())
            putExtra(Intent.EXTRA_TEXT, "${titulo.trim()}\n\n${textoPlano(contenido())}")
        }
        context.startActivity(Intent.createChooser(envio, "Compartir nota"))
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            if (esNueva) "Nueva nota" else "Editar nota",
            style = MaterialTheme.typography.headlineSmall
        )
        OutlinedTextField(
            value = titulo,
            onValueChange = { titulo = it },
            label = { Text("Título") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        // Editor de la nota: texto e imágenes en el mismo cuadro, como en Word
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { ctx ->
                EditorTexto(ctx).apply {
                    hint = "Escribe la nota..."
                    minLines = 6
                    setText(bloquesAEditable(ctx, bloquesIniciales))
                    holder.view = this
                    alCambiarTexto { versionTexto++ }
                    alCambiarSeleccion = { inicio, fin ->
                        seleccion = imagenCerca(this, inicio, fin)
                    }
                }
            }
        )

        // Barra de la imagen seleccionada: se muestra al tocar una imagen
        seleccion?.let { s ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(onClick = { escalar(-0.1f) }) {
                    Text("−")
                }
                Text(
                    "Imagen ${(s.escala * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(onClick = { escalar(0.1f) }) {
                    Text("+")
                }
                OutlinedButton(onClick = { quitarImagen() }) {
                    Text("Quitar")
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedButton(onClick = { dictar() }, modifier = Modifier.weight(1f)) {
                Text("Dictar")
            }
            OutlinedButton(onClick = { copiarNota() }, modifier = Modifier.weight(1f)) {
                Text("Copiar")
            }
            OutlinedButton(onClick = { compartirNota() }, modifier = Modifier.weight(1f)) {
                Text("Compartir")
            }
        }

        OutlinedButton(
            onClick = { lanzarArchivos.launch(arrayOf("*/*")) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Insertar imagen o archivo en el cursor")
        }
        ZonaArrastre(onSoltar = { uris, permisos -> adjuntarAsync(uris, permisos) })

        if (docs.isNotEmpty()) {
            Text("Documentos adjuntos", style = MaterialTheme.typography.titleSmall)
            docs.forEach { a ->
                AdjuntoFila(context, a) { docs = docs - a }
            }
        }

        Text("Color", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PALETA.forEachIndexed { i, c ->
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(c)
                        .border(if (i == color) 3.dp else 1.dp, Color.DarkGray, CircleShape)
                        .clickable { color = i }
                )
            }
        }
        OutlinedButton(
            onClick = { fijada = !fijada },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (fijada) "Quitar de arriba" else "Fijar arriba")
        }

        Button(
            onClick = {
                val t = titulo
                val bl = contenido()
                originalTitulo = t
                originalBloques = bl
                corrigiendo = true
                mensaje = "Corrigiendo..."
                propuestaTitulo = null
                propuestaBloques = null
                scope.launch {
                    try {
                        val (tituloNuevo, bloquesNuevos) = withContext(Dispatchers.IO) {
                            corregirBloques(clave.trim(), t, bl)
                        }
                        propuestaTitulo = tituloNuevo
                        propuestaBloques = bloquesNuevos
                        val hayCambios = tituloNuevo != t || bloquesNuevos != bl
                        mensaje = if (hayCambios) "Revisa los cambios antes de usarlos"
                        else "No se encontraron errores"
                    } catch (e: Exception) {
                        mensaje = "Error: ${e.message ?: "desconocido"}"
                    } finally {
                        corrigiendo = false
                    }
                }
            },
            enabled = !corrigiendo && clave.isNotBlank() && textoPlano(contenido()).isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Corregir ortografía con IA")
        }
        if (clave.isBlank()) {
            Text(
                "Pon la clave de Groq en la pantalla principal para usar la corrección.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (mensaje.isNotBlank()) {
            Text(mensaje, style = MaterialTheme.typography.bodySmall)
        }

        propuestaBloques?.let { pb ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF5F5F5))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Cambios propuestos", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Rojo tachado: se borra. Verde en negrita: se añade.",
                    style = MaterialTheme.typography.bodySmall
                )
                propuestaTitulo?.let { pt ->
                    if (pt != originalTitulo) VistaDiferencias(originalTitulo, pt)
                }
                originalBloques.forEachIndexed { i, ob ->
                    val nb = pb.getOrNull(i)
                    if (ob is Bloque.Texto && nb is Bloque.Texto && ob.valor != nb.valor) {
                        VistaDiferencias(ob.valor, nb.valor)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            propuestaTitulo?.let { titulo = it }
                            docs = pb.filterIsInstance<Bloque.Archivo>()
                                .filter { !esImagen(it.adjunto) }
                                .map { it.adjunto }
                            holder.view?.let { cargarEnEditor(context, it, pb) }
                            propuestaTitulo = null
                            propuestaBloques = null
                            mensaje = "Corrección aplicada"
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Usar")
                    }
                    OutlinedButton(
                        onClick = {
                            propuestaTitulo = null
                            propuestaBloques = null
                            mensaje = "Corrección descartada"
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Descartar")
                    }
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(
                onClick = {
                    val bl = contenido()
                    val sinContenido = titulo.isBlank() &&
                        bl.all { it is Bloque.Texto && it.valor.isBlank() }
                    if (sinContenido) {
                        // Una nota vacía no se guarda
                        onBorrar()
                    } else {
                        onGuardar(
                            Nota(
                                id = nota.id,
                                titulo = titulo.trim(),
                                color = color,
                                fecha = System.currentTimeMillis(),
                                fijada = fijada,
                                bloques = limpiarBloques(bl)
                            )
                        )
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Guardar")
            }
            OutlinedButton(onClick = onCancelar, modifier = Modifier.weight(1f)) {
                Text("Cancelar")
            }
        }
        if (!esNueva) {
            OutlinedButton(onClick = onBorrar, modifier = Modifier.fillMaxWidth()) {
                Text("Borrar nota")
            }
        }
    }
}
