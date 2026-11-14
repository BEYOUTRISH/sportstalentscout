package com.minor_project.sportstalentscout.ui.screens

import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun CameraPreview(
    controller: LifecycleCameraController,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    AndroidView(
        factory = { context ->
            PreviewView(context).apply {
                // COMPATIBLE (TextureView) mode avoids black preview screens on Android emulators and host GPUs
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                this.controller = controller
                try {
                    controller.bindToLifecycle(lifecycleOwner)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        },
        update = { previewView ->
            if (previewView.controller != controller) {
                previewView.controller = controller
                try {
                    controller.bindToLifecycle(lifecycleOwner)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        },
        modifier = modifier
    )
}
