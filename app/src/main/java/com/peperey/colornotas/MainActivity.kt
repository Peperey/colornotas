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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

// Cada parte de una nota: un trozo de texto, o un archivo (imagen o documento) en su posición
sealed class Bloque {
    data class Texto(val valor: String) : Bloque()
    data class Archivo(val adjunto: Adjunto) : Bloque()
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

// Añade texto al final: si el último bloque ya es texto, continúa en ese mismo bloque
fun anadirTexto(bloques: List<Bloque>, s: String): List<Bloque> {
    val res = bloques.toMutableList()
    val ult = res.lastOrNull()
    if (ult is Bloque.Texto) {
        res[res.lastIndex] = Bloque.Texto(if (ult.valor.isBlank()) s else ult.valor.trimEnd() + " " + s)
    } else {
        res.add(Bloque.Texto(s))
    }
    return res
}

// Añade archivos al final, seguidos de un bloque de texto para seguir escribiendo debajo
fun anadirAdjuntos(bloques: List<Bloque>, nuevos: List<Adjunto>): List<Bloque> {
    if (nuevos.isEmpty()) return bloques
    val res = bloques.toMutableList()
    nuevos.forEach { res.add(Bloque.Archivo(it)) }
    if (res.lastOrNull() !is Bloque.Texto) res.add(Bloque.Texto(""))
    return res
}

// Quita espacios sobrantes y elimina los bloques de texto vacíos
fun limpiarBloques(bloques: List<Bloque>): List<Bloque> =
    bloques.map { b -> if (b is Bloque.Texto) Bloque.Texto(b.valor.trim()) else b }
        .filter { !(it is Bloque.Texto && it.valor.isEmpty()) }

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

fun guardarNotas(context: Context, notas: List<Nota>) {
    val array = JSONArray()
    notas.forEach { n ->
        val bl = JSONArray()
        n.bloques.forEach { b ->
            val o = JSONObject()
            when (b) {
                is Bloque.Texto -> o.put("tipo", "texto").put("valor", b.valor)
                is Bloque.Archivo -> o.put("tipo", "archivo")
                    .put("archivo", b.adjunto.archivo)
                    .put("nombre", b.adjunto.nombre)
            }
            bl.put(o)
        }
        array.put(
            JSONObject()
                .put("id", n.id)
                .put("titulo", n.titulo)
                .put("color", n.color)
                .put("fecha", n.fecha)
                .put("fijada", n.fijada)
                .put("bloques", bl)
        )
    }
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putString("notas", array.toString())
        .apply()
}

// Lee los bloques de una nota. Las notas anteriores (texto y adjuntos sueltos) se convierten aquí.
fun leerBloques(o: JSONObject): List<Bloque> {
    val lista = mutableListOf<Bloque>()
    val arr = o.optJSONArray("bloques")
    if (arr != null) {
        for (k in 0 until arr.length()) {
            val b = arr.getJSONObject(k)
            if (b.optString("tipo") == "archivo") {
                lista.add(Bloque.Archivo(Adjunto(b.getString("archivo"), b.getString("nombre"))))
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
    var editando by remember { mutableStateOf<Nota?>(null) }
    var esNueva by remember { mutableStateOf(false) }
    var busqueda by remember { mutableStateOf("") }
    var clave by remember { mutableStateOf(cargarClave(context)) }
    var orden by remember { mutableStateOf(Orden.FECHA) }

    AbrirDesdeCompartir(entrada) { n ->
        esNueva = true
        editando = n
    }

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

    val actual = editando
    if (actual != null) {
        EditorNota(
            nota = actual,
            esNueva = esNueva,
            clave = clave,
            onGuardar = {
                guardar(it)
                editando = null
            },
            onBorrar = {
                borrar(actual.id)
                editando = null
            },
            onCancelar = { editando = null }
        )
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
            onNueva = {
                esNueva = true
                editando = Nota(System.currentTimeMillis(), "", 0, 0L)
            },
            onAbrir = { n ->
                esNueva = false
                editando = n
            },
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

// Imagen dentro de la nota, a tamaño completo, con botón para quitarla
@Composable
fun ImagenEnNota(context: Context, a: Adjunto, onQuitar: () -> Unit) {
    val imagen = remember(a.archivo) { cargarMiniatura(context, a)?.asImageBitmap() }
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        if (imagen != null) {
            Image(
                bitmap = imagen,
                contentDescription = a.nombre,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(8.dp))
            )
        } else {
            Text("No se pudo mostrar ${a.nombre}", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = onQuitar) {
            Text("Quitar imagen")
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

    var titulo by remember(nota.id) { mutableStateOf(nota.titulo) }
    var color by remember(nota.id) { mutableStateOf(nota.color) }
    var fijada by remember(nota.id) { mutableStateOf(nota.fijada) }
    var bloques by remember(nota.id) {
        mutableStateOf<List<Bloque>>(nota.bloques.ifEmpty { listOf(Bloque.Texto("")) })
    }
    var corrigiendo by remember(nota.id) { mutableStateOf(false) }
    var originalTitulo by remember(nota.id) { mutableStateOf("") }
    var originalBloques by remember(nota.id) { mutableStateOf<List<Bloque>>(emptyList()) }
    var propuestaTitulo by remember(nota.id) { mutableStateOf<String?>(null) }
    var propuestaBloques by remember(nota.id) { mutableStateOf<List<Bloque>?>(null) }
    var mensaje by remember(nota.id) { mutableStateOf("") }

    fun cambiarTexto(i: Int, valor: String) {
        bloques = bloques.toMutableList().also { it[i] = Bloque.Texto(valor) }
    }

    fun quitarBloque(i: Int) {
        val b = bloques[i]
        if (b is Bloque.Archivo) borrarAdjunto(context, b.adjunto)
        bloques = bloques.toMutableList().also { it.removeAt(i) }.ifEmpty { listOf(Bloque.Texto("")) }
    }

    // Copia los archivos a la carpeta privada en segundo plano y los inserta en la nota
    fun adjuntarAsync(uris: List<Uri>, permisos: DragAndDropPermissions?) {
        if (uris.isEmpty()) return
        scope.launch {
            val nuevos = withContext(Dispatchers.IO) {
                uris.mapNotNull { copiarAdjunto(context, it) }
            }
            // Libera el permiso del arrastre cuando ya se han copiado los archivos
            permisos?.release()
            bloques = anadirAdjuntos(bloques, nuevos)
            mensaje = if (nuevos.isEmpty()) "No se pudo adjuntar" else "Adjuntado: ${nuevos.size}"
        }
    }

    // Recibe el texto que el reconocedor de voz ha entendido y lo añade al final de la nota
    val lanzarDictado = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { resultado ->
        val dicho = resultado.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!dicho.isNullOrBlank()) {
            bloques = anadirTexto(bloques, dicho)
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
            ClipData.newPlainText("nota", "${titulo.trim()}\n\n${textoPlano(bloques)}")
        )
        Toast.makeText(context, "Nota copiada", Toast.LENGTH_SHORT).show()
    }

    fun compartirNota() {
        val envio = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, titulo.trim())
            putExtra(Intent.EXTRA_TEXT, "${titulo.trim()}\n\n${textoPlano(bloques)}")
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

        // Contenido de la nota: texto e imágenes en el orden en que están en la nota
        bloques.forEachIndexed { i, b ->
            when (b) {
                is Bloque.Texto -> OutlinedTextField(
                    value = b.valor,
                    onValueChange = { cambiarTexto(i, it) },
                    label = { Text("Nota") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                is Bloque.Archivo -> {
                    if (esImagen(b.adjunto)) {
                        ImagenEnNota(context, b.adjunto) { quitarBloque(i) }
                    } else {
                        AdjuntoFila(context, b.adjunto) { quitarBloque(i) }
                    }
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
            Text("Insertar imagen o archivo al final")
        }
        ZonaArrastre(onSoltar = { uris, permisos -> adjuntarAsync(uris, permisos) })

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
                val bl = bloques
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
            enabled = !corrigiendo && clave.isNotBlank() && textoPlano(bloques).isNotBlank(),
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
                            propuestaBloques?.let { bloques = it }
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
                    val sinContenido = titulo.isBlank() &&
                        bloques.all { it is Bloque.Texto && it.valor.isBlank() }
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
                                bloques = limpiarBloques(bloques)
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
