#include "veyra_session.h"
#include <windows.h>
#include <mmdeviceapi.h>
#include <functiondiscoverykeys_devpkey.h>
#include <systemmediatransportcontrolsinterop.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Media.h>
#include <winrt/Windows.Storage.h>
#include <winrt/Windows.Storage.Streams.h>
#include <atomic>
#include <condition_variable>
#include <functional>
#include <future>
#include <memory>
#include <mutex>
#include <queue>
#include <stdexcept>
#include <string>
#include <thread>

using namespace winrt;
using namespace Windows::Media;

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

class OutputNotifications final : public IMMNotificationClient {
    std::atomic<ULONG> references{1};
    std::shared_ptr<CallbackState> callback;
    std::mutex mutex;
    std::wstring current;
public:
    OutputNotifications(std::shared_ptr<CallbackState> cb, std::wstring id) : callback(std::move(cb)), current(std::move(id)) {}
    ULONG STDMETHODCALLTYPE AddRef() override { return ++references; }
    ULONG STDMETHODCALLTYPE Release() override { auto count = --references; if (!count) delete this; return count; }
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID iid, void** object) override {
        if (!object) return E_POINTER;
        *object = nullptr;
        if (iid == __uuidof(IUnknown) || iid == __uuidof(IMMNotificationClient)) {
            *object = static_cast<IMMNotificationClient*>(this); AddRef(); return S_OK;
        }
        return E_NOINTERFACE;
    }
    HRESULT STDMETHODCALLTYPE OnDefaultDeviceChanged(EDataFlow flow, ERole role, LPCWSTR id) override {
        if (flow != eRender || role != eMultimedia) return S_OK;
        bool changed;
        { std::lock_guard<std::mutex> lock(mutex); std::wstring next(id ? id : L""); changed = next != current; current = std::move(next); }
        if (changed) callback->send(VEYRA_OUTPUT_CHANGED);
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE OnDeviceStateChanged(LPCWSTR id, DWORD state) override {
        bool removed;
        { std::lock_guard<std::mutex> lock(mutex); removed = id && current == id && state != DEVICE_STATE_ACTIVE; }
        if (removed) callback->send(VEYRA_OUTPUT_CHANGED);
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE OnDeviceRemoved(LPCWSTR id) override { return OnDeviceStateChanged(id, DEVICE_STATE_NOTPRESENT); }
    HRESULT STDMETHODCALLTYPE OnDeviceAdded(LPCWSTR) override { return S_OK; }
    HRESULT STDMETHODCALLTYPE OnPropertyValueChanged(LPCWSTR, const PROPERTYKEY) override { return S_OK; }
};

struct Update {
    std::string title, artist, album, artwork, entry;
    int64_t duration, position;
    double rate;
    int status;
    uint32_t capabilities;
};

class Session {
    std::shared_ptr<CallbackState> callback;
    std::mutex mutex;
    std::condition_variable available;
    std::queue<std::function<void()>> commands;
    bool stopping = false;
    std::atomic<bool> healthy{true};
    std::thread owner;
    SystemMediaTransportControls controls{nullptr};
    event_token buttonToken{}, positionToken{}, rateToken{};
    com_ptr<IMMDeviceEnumerator> devices;
    com_ptr<OutputNotifications> outputNotifications;

    void initialize(HWND window) {
        auto factory = get_activation_factory<SystemMediaTransportControls, ISystemMediaTransportControlsInterop>();
        check_hresult(factory->GetForWindow(window, guid_of<SystemMediaTransportControls>(), put_abi(controls)));
        controls.IsEnabled(true);
        controls.IsPlayEnabled(true); controls.IsPauseEnabled(true); controls.IsStopEnabled(true);
        auto cb = callback;
        buttonToken = controls.ButtonPressed([cb](auto const&, SystemMediaTransportControlsButtonPressedEventArgs const& event) {
            switch (event.Button()) {
                case SystemMediaTransportControlsButton::Play: cb->send(VEYRA_PLAY); break;
                case SystemMediaTransportControlsButton::Pause: cb->send(VEYRA_PAUSE); break;
                case SystemMediaTransportControlsButton::Stop: cb->send(VEYRA_STOP); break;
                case SystemMediaTransportControlsButton::Next: cb->send(VEYRA_NEXT); break;
                case SystemMediaTransportControlsButton::Previous: cb->send(VEYRA_PREVIOUS); break;
                case SystemMediaTransportControlsButton::FastForward: cb->send(VEYRA_SKIP_FORWARD); break;
                case SystemMediaTransportControlsButton::Rewind: cb->send(VEYRA_SKIP_BACK); break;
                default: break;
            }
        });
        positionToken = controls.PlaybackPositionChangeRequested([cb](auto const&, PlaybackPositionChangeRequestedEventArgs const& event) {
            cb->send(VEYRA_SEEK, static_cast<double>(event.RequestedPlaybackPosition().count()) / 10000.0);
        });
        rateToken = controls.PlaybackRateChangeRequested([cb](auto const&, PlaybackRateChangeRequestedEventArgs const& event) {
            cb->send(VEYRA_RATE, event.RequestedPlaybackRate());
        });
        check_hresult(CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL, IID_PPV_ARGS(devices.put())));
        std::wstring initial;
        com_ptr<IMMDevice> device;
        if (SUCCEEDED(devices->GetDefaultAudioEndpoint(eRender, eMultimedia, device.put()))) {
            LPWSTR id = nullptr;
            if (SUCCEEDED(device->GetId(&id))) { initial = id; CoTaskMemFree(id); }
        }
        outputNotifications.attach(new OutputNotifications(cb, std::move(initial)));
        check_hresult(devices->RegisterEndpointNotificationCallback(outputNotifications.get()));
    }

    void dispose() noexcept {
        callback->clear();
        try {
            if (devices && outputNotifications) devices->UnregisterEndpointNotificationCallback(outputNotifications.get());
            if (controls) {
                controls.ButtonPressed(buttonToken);
                controls.PlaybackPositionChangeRequested(positionToken);
                controls.PlaybackRateChangeRequested(rateToken);
                controls.PlaybackStatus(MediaPlaybackStatus::Closed);
                controls.IsEnabled(false);
                controls.DisplayUpdater().ClearAll();
                controls = nullptr;
            }
            outputNotifications = nullptr; devices = nullptr;
        } catch (...) { /* Never throw through the C/JNA ABI during shutdown. */ }
    }

    void update(const Update& value) {
        auto display = controls.DisplayUpdater();
        display.Type(MediaPlaybackType::Music);
        display.AppMediaId(to_hstring(value.entry));
        auto music = display.MusicProperties();
        music.Title(to_hstring(value.title)); music.Artist(to_hstring(value.artist)); music.AlbumTitle(to_hstring(value.album));
        // Local artwork is optional; never fetch an ephemeral source URL from a native session.
        if (value.artwork.empty()) display.Thumbnail(nullptr);
        else {
            try {
                auto file = Windows::Storage::StorageFile::GetFileFromPathAsync(to_hstring(value.artwork)).get();
                display.Thumbnail(Windows::Storage::Streams::RandomAccessStreamReference::CreateFromFile(file));
            } catch (...) { display.Thumbnail(nullptr); }
        }
        display.Update();
        controls.PlaybackStatus(value.status == 1 ? MediaPlaybackStatus::Playing : value.status == 2 ? MediaPlaybackStatus::Paused : MediaPlaybackStatus::Stopped);
        controls.PlaybackRate(value.rate);
        controls.IsPlayEnabled((value.capabilities & 1) != 0); controls.IsPauseEnabled((value.capabilities & 1) != 0);
        controls.IsNextEnabled((value.capabilities & 2) != 0); controls.IsPreviousEnabled((value.capabilities & 4) != 0);
        auto timeline = SystemMediaTransportControlsTimelineProperties();
        timeline.StartTime(Windows::Foundation::TimeSpan{0});
        timeline.MinSeekTime(Windows::Foundation::TimeSpan{0});
        timeline.EndTime(Windows::Foundation::TimeSpan{value.duration > 0 ? value.duration * 10000 : 0});
        timeline.MaxSeekTime(Windows::Foundation::TimeSpan{(value.capabilities & 8) && value.duration > 0 ? value.duration * 10000 : 0});
        timeline.Position(Windows::Foundation::TimeSpan{value.position * 10000});
        controls.UpdateTimelineProperties(timeline);
    }
public:
    Session(HWND window, VeyraCommandCallback cb) : callback(std::make_shared<CallbackState>(cb)) {
        std::promise<bool> ready;
        auto result = ready.get_future();
        owner = std::thread([this, window, ready = std::move(ready)]() mutable {
            bool initialized = false;
            try {
                init_apartment(apartment_type::multi_threaded);
                initialized = true;
                initialize(window);
                ready.set_value(true);
                for (;;) {
                    std::function<void()> next;
                    {
                        std::unique_lock<std::mutex> lock(mutex);
                        available.wait(lock, [this] { return stopping || !commands.empty(); });
                        if (stopping) break;
                        next = std::move(commands.front()); commands.pop();
                    }
                    try { next(); } catch (...) {
                        // The next host projection reports failure; never keep claiming that
                        // system controls are available after a disconnected COM session.
                        // This status change must not itself control or resume audio.
                        healthy = false;
                    }
                }
            } catch (...) { try { ready.set_value(false); } catch (...) {} }
            dispose();
            if (initialized) uninit_apartment();
        });
        if (!result.get()) { owner.join(); throw std::runtime_error("SMTC initialization failed"); }
    }
    bool submit(Update value) {
        std::lock_guard<std::mutex> lock(mutex);
        if (stopping || !healthy) return false;
        // Metadata/position are projections; retain only the latest pending update.
        while (!commands.empty()) commands.pop();
        commands.push([this, value = std::move(value)] { update(value); });
        available.notify_one();
        return true;
    }
    ~Session() {
        callback->clear();
        { std::lock_guard<std::mutex> lock(mutex); stopping = true; }
        available.notify_one();
        if (owner.joinable()) owner.join();
    }
};
}

void* veyra_session_create(uint64_t window, VeyraCommandCallback callback) {
    if (!window || !callback) return nullptr;
    try { return new Session(reinterpret_cast<HWND>(static_cast<uintptr_t>(window)), callback); } catch (...) { return nullptr; }
}
int veyra_session_update(void* session, const char* title, const char* artist, const char* album,
    const char* artwork, const char* entry, int64_t duration, int64_t position, double rate, int status, uint32_t capabilities) {
    if (!session) return 0;
    try { return static_cast<Session*>(session)->submit({title ? title : "", artist ? artist : "", album ? album : "",
        artwork ? artwork : "", entry ? entry : "", duration, position, rate, status, capabilities}) ? 1 : 0; } catch (...) { return 0; }
}
void veyra_session_close(void* session) { delete static_cast<Session*>(session); }
