package az.ada.glassesav.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.VideoView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import az.ada.glassesav.capture.CaptureViewModel
import az.ada.glassesav.capture.UiState
import com.meta.wearable.dat.core.types.RegistrationState
import java.util.Locale

private val Green = Color(0xFF1B7F3B)
private val Amber = Color(0xFFB26A00)
private val Red = Color(0xFFC62828)

@Composable
fun CaptureScreen(
    vm: CaptureViewModel,
    onRegister: () -> Unit,
    onUnregister: () -> Unit,
    onStart: () -> Unit,
) {
  val s by vm.state.collectAsStateWithLifecycle()
  val preview by vm.preview.collectAsStateWithLifecycle()
  MaterialTheme {
    Surface(Modifier.fillMaxSize()) {
      Column(
          Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(12.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Text("Glasses AV Capture", style = MaterialTheme.typography.headlineSmall)

        SourcePanel(s)

        s.lastError?.let {
          Text("Error: $it", color = Red, fontWeight = FontWeight.Bold)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(onClick = onStart, enabled = !s.capturing) { Text("Start") }
          OutlinedButton(onClick = vm::stopCapture, enabled = s.capturing) { Text("Stop") }
          OutlinedButton(onClick = vm::addMarker, enabled = s.capturing) { Text("Marker") }
        }

        // Replay the last capture: video in the box below, audio on the glasses' speakers.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          if (s.isPlaying) {
            Button(onClick = vm::stopPlayback) { Text("Stop playback") }
          } else {
            Button(onClick = vm::playLastCapture, enabled = !s.capturing && s.replaySeconds > 0) {
              Text("Play last capture (${fmt(s.replaySeconds)} s)")
            }
          }
        }
        if (s.replayLabel.isNotEmpty() && !s.capturing) {
          Text(
              "Recorded from: ${s.replayLabel}",
              fontSize = 12.sp,
              color = if (s.replayLabel.startsWith("PHONE")) Red else Green,
          )
        }
        if (s.playbackDevice.isNotEmpty()) {
          Text("Playing on: ${s.playbackDevice}", fontSize = 12.sp, color = Green)
        }

        // Save: keeps the last capture (video.mp4, audio.wav, timestamp CSVs). Without Save it is
        // deleted when the next capture starts or the app closes (SR9).
        if (s.hasUnsavedCapture && !s.capturing) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::saveLastCapture, enabled = !s.isSaving) {
              Text(if (s.isSaving) "Saving..." else "Save")
            }
            Text("Not saved yet: deleted on next Start unless you tap Save.", fontSize = 12.sp, color = Amber)
          }
        }
        s.savedDir?.let { Text("Saved to: $it", fontSize = 12.sp, color = Green) }

        // Live video, or the recorded video while playing back the last capture.
        Box(
            Modifier.fillMaxWidth().height(360.dp).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
          val bmp = preview
          val replayPath = s.replayVideoPath
          if (replayPath != null) {
            ReplayVideo(
                path = replayPath,
                onStarted = vm::onReplayVideoStarted,
                onError = vm::onReplayVideoError,
            )
          } else if (bmp != null) {
            Image(bmp.asImageBitmap(), "Glasses camera", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
          } else {
            Text(if (s.capturing) "Waiting for glasses frames..." else "No video", color = Color.White)
          }
        }

        StatsCard(s)
        SetupCard(s, onRegister, onUnregister, vm)

        Card(Modifier.fillMaxWidth()) {
          Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text("Audio route report (Android)", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
              OutlinedButton(onClick = vm::refreshRouteReport) { Text("Refresh") }
            }
            Mono(s.routeReport.ifEmpty { "Tap Refresh (also logged to Logcat tag AudioRoute)." })
          }
        }

        Card(Modifier.fillMaxWidth()) {
          Column(Modifier.padding(10.dp)) {
            Text("Event log (newest last; Logcat tag GlassesAV)", fontWeight = FontWeight.Bold)
            Mono(s.events.takeLast(30).joinToString("\n"))
          }
        }
        Spacer(Modifier.height(24.dp))
      }
    }
  }
}

@Composable
private fun SourcePanel(s: UiState) {
  val audioColor =
      when (s.audioIsGlasses) {
        true -> Green
        false -> Red
        null -> Amber
      }
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(
          "Connection: session=${s.sessionState ?: "-"}  stream=${s.streamState ?: "-"}",
          fontWeight = FontWeight.Bold,
      )
      Text("VIDEO SOURCE: ${s.videoSource}")
      Text("AUDIO SOURCE: ${s.audioSource}", color = audioColor, fontWeight = FontWeight.Bold)
      if (s.audioVerification.isNotEmpty()) Text(s.audioVerification, fontSize = 12.sp)
    }
  }
}

@Composable
private fun StatsCard(s: UiState) {
  val a = s.audio
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text("Live statistics", fontWeight = FontWeight.Bold)
      Text("Video: ${s.videoFrames} frames, ${fmt(s.videoFps)} fps, ${s.videoSize}, skipped by processors: ${s.droppedForProcessing}")
      Text("Audio: ${a.chunks} chunks, level ${fmt(a.levelDbfs)} dBFS, peak ${fmt(a.peakDbfs)} dBFS")
      LinearProgressIndicator(
          progress = { ((a.levelDbfs + 70.0) / 70.0).coerceIn(0.0, 1.0).toFloat() },
          modifier = Modifier.fillMaxWidth(),
      )
      Text("Measured audio rate: by arrival ${a.measuredRateByArrivalHz?.let { fmt(it) } ?: "-"} Hz, by PTS ${a.measuredRateByPtsHz?.let { fmt(it) } ?: "-"} Hz")
      s.sdkAudioBytesPerSample?.let { Text("SDK audio bytes per sample: ${fmt(it)} (2.0 = 16-bit mono as assumed)") }
      Text("Max gap between audio chunks: ${fmt(a.maxArrivalGapMs)} ms")
      Text("Last audio arrival minus last video arrival: ${s.avArrivalGapMs?.let { fmt(it) } ?: "-"} ms")
      s.avPtsGapMs?.let { Text("SDK audio PTS minus video PTS: ${fmt(it)} ms (stable = shared clock)") }
    }
  }
}

@Composable
private fun SetupCard(s: UiState, onRegister: () -> Unit, onUnregister: () -> Unit, vm: CaptureViewModel) {
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text("Setup", fontWeight = FontWeight.Bold)
      Text("Bluetooth permission: ${if (s.bluetoothPermission) "granted" else "missing"}")
      Text("SDK: ${if (s.sdkInitialized) "initialised" else s.sdkInitError ?: "not initialised"}; Developer Mode: ${s.isDevMode ?: "?"}")
      Text("Registration with Meta AI: ${s.registration ?: "-"}")
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onRegister, enabled = s.sdkInitialized && s.registration != RegistrationState.REGISTERED) { Text("Register") }
        OutlinedButton(onClick = onUnregister, enabled = s.registration == RegistrationState.REGISTERED) { Text("Unregister") }
        OutlinedButton(onClick = vm::refreshWearablePermissions, enabled = s.sdkInitialized) { Text("Check perms") }
      }
      Text("Glasses permissions (Meta AI): camera=${s.cameraPermission}, microphone=${s.micPermission}")
      if (s.devices.isEmpty()) Text("No glasses known to the SDK yet.")
      s.devices.forEach {
        Text("Device: ${it.name} [${it.type}] link=${it.link} fw=${it.firmware} battery=${it.battery}%", fontSize = 13.sp)
      }
    }
  }
}

/**
 * Plays the capture's video.mp4 (video only; it has no audio track). Audio is played separately
 * on the glasses, started via [onStarted] so the two begin together.
 */
@Composable
private fun ReplayVideo(path: String, onStarted: () -> Unit, onError: (String) -> Unit) {
  AndroidView(
      modifier = Modifier.fillMaxSize(),
      factory = { context ->
        VideoView(context).apply {
          setOnPreparedListener { player ->
            player.setVolume(0f, 0f)
            start()
            onStarted()
          }
          setOnErrorListener { _, what, extra ->
            onError("MediaPlayer error $what/$extra")
            true
          }
          setVideoPath(path)
        }
      },
      onRelease = { it.stopPlayback() },
  )
}

@Composable
private fun Mono(text: String) = Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)

private fun fmt(v: Double) = String.format(Locale.US, "%.1f", v)
