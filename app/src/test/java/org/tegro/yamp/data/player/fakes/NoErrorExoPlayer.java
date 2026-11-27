package org.tegro.yamp.data.player.fakes;

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.test.utils.StubExoPlayer;

/**
 * A stub player that reports no error. In Java because StubExoPlayer declares the error as non-null, which a
 * Kotlin override returning null may not do; the engine reads it as nullable, as the real player returns it.
 */
@UnstableApi
public class NoErrorExoPlayer extends StubExoPlayer {
    @Nullable
    @Override
    public ExoPlaybackException getPlayerError() {
        return null;
    }
}
