#pragma once
#include <stdint.h>

#if defined(_WIN32)
#define VEYRA_EXPORT extern "C" __declspec(dllexport)
#else
#define VEYRA_EXPORT extern "C" __attribute__((visibility("default")))
#endif

// Callback values: Seek is milliseconds; Rate is playback multiplier; other values are zero.
enum VeyraCommand { VEYRA_PLAY = 1, VEYRA_PAUSE = 2, VEYRA_TOGGLE = 3, VEYRA_NEXT = 4,
    VEYRA_PREVIOUS = 5, VEYRA_SEEK = 6, VEYRA_RATE = 7, VEYRA_SKIP_BACK = 8,
    VEYRA_SKIP_FORWARD = 9, VEYRA_STOP = 10, VEYRA_OUTPUT_CHANGED = 11 };
typedef void (*VeyraCommandCallback)(int command, double value);

// HWND on Windows (the actual Compose window); ignored on macOS. Null means unavailable.
VEYRA_EXPORT void* veyra_session_create(uint64_t window, VeyraCommandCallback callback);
// UTF-8 strings are copied during this call; no URI/cookies/headers are passed to native sessions.
// status: 0 stopped, 1 playing, 2 paused. capabilities: bit0 play/pause, bit1 next, bit2 previous, bit3 seek.
VEYRA_EXPORT int veyra_session_update(void* session, const char* title, const char* artist,
    const char* album, const char* artwork_path, const char* entry_id, int64_t duration_ms,
    int64_t position_ms, double rate, int status, uint32_t capabilities);
// Removes handlers and drains callbacks before returning; safe to release the JNA callback afterwards.
VEYRA_EXPORT void veyra_session_close(void* session);
