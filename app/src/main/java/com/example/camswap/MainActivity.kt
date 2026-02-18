package com.example.camswap

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.camswap.ui.*
import com.example.camswap.ui.theme.CamSwapTheme
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()
    private val mediaViewModel: MediaManagerViewModel by viewModels()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        checkPermissionsStatus()
    }

    private val manageExternalStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        checkPermissionsStatus()
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.example.camswap.utils.LocaleHelper.onAttach(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Migration logic
        val configManager = ConfigManager()
        if (configManager.migrateIfNeeded()) {
            Toast.makeText(this, getString(R.string.config_migrated), Toast.LENGTH_LONG).show()
        }

        // Auto-start service if enabled
        if (configManager.getBoolean("notification_control_enabled", false)) {
            try {
                val intent = Intent(this, NotificationService::class.java)
                startForegroundService(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        setContent {
            CamSwapTheme {
                MainApp()
            }
        }
    }

    @Composable
    fun MainApp() {
        val navController = rememberNavController()
        val items = listOf(Screen.Home, Screen.Manage, Screen.Settings)

        Scaffold(
            topBar = {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route
                
                if (currentRoute != null) {
                    val title = when (currentRoute) {
                        Screen.Home.route -> stringResource(id = R.string.app_name)
                        Screen.Manage.route -> stringResource(Screen.Manage.titleResId)
                        Screen.Settings.route -> stringResource(Screen.Settings.titleResId)
                        else -> stringResource(id = R.string.app_name)
                    }
                    
                    TopAppBar(
                        title = { 
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontSize = 22.sp,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                )
                            ) 
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                            titleContentColor = MaterialTheme.colorScheme.onBackground
                        )
                    )
                }
            },
            bottomBar = {
                NavigationBar {
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val currentRoute = navBackStackEntry?.destination?.route

                    items.forEach { screen ->
                        NavigationBarItem(
                            icon = { Icon(screen.icon, contentDescription = stringResource(screen.titleResId)) },
                            label = { Text(stringResource(screen.titleResId)) },
                            selected = currentRoute == screen.route,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = Screen.Home.route,
                modifier = Modifier.padding(innerPadding),
                enterTransition = { fadeIn(animationSpec = tween(150)) },
                exitTransition = { fadeOut(animationSpec = tween(150)) },
                popEnterTransition = { fadeIn(animationSpec = tween(150)) },
                popExitTransition = { fadeOut(animationSpec = tween(150)) }
            ) {
                composable(Screen.Home.route) {
                    HomeScreen(
                        mainViewModel = mainViewModel,
                        mediaViewModel = mediaViewModel,
                        onPermissionRequest = { checkAndRequestPermissions() }
                    )
                }
                composable(Screen.Manage.route) {
                    ManageScreen(viewModel = mediaViewModel)
                }
                composable(Screen.Settings.route) {
                    SettingsScreen(viewModel = mainViewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkPermissionsStatus()
        mainViewModel.loadConfig()
        mediaViewModel.loadMedia()
    }

    private fun checkPermissionsStatus() {
        val hasPermission = hasRequiredPermissions()
        mainViewModel.updatePermissionStatus(hasPermission)
        mainViewModel.updateXposedStatus(isModuleActive())
    }

    // This method will be hooked by the Xposed module to return true
    private fun isModuleActive(): Boolean {
        return false
    }

    private fun checkAndRequestPermissions() {
        if (!hasRequiredPermissions()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!android.os.Environment.isExternalStorageManager()) {
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        intent.addCategory("android.intent.category.DEFAULT")
                        intent.data = Uri.parse("package:$packageName")
                        manageExternalStorageLauncher.launch(intent)
                    } catch (e: Exception) {
                        val intent = Intent()
                        intent.action = Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                        manageExternalStorageLauncher.launch(intent)
                    }
                }
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                     if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                         requestPermissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                     }
                }
            } else {
                val permissions = getRequiredPermissions()
                requestPermissionLauncher.launch(permissions)
            }
        } else {
             initDirectory()
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val storageGranted = android.os.Environment.isExternalStorageManager()
            val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
            return storageGranted && notificationGranted
        } else {
            return getRequiredPermissions().all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }
        }
    }

    private fun getRequiredPermissions(): Array<String> {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return permissions.toTypedArray()
    }

    private fun initDirectory() {
        val cameraDir = File(android.os.Environment.getExternalStorageDirectory().absolutePath + "/DCIM/Camera1/")
        if (!cameraDir.exists()) {
            cameraDir.mkdirs()
        }
    }
}
