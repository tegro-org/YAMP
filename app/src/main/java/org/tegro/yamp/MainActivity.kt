package org.tegro.yamp

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import org.tegro.yamp.widget.WidgetAsk
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import org.tegro.yamp.domain.repository.SettingsRepository
import org.tegro.yamp.utils.DisplayScale
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.tegro.yamp.presentation.auth.AuthScreen
import org.tegro.yamp.presentation.main.MainScreen
import org.tegro.yamp.presentation.main.MainViewModel
import org.tegro.yamp.ui.theme.YandexMusicTheme
import dagger.hilt.android.AndroidEntryPoint
import android.content.ComponentName
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import org.tegro.yamp.data.player.MusicService

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    // Binding a controller keeps MusicService alive while the UI is visible; media3 then promotes it
    // to a foreground service by itself once playback starts.
    private var controllerFuture: ListenableFuture<MediaController>? = null

    @javax.inject.Inject
    lateinit var libraryTree: org.tegro.yamp.data.player.MediaLibraryTree

    @javax.inject.Inject
    lateinit var settingsRepository: SettingsRepository

    @javax.inject.Inject
    lateinit var downloadStorage: org.tegro.yamp.data.download.DownloadStorage

    @javax.inject.Inject
    lateinit var diagnosticLog: org.tegro.yamp.data.diagnostics.DiagnosticLog

    @javax.inject.Inject
    lateinit var accountSession: org.tegro.yamp.data.account.AccountSessionManager

    /** Incremented on each "open the player" request (notification tap); MainScreen reacts to changes. */
    private var openPlayerSignal by mutableIntStateOf(0)

    /** What a widget asked (start the music, open a tab) and MainScreen has not answered yet; survives recreation. */
    private var widgetAsk by mutableStateOf(WidgetAsk())

    /** Recreated with a session on screen (the splash is skipped); kept for the composables. */
    private var restored = false

    /**
     * This opening of the app: new when the activity is created afresh (from the launcher, after Back, after the
     * system closed it), the same when it is only recreated. «Музыка при запуске» plays once for each.
     */
    private var launch = 0L

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        // Handle permission granted or denied if needed
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // The launcher icon over a task that already runs YAMP (started from the notification, a widget, Android Auto
        // or the assistant): Android stacks a fresh MainActivity on top of it, which shows the main screen instead of
        // the screen the user left. Drop the duplicate; the task underneath comes to the front as it was.
        if (isLauncherDuplicate()) {
            finish()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true && savedInstanceState == null) openPlayerSignal++
        widgetAsk = WidgetAsk.restoreFrom(savedInstanceState)
        if (savedInstanceState == null) intent?.let { widgetAsk = widgetAsk.withIntent(it) }
        restored = savedInstanceState != null
        launch = savedInstanceState?.getLong(KEY_LAUNCH) ?: android.os.SystemClock.elapsedRealtimeNanos()
        if (savedInstanceState == null) intent?.let { playFromSearch(it) }

        // The app is always dark: dark system bars whatever the system's own theme. "Auto" followed the system,
        // so a light head unit got a light navigation bar (painted turquoise by the vendor) and dark status icons
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        // Back at the app's root sends the task to the background instead of finishing the activity: the player keeps
        // its UI state, and a head unit's Back never tears the activity down mid-playback. Registered before
        // setContent, so every Compose BackHandler (player, sheets, car screen) takes precedence over it.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                diagnosticLog.event("BACK", "root → background")
                moveTaskToBack(true)
            }
        })
        setContent {
            // The size the app really draws in, measured here at its root: every page decides what fits by it, not
            // by what the system says of the window, which can be stale (a head unit's launcher card still reported
            // a second after the app was opened full screen). Zero, and the pages fall back to the system's word.
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                val drawn = androidx.compose.ui.unit.IntSize(
                    if (constraints.hasBoundedWidth) constraints.maxWidth else 0,
                    if (constraints.hasBoundedHeight) constraints.maxHeight else 0
                )
                CompositionLocalProvider(org.tegro.yamp.utils.LocalAppWindowSize provides drawn) { AppRoot() }
            }
        }
    }

    @Composable
    private fun AppRoot() {
        // Scale the whole UI for screens whose reported density is wrong (dense car head units):
        // the app cannot change the system density, so it overrides its own instead.
        val uiScaleAuto by settingsRepository.uiScaleAuto.collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_UI_SCALE_AUTO)
        val uiScaleManual by settingsRepository.uiScaleManual.collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_UI_SCALE)
        val resources = androidx.compose.ui.platform.LocalResources.current
        val baseDensity = LocalDensity.current
        // The window no longer recreates the activity (configChanges), so the scale is recomputed in place when the
        // screen changes: a rotation or a moved split-screen divider changes the metrics the scale is read from
        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
        val uiScale = remember(uiScaleAuto, uiScaleManual, resources, configuration) {
            val info = DisplayScale.analyze(resources.displayMetrics)
            DisplayScale.effectiveScale(uiScaleAuto, uiScaleManual, info).also { scale ->
                diagnosticLog.event(
                    "SCREEN",
                    "${info.widthPx}×${info.heightPx} px · reported ${info.reportedDpi} dpi · physical ≈${info.physicalDpi} dpi · " +
                        "auto ${(info.autoScale * 100).toInt()} % · ${if (uiScaleAuto) "auto" else "manual"} → ${(scale * 100).toInt()} %"
                )
            }
        }
        CompositionLocalProvider(
            LocalDensity provides Density(density = baseDensity.density * uiScale, fontScale = baseDensity.fontScale)
        ) {
            YandexMusicTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = org.tegro.yamp.presentation.hifi.HiFi.Ground
                ) {
                    // Skip the splash when the app is re-entered with a session already on screen
                    androidx.compose.runtime.CompositionLocalProvider(
                        org.tegro.yamp.presentation.car.LocalBackLog provides { where -> diagnosticLog.event("BACK", where) }
                    ) {
                        AppNavigation(
                            skipSplash = restored,
                            openPlayerSignal = openPlayerSignal,
                            launch = launch,
                            startAsk = widgetAsk.start,
                            onStartAskHandled = { widgetAsk = widgetAsk.copy(start = false) },
                            tabAsk = widgetAsk.tab,
                            onTabAskHandled = { widgetAsk = widgetAsk.copy(tab = null) }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) openPlayerSignal++
        widgetAsk = widgetAsk.withIntent(intent)
        playFromSearch(intent)
    }

    /** Assistant request "play <query> on YAMP": same matching as voice search in the car. */
    private fun playFromSearch(intent: Intent) {
        if (intent.action != android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) return
        val query = intent.getStringExtra(android.app.SearchManager.QUERY)
        lifecycleScope.launch {
            libraryTree.startAction(mediaId = "", searchQuery = query)?.invoke()
            openPlayerSignal++
        }
    }

    override fun onStart() {
        super.onStart()
        org.tegro.yamp.data.player.AppPresence.visibleActivities++
        downloadStorage.refresh()
        val token = SessionToken(this, ComponentName(this, MusicService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
    }

    override fun onResume() {
        super.onResume()
        // Back from the subscribe page: ask Plus again at once; otherwise at most once a minute (D101)
        if (org.tegro.yamp.presentation.common.PlusPage.takeReturned()) accountSession.recheckPlus() else accountSession.refreshPlusIfStale()
    }

    override fun onStop() {
        org.tegro.yamp.data.player.AppPresence.visibleActivities--
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(KEY_LAUNCH, launch)
        widgetAsk.saveTo(outState)
    }

    private fun isLauncherDuplicate(): Boolean = isLauncherReentry(isTaskRoot, intent)

    companion object {
        const val EXTRA_OPEN_PLAYER = "open_player"
        const val EXTRA_START_MUSIC = "start_music"
        const val EXTRA_OPEN_TAB = "open_tab"
        private const val KEY_LAUNCH = "launch"

        /** A launcher start (MAIN + LAUNCHER) of an activity that is not its task's root is a duplicate. */
        internal fun isLauncherReentry(isTaskRoot: Boolean, intent: Intent?): Boolean =
            !isTaskRoot && intent?.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER)
    }
}

@Composable
fun AppNavigation(
    skipSplash: Boolean = false,
    openPlayerSignal: Int = 0,
    launch: Long = 0L,
    startAsk: Boolean = false,
    onStartAskHandled: () -> Unit = {},
    tabAsk: String? = null,
    onTabAskHandled: () -> Unit = {}
) {
    val navController = rememberNavController()
    val mainViewModel: MainViewModel = hiltViewModel()
    // Any provider's account opens the app; with none the first start asks where the music is (Yandex isn't required)
    val hasAccount by mainViewModel.hasAnyAccount.collectAsStateWithLifecycle()
    // Music is playing or a session was restored: open straight to the player UI, like Apple Music.
    val startDestination = remember {
        val hasSession = mainViewModel.currentTrack.value != null
        if (hasAccount && (skipSplash || hasSession)) "main" else "splash"
    }

    androidx.compose.runtime.LaunchedEffect(hasAccount) {
        val currentRoute = navController.currentBackStackEntry?.destination?.route
        if (!hasAccount && currentRoute !in setOf("splash", "welcome", "auth")) {
            navController.navigate("welcome") {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable("splash") {
            org.tegro.yamp.presentation.splash.SplashScreen(
                onSplashFinished = {
                    val destination = if (hasAccount) "main" else "welcome"
                    navController.navigate(destination) {
                        popUpTo("splash") { inclusive = true }
                    }
                }
            )
        }
        composable("welcome") {
            var signingInJellyfin by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
            val wheelLeft by mainViewModel.steeringWheelOnLeft.collectAsStateWithLifecycle()
            var addingLocal by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
            // A Jellyfin sign-in or the first folder completes here: the source exists, so the app opens
            androidx.compose.runtime.LaunchedEffect(hasAccount) {
                if (hasAccount) navController.navigate("main") { popUpTo(0) { inclusive = true } }
            }
            org.tegro.yamp.presentation.auth.WelcomeScreen(
                onLocal = { addingLocal = true },
                onJellyfin = { signingInJellyfin = true }
            )
            if (signingInJellyfin) {
                org.tegro.yamp.presentation.account.JellyfinSignInHost(onDismiss = { signingInJellyfin = false })
            }
            if (addingLocal) {
                org.tegro.yamp.presentation.sources.LocalSourcesHost(
                    start = org.tegro.yamp.presentation.sources.LocalStart.FIRST_RUN,
                    big = org.tegro.yamp.presentation.account.isHeadUnitWidth(),
                    wheelLeft = wheelLeft,
                    onClose = { addingLocal = false }
                )
            }
        }
        composable("auth") {
            AuthScreen(
                onAuthSuccess = {
                    navController.navigate("main") { popUpTo("auth") { inclusive = true } }
                }
            )
        }
        composable("main") {
            MainScreen(
                viewModel = mainViewModel,
                openPlayerSignal = openPlayerSignal,
                launch = launch,
                startAsk = startAsk,
                onStartAskHandled = onStartAskHandled,
                tabAsk = tabAsk,
                onTabAskHandled = onTabAskHandled,
                onNavigateToAuth = {
                    navController.navigate("auth")
                }
            )
        }
    }
}