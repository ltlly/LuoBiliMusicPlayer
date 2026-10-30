package com.bilimusicplayer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.bilimusicplayer.android.AndroidAppContainer
import com.bilimusicplayer.android.AndroidLocalLibraryService
import com.bilimusicplayer.android.AndroidQualityUpgradeService
import com.bilimusicplayer.platform.FoldInfo
import com.bilimusicplayer.shared.App
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {

    private var permissionContinuation: CancellableContinuation<Boolean>? = null
    private var writeContinuation: CancellableContinuation<Boolean>? = null

    private val mediaPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    private val mediaPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionContinuation?.resume(granted)
            permissionContinuation = null
        }

    private val writePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            writeContinuation?.resume(result.resultCode == RESULT_OK)
            writeContinuation = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as BiliMusicApplication
        val container: AndroidAppContainer = app.container

        if (intent?.getBooleanExtra("open_quality_upgrade", false) == true) {
            container.pendingRoute = "quality_upgrade"
        }

        (container.library as? AndroidLocalLibraryService)?.permissionRequester = {
            if (ContextCompat.checkSelfPermission(this, mediaPermission) == PackageManager.PERMISSION_GRANTED) {
                true
            } else {
                suspendCancellableCoroutine { cont ->
                    permissionContinuation = cont
                    mediaPermissionLauncher.launch(mediaPermission)
                }
            }
        }
        (container.qualityUpgrade as? AndroidQualityUpgradeService)?.writePermissionRequester = { intentSender ->
            suspendCancellableCoroutine { cont ->
                writeContinuation = cont
                writePermissionLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
        }

        // Keep the shared fold-aware player layout identical to the previous release.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WindowInfoTracker.getOrCreate(this@MainActivity)
                    .windowLayoutInfo(this@MainActivity)
                    .collect { info ->
                        val fold = info.displayFeatures.filterIsInstance<FoldingFeature>()
                            .firstOrNull { it.isSeparating }
                        container.foldInfo.value = fold?.let {
                            FoldInfo(
                                horizontal = it.orientation == FoldingFeature.Orientation.HORIZONTAL,
                                top = it.bounds.top,
                                bottom = it.bounds.bottom,
                                left = it.bounds.left,
                                right = it.bounds.right,
                            )
                        }
                    }
            }
        }

        setContent { App(container) }
    }

    override fun onStop() {
        (application as BiliMusicApplication).container.player.saveState()
        super.onStop()
    }
}