package com.theo.distractionblocker.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.theo.distractionblocker.blocking.AccessibilityStatus
import com.theo.distractionblocker.blocking.DetectionConfig
import com.theo.distractionblocker.service.KeepAliveService
import com.theo.distractionblocker.ui.theme.DistractionBlockerTheme

/** Ecran unique de l'application : la configuration. */
class MainActivity : ComponentActivity() {

    private val viewModel: ConfigViewModel by viewModels()

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* purement informatif */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Depose detection_config.json dans le dossier externe au premier
        // lancement, pour qu'il soit editable meme avant d'activer le service.
        DetectionConfig.ensureFileExists(this)

        requestNotificationPermissionIfNeeded()

        setContent {
            DistractionBlockerTheme {
                ConfigScreen(viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Demarrer le foreground service depuis une activite visible est
        // toujours autorise, contrairement a un demarrage en arriere-plan.
        if (AccessibilityStatus.isEnabled(this)) {
            KeepAliveService.start(this)
        }
    }

    /**
     * Sans cette permission (Android 13+), le foreground service demarre quand
     * meme mais sa notification reste invisible. Ce n'est pas bloquant, d'ou
     * l'absence d'insistance si l'utilisateur refuse.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
