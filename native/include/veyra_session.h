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

// Check for exactly version 2 BEFORE create. Version 1 had a void close with no cleanup outcome.
VEYRA_EXPORT int veyra_session_abi_version();

// HWND on Windows (the actual Compose window); ignored on macOS.
// Null means no native registrations were acquired. A non-null result transfers ownership,
// NOT proof of successful platform initialization: attach it to its host owner before the
// first update, which returns 0 if initialization failed. Close every non-null result.
VEYRA_EXPORT void* veyra_session_create(uint64_t window, VeyraCommandCallback callback);
// UTF-8 strings are copied during this call; no URI/cookies/headers are passed to native sessions.
// status: 0 stopped, 1 playing, 2 paused. capabilities: bit0 play/pause, bit1 next, bit2 previous, bit3 seek.
// Windows accepts a queued projection; a later update reports an asynchronous native failure.
// A failed update permanently retires callback admission. Calls using one handle must be
// serialized by the host. Updates after retirement return 0.
VEYRA_EXPORT int veyra_session_update(void* session, const char* title, const char* artist,
    const char* album, const char* artwork_path, const char* entry_id, int64_t duration_ms,
    int64_t position_ms, double rate, int status, uint32_t capabilities);

// 1: every required cleanup action succeeded, JNA callbacks are disarmed/drained, and the
//    handle is consumed. Never use it again; the host may release its JNA callback.
// 0: incomplete cleanup, handle NOT consumed. Retain the handle, JNA callback and library;
//    this failure is sticky, not a retry API. Callback admission is permanently disabled.
// Null closes successfully. Callbacks must only enqueue host work: do not close inline or
// synchronously wait for an operation that can close this session. Direct reentrant close
// is refused with 0; cross-thread callback/wait dependency cycles cannot be detected.
// Keep the native library loaded for the process lifetime even after 1: already-enqueued
// platform wrappers can finish native-only work after JNA callback admission has closed.
VEYRA_EXPORT int veyra_session_close(void* session);
