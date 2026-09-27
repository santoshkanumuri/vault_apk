package com.privatevault.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.privatevault.app.security.decodePhoto
import kotlinx.coroutines.launch

internal class PhotoDraftEditorState {
    val photos = mutableStateListOf<DraftPhoto>()
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var addFromGallery: () -> Unit = {}
    var addFromCamera: () -> Unit = {}

    fun takeForSave(): List<DraftPhoto> = photos.toList().also { photos.clear() }
}

@Composable
internal fun rememberPhotoDraftEditor(viewModel: VaultViewModel): PhotoDraftEditorState {
    val state = remember { PhotoDraftEditorState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        viewModel.externalFlowActive = false
        if (uri == null) state.busy = false
        else scope.launch {
            try { state.photos.add(viewModel.stagePhoto(uri)) }
            catch (failure: Exception) { state.error = "Could not add that photo." }
            finally { state.busy = false }
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        scope.launch {
            try { viewModel.finishDraftCamera(success)?.let(state.photos::add) }
            catch (failure: Exception) { state.error = "Could not add that photo." }
            finally { state.busy = false }
        }
    }
    fun launchCamera() {
        val uri = viewModel.prepareCamera()
        if (uri == null) { state.busy = false; return }
        runCatching { camera.launch(uri) }.onFailure {
            state.busy = false
            state.error = "Could not open the camera."
            viewModel.externalFlowActive = false
            viewModel.clearCamera()
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else {
            state.busy = false
            state.error = "Camera permission is needed to take a photo."
        }
    }
    state.addFromGallery = {
        state.error = null
        state.busy = true
        viewModel.externalFlowActive = true
        runCatching { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            .onFailure { state.busy = false; viewModel.externalFlowActive = false; state.error = "Could not open photos." }
    }
    state.addFromCamera = {
        state.error = null
        state.busy = true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            launchCamera()
        else runCatching { permission.launch(Manifest.permission.CAMERA) }
            .onFailure { state.busy = false; state.error = "Could not request camera permission." }
    }
    DisposableEffect(state) {
        onDispose {
            state.photos.forEach(viewModel::discardDraftPhoto)
            viewModel.externalFlowActive = false
            viewModel.clearCamera()
        }
    }
    return state
}

@Composable
internal fun PhotoDraftControls(state: PhotoDraftEditorState, viewModel: VaultViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Photos", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = state.addFromGallery, enabled = !state.busy) { Text("Add photo") }
            OutlinedButton(onClick = state.addFromCamera, enabled = !state.busy) { Text("Camera") }
        }
        state.photos.forEachIndexed { index, draft ->
            val bitmap = remember(draft) {
                viewModel.loadDraftThumbnail(draft)?.let { bytes ->
                    try { runCatching { decodePhoto(bytes, 160) }.getOrNull() }
                    finally { bytes.fill(0) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (bitmap != null) Image(bitmap.asImageBitmap(), "Photo ${index + 1} ready to save",
                    Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                Text("Photo ${index + 1}", Modifier.weight(1f))
                TextButton(onClick = {
                    state.photos.remove(draft)
                    viewModel.discardDraftPhoto(draft)
                }) { Text("Remove") }
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
