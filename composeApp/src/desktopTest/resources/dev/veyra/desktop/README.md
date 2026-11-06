# Authored native audio test fixture

`tone.mp3` is a synthetic 440 Hz, 48 kHz, 12-second sine wave generated for Veyra using the existing FFmpeg 6.1.1 `sine` source and `libmp3lame` encoder. It is not a sampled song or podcast and has no third-party recording content. Dedicated to CC0-1.0 for redistribution/testing.

Generation: `ffmpeg -f lavfi -i sine=frequency=440:sample_rate=48000:duration=12 -c:a libmp3lame tone.mp3`.

Tests must identify it as synthetic. It never satisfies the separately required real-source listening acceptance test. The chosen native runtime does not demux WAV, so generating PCM WAV instead is not an equivalent positive fixture.
