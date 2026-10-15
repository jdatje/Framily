package nl.jdatje.framily.ui

import android.Manifest
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import nl.jdatje.framily.Permissions
import nl.jdatje.framily.wallpaper.FramilyWallpaperService

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FramilyTheme {
                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { vm.onResume() }
                val locationLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { vm.updateWeather(force = true) }
                MainScreen(
                    vm = vm,
                    onRequestPermissions = { launcher.launch(Permissions.requestList) },
                    onOpenAppSettings = { openAppSettings() },
                    onSetWallpaper = { openWallpaperPicker() },
                    onRequestLocation = { locationLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        )
    }

    private fun openWallpaperPicker() {
        val direct = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
            WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            ComponentName(this, FramilyWallpaperService::class.java),
        )
        try {
            startActivity(direct)
        } catch (e: Exception) {
            startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
        }
    }
}
