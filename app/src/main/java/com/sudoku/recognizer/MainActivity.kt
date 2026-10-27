package com.sudoku.recognizer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sudoku.recognizer.ui.CropScreen
import com.sudoku.recognizer.ui.CameraScreen
import com.sudoku.recognizer.ui.HistoryDetailScreen
import com.sudoku.recognizer.ui.HistoryScreen
import com.sudoku.recognizer.ui.HomeScreen
import com.sudoku.recognizer.ui.PreviewScreen
import com.sudoku.recognizer.ui.SolutionScreen
import com.sudoku.recognizer.ui.SudokuTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppRoot() }
    }
}

/** Корень приложения: одно активити, переключение экранов состоянием во ViewModel. */
@Composable
fun AppRoot(vm: AppViewModel = viewModel()) {
    val context = LocalContext.current
    var cameraDenied by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            cameraDenied = false
            vm.openCamera()
        } else {
            cameraDenied = true
        }
    }

    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) vm.onImagePicked(uri)
    }

    fun openCamera() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            cameraDenied = false
            vm.openCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    SudokuTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            when (val current = vm.screen) {
                Screen.Home -> HomeScreen(
                    onCamera = ::openCamera,
                    onFile = { pickLauncher.launch("image/*") },
                    onHistory = vm::openHistory,
                    historyCount = vm.history.size,
                    notice = if (cameraDenied) {
                        "Доступ к камере не выдан — можно выбрать изображение из галереи"
                    } else {
                        vm.hint
                    },
                )

                Screen.Camera -> CameraScreen(
                    onPhoto = vm::onPhotoCaptured,
                    onBack = vm::back,
                    onPickFile = { pickLauncher.launch("image/*") },
                    onError = vm::showError,
                )

                Screen.Crop -> CropScreen(vm)
                Screen.Preview -> PreviewScreen(vm)
                Screen.Solution -> SolutionScreen(vm)
                Screen.History -> HistoryScreen(vm)
                is Screen.HistoryDetail -> HistoryDetailScreen(vm, current.record)
            }
        }
    }
}
