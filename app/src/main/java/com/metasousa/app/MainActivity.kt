package com.metasousa.app

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.AudioCodec
import com.meta.wearable.dat.camera.types.AudioSampleRate
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var conversationText: TextView
    private lateinit var previewView: ImageView
    private lateinit var apiKeyInput: EditText
    private lateinit var registerButton: Button
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button

    private var session: DeviceSession? = null
    private var camera: Camera? = null
    private var frameJob: Job? = null
    private var audioJob: Job? = null
    private var gemini: GeminiLiveClient? = null
    private var audioTrack: AudioTrack? = null

    private var converting = false
    private var frameCount = 0
    private var lastVideoSent = 0L
    private val conversation = StringBuilder()

    private val prefs by lazy { getSharedPreferences("metasousa", Context.MODE_PRIVATE) }

    private val permissionLauncher =
        registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
            result.fold(
                onSuccess = { status ->
                    if (status == PermissionStatus.Granted) {
                        // Volver a comprobar: puede faltar el otro permiso
                        connectAll()
                    } else {
                        updateStatus("Permiso denegado en las gafas.")
                        runOnUiThread { connectButton.isEnabled = true }
                    }
                },
                onFailure = { error, _ ->
                    updateStatus("Error pidiendo permiso: ${error.description}")
                }
            )
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        conversationText = findViewById(R.id.conversationText)
        previewView = findViewById(R.id.previewView)
        apiKeyInput = findViewById(R.id.apiKeyInput)
        registerButton = findViewById(R.id.registerButton)
        connectButton = findViewById(R.id.connectButton)
        disconnectButton = findViewById(R.id.disconnectButton)

        apiKeyInput.setText(prefs.getString("gemini_key", ""))

        registerButton.setOnClickListener { Wearables.startRegistration(this) }
        connectButton.setOnClickListener { connectAll() }
        disconnectButton.setOnClickListener { disconnectAll() }

        connectButton.isEnabled = false
        disconnectButton.isEnabled = false

        lifecycleScope.launch {
            Wearables.registrationState.collect { state ->
                if (state == RegistrationState.REGISTERED) {
                    updateStatus("Registrado en Meta AI. Pon tu clave de Gemini y pulsa Conectar.")
                    runOnUiThread { connectButton.isEnabled = true }
                } else {
                    updateStatus("Estado de registro: $state")
                }
            }
        }
    }

    private fun connectAll() {
        val key = apiKeyInput.text.toString().trim()
        if (key.isEmpty()) {
            updateStatus("Escribe tu clave API de Gemini primero.")
            return
        }
        prefs.edit().putString("gemini_key", key).apply()
        connectButton.isEnabled = false
        updateStatus("Comprobando permisos de las gafas…")

        lifecycleScope.launch {
            val camOk = Wearables.checkPermissionStatus(Permission.CAMERA)
                .fold(onSuccess = { it == PermissionStatus.Granted }, onFailure = { _, _ -> false })
            val micOk = Wearables.checkPermissionStatus(Permission.MICROPHONE)
                .fold(onSuccess = { it == PermissionStatus.Granted }, onFailure = { _, _ -> false })
            if (camOk && micOk) {
                updateStatus("Permisos OK. Conectando…")
                startSession()
            } else {
                updateStatus("Pidiendo permisos a las gafas…")
                if (!camOk) permissionLauncher.launch(Permission.CAMERA)
                else permissionLauncher.launch(Permission.MICROPHONE)
            }
        }
    }

    private fun startSession() {
        updateStatus("Creando sesión con las gafas…")
        Wearables.createSession(AutoDeviceSelector()).fold(
            onSuccess = { newSession ->
                session = newSession
                lifecycleScope.launch {
                    newSession.errors.collect { error ->
                        updateStatus("Error de sesión: ${error.description}")
                    }
                }
                lifecycleScope.launch {
                    newSession.state.collect { state ->
                        updateStatus("Sesión: $state")
                        if (state == DeviceSessionState.STARTED && camera == null) {
                            addCamera(newSession)
                        }
                    }
                }
                newSession.start()
            },
            onFailure = { error, _ ->
                updateStatus("No se pudo crear la sesión: ${error.description}")
                runOnUiThread { connectButton.isEnabled = true }
            }
        )
    }

    private fun addCamera(newSession: DeviceSession) {
        updateStatus("Sesión lista. Pidiendo cámara y micro…")
        newSession.addCamera(
            StreamConfiguration(
                audioCodec = AudioCodec.PCM(
                    sampleRate = AudioSampleRate.RATE_16000,
                    numberOfChannels = 1
                ),
                videoQuality = VideoQuality.MEDIUM,
                frameRate = 24
            )
        ).fold(
            onSuccess = { newCamera ->
                camera = newCamera
                updateStatus("Cámara lista. Conectando con Gemini…")
                connectGemini()
                newCamera.stream.start().fold(
                    onSuccess = {
                        updateStatus("Todo conectado. Habla con tus gafas.")
                        runOnUiThread { disconnectButton.isEnabled = true }
                        observeStream(newCamera)
                    },
                    onFailure = { error, _ ->
                        updateStatus("No se pudo iniciar el vídeo: ${error.description}")
                    }
                )
            },
            onFailure = { error, _ ->
                updateStatus("No se pudo usar la cámara: ${error.description}")
            }
        )
    }

    private fun connectGemini() {
        val key = prefs.getString("gemini_key", "") ?: return
        setupAudioOutput()
        gemini = GeminiLiveClient(key, object : GeminiLiveClient.Listener {
            override fun onConnected() {
                updateStatus("Conectado a Gemini, esperando listo…")
            }
            override fun onSetupComplete() {
                updateStatus("Gemini listo. Habla con tus gafas.")
                addLine("— Gemini conectado —")
            }
            override fun onAudioOutput(pcm24k: ByteArray) {
                playPcm(pcm24k)
            }
            override fun onInputTranscription(text: String) {
                addLine("Tú: $text")
            }
            override fun onOutputTranscription(text: String) {
                addLine("Gemini: $text")
            }
            override fun onError(message: String) {
                updateStatus(message)
            }
            override fun onClosed() {
                updateStatus("Gemini desconectado.")
            }
        })
        gemini?.connect()
    }

    private fun setupAudioOutput() {
        val bufferSize = AudioTrack.getMinBufferSize(
            24000,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        audioTrack = AudioTrack(
            AudioManager.STREAM_MUSIC,
            24000,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize * 4,
            AudioTrack.MODE_STREAM
        )
        audioTrack?.play()
    }

    private fun playPcm(pcm: ByteArray) {
        try {
            audioTrack?.write(pcm, 0, pcm.size)
        } catch (_: Exception) {
        }
    }

    private fun observeStream(newCamera: Camera) {
        lifecycleScope.launch {
            newCamera.stream.state.collect { state ->
                if (state == StreamState.STREAMING) {
                    updateStatus("Recibiendo vídeo y audio de las gafas.")
                }
            }
        }

        // Audio de las gafas -> Gemini (16kHz PCM directo)
        audioJob = lifecycleScope.launch(Dispatchers.IO) {
            newCamera.stream.audioStream.collect { frame ->
                val g = gemini
                if (g != null && g.isReady()) {
                    val bytes = ByteArray(frame.buffer.remaining())
                    frame.buffer.duplicate().get(bytes)
                    g.sendAudioPcm16(bytes)
                }
            }
        }

        // Vídeo de las gafas -> vista previa + Gemini (1 foto/segundo)
        frameJob = lifecycleScope.launch(Dispatchers.Default) {
            newCamera.stream.videoStream.collect { frame ->
                if (frame.isCompressed || frame.isCodecConfig) return@collect
                frameCount++
                if (!converting && frameCount % 3 == 0) {
                    converting = true
                    try {
                        val bitmap = YuvToBitmap.convert(frame.buffer, frame.width, frame.height)
                        withContext(Dispatchers.Main) {
                            previewView.setImageBitmap(bitmap)
                        }
                        // Enviar 1 fotograma por segundo a Gemini como JPEG
                        val now = System.currentTimeMillis()
                        val g = gemini
                        if (g != null && g.isReady() && now - lastVideoSent > 1000) {
                            lastVideoSent = now
                            val out = ByteArrayOutputStream()
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 60, out)
                            g.sendVideoJpeg(out.toByteArray())
                        }
                    } catch (_: Exception) {
                    } finally {
                        converting = false
                    }
                }
            }
        }
    }

    private fun addLine(line: String) {
        runOnUiThread {
            conversation.append(line).append("\n")
            conversationText.text = conversation.toString()
        }
    }

    private fun disconnectAll() {
        frameJob?.cancel()
        audioJob?.cancel()
        frameJob = null
        audioJob = null
        gemini?.disconnect()
        gemini = null
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {
        }
        audioTrack = null
        try {
            camera?.stop()
        } catch (_: Exception) {
        }
        camera = null
        try {
            session?.stop()
        } catch (_: Exception) {
        }
        session = null
        frameCount = 0
        runOnUiThread {
            previewView.setImageDrawable(null)
            connectButton.isEnabled = true
            disconnectButton.isEnabled = false
        }
        updateStatus("Desconectado.")
    }

    private fun updateStatus(text: String) {
        runOnUiThread { statusText.text = text }
    }

    override fun onDestroy() {
        disconnectAll()
        super.onDestroy()
    }
}
