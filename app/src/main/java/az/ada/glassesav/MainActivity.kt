package az.ada.glassesav

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.viewModels
import az.ada.glassesav.capture.CaptureViewModel
import az.ada.glassesav.ui.CaptureScreen
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Hosts the UI and bridges permission dialogs (which need an Activity) to the ViewModel.
 *
 * Two different permission systems are involved:
 * 1. Android runtime permissions (BLUETOOTH_CONNECT; RECORD_AUDIO only for HFP / phone modes).
 * 2. Glasses permissions (Permission.CAMERA, Permission.MICROPHONE), granted to this app inside
 *    the Meta AI app via Wearables.RequestPermissionContract().
 */
class MainActivity : ComponentActivity() {
  private val vm: CaptureViewModel by viewModels()
  private val permissionMutex = Mutex()

  private val bluetoothLauncher =
      registerForActivityResult(RequestMultiplePermissions()) { result ->
        vm.onBluetoothPermission(result[Manifest.permission.BLUETOOTH_CONNECT] == true)
      }

  private var wearableContinuation: CancellableContinuation<PermissionStatus>? = null
  private val wearablePermissionLauncher =
      registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
        wearableContinuation?.resume(result.getOrDefault(PermissionStatus.Denied))
        wearableContinuation = null
      }

  private var recordAudioContinuation: CancellableContinuation<Boolean>? = null
  private val recordAudioLauncher =
      registerForActivityResult(RequestPermission()) { granted ->
        recordAudioContinuation?.resume(granted)
        recordAudioContinuation = null
      }

  /** Opens the Meta AI app to ask the user for a glasses permission; suspends until it returns. */
  private suspend fun requestWearablePermission(p: Permission): PermissionStatus =
      permissionMutex.withLock {
        suspendCancellableCoroutine { c ->
          wearableContinuation = c
          c.invokeOnCancellation { wearableContinuation = null }
          wearablePermissionLauncher.launch(p)
        }
      }

  private suspend fun requestRecordAudio(): Boolean {
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
      return true
    }
    return permissionMutex.withLock {
      suspendCancellableCoroutine { c ->
        recordAudioContinuation = c
        c.invokeOnCancellation { recordAudioContinuation = null }
        recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
      }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      CaptureScreen(
          vm = vm,
          onRegister = { vm.register(this) },
          onUnregister = { vm.unregister(this) },
          onStart = { vm.startCapture(::requestWearablePermission, ::requestRecordAudio) },
      )
    }
    bluetoothLauncher.launch(
        arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_CONNECT)
    )
  }

  override fun onResume() {
    super.onResume()
    // Coming back from the Meta AI app (registration / permission) - refresh what it granted.
    if (vm.state.value.sdkInitialized) vm.refreshWearablePermissions()
  }

  override fun onStop() {
    super.onStop()
    // Lifecycle rule for this prototype: capture runs only while the app is visible. Leaving the
    // app (Home, screen off) stops the stream and releases the microphone route cleanly.
    // Permission/registration hand-offs to Meta AI happen before capture starts, so they are safe.
    if (!isChangingConfigurations) vm.stopCapture()
  }
}
