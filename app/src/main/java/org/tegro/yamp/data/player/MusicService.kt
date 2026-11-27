package org.tegro.yamp.data.player

import org.tegro.yamp.domain.queue.service
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionCommand
import org.tegro.yamp.R

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import androidx.media3.common.Player
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.SessionError
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import org.tegro.yamp.domain.repository.PlayerRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@AndroidEntryPoint
class MusicService : MediaLibraryService() {

    @Inject
    lateinit var playerRepository: PlayerRepository

    @Inject
    lateinit var sessionPlayers: SessionPlayers

    @Inject
    lateinit var playbackController: PlaybackController

    @Inject
    lateinit var libraryTree: MediaLibraryTree

    @Inject
    lateinit var reactions: TrackReactions

    @Inject
    lateinit var equalizer: SystemEqualizer

    @Inject
    lateinit var journal: PlaybackJournal

    @Inject
    lateinit var accountSession: org.tegro.yamp.data.account.AccountSessionManager

    @Inject
    lateinit var diagnosticLog: org.tegro.yamp.data.diagnostics.DiagnosticLog

    @Inject
    lateinit var settings: org.tegro.yamp.domain.repository.SettingsRepository

    @Inject
    lateinit var fixedOutput: FixedOutputRate

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var mediaLibrarySession: MediaLibrarySession? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: android.media.AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var wasPlayingBeforeTransientLoss = false

    /** "Keep playing under announcements" (Settings → Playback). */
    @Volatile
    private var duckForAnnouncements = false

    /** "Resume when nothing else plays" (Settings → Playback, D66). */
    @Volatile
    private var resumeWhenQuiet = false

    /** After a permanent loss: plays on once nothing else plays — only if YAMP was playing at the loss (D66). */
    private val focusResumer by lazy {
        FocusResumer(
            scope = serviceScope,
            nowMs = android.os.SystemClock::elapsedRealtime,
            audioMode = { audioManager?.mode ?: AudioManager.MODE_NORMAL },
            otherMusicActive = { audioManager?.isMusicActive == true },
            intentEpoch = { playbackController.userIntentEpoch },
            playingNow = { playbackController.isPlaying.value },
            resume = { playbackController.play() },
            log = { journal.record(PlaybackJournal.Kind.FOCUS, detail = it) }
        )
    }

    /**
     * Ducked for a transient loss instead of pausing. A call that starts while another app holds the focus is not
     * announced to us again, so meanwhile the audio mode is watched: a call turns the duck into a pause.
     */
    private var transientDuckJob: kotlinx.coroutines.Job? = null

    /** The active queue as the session shows it (the car's «Очередь»), kept up to date in [onCreate]. */
    @Volatile
    private var sessionQueue: SessionQueue? = null

    /** Item picked in a car/browser; started by the play command that follows the selection. */
    private var pendingStart: (() -> Unit)? = null

    /**
     * The saved session is being prepared for a Play from outside (a headset, a head unit at power-on): until it is
     * done, "nothing plays yet" is not yet "nothing is coming".
     */
    @Volatile
    private var resumptionPending = false

    /** The main thread: the foreground promise's start and its watch. */
    private val startGuard = Handler(Looper.getMainLooper())

    /**
     * What took the sound, as far as Android tells another app: a call or a ringing phone, a voice chat, a navigator, an
     * assistant, a notification or an alarm, a game; otherwise some other app with sound. Best effort: the journal's
     * pause line says who to blame.
     */
    private fun soundTakenBy(): String {
        val am = audioManager ?: return SoundTaker.APP
        SoundTaker.byMode(am.mode)?.let { return it }
        val usages = runCatching { am.activePlaybackConfigurations.map { it.audioAttributes.usage } }.getOrDefault(emptyList())
        return SoundTaker.byUsages(usages)
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                transientDuckJob?.cancel()
                transientDuckJob = null
                hasAudioFocus = false
                // Playing at the loss: sounding, or loading to play. Read before the pause below changes it
                val wasPlaying = playbackController.isPlaying.value || wasPlayingBeforeTransientLoss
                wasPlayingBeforeTransientLoss = false
                playerRepository.setDucked(false)
                playbackController.pause(reason = "audio focus lost · ${soundTakenBy()}")
                focusResumer.onLoss(resumeWhenQuiet, wasPlaying, playbackController.userIntentEpoch)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                val mode = audioManager?.mode ?: AudioManager.MODE_NORMAL
                when (AudioFocusPolicy.onTransientLoss(duckForAnnouncements, mode)) {
                    AudioFocusPolicy.TransientLoss.DUCK -> {
                        // A navigator or a radar detector asking for the sound to itself: keep playing quieter
                        journal.record(PlaybackJournal.Kind.FOCUS, detail = "lost for a while, ducking (announcements)")
                        playerRepository.setDucked(true)
                        watchForCallWhileDucked()
                    }
                    AudioFocusPolicy.TransientLoss.PAUSE -> {
                        // Phone call, voice assistant: pause and resume afterwards. A start in flight counts as
                        // playing: the loss came while the music was on its way, so it resumes on the gain (R12)
                        wasPlayingBeforeTransientLoss = playbackController.isPlaying.value
                        journal.record(PlaybackJournal.Kind.FOCUS, detail = "lost for a while, by ${soundTakenBy()}" + (if (wasPlayingBeforeTransientLoss) ", resumes after" else ""))
                        playerRepository.pause()
                    }
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Navigation prompt, notification sound: keep playing quieter, like Apple Music
                playerRepository.setDucked(true)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                focusResumer.cancel()
                transientDuckJob?.cancel()
                transientDuckJob = null
                hasAudioFocus = true
                playerRepository.setDucked(false)
                journal.record(PlaybackJournal.Kind.FOCUS, detail = "regained" + (if (wasPlayingBeforeTransientLoss) ", resuming" else ""))
                // No re-announce to equalizer apps here: the session never closed (D70)
                if (wasPlayingBeforeTransientLoss) {
                    wasPlayingBeforeTransientLoss = false
                    playbackController.play()
                } else if (!playbackController.isPlaying.value) {
                    // Paused meanwhile (a listener's pause): the sound is not held in silence (R12)
                    abandonSystemAudioFocus()
                }
            }
        }
    }

    private fun watchForCallWhileDucked() {
        transientDuckJob?.cancel()
        transientDuckJob = serviceScope.launch {
            while (true) {
                kotlinx.coroutines.delay(CALL_CHECK_INTERVAL_MS)
                val mode = audioManager?.mode ?: return@launch
                if (AudioFocusPolicy.isCall(mode)) {
                    wasPlayingBeforeTransientLoss = playbackController.isPlaying.value
                    journal.record(PlaybackJournal.Kind.FOCUS, detail = "call during an announcement, pausing" + (if (wasPlayingBeforeTransientLoss) ", resumes after" else ""))
                    playerRepository.pause()
                    return@launch
                }
            }
        }
    }

    /** "mixer 48000 Hz, 960 frames" from the platform's output properties; "unknown" where they are missing. */
    private fun mixerLine(am: AudioManager?): String {
        val rate = am?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE) ?: "unknown"
        val frames = am?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER) ?: "unknown"
        return "mixer $rate Hz, $frames frames"
    }

    override fun onCreate() {
        super.onCreate()
        AppPresence.playerServiceRunning = true
        audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager
        diagnosticLog.event("SERVICE", "created")
        // A cold start without the app (a headset, a wheel, a widget, Android Auto) asks Plus too, at most once a minute (D101)
        accountSession.refreshPlusIfStale()
        // The device mixer's native output: the players resample every track to it (fixed output format, D69)
        journal.record(PlaybackJournal.Kind.AUDIO, detail = "${mixerLine(audioManager)}, fixed output ${fixedOutput.mixerRateHz} Hz")
        serviceScope.launch { settings.duckForAnnouncements.collect { duckForAnnouncements = it } }
        serviceScope.launch { settings.resumeWhenQuiet.collect { resumeWhenQuiet = it } }
        // A pause the listener asked for is kept: the end of a call or a focus gain does not start the music
        // again (R12). A pause by the app and by the notification/headset/car both count
        serviceScope.launch { playbackController.listenerPauses.collect { wasPlayingBeforeTransientLoss = false } }
        // Equalizer apps hear about the one session once, when the sound first starts; closed in onDestroy (D70)
        equalizer.openWhenTheSoundStarts(serviceScope, playerRepository.isPlaying)

        run {
            val channel = NotificationChannel(
                PLAYBACK_CHANNEL_ID,
                "YAMP",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }

        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(PLAYBACK_CHANNEL_ID)
            .setChannelName(org.tegro.yamp.R.string.app_name)
            .build()
            .apply { setSmallIcon(org.tegro.yamp.R.drawable.ic_notification) }
        setMediaNotificationProvider(notificationProvider)

        setupMediaSession()

        // Track isPlaying via StateFlow to request/abandon system audio focus without dropping it during player swaps
        serviceScope.launch {
            playerRepository.isPlaying.collect { isPlaying ->
                if (isPlaying) {
                    if (!hasAudioFocus && !requestSystemAudioFocus()) {
                        // Focus denied (e.g. an active phone call): don't play over it
                        journal.record(PlaybackJournal.Kind.PAUSE, detail = "audio focus refused, by ${soundTakenBy()}")
                        playerRepository.pause()
                    }
                } else if (!wasPlayingBeforeTransientLoss) {
                    abandonSystemAudioFocus()
                }
            }
        }

        // Dynamically update MediaLibrarySession when active player swaps
        serviceScope.launch {
            sessionPlayers.activePlayerState.collect { activePl ->
                val forwardingPl = createForwardingPlayer(activePl)
                if (mediaLibrarySession == null) {
                    setupMediaSession(forwardingPl)
                } else {
                    mediaLibrarySession?.player = forwardingPl
                }
            }
        }

        // The car's queue follows YAMP's: every edit, pick and next track; nothing while a station is on air
        serviceScope.launch {
            combine(playbackController.activeQueue, playbackController.currentSource) { queue, source ->
                SessionQueue.of(queue, onAir = source == PlaylistSource.RADIO)
            }
                .distinctUntilChanged()
                .collect { shown ->
                    sessionQueue = shown
                    (mediaLibrarySession?.player as? SeekAwarePlayer)?.run {
                        commandsChanged()
                        queueChanged()
                    }
                }
        }

        // Seeking waits for a converted stream's whole download (D91): the notification and the car drop their seek
        // bar until then; it comes back when the download is complete (a seek then reopens the item there)
        serviceScope.launch {
            combine(playbackController.seekLock.map { it != null }, playbackController.seekByReopen) { locked, reopen -> locked to reopen }
                .distinctUntilChanged()
                .collect { (mediaLibrarySession?.player as? SeekAwarePlayer)?.commandsChanged() }
        }
    }

    /** Returns true when playback may proceed (focus granted now or delayed). */
    private fun requestSystemAudioFocus(): Boolean {
        val result = try {
            val playbackAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val request = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(true)
                // Ducking is handled by us (both players must duck together)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            focusRequest = request
            audioManager?.requestAudioFocus(request)
        } catch (e: Exception) {
            android.util.Log.e("MusicService", "Unexpected error", e)
            null
        }
        return when (result) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                hasAudioFocus = true
                journal.record(PlaybackJournal.Kind.FOCUS, detail = "granted")
                true
            }
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                // Resumes on AUDIOFOCUS_GAIN
                wasPlayingBeforeTransientLoss = true
                journal.record(PlaybackJournal.Kind.FOCUS, detail = "request delayed")
                false
            }
            AudioManager.AUDIOFOCUS_REQUEST_FAILED -> {
                journal.record(PlaybackJournal.Kind.FOCUS, detail = "request failed")
                false
            }
            else -> true // AudioManager unavailable: don't block playback
        }
    }

    private fun abandonSystemAudioFocus() {
        if (hasAudioFocus) journal.record(PlaybackJournal.Kind.FOCUS, detail = "abandoned (nothing playing)")
        hasAudioFocus = false
        try {
            focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        } catch (e: Exception) {
            android.util.Log.e("MusicService", "Unexpected error", e)
        }
    }

    private fun setupMediaSession(forwardingPlayer: ForwardingPlayer = createForwardingPlayer(sessionPlayers.getPlayer())) {
        // Tapping the notification opens the full-screen player, like Apple Music
        val intent = Intent(this, org.tegro.yamp.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(org.tegro.yamp.MainActivity.EXTRA_OPEN_PLAYER, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val sessionCallback = object : MediaLibrarySession.Callback {
            /**
             * The service is exported, so any installed app may connect. The system, Bluetooth and this
             * app are trusted; other apps (widgets, watch/car launchers) get transport controls only.
             */
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult {
                if (controller.isTrusted || controller.packageName == packageName || controller.packageName in CAR_HOSTS) {
                    // Everything, plus the heart and "Не нравится" of the notification and the car
                    val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                        .add(SessionButtons.LIKE)
                        .add(SessionButtons.LIKED)
                        .add(SessionButtons.DISLIKE)
                        .build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                        .setAvailableSessionCommands(commands)
                        .build()
                }
                val transportOnly = Player.Commands.Builder()
                    .addAll(
                        Player.COMMAND_PLAY_PAUSE,
                        Player.COMMAND_SEEK_TO_NEXT,
                        Player.COMMAND_SEEK_TO_PREVIOUS,
                        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_GET_METADATA,
                        Player.COMMAND_GET_TIMELINE
                    )
                    .build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                    .setAvailablePlayerCommands(transportOnly)
                    .setAvailableSessionCommands(androidx.media3.session.SessionCommands.EMPTY)
                    .build()
            }

            override fun onGetLibraryRoot(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                params: LibraryParams?
            ): ListenableFuture<LibraryResult<MediaItem>> =
                Futures.immediateFuture(LibraryResult.ofItem(libraryTree.root(), params))

            override fun onGetChildren(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                parentId: String,
                page: Int,
                pageSize: Int,
                params: LibraryParams?
            ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = libraryFuture {
                val children = libraryTree.children(parentId)
                    ?: return@libraryFuture LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                val from = (page.toLong() * pageSize).coerceAtMost(children.size.toLong()).toInt()
                val to = (from.toLong() + pageSize).coerceAtMost(children.size.toLong()).toInt()
                LibraryResult.ofItemList(children.subList(from, to), params)
            }

            override fun onGetItem(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                mediaId: String
            ): ListenableFuture<LibraryResult<MediaItem>> = libraryFuture {
                libraryTree.item(mediaId)?.let { LibraryResult.ofItem(it, null) }
                    ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            }

            // The car's search: every account's results (Yandex and Jellyfin), announced then fetched by page
            override fun onSearch(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                query: String,
                params: LibraryParams?
            ): ListenableFuture<LibraryResult<Void>> = libraryFuture {
                val found = searchResults(query)
                session.notifySearchResultChanged(browser, query, found.size, params)
                LibraryResult.ofVoid(params)
            }

            override fun onGetSearchResult(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                query: String,
                page: Int,
                pageSize: Int,
                params: LibraryParams?
            ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = libraryFuture {
                val found = searchResults(query)
                val from = (page.toLong() * pageSize).coerceAtMost(found.size.toLong()).toInt()
                val to = (from.toLong() + pageSize).coerceAtMost(found.size.toLong()).toInt()
                LibraryResult.ofItemList(found.subList(from, to), params)
            }

            // A car or another browser picked an item: PlaybackController builds the real queue,
            // the forwarding player ignores the metadata-only items it is then given.
            override fun onSetMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>,
                startIndex: Int,
                startPositionMs: Long
            ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
                val index = if (startIndex in mediaItems.indices) startIndex else 0
                serviceScope.launch {
                    val picked = mediaItems.getOrNull(index)
                    val resolved = libraryTree.resolveStart(picked?.mediaId.orEmpty(), picked?.requestMetadata?.searchQuery)
                    // An account without Plus: the pick starts nothing; the car shows the session error (D101)
                    if (resolved != null && libraryTree.blocksPlus(resolved.account)) {
                        mediaSession.sendError(
                            controller,
                            SessionError(SessionError.ERROR_SESSION_PREMIUM_ACCOUNT_REQUIRED, getString(R.string.plus_required))
                        )
                        future.setException(UnsupportedOperationException("Yandex Plus required"))
                        return@launch
                    }
                    val start = resolved?.action
                    if (start != null) {
                        pendingStart = start
                        future.set(MediaSession.MediaItemsWithStartPosition(mediaItems, index, startPositionMs))
                    } else {
                        future.setException(UnsupportedOperationException("Unknown media item"))
                    }
                }
                return future
            }

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: android.os.Bundle
            ): ListenableFuture<SessionResult> {
                val track = playbackController.currentTrack.value
                    ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
                // A Jellyfin track has no likes yet (P5): a stale button never reaches the Yandex account
                if (track.ref.service == org.tegro.yamp.domain.account.Provider.JELLYFIN || track.ref.service == org.tegro.yamp.domain.account.Provider.LOCAL) {
                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                }
                val action = customCommand.customAction
                when {
                    // The heart, liked or not: the same as the app's heart
                    SessionButtons.isHeart(action) -> reactions.toggleLike(track)
                    action == SessionButtons.DISLIKE.customAction -> reactions.dislike(track)
                    else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            // Headset/Bluetooth Play or the system media card when the app process is not running
            override fun onPlaybackResumption(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                isForPlayback: Boolean
            ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
                resumptionPending = true
                serviceScope.launch {
                    val resumable = try {
                        playbackController.prepareResumption(startPlayback = isForPlayback)
                    } finally {
                        resumptionPending = false
                    }
                    if (resumable == null) {
                        future.setException(UnsupportedOperationException("No saved session to resume"))
                    } else {
                        future.set(MediaSession.MediaItemsWithStartPosition(listOf(resumable.first), 0, resumable.second))
                    }
                }
                return future
            }
        }

        if (mediaLibrarySession == null) {
            val session = MediaLibrarySession.Builder(this, forwardingPlayer, sessionCallback)
                .setSessionActivity(pendingIntent)
                .build()
            mediaLibrarySession = session
            addSession(session)
            showReactionButtons(session)
        } else {
            mediaLibrarySession?.player = forwardingPlayer
        }
    }

    /** The heart and "Не нравится" follow the playing track and its like (from here or from the app). */
    private fun showReactionButtons(session: MediaSession) {
        serviceScope.launch {
            combine(playbackController.currentTrack, reactions.likedTrackIds, reactions.favoriteStationIds) { track, _, _ ->
                SessionButtons.buttonsFor(this@MusicService, track, track != null && reactions.isLiked(track))
            }
                .distinctUntilChanged()
                .collect { session.setMediaButtonPreferences(it) }
        }
    }

    /** The last search's results: `onSearch` and the pages that follow ask for the same query. */
    private var lastSearch: Pair<String, List<MediaItem>>? = null

    private suspend fun searchResults(query: String): List<MediaItem> {
        lastSearch?.takeIf { it.first == query }?.let { return it.second }
        return libraryTree.search(query).also { lastSearch = query to it }
    }

    private fun <T : Any> libraryFuture(block: suspend () -> LibraryResult<T>): ListenableFuture<LibraryResult<T>> {
        val future = SettableFuture.create<LibraryResult<T>>()
        serviceScope.launch {
            future.set(
                try {
                    block()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("MusicService", "Browse request failed", e)
                    LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
                }
            )
        }
        return future
    }

    /**
     * Transport commands from the notification, lock screen and headsets go through the
     * [PlaybackController], so they get the same queue, crossfade and error recovery as the app UI.
     */
    private fun createForwardingPlayer(activePlayer: Player): ForwardingPlayer {
        return object : SeekAwarePlayer(
            activePlayer,
            locked = { playbackController.seekLock.value != null },
            seekByReopen = { playbackController.seekByReopen.value },
            queue = { sessionQueue },
            pick = { entryId -> playbackController.playEntry(entryId) }
        ) {

            override fun isCommandAvailable(command: Int): Boolean =
                availableCommands.contains(command)

            override fun play() {
                startPendingOrPlay()
            }
            override fun pause() {
                pendingStart = null
                // From outside the app: the notification, the lock screen, a headset, a car. Who it was is kept when it
                // is another app (a car launcher, a wheel app): the app's own notification controller adds nothing
                val origin = runCatching { mediaLibrarySession?.controllerForCurrentRequest?.packageName }
                    .getOrNull()?.takeIf { it != packageName }
                playbackController.pause(reason = PlaybackController.devicePauseReason(origin), smooth = true)
            }
            override fun setPlayWhenReady(playWhenReady: Boolean) {
                if (playWhenReady) startPendingOrPlay() else pause()
            }
            override fun prepare() {
                // A failed/idle stream is re-resolved by the controller on play()
            }
            // The queue belongs to PlaybackController; external controllers (resumption, system UI)
            // must not replace the loaded stream with metadata-only items.
            override fun setMediaItem(mediaItem: MediaItem) = Unit
            override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) = Unit
            override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) = Unit
            override fun setMediaItems(mediaItems: MutableList<MediaItem>) = Unit
            override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) = Unit
            override fun setMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long) = Unit
            override fun seekTo(positionMs: Long) {
                playbackController.seekTo(positionMs)
            }
            override fun seekToNext() {
                playbackController.playNext()
            }
            override fun seekToPrevious() {
                playbackController.playPrev()
            }
            override fun seekToNextMediaItem() {
                seekToNext()
            }
            override fun seekToPreviousMediaItem() {
                seekToPrevious()
            }
        }
    }

    private fun startPendingOrPlay() {
        val start = pendingStart
        pendingStart = null
        if (start != null) start() else playbackController.play()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Started for a media button (a head unit sends Play at power-on): Android wants the service in the
        // foreground within seconds, and with nothing to resume the player's notification never comes —
        // "did not then call Service.startForeground()" (Teyes CC3, upstream 1.14.0)
        if (intent?.action == Intent.ACTION_MEDIA_BUTTON && !isPlaybackOngoing) {
            startGuard.removeCallbacksAndMessages(null)
            startGuard.postDelayed(::keepTheForegroundPromise, FOREGROUND_PROMISE_MS)
        }
        return START_NOT_STICKY
    }

    /**
     * The music did not start in time: the service goes to the foreground with a quiet note of its own, so Android
     * does not end the app. Nothing to resume — it leaves at once; still preparing — the note stays until the
     * player's own notification takes over.
     */
    // ServiceCompat passes the type only where Android has it (10+); before that it is ignored
    @SuppressLint("InlinedApi")
    private fun keepTheForegroundPromise() {
        if (isPlaybackOngoing) return
        val note = NotificationCompat.Builder(this, PLAYBACK_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_preparing))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
        val inForeground = runCatching {
            ServiceCompat.startForeground(this, PROMISE_NOTIFICATION_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        }.onFailure { android.util.Log.w("MusicService", "Could not keep the foreground promise", it) }.isSuccess
        if (inForeground) watchThePromise(PROMISE_WATCH_MS)
        else stopSelf()
    }

    /** Leaves the foreground of the quiet note: to the player's notification, or out when nothing plays. */
    private fun watchThePromise(leftMs: Long) {
        val playerTookOver = isPlaybackOngoing
        val nothingComing = !resumptionPending && !playerRepository.isPlaying.value
        if (playerTookOver || nothingComing || leftMs <= 0) {
            if (playerTookOver) NotificationManagerCompat.from(this).cancel(PROMISE_NOTIFICATION_ID)
            else {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return
        }
        startGuard.postDelayed({ watchThePromise(leftMs - PROMISE_STEP_MS) }, PROMISE_STEP_MS)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        diagnosticLog.event("SERVICE", "task removed (app swiped away)")
        playbackController.persistNow()
        if (!playerRepository.isPlaying.value) {
            // Paused: remove the notification but keep the session, so the app reopens on the same track.
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onDestroy() {
        diagnosticLog.event("SERVICE", "destroyed")
        startGuard.removeCallbacksAndMessages(null)
        AppPresence.playerServiceRunning = false
        serviceScope.cancel()
        abandonSystemAudioFocus()
        equalizer.close()
        mediaLibrarySession?.run {
            removeSession(this)
            release()
            mediaLibrarySession = null
        }
        super.onDestroy()
    }

    companion object {
        const val PLAYBACK_CHANNEL_ID = "yandex_music_lite_playback_channel"

        /** How often the audio mode is checked for a call while ducked under another app's announcement. */
        private const val CALL_CHECK_INTERVAL_MS = 500L

        /** Android ends a service started for the foreground that is not there in 5–10 s: well before that. */
        private const val FOREGROUND_PROMISE_MS = 3_000L
        private const val PROMISE_STEP_MS = 1_000L
        /** A resumption still not done after this is not coming. */
        private const val PROMISE_WATCH_MS = 30_000L
        private const val PROMISE_NOTIFICATION_ID = 7_301

        /** Android Auto and the Automotive OS media center: allowed to browse and pick what to play. */
        private val CAR_HOSTS = setOf("com.google.android.projection.gearhead", "com.android.car.media")
    }
}

/**
 * The session's player: the transport commands always, and seeking only while the engine allows it — never while a
 * converted stream waits for its whole download ([locked]); once it is complete ([seekByReopen], D91) the session
 * seeks although the player can't by itself (the seek goes through the controller, which reopens the item there), and
 * the item's length is the catalog's while the player has none. [commandsChanged] tells the session of each change.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal open class SeekAwarePlayer(
    player: Player,
    private val locked: () -> Boolean,
    private val seekByReopen: () -> Boolean = { false },
    private val queue: () -> SessionQueue? = { null },
    private val pick: (entryId: Long) -> Unit = {}
) : ForwardingPlayer(player) {
    /** The session's listeners, each as the wrapped player hears it ([queueListener]). */
    private val listeners = java.util.concurrent.ConcurrentHashMap<Player.Listener, Player.Listener>()

    override fun addListener(listener: Player.Listener) {
        val heard = queueListener(listener)
        if (listeners.putIfAbsent(listener, heard) == null) super.addListener(heard)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)?.let { super.removeListener(it) }
    }

    // ---- The queue (Android Auto's «Очередь»): YAMP's queue around the cursor, not the engine's one or two items

    /** The queue the session shows, or null (the engine's own timeline) while the engine has nothing loaded. */
    private fun shownQueue(): SessionQueue? = if (super.getCurrentMediaItem() == null) null else queue()

    override fun getCurrentTimeline(): androidx.media3.common.Timeline {
        val shown = shownQueue() ?: return super.getCurrentTimeline()
        return SessionQueueTimeline(shown, currentWindow(shown))
    }

    /** The cursor's window is the engine's own item when it is the cursor's track (a pick may still be loading). */
    private fun currentWindow(shown: SessionQueue): CurrentWindow? {
        val item = super.getCurrentMediaItem() ?: return null
        if (item.mediaId != shown.entryAt(shown.currentIndex)?.trackId) return null
        return CurrentWindow(
            item = item,
            durationMs = duration,
            seekable = isCurrentMediaItemSeekable,
            dynamic = super.isCurrentMediaItemDynamic(),
            live = if (isCurrentMediaItemLive) item.liveConfiguration else null
        )
    }

    override fun getCurrentMediaItemIndex(): Int = shownQueue()?.currentIndex ?: super.getCurrentMediaItemIndex()

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun getCurrentWindowIndex(): Int = currentMediaItemIndex

    override fun getCurrentPeriodIndex(): Int = shownQueue()?.currentIndex ?: super.getCurrentPeriodIndex()

    override fun getMediaItemCount(): Int = shownQueue()?.entries?.size ?: super.getMediaItemCount()

    override fun getMediaItemAt(index: Int): MediaItem =
        if (shownQueue() != null) currentTimeline.getWindow(index, androidx.media3.common.Timeline.Window()).mediaItem else super.getMediaItemAt(index)

    override fun getNextMediaItemIndex(): Int = shownQueue()?.let { q ->
        if (q.currentIndex + 1 < q.entries.size) q.currentIndex + 1 else androidx.media3.common.C.INDEX_UNSET
    } ?: super.getNextMediaItemIndex()

    override fun getPreviousMediaItemIndex(): Int = shownQueue()?.let { q ->
        if (q.currentIndex > 0) q.currentIndex - 1 else androidx.media3.common.C.INDEX_UNSET
    } ?: super.getPreviousMediaItemIndex()

    override fun hasNextMediaItem(): Boolean = nextMediaItemIndex != androidx.media3.common.C.INDEX_UNSET

    override fun hasPreviousMediaItem(): Boolean = previousMediaItemIndex != androidx.media3.common.C.INDEX_UNSET

    /**
     * A tap in the car's queue (Media3 `seekTo(index, …)`, legacy `onSkipToQueueItem`): another entry is a pick of it
     * in YAMP's queue; the current one is a seek within it, under the seek lock like any other.
     */
    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        val shown = shownQueue()
        val entry = shown?.entryAt(mediaItemIndex)
        when {
            shown == null || mediaItemIndex == shown.currentIndex -> seekTo(positionMs.coerceAtLeast(0L))
            entry != null -> pick(entry.entryId)
        }
    }

    override fun seekToDefaultPosition(mediaItemIndex: Int) = seekTo(mediaItemIndex, 0L)

    /** YAMP's queue changed (an edit, a pick, the next track): the session, and through it the car, is told. */
    fun queueChanged() {
        val timeline = currentTimeline
        listeners.keys.forEach { it.onTimelineChanged(timeline, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) }
    }

    /**
     * [listener] as the wrapped player calls it: the engine's timeline and positions replaced by the queue's, every
     * other event as it is. A proxy, not a Kotlin delegate: `by` does not forward the interface's Java default methods
     * (all of [Player.Listener]), and a hand-written forward would drop the events a newer Media3 adds.
     */
    @Suppress("SpreadOperator")
    private fun queueListener(listener: Player.Listener): Player.Listener =
        java.lang.reflect.Proxy.newProxyInstance(Player.Listener::class.java.classLoader, arrayOf(Player.Listener::class.java)) { proxy, method, args ->
            val given = args.orEmpty()
            when {
                method.name == "equals" && given.size == 1 -> proxy === given[0]
                method.name == "hashCode" && given.isEmpty() -> System.identityHashCode(proxy)
                method.name == "toString" && given.isEmpty() -> "QueueListener($listener)"
                method.name == "onTimelineChanged" && given.size == 2 ->
                    listener.onTimelineChanged(currentTimeline, given[1] as Int)
                method.name == "onPositionDiscontinuity" && given.size == 3 ->
                    listener.onPositionDiscontinuity(inQueue(given[0] as Player.PositionInfo), inQueue(given[1] as Player.PositionInfo), given[2] as Int)
                else -> try {
                    method.invoke(listener, *given)
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    throw e.targetException
                }
            }
        } as Player.Listener

    /** An engine position as the queue's timeline has it: the cursor's window and period. */
    private fun inQueue(position: Player.PositionInfo): Player.PositionInfo {
        val shown = shownQueue() ?: return position
        val index = shown.currentIndex
        val uid = currentTimeline.getUidOfPeriod(index)
        return Player.PositionInfo(
            uid, index, position.mediaItem, uid, index,
            position.positionMs, position.contentPositionMs, position.adGroupIndex, position.adIndexInAdGroup
        )
    }

    override fun getAvailableCommands(): Player.Commands {
        val commands = super.getAvailableCommands().buildUpon()
            .add(Player.COMMAND_PLAY_PAUSE)
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        // The car's queue: its rows ([getCurrentTimeline]) and a tap on one ([seekTo] with an index, a pick)
        if (queue() != null) commands.add(Player.COMMAND_GET_TIMELINE).add(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
        if (locked()) {
            commands.removeAll(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD)
        } else if (seekByReopen()) {
            // Only the seek to a position: it goes through seekTo (the controller); seek back / forward would go
            // straight to the player, which can't
            commands.add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        }
        return commands.build()
    }

    override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

    override fun isCurrentMediaItemSeekable(): Boolean = super.isCurrentMediaItemSeekable() || !locked() && seekByReopen()

    /**
     * Media3 calls a stream with no length "live" (unknown length, no seek map); the platform session then shows no
     * position and drops ACTION_SEEK_TO whatever the commands say. A track with a catalog length is not live.
     */
    override fun isCurrentMediaItemLive(): Boolean = super.isCurrentMediaItemLive() && catalogDurationMs() == null

    /** A stream sent with no length has no duration of its own: the catalog's (the item's metadata). */
    override fun getDuration(): Long = super.getDuration().orCatalog()

    override fun getContentDuration(): Long = super.getContentDuration().orCatalog()

    private fun Long.orCatalog(): Long = if (this == androidx.media3.common.C.TIME_UNSET) catalogDurationMs() ?: this else this

    private fun catalogDurationMs(): Long? = currentMediaItem?.mediaMetadata?.durationMs?.takeIf { it > 0L }

    /** The seek command came or went: the session (and through it the notification and the car) is told. */
    fun commandsChanged() {
        val commands = availableCommands
        listeners.keys.forEach { it.onAvailableCommandsChanged(commands) }
    }
}
