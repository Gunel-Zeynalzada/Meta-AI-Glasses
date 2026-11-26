package az.ada.glassesav.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executor

/**
 * Reports what Android ITSELF says about audio input routing. This is the evidence layer:
 * "I can hear sound" proves nothing about which microphone produced it.
 *
 * Everything is logged under the Logcat tag [TAG] ("AudioRoute") and forwarded to [onEvent].
 */
class AudioRouteDiagnostics(context: Context, private val onEvent: (String) -> Unit) {
  private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
  private val mainHandler = Handler(Looper.getMainLooper())
  private val mainExecutor = Executor { mainHandler.post(it) }

  private val deviceCallback =
      object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
          addedDevices.filter { it.isSource }.forEach { emit("Input device ADDED: ${describe(it)}") }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
          removedDevices.filter { it.isSource }.forEach {
            emit("Input device REMOVED: ${describe(it)}")
          }
        }
      }

  private val recordingCallback =
      object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
          emit("Active recordings changed: ${configs.size} -> ${configs.joinToString { describe(it) }}")
        }
      }

  private val commDeviceListener =
      AudioManager.OnCommunicationDeviceChangedListener { device ->
        emit("Communication device changed -> ${device?.let { describe(it) } ?: "none"}")
      }

  private var registered = false

  fun register() {
    if (registered) return
    registered = true
    audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
    audioManager.registerAudioRecordingCallback(recordingCallback, mainHandler)
    audioManager.addOnCommunicationDeviceChangedListener(mainExecutor, commDeviceListener)
  }

  fun unregister() {
    if (!registered) return
    registered = false
    runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
    runCatching { audioManager.unregisterAudioRecordingCallback(recordingCallback) }
    runCatching { audioManager.removeOnCommunicationDeviceChangedListener(commDeviceListener) }
  }

  /** Full multi-line report. Pass the active AudioRecord (if any) to include its routed device. */
  fun snapshot(activeRecord: AudioRecord?): String = buildString {
    appendLine("Audio mode: ${modeName(audioManager.mode)}")
    appendLine(
        "Communication device: ${audioManager.communicationDevice?.let { describe(it) } ?: "none"}"
    )
    appendLine("Input devices known to Android:")
    audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).forEach { appendLine("  - ${describe(it)}") }
    appendLine("Available communication devices:")
    audioManager.availableCommunicationDevices.forEach { appendLine("  - ${describe(it)}") }
    val configs = audioManager.activeRecordingConfigurations
    appendLine("Active recordings visible to this app: ${configs.size}")
    configs.forEach { appendLine("  - ${describe(it)}") }
    if (activeRecord != null) {
      appendLine(
          "This app's AudioRecord routedDevice: " +
              (activeRecord.routedDevice?.let { describe(it) } ?: "null (not started yet)")
      )
      appendLine("This app's AudioRecord preferredDevice: ${activeRecord.preferredDevice?.let { describe(it) } ?: "none"}")
    } else {
      appendLine("This app has NO AudioRecord open (Android phone/HFP capture not in use by us).")
    }
  }

  fun emit(message: String) {
    Log.i(TAG, message)
    onEvent(message)
  }

  companion object {
    const val TAG = "AudioRoute"

    fun describe(d: AudioDeviceInfo): String =
        "${typeName(d.type)} \"${d.productName}\" id=${d.id}" +
            (if (d.address.isNullOrEmpty()) "" else " addr=${d.address}") +
            (if (d.sampleRates.isNotEmpty()) " rates=${d.sampleRates.joinToString("/")}" else "")

    fun describe(c: AudioRecordingConfiguration): String =
        "source=${sourceName(c.clientAudioSource)} device=${c.audioDevice?.let { describe(it) } ?: "?"}" +
            " silenced=${c.isClientSilenced} rate=${c.format.sampleRate}"

    fun typeName(type: Int): String =
        when (type) {
          AudioDeviceInfo.TYPE_BUILTIN_MIC -> "PHONE_BUILTIN_MIC"
          AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO(HFP)"
          AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP(output-only)"
          AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET(LE Audio)"
          AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
          AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
          AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
          AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
          AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "REMOTE_SUBMIX"
          AudioDeviceInfo.TYPE_FM_TUNER -> "FM_TUNER"
          AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "PHONE_EARPIECE"
          AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "PHONE_SPEAKER"
          else -> "TYPE_$type"
        }

    fun sourceName(source: Int): String =
        when (source) {
          MediaRecorder.AudioSource.MIC -> "MIC"
          MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
          MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
          MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER"
          MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
          MediaRecorder.AudioSource.DEFAULT -> "DEFAULT"
          else -> "SOURCE_$source"
        }

    fun modeName(mode: Int): String =
        when (mode) {
          AudioManager.MODE_NORMAL -> "NORMAL"
          AudioManager.MODE_IN_CALL -> "IN_CALL"
          AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
          AudioManager.MODE_RINGTONE -> "RINGTONE"
          else -> "MODE_$mode"
        }
  }
}
