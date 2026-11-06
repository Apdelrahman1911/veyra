#include "veyra_session.h"
#import <AppKit/AppKit.h>
#import <MediaPlayer/MediaPlayer.h>
#import <CoreAudio/CoreAudio.h>
#include <array>
#include <atomic>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <utility>

namespace {
template<class Action>
bool attempt(Action&& action) noexcept {
    @try {
        try { std::forward<Action>(action)(); return true; } catch (...) { return false; }
    } @catch (NSException*) { return false; }
}

struct CallbackState {
    enum class Phase { Inactive, Active, Retired };
    struct Invocation;
    inline static thread_local Invocation* currentInvocation = nullptr;
    struct Invocation {
        const CallbackState* state;
        Invocation* previous;
        explicit Invocation(const CallbackState* value) noexcept : state(value), previous(currentInvocation) { currentInvocation = this; }
        ~Invocation() noexcept { currentInvocation = previous; }
    };

    std::mutex mutex;
    VeyraCommandCallback callback;
    std::atomic<Phase> phase{Phase::Inactive};
    explicit CallbackState(VeyraCommandCallback cb) : callback(cb) {}
    bool onCurrentThread() const noexcept {
        for (auto* invocation = currentInvocation; invocation; invocation = invocation->previous) {
            if (invocation->state == this) return true;
        }
        return false;
    }
    void activate() noexcept {
        auto expected = Phase::Inactive;
        phase.compare_exchange_strong(expected, Phase::Active);
    }
    void retire() noexcept { phase = Phase::Retired; }
    bool send(int command, double value = 0) noexcept {
        if (phase != Phase::Active || onCurrentThread()) return false;
        bool delivered = false;
        const bool succeeded = attempt([&] {
            std::lock_guard<std::mutex> lock(mutex);
            if (phase != Phase::Active || !callback) return;
            Invocation invocation(this);
            callback(command, value);
            delivered = true;
        });
        return succeeded && delivered;
    }
    bool clear() noexcept {
        retire();
        if (onCurrentThread()) return false;
        return attempt([&] { std::lock_guard<std::mutex> lock(mutex); callback = nullptr; });
    }
};

bool mainQueue(dispatch_block_t work) noexcept {
    __block bool succeeded = false;
    const bool dispatched = attempt([&] {
        // Exceptions must be caught ON the dispatch target. A caller-thread @try does
        // not protect an Objective-C/C++ exception unwinding from a dispatch_sync block.
        dispatch_block_t guarded = ^{
            succeeded = attempt([&] { @autoreleasepool { work(); } });
        };
        if ([NSThread isMainThread]) guarded();
        else dispatch_sync(dispatch_get_main_queue(), guarded);
    });
    return dispatched && succeeded;
}

NSString* text(const char* value) { return value ? [[NSString alloc] initWithUTF8String:value] ?: @"" : @""; }
}

// Own the exact selector target BEFORE calling addTarget:action:. Unlike an opaque
// addTargetWithHandler return token, it remains removable if registration itself raises
// after side effects. Never use removeTarget:nil, which would remove other owners too.
@interface VeyraSessionCommandTarget : NSObject {
@private
    std::shared_ptr<CallbackState> callbackState;
    int commandIdentifier;
}
- (instancetype)initWithCallback:(const std::shared_ptr<CallbackState>&)state identifier:(int)identifier;
- (MPRemoteCommandHandlerStatus)handleCommand:(MPRemoteCommandEvent*)event;
@end

@implementation VeyraSessionCommandTarget
- (instancetype)initWithCallback:(const std::shared_ptr<CallbackState>&)state identifier:(int)identifier {
    self = [super init];
    if (self) { callbackState = state; commandIdentifier = identifier; }
    return self;
}
- (MPRemoteCommandHandlerStatus)handleCommand:(MPRemoteCommandEvent*)event {
    MPRemoteCommandHandlerStatus result = MPRemoteCommandHandlerStatusCommandFailed;
    const bool succeeded = attempt([&] {
        // No Session pointer crosses into the system command dispatcher. A native-only
        // wrapper tail may outlive close, but its shared admission state remains valid.
        auto state = callbackState;
        const int identifier = commandIdentifier;
        double value = 0;
        if (identifier == VEYRA_SEEK) {
            if (![event isKindOfClass:MPChangePlaybackPositionCommandEvent.class]) return;
            value = ((MPChangePlaybackPositionCommandEvent*)event).positionTime * 1000;
        } else if (identifier == VEYRA_RATE) {
            if (![event isKindOfClass:MPChangePlaybackRateCommandEvent.class]) return;
            value = ((MPChangePlaybackRateCommandEvent*)event).playbackRate;
        }
        if (state && state->send(identifier, value)) result = MPRemoteCommandHandlerStatusSuccess;
    });
    return succeeded ? result : MPRemoteCommandHandlerStatusCommandFailed;
}
@end

namespace {
struct CommandRegistration {
    MPRemoteCommand* __strong command = nil;
    VeyraSessionCommandTarget* __strong target = nil;
    bool registrationAttempted = false;
    bool enabledTouched = false;
};

struct Session {
    std::shared_ptr<CallbackState> callback;
    std::array<CommandRegistration, 10> handlers{};
    MPRemoteCommandCenter* __strong center = nil;
    MPNowPlayingInfoCenter* __strong nowPlaying = nil;
    AudioObjectPropertyListenerBlock __strong routeListener = nil;
    AudioObjectPropertyAddress routeAddress{kAudioHardwarePropertyDefaultOutputDevice, kAudioObjectPropertyScopeGlobal, kAudioObjectPropertyElementMain};
    bool routeRemovalNeeded = false;
    bool infoTouched = false;
    bool playbackStateTouched = false;
    std::atomic<bool> ready{false};
    std::atomic<bool> retiring{false};
    std::atomic<bool> closeAttempted{false};

    // No registration in a C++ constructor: its destructor would not run after a throw.
    explicit Session(VeyraCommandCallback cb) : callback(std::make_shared<CallbackState>(cb)) {}

    void add(size_t index, MPRemoteCommand* command, int identifier) {
        if (!command) throw std::runtime_error("MediaPlayer command unavailable");
        auto& slot = handlers[index];
        slot.command = command;
        slot.target = [[VeyraSessionCommandTarget alloc] initWithCallback:callback identifier:identifier];
        if (!slot.target) throw std::runtime_error("MediaPlayer command target unavailable");
        // Fixed storage and a pre-owned target: no container allocation after registration.
        slot.registrationAttempted = true;
        [slot.command addTarget:slot.target action:@selector(handleCommand:)];
        slot.enabledTouched = true;
        slot.command.enabled = YES;
    }

    void initialize() noexcept {
        bool initialized = mainQueue(^{
            center = MPRemoteCommandCenter.sharedCommandCenter;
            nowPlaying = MPNowPlayingInfoCenter.defaultCenter;
            if (!center || !nowPlaying) throw std::runtime_error("MediaPlayer unavailable");
            add(0, center.playCommand, VEYRA_PLAY);
            add(1, center.pauseCommand, VEYRA_PAUSE);
            add(2, center.togglePlayPauseCommand, VEYRA_TOGGLE);
            add(3, center.stopCommand, VEYRA_STOP);
            add(4, center.nextTrackCommand, VEYRA_NEXT);
            add(5, center.previousTrackCommand, VEYRA_PREVIOUS);
            add(6, center.skipBackwardCommand, VEYRA_SKIP_BACK);
            add(7, center.skipForwardCommand, VEYRA_SKIP_FORWARD);
            center.skipBackwardCommand.preferredIntervals = @[@15];
            center.skipForwardCommand.preferredIntervals = @[@30];
            add(8, center.changePlaybackPositionCommand, VEYRA_SEEK);
            add(9, center.changePlaybackRateCommand, VEYRA_RATE);
            center.changePlaybackRateCommand.supportedPlaybackRates = @[@0.5, @0.75, @1, @1.25, @1.5, @1.75, @2];
        });
        if (initialized) {
            bool registered = false;
            const bool completed = attempt([&] {
                auto state = callback;
                routeListener = ^(UInt32, const AudioObjectPropertyAddress*) { state->send(VEYRA_OUTPUT_CHANGED); };
                routeRemovalNeeded = true;
                const auto status = AudioObjectAddPropertyListenerBlock(kAudioObjectSystemObject, &routeAddress, dispatch_get_main_queue(), routeListener);
                registered = status == noErr;
                // Keep the exact removal obligation even on an unsuccessful add: the
                // failure result alone does not prove that no registration side effect ran.
            });
            // Losing route registration is an unavailable session, not a silent downgrade.
            initialized = completed && registered;
        }
        ready = initialized;
        if (!initialized) callback->retire();
        // A failed initialization still transfers this inactive handle to the host, which
        // must attach it before update0, then close or retain any incomplete native rollback.
    }

    bool update(NSString* title, NSString* artist, NSString* album, NSString* artwork, NSString* entry,
        int64_t duration, int64_t position, double rate, int status, uint32_t capabilities) noexcept {
        if (!ready || retiring) return false;
        const bool succeeded = mainQueue(^{
            NSMutableDictionary* info = [NSMutableDictionary dictionary];
            info[MPMediaItemPropertyTitle] = title;
            info[MPMediaItemPropertyArtist] = artist;
            info[MPMediaItemPropertyAlbumTitle] = album;
            info[MPNowPlayingInfoPropertyExternalContentIdentifier] = entry;
            if (duration > 0) info[MPMediaItemPropertyPlaybackDuration] = @(duration / 1000.0);
            info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = @(position / 1000.0);
            info[MPNowPlayingInfoPropertyPlaybackRate] = @(status == 1 ? rate : 0);
            info[MPNowPlayingInfoPropertyDefaultPlaybackRate] = @(rate);
            if (artwork.length) {
                NSImage* image = [[NSImage alloc] initWithContentsOfFile:artwork];
                if (image) info[MPMediaItemPropertyArtwork] = [[MPMediaItemArtwork alloc] initWithBoundsSize:image.size requestHandler:^NSImage*(CGSize) { return image; }];
            }
            infoTouched = true;
            nowPlaying.nowPlayingInfo = info;
            playbackStateTouched = true;
            nowPlaying.playbackState = status == 1 ? MPNowPlayingPlaybackStatePlaying : status == 2 ? MPNowPlayingPlaybackStatePaused : MPNowPlayingPlaybackStateStopped;
            handlers[0].command.enabled = (capabilities & 1) != 0;
            handlers[1].command.enabled = (capabilities & 1) != 0;
            handlers[2].command.enabled = (capabilities & 1) != 0;
            handlers[4].command.enabled = (capabilities & 2) != 0;
            handlers[5].command.enabled = (capabilities & 4) != 0;
            handlers[8].command.enabled = (capabilities & 8) != 0;
            handlers[6].command.enabled = (capabilities & 8) != 0;
            handlers[7].command.enabled = (capabilities & 8) != 0;
            callback->activate();
        });
        if (!succeeded) failProjection();
        return succeeded && !retiring;
    }

    void failProjection() noexcept { ready = false; callback->retire(); }

    bool dispose() noexcept {
        bool succeeded = callback->clear();
        if (routeRemovalNeeded) {
            bool removed = false;
            const bool completed = attempt([&] {
                removed = AudioObjectRemovePropertyListenerBlock(kAudioObjectSystemObject, &routeAddress, dispatch_get_main_queue(), routeListener) == noErr;
                if (removed) routeRemovalNeeded = false;
            });
            if (!completed || !removed) succeeded = false;
        }
        if (!routeRemovalNeeded && !attempt([&] { routeListener = nil; })) succeeded = false;

        __block bool mainSucceeded = true;
        const bool dispatched = mainQueue(^{
            for (auto& slot : handlers) {
                if (slot.registrationAttempted && !attempt([&] {
                    [slot.command removeTarget:slot.target];
                    slot.registrationAttempted = false;
                })) mainSucceeded = false;
                // A failed remove must not skip disabling this command or any other slot.
                if (slot.enabledTouched && !attempt([&] {
                    slot.command.enabled = NO;
                    slot.enabledTouched = false;
                })) mainSucceeded = false;
            }
            if (infoTouched && !attempt([&] {
                nowPlaying.nowPlayingInfo = nil;
                infoTouched = false;
            })) mainSucceeded = false;
            if (playbackStateTouched && !attempt([&] {
                nowPlaying.playbackState = MPNowPlayingPlaybackStateStopped;
                playbackStateTouched = false;
            })) mainSucceeded = false;

            if (mainSucceeded) {
                for (auto& slot : handlers) {
                    if (!attempt([&] { slot.target = nil; })) mainSucceeded = false;
                    if (!attempt([&] { slot.command = nil; })) mainSucceeded = false;
                }
                if (!attempt([&] { center = nil; })) mainSucceeded = false;
                if (!attempt([&] { nowPlaying = nil; })) mainSucceeded = false;
            }
        });
        return succeeded && dispatched && mainSucceeded;
    }

    bool close() noexcept {
        if (closeAttempted.exchange(true)) return false;
        retiring = true;
        ready = false;
        callback->retire();
        if (callback->onCurrentThread()) return false;
        return dispose();
    }
    // Only close1 deletes this object, after all fallible ObjC work and references are cleared.
    ~Session() noexcept = default;
};
}

int veyra_session_abi_version() { return 2; }
void* veyra_session_create(uint64_t, VeyraCommandCallback callback) {
    if (!callback) return nullptr;
    Session* session = nullptr;
    if (!attempt([&] { session = new Session(callback); })) return nullptr;
    session->initialize();
    return session;
}
int veyra_session_update(void* session, const char* title, const char* artist, const char* album,
    const char* artwork, const char* entry, int64_t duration, int64_t position, double rate, int status, uint32_t capabilities) {
    if (!session) return 0;
    bool updated = false;
    const bool succeeded = attempt([&] {
        @autoreleasepool {
            updated = static_cast<Session*>(session)->update(text(title), text(artist), text(album), text(artwork), text(entry), duration, position, rate, status, capabilities);
        }
    });
    if (!succeeded || !updated) static_cast<Session*>(session)->failProjection();
    return succeeded && updated ? 1 : 0;
}
int veyra_session_close(void* session) {
    if (!session) return 1;
    bool closed = false;
    const bool succeeded = attempt([&] {
        auto* value = static_cast<Session*>(session);
        if (!value->close()) return;
        delete value;
        closed = true;
    });
    return succeeded && closed ? 1 : 0;
}
