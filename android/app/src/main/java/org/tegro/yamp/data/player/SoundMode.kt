package org.tegro.yamp.data.player

import android.media.MediaCodecList
import android.media.MediaFormat
import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Compatible" sound: some old head units play a FLAC track in silence, with no error anywhere, so the app
 * cannot notice by itself and the user chooses. In this mode everything goes through the ordinary stream.
 * Nothing chosen yet: compatible only on a device without a FLAC decoder.
 */
@Singleton
class SoundMode(
    private val chosen: Flow<Boolean?>,
    private val save: suspend (Boolean) -> Unit,
    private val hasFlacDecoder: () -> Boolean,
    /** Each account's own choice for unmetered / metered networks; an account without one follows the global choice. */
    private val perAccount: AccountQualitySource = AccountQualitySource.None,
    /** Told the effective choice for an account whenever it changes (the journal's QUALITY line). */
    private val onEffective: (String) -> Unit = {}
) {
    @Inject
    constructor(settings: SettingsRepository, store: AccountQualityStore, journal: PlaybackJournal) : this(
        settings.compatibleSound, settings::setCompatibleSound, ::deviceHasFlacDecoder, store,
        { journal.record(PlaybackJournal.Kind.QUALITY, detail = it) }
    )

    private val decoderPresent by lazy(hasFlacDecoder)

    private fun isCompatible(choice: Boolean?): Boolean = choice ?: !decoderPresent

    /** For the main thread. False for the moment it takes the saved choice to load. */
    val compatible: StateFlow<Boolean> = chosen.map(::isCompatible)
        .stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Default), SharingStarted.Eagerly, false)

    /** Always the saved choice: a request must not slip through while it loads. */
    suspend fun isCompatible(): Boolean = isCompatible(chosen.first())

    /**
     * Compatible for [account] on the network in use: its own choice for a metered or unmetered network, else the
     * global one. Answers from memory (main thread, link keys); a network change applies from the next track.
     */
    fun compatibleFor(account: AccountId?): Boolean = perAccount.chosen(account).let { own -> effective(account, own, own ?: compatible.value) }

    /** [compatibleFor], waiting for the saved global choice when the account has none of its own. */
    suspend fun isCompatible(account: AccountId?): Boolean = perAccount.chosen(account).let { own ->
        effective(account, own, own ?: isCompatible())
    }

    private fun AccountQualitySource.chosen(account: AccountId?): Boolean? = account?.let { chosen(it.value, isMetered()) }

    private val lastNoted = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun effective(account: AccountId?, own: Boolean?, result: Boolean): Boolean {
        val key = account?.value ?: "context"
        val line = "$key ${if (perAccount.isMetered()) "metered" else "unmetered"} → ${if (result) "compatible" else "best"}" +
            if (own == null) " (global)" else " (account)"
        if (lastNoted.put(key, line) != line) onEffective(line)
        return result
    }

    /** Returns once [compatible] says so too, so what happens next already sees the new mode. */
    suspend fun choose(enabled: Boolean) {
        save(enabled)
        compatible.first { it == enabled }
    }
}

/** A device that cannot answer keeps playing as it did. */
internal fun deviceHasFlacDecoder(): Boolean = runCatching {
    MediaCodecList(MediaCodecList.REGULAR_CODECS)
        .findDecoderForFormat(MediaFormat.createAudioFormat("audio/flac", 44_100, 2)) != null
}.getOrDefault(true)
