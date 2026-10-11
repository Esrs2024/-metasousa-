package com.metasousa.app

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Cliente WebSocket para la API Gemini Live (BidiGenerateContent).
 * Envía audio PCM 16kHz mono y fotogramas JPEG; recibe audio PCM 24kHz.
 */
class GeminiLiveClient(
    private val apiKey: String,
    private val listener: Listener
) {

    interface Listener {
        fun onConnected()
        fun onSetupComplete()
        fun onAudioOutput(pcm24k: ByteArray)
        fun onInputTranscription(text: String)
        fun onOutputTranscription(text: String)
        fun onError(message: String)
        fun onClosed()
    }

    companion object {
        private const val TAG = "GeminiLive"
        private const val MODEL = "models/gemini-2.0-flash-live-001"
        private const val WS_URL =
            "wss://generativelanguage.googleapis.com/ws/" +
                "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private var ws: WebSocket? = null
    private var setupDone = false

    fun connect() {
        val url = "$WS_URL?key=$apiKey"
        val request = Request.Builder().url(url).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket abierto")
                listener.onConnected()
                sendSetup(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error: ${t.message}")
                listener.onError("Gemini: ${t.message ?: "error de conexión"}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket cerrado: $code $reason")
                listener.onClosed()
            }
        })
    }

    private fun sendSetup(webSocket: WebSocket) {
        val setup = JSONObject()
            .put("model", MODEL)
            .put("generationConfig", JSONObject()
                .put("responseModalities", org.json.JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject()
                    .put("voiceConfig", JSONObject()
                        .put("prebuiltVoiceConfig", JSONObject()
                            .put("voiceName", "Kore")))))
            .put("systemInstruction", JSONObject()
                .put("parts", org.json.JSONArray()
                    .put(JSONObject().put("text",
                        "Eres MetaSousa, un asistente de voz amable que ve lo que el usuario " +
                        "ve a través de sus gafas inteligentes. Responde SIEMPRE en español, " +
                        "de forma breve y conversacional, como en una llamada de voz. " +
                        "Cuando te pregunten qué ves, describe la imagen que recibes."))))
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())

        val msg = JSONObject().put("setup", setup).toString()
        webSocket.send(msg)
        Log.d(TAG, "Setup enviado")
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            if (json.has("setupComplete")) {
                setupDone = true
                Log.d(TAG, "Setup completo")
                listener.onSetupComplete()
                return
            }
            if (json.has("serverContent")) {
                val sc = json.getJSONObject("serverContent")
                if (sc.has("modelTurn")) {
                    val parts = sc.getJSONObject("modelTurn").optJSONArray("parts")
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)
                            if (part.has("inlineData")) {
                                val data = part.getJSONObject("inlineData").getString("data")
                                val pcm = Base64.decode(data, Base64.DEFAULT)
                                listener.onAudioOutput(pcm)
                            }
                        }
                    }
                }
                if (sc.has("inputTranscription")) {
                    val t = sc.getJSONObject("inputTranscription").optString("text", "")
                    if (t.isNotEmpty()) listener.onInputTranscription(t)
                }
                if (sc.has("outputTranscription")) {
                    val t = sc.getJSONObject("outputTranscription").optString("text", "")
                    if (t.isNotEmpty()) listener.onOutputTranscription(t)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error procesando mensaje: ${e.message}")
        }
    }

    fun isReady(): Boolean = setupDone && ws != null

    fun sendAudioPcm16(pcm: ByteArray) {
        val webSocket = ws ?: return
        if (!setupDone) return
        try {
            val b64 = Base64.encodeToString(pcm, Base64.NO_WRAP)
            val msg = JSONObject()
                .put("realtimeInput", JSONObject()
                    .put("audio", JSONObject()
                        .put("data", b64)
                        .put("mimeType", "audio/pcm;rate=16000")))
                .toString()
            webSocket.send(msg)
        } catch (e: Exception) {
            Log.e(TAG, "Error enviando audio: ${e.message}")
        }
    }

    fun sendVideoJpeg(jpeg: ByteArray) {
        val webSocket = ws ?: return
        if (!setupDone) return
        try {
            val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
            val msg = JSONObject()
                .put("realtimeInput", JSONObject()
                    .put("video", JSONObject()
                        .put("data", b64)
                        .put("mimeType", "image/jpeg")))
                .toString()
            webSocket.send(msg)
        } catch (e: Exception) {
            Log.e(TAG, "Error enviando vídeo: ${e.message}")
        }
    }

    fun disconnect() {
        try {
            ws?.close(1000, "fin")
        } catch (_: Exception) {
        }
        ws = null
        setupDone = false
        try {
            client.dispatcher.executorService.shutdown()
        } catch (_: Exception) {
        }
    }
}
