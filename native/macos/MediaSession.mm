#include "veyra_session.h"
#import <AppKit/AppKit.h>
#import <MediaPlayer/MediaPlayer.h>
#import <CoreAudio/CoreAudio.h>
#include <memory>
#include <mutex>
#include <string>

namespace {
struct CallbackState {
    std::mutex mutex;
    VeyraCommandCallback callback;
    explicit CallbackState(VeyraCommandCallback cb) : callback(cb) {}
    void send(int command, double value = 0) {
        std::lock_guard<std::mutex> lock(mutex);
        if (callback) callback(command, value);
    }
    void clear() { std::lock_guard<std::mutex> lock(mutex); callback = nullptr; }
};

void mainQueue(dispatch_block_t block) {
    if ([NSThread isMainThread]) block(); else dispatch_sync(dispatch_get_main_queue(), block);
}
NSString* text(const char* value) { return value ? [[NSString alloc] initWithUTF8String:value] ?: @"" : @""; }

struct Session {
    std::shared_ptr<CallbackState> callback;
    NSMutableArray<NSArray*>* handlers;
    AudioObjectPropertyListenerBlock routeListener;
    AudioObjectPropertyAddress routeAddress;
    bool routeRegistered = false;

    explicit Session(VeyraCommandCallback cb) : callback(std::make_shared<CallbackState>(cb)) {
        auto state = callback;
        mainQueue(^{
            handlers = [NSMutableArray array];
            MPRemoteCommandCenter* center = MPRemoteCommandCenter.sharedCommandCenter;
            add(center.playCommand, VEYRA_PLAY);
            add(center.pauseCommand, VEYRA_PAUSE);
            add(center.togglePlayPauseCommand, VEYRA_TOGGLE);
            add(center.stopCommand, VEYRA_STOP);
            add(center.nextTrackCommand, VEYRA_NEXT);
            add(center.previousTrackCommand, VEYRA_PREVIOUS);
            add(center.skipBackwardCommand, VEYRA_SKIP_BACK);
            add(center.skipForwardCommand, VEYRA_SKIP_FORWARD);
            center.skipBackwardCommand.preferredIntervals = @[@15];
            center.skipForwardCommand.preferredIntervals = @[@30];
            center.changePlaybackPositionCommand.enabled = YES;
            id position = [center.changePlaybackPositionCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent* event) {
                if (![event isKindOfClass:MPChangePlaybackPositionCommandEvent.class]) return MPRemoteCommandHandlerStatusCommandFailed;
                state->send(VEYRA_SEEK, ((MPChangePlaybackPositionCommandEvent*)event).positionTime * 1000);
                return MPRemoteCommandHandlerStatusSuccess;
            }];
            [handlers addObject:@[center.changePlaybackPositionCommand, position]];
            center.changePlaybackRateCommand.enabled = YES;
            center.changePlaybackRateCommand.supportedPlaybackRates = @[@0.5, @0.75, @1, @1.25, @1.5, @1.75, @2];
            id rate = [center.changePlaybackRateCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent* event) {
                if (![event isKindOfClass:MPChangePlaybackRateCommandEvent.class]) return MPRemoteCommandHandlerStatusCommandFailed;
                state->send(VEYRA_RATE, ((MPChangePlaybackRateCommandEvent*)event).playbackRate);
                return MPRemoteCommandHandlerStatusSuccess;
            }];
            [handlers addObject:@[center.changePlaybackRateCommand, rate]];
        });
        routeAddress = {kAudioHardwarePropertyDefaultOutputDevice, kAudioObjectPropertyScopeGlobal, kAudioObjectPropertyElementMain};
        routeListener = ^(UInt32, const AudioObjectPropertyAddress*) { state->send(VEYRA_OUTPUT_CHANGED); };
        routeRegistered = AudioObjectAddPropertyListenerBlock(kAudioObjectSystemObject, &routeAddress, dispatch_get_main_queue(), routeListener) == noErr;
    }

    void add(MPRemoteCommand* command, int identifier) {
        auto state = callback;
        command.enabled = YES;
        id token = [command addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent*) {
            state->send(identifier);
            return MPRemoteCommandHandlerStatusSuccess;
        }];
        [handlers addObject:@[command, token]];
    }

    void update(NSString* title, NSString* artist, NSString* album, NSString* artwork, NSString* entry,
        int64_t duration, int64_t position, double rate, int status, uint32_t capabilities) {
        mainQueue(^{
            @autoreleasepool {
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
                MPNowPlayingInfoCenter* nowPlaying = MPNowPlayingInfoCenter.defaultCenter;
                nowPlaying.nowPlayingInfo = info;
                nowPlaying.playbackState = status == 1 ? MPNowPlayingPlaybackStatePlaying : status == 2 ? MPNowPlayingPlaybackStatePaused : MPNowPlayingPlaybackStateStopped;
                MPRemoteCommandCenter* center = MPRemoteCommandCenter.sharedCommandCenter;
                center.playCommand.enabled = (capabilities & 1) != 0;
                center.pauseCommand.enabled = (capabilities & 1) != 0;
                center.togglePlayPauseCommand.enabled = (capabilities & 1) != 0;
                center.nextTrackCommand.enabled = (capabilities & 2) != 0;
                center.previousTrackCommand.enabled = (capabilities & 4) != 0;
                center.changePlaybackPositionCommand.enabled = (capabilities & 8) != 0;
                center.skipBackwardCommand.enabled = (capabilities & 8) != 0;
                center.skipForwardCommand.enabled = (capabilities & 8) != 0;
            }
        });
    }

    ~Session() {
        callback->clear();
        if (routeRegistered) AudioObjectRemovePropertyListenerBlock(kAudioObjectSystemObject, &routeAddress, dispatch_get_main_queue(), routeListener);
        mainQueue(^{
            for (NSArray* handler in handlers) {
                MPRemoteCommand* command = handler[0];
                [command removeTarget:handler[1]];
                command.enabled = NO;
            }
            [handlers removeAllObjects];
            MPNowPlayingInfoCenter.defaultCenter.nowPlayingInfo = nil;
            MPNowPlayingInfoCenter.defaultCenter.playbackState = MPNowPlayingPlaybackStateStopped;
        });
    }
};
}

void* veyra_session_create(uint64_t, VeyraCommandCallback callback) {
    if (!callback) return nullptr;
    @try { try { return new Session(callback); } catch (...) { return nullptr; } }
    @catch (NSException*) { return nullptr; }
}
int veyra_session_update(void* session, const char* title, const char* artist, const char* album,
    const char* artwork, const char* entry, int64_t duration, int64_t position, double rate, int status, uint32_t capabilities) {
    if (!session) return 0;
    @try {
        static_cast<Session*>(session)->update(text(title), text(artist), text(album), text(artwork), text(entry), duration, position, rate, status, capabilities);
        return 1;
    } @catch (NSException*) { return 0; }
}
void veyra_session_close(void* session) { delete static_cast<Session*>(session); }
