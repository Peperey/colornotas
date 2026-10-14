package com.peperey.colornotas

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
                ColorNotasApp()
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

data class Nota(
    val id: Long,
    val titulo: String,
    val texto: String,
    val color: Int,
    val fecha: Long
)

private const val PREFS = "colornotas_prefs"

fun guardarNotas(context: Context, notas: List<Nota>) {
    val array = JSONArray()
    notas.forEach { n ->
        array.put(
            JSONObject()
                .put("id", n.id)
                .put("titulo", n.titulo)
                .put("texto", n.texto)
                .put("color", n.color)
                .put("fecha", n.fecha)
        )
    }
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putString("notas", array.toString())
        .apply()
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
                texto = o.getString("texto"),
                color = o.getInt("color"),
                fecha = o.getLong("fecha")
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
fun ColorNotasApp() {
    val context = LocalContext.current
    val notas = remember { mutableStateListOf<Nota>().apply { addAll(cargarNotas(context)) } }
    var editando by remember { mutableStateOf<Nota?>(null) }
    var esNueva by remember { mutableStateOf(false) }
    var busqueda by remember { mutableStateOf("") }
    var clave by remember { mutableStateOf(cargarClave(context)) }

    fun guardarTodo() {
        guardarNotas(context, notas.toList())
    }

    fun guardar(nota: Nota) {
        val idx = notas.indexOfFirst { it.id == nota.id }
        if (idx >= 0) notas[idx] = nota else notas.add(nota)
        guardarTodo()
    }

    fun borrar(id: Long) {
        notas.removeAll { it.id == id }
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
        val visibles = notas
            .filter { n ->
                busqueda.isBlank() ||
                    n.titulo.contains(busqueda, ignoreCase = true) ||
                    n.texto.contains(busqueda, ignoreCase = true)
            }
            .sortedByDescending { it.fecha }

        ListaNotas(
            visibles = visibles,
            busqueda = busqueda,
            onBusqueda = { busqueda = it },
            clave = clave,
            onClave = { clave = it },
            onGuardarClave = { guardarClave(context, clave.trim()) },
            onNueva = {
                esNueva = true
                editando = Nota(System.currentTimeMillis(), "", "", 0, 0L)
            },
            onAbrir = { n ->
                esNueva = false
                editando = n
            }
        )
    }
}

@Composable
fun ListaNotas(
    visibles: List<Nota>,
    busqueda: String,
    onBusqueda: (String) -> Unit,
    clave: String,
    onClave: (String) -> Unit,
    onGuardarClave: () -> Unit,
    onNueva: () -> Unit,
    onAbrir: (Nota) -> Unit
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
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colorDe(n.color))
                        .clickable { onAbrir(n) }
                        .padding(12.dp)
                ) {
                    Column {
                        Text(
                            n.titulo.ifBlank { "Sin título" },
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (n.texto.isNotBlank()) {
                            Text(
                                n.texto,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
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
    val scope = rememberCoroutineScope()

    var titulo by remember(nota.id) { mutableStateOf(nota.titulo) }
    var texto by remember(nota.id) { mutableStateOf(nota.texto) }
    var color by remember(nota.id) { mutableStateOf(nota.color) }
    var corrigiendo by remember(nota.id) { mutableStateOf(false) }
    var propuesta by remember(nota.id) { mutableStateOf<Correccion?>(null) }
    var mensaje by remember(nota.id) { mutableStateOf("") }
    val scroll = rememberScrollState()

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
        OutlinedTextField(
            value = texto,
            onValueChange = { texto = it },
            label = { Text("Nota") },
            minLines = 6,
            modifier = Modifier.fillMaxWidth()
        )
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

        Button(
            onClick = {
                corrigiendo = true
                mensaje = "Corrigiendo..."
                propuesta = null
                scope.launch {
                    try {
                        val c = withContext(Dispatchers.IO) {
                            corregirConGroq(clave.trim(), titulo, texto)
                        }
                        propuesta = c
                        mensaje = if (c.titulo == titulo && c.texto == texto) {
                            "No se encontraron errores"
                        } else {
                            "Revisa la corrección antes de usarla"
                        }
                    } catch (e: Exception) {
                        mensaje = "Error: ${e.message ?: "desconocido"}"
                    } finally {
                        corrigiendo = false
                    }
                }
            },
            enabled = !corrigiendo && clave.isNotBlank() && texto.isNotBlank(),
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
        propuesta?.let { c ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF5F5F5))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Corrección", style = MaterialTheme.typography.titleSmall)
                if (c.titulo.isNotBlank()) {
                    Text(c.titulo, style = MaterialTheme.typography.titleMedium)
                }
                Text(c.texto, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            titulo = c.titulo
                            texto = c.texto
                            propuesta = null
                            mensaje = "Corrección aplicada"
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Usar")
                    }
                    OutlinedButton(
                        onClick = {
                            propuesta = null
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
                    if (titulo.isBlank() && texto.isBlank()) {
                        // Una nota vacía no se guarda
                        onBorrar()
                    } else {
                        onGuardar(
                            Nota(
                                id = nota.id,
                                titulo = titulo.trim(),
                                texto = texto.trim(),
                                color = color,
                                fecha = System.currentTimeMillis()
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
