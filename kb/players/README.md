# Audio player — knowledge base

Our own audio player for the head unit (Android 9, limited resources, manufacturer firmware).
Goal: playback without stutters or clicks.

## Sections

| Document | Contents |
|---|---|
| [00. Conclusions and ideas](00-conclusions-and-ideas.md) | **Start here.** Summary table, recommended architecture by layer, head-unit risks and how to measure them, open questions |
| [01. Ultrasonic 4.9.0](01-ultrasonic.md) | Media3 1.9.2, one ExoPlayer, own file cache with preloading, audiofx EQ, bugs found |
| [02. Symfonium 14.0.0](02-symfonium.md) | minSdk 32 (does not install on Android 9); Media3 1.9.1 + FFmpeg, second player for crossfade, own LoadControl/retries, DSP EQ via AudioProcessor |
| [03. Musicolet 6.9](03-musicolet.md) | Local files only; FFmpeg+libavfilter (EQ, ReplayGain, limiter, resampling) → ExoPlayer 2.11.8 as output; EQ profiles per output device |
| [04. YouTube Music 9.15.51 (anddea)](04-youtube-music.md) | Media3 1.10 + native Platypus core (SABR/UMP, Cronet/QUIC); own LoadControl, "stuck in buffering" watchdog, fallback ladder on errors |
| [05. Yandex Music 2025.09.1](05-yandex-music.md) | ExoPlayer 2.18.4 + libflac; crossfade with a second player on PCM; URL mirrors and host ban, retries by error type, 60 s wait for the network; cache 250 MB–4 GB |

## Source material

Findings only: these notes describe how the players behave and are configured; no third-party code is reproduced here. The APKs studied are not kept in this repository.
