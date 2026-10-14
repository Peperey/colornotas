package com.peperey.colornotas

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Correccion(val titulo: String, val texto: String)

// Envía el título y el texto a Groq y devuelve la versión corregida.
// La clave llega desde la app; nunca se guarda en el código.
fun corregirConGroq(clave: String, titulo: String, texto: String): Correccion {
    val sistema = """Eres un corrector de textos en español. Corriges ortografía, gramática, tildes y puntuación del título y del texto. No cambies el significado, no añadas información, no cambies el idioma ni el tono. Responde SOLO con un objeto JSON, sin texto ni markdown, con las claves "titulo" y "texto" con el contenido corregido."""

    val entrada = JSONObject().put("titulo", titulo).put("texto", texto).toString()
    val cuerpo = JSONObject().apply {
        put("model", "openai/gpt-oss-120b")
        put("temperature", 0.0)
        put(
            "messages",
            JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", sistema))
                put(JSONObject().put("role", "user").put("content", entrada))
            }
        )
    }

    val conexion = URL("https://api.groq.com/openai/v1/chat/completions")
        .openConnection() as HttpURLConnection
    conexion.requestMethod = "POST"
    conexion.setRequestProperty("Content-Type", "application/json")
    conexion.setRequestProperty("Authorization", "Bearer $clave")
    conexion.connectTimeout = 15000
    conexion.readTimeout = 60000
    conexion.doOutput = true
    conexion.outputStream.use { it.write(cuerpo.toString().toByteArray(Charsets.UTF_8)) }

    val codigo = conexion.responseCode
    val respuestaBruta = (if (codigo in 200..299) conexion.inputStream else conexion.errorStream)
        .bufferedReader()
        .use { it.readText() }
    if (codigo !in 200..299) {
        throw IllegalStateException("Error $codigo: $respuestaBruta")
    }

    val contenido = JSONObject(respuestaBruta)
        .getJSONArray("choices")
        .getJSONObject(0)
        .getJSONObject("message")
        .getString("content")

    val inicio = contenido.indexOf('{')
    val fin = contenido.lastIndexOf('}')
    if (inicio < 0 || fin <= inicio) {
        throw IllegalStateException("La respuesta no tiene el formato esperado")
    }
    val o = JSONObject(contenido.substring(inicio, fin + 1))
    return Correccion(
        titulo = o.optString("titulo", titulo),
        texto = o.optString("texto", texto)
    )
}
