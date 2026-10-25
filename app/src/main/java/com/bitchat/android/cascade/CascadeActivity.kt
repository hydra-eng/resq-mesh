package com.bitchat.android.cascade

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.bitchat.android.cascade.data.CascadeRepository
import com.bitchat.android.cascade.ui.CascadeUi
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.cascade.crypto.PacketCrypto
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

data class DeviceActions(
    val permissions: () -> Unit,
    val voice: ((String) -> Unit) -> Unit,
    val photo: ((String) -> Unit) -> Unit,
    val export: () -> Unit,
    val batterySettings: () -> Unit
)

@AndroidEntryPoint
class CascadeActivity : ComponentActivity() {
    @Inject lateinit var repository: CascadeRepository
    private var voiceResult: ((String)->Unit)?=null
    private var photoResult: ((String)->Unit)?=null
    private var speech: SpeechRecognizer?=null
    private val io=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val permissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startIfPermitted() }
    private val enableBluetooth=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { startIfPermitted() }
    private val microphone=registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(granted) startVoice() else repository.notifyUser("Microphone permission denied. You can type your SOS.")
    }
    private val picker=registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if(uri!=null) io.launch {
            runCatching {
                val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it,null,bounds) }
                require(bounds.outWidth>0 && bounds.outHeight>0)
                val options=BitmapFactory.Options().apply { inSampleSize=(maxOf(bounds.outWidth,bounds.outHeight)/384).coerceAtLeast(1) }
                val source=contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it,null,options) } ?: error("Image unreadable")
                val ratio=256f/maxOf(source.width,source.height)
                val bitmap=Bitmap.createScaledBitmap(source,(source.width*ratio).toInt().coerceAtLeast(1),(source.height*ratio).toInt().coerceAtLeast(1),true)
                val bytes=ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.JPEG,35,bytes)
                require(bytes.size()<=12000)
                val encoded=PacketCrypto.b64(bytes.toByteArray())
                withContext(Dispatchers.Main) { photoResult?.invoke(encoded); repository.notifyUser("Photo compressed; metadata removed. Shared only with the accepted responder.") }
            }.onFailure { repository.notifyUser("Photo could not fit the 12 KB BLE limit. Choose a simpler image.") }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CascadeUi(repository,DeviceActions(
            permissions={ requestMeshPermissions() },
            voice={ callback -> voiceResult=callback; microphone.launch(Manifest.permission.RECORD_AUDIO) },
            photo={ callback -> photoResult=callback; picker.launch("image/*") },
            export={ export() },
            batterySettings={ startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        )) }
        lifecycleScope.launch { val initial=repository.state.first { it.ready }; if(initial.onboarded) startIfPermitted() }
    }
    private fun requiredPermissions() = buildList {
        addAll(com.bitchat.android.cascade.transport.RadioReadiness.permissions())
        if(Build.VERSION.SDK_INT>=33) add(Manifest.permission.POST_NOTIFICATIONS)
    }
    private fun requestMeshPermissions() {
        if(com.bitchat.android.cascade.transport.RadioReadiness.missing(this).isNotEmpty()) {
            permissions.launch(requiredPermissions().toTypedArray()); return
        }
        val adapter=getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
        if(adapter!=null && !adapter.isEnabled) {
            runCatching { enableBluetooth.launch(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                .onFailure { repository.notifyUser("Turn on Bluetooth in Quick Settings, then tap Connect.") }
            return
        }
        startIfPermitted()
    }
    private fun startIfPermitted() {
        val required=requiredPermissions().filter { it!=Manifest.permission.POST_NOTIFICATIONS }
        if(required.all { ContextCompat.checkSelfPermission(this,it)==PackageManager.PERMISSION_GRANTED }) {
            runCatching { ContextCompat.startForegroundService(this,Intent(this,CascadeService::class.java)) }
                .onFailure { repository.notifyUser("Android could not start the Bluetooth service (${it.javaClass.simpleName}). Reopen the app and tap Connect.") }
        } else repository.notifyUser(com.bitchat.android.cascade.transport.RadioReadiness.problem(this) ?: "Review app permissions, then tap Connect.")
    }
    override fun onResume() {
        super.onResume()
        if(repository.state.value.ready && repository.state.value.onboarded) startIfPermitted()
    }
    private fun startVoice() {
        if(Build.VERSION.SDK_INT<31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            repository.notifyUser("Offline speech recognition is not installed. Type your SOS or install an offline language pack later."); return
        }
        speech?.destroy()
        speech=SpeechRecognizer.createOnDeviceSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { repository.notifyUser("Listening offline…") }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onError(error: Int) { repository.notifyUser("Offline voice input unavailable ($error). Try typing.") }
                override fun onResults(results: Bundle?) { results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { voiceResult?.invoke(it) } }
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int,params: Bundle?) = Unit
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE,if(repository.state.value.language=="EN") "en-IN" else "hi-IN").putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true))
        }
    }
    private fun export() { io.launch {
        val directory=File(cacheDir,"exports").apply { mkdirs() }
        val file=File(directory,"cascade-sanitized-log.txt").apply { writeText(repository.exportLog()) }
        withContext(Dispatchers.Main) {
            val uri=FileProvider.getUriForFile(this@CascadeActivity,"$packageName.files",file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"Export sanitized log"))
        }
    } }
    override fun onDestroy() { speech?.destroy(); io.cancel(); super.onDestroy() }
}
