#include "veyra_session.h"
#include <windows.h>
#include <mmdeviceapi.h>
#include <functiondiscoverykeys_devpkey.h>
#include <systemmediatransportcontrolsinterop.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Media.h>
#include <winrt/Windows.Storage.h>
#include <winrt/Windows.Storage.Streams.h>
#include <array>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <functional>
#include <future>
#include <memory>
#include <mutex>
#include <queue>
#include <stdexcept>
#include <string>
#include <thread>
#include <utility>

using namespace winrt;
using namespace Windows::Media;

namespace {
template<class Action>
bool attempt(Action&& action) noexcept {
    try { std::forward<Action>(action)(); return true; } catch (...) { return false; }
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
    bool active() const noexcept { return phase == Phase::Active; }
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
        // Never wait for the invocation whose own stack is trying to close us.
        if (onCurrentThread()) return false;
        return attempt([&] { std::lock_guard<std::mutex> lock(mutex); callback = nullptr; });
    }
};

// IMMNotificationClient forbids waiting on synchronization objects or calling back into
// MMDevice ownership during notification delivery. Only this bounded mailbox is touched
// there; route interpretation and JNA delivery run on the already-owned MTA worker.
struct RouteInbox {
    enum class Kind { DefaultChanged, Removed };
    static constexpr size_t capacity = 16;
    static constexpr size_t maxIdLength = 1024;
    struct Event { Kind kind = Kind::DefaultChanged; std::wstring id; };
    std::mutex mutex;
    std::array<Event, capacity> events;
    size_t count = 0;
    std::wstring current;
    bool currentKnown = false;
    std::atomic<bool> dirty{false};
    std::atomic<bool> conservativePause{false};
    std::atomic<bool> retired{false};
    std::condition_variable available;

    RouteInbox() {
        // Allocate before ANY platform registration. Bounded callback copies do not grow
        // these strings, allocate a queue node, or wait for the consumer's mutex.
        current.reserve(maxIdLength);
        for (auto& event : events) event.id.reserve(maxIdLength);
    }
    void setInitial(const std::wstring& id, bool known) { current = id; currentKnown = known; }
    bool pending() const noexcept { return !retired && (dirty || conservativePause); }
    void retire() noexcept { retired = true; }

    void post(Kind kind, LPCWSTR id) noexcept {
        if (retired) return;
        const bool completed = attempt([&] {
            std::unique_lock<std::mutex> lock(mutex, std::try_to_lock);
            if (!lock.owns_lock()) { conservativePause = true; return; }
            if (retired) return;
            size_t length = 0;
            while (id && length < maxIdLength && id[length]) ++length;
            if (count == capacity || (id && length == maxIdLength && id[length])) {
                // Never truncate an identity or silently lose a possible output change.
                // Saturation, contention and oversized IDs coalesce into a safe pause.
                conservativePause = true;
                return;
            }
            auto& event = events[count];
            event.id.assign(id ? id : L"", length);
            event.kind = kind;
            ++count;
            dirty = true;
        });
        if (!completed) conservativePause = true;
        available.notify_one();
    }

    bool consume() {
        std::lock_guard<std::mutex> lock(mutex);
        const bool uncertain = conservativePause.exchange(false);
        bool pause = uncertain;
        for (size_t index = 0; index < count; ++index) {
            auto& event = events[index];
            if (event.kind == Kind::DefaultChanged) {
                if (!currentKnown || current != event.id) pause = true;
                current = event.id;
                currentKnown = true;
            } else if (!currentKnown || current == event.id) pause = true;
            event.id.clear();
        }
        count = 0;
        dirty = false;
        // An unrecorded event may be newer than any queued ID. Until a later exact
        // default event restores identity, a device removal must conservatively pause.
        if (uncertain) currentKnown = false;
        return pause;
    }
};

class OutputNotifications final : public IMMNotificationClient {
    std::atomic<ULONG> references{1};
    std::shared_ptr<RouteInbox> inbox;
    OutputNotifications* nextRetired = nullptr;
    inline static std::atomic<OutputNotifications*> retiredObservers{nullptr};
public:
    explicit OutputNotifications(std::shared_ptr<RouteInbox> state) : inbox(std::move(state)) {}
    static void retainUntilProcessExit(OutputNotifications* value) noexcept {
        if (!value) return;
        // IMM does not AddRef its recipient and does not expose a separate queued-call
        // drain primitive. After successful unregister keep a small, inert tombstone,
        // not a Session/JNA pointer, for native-only entries/tails. No allocation here.
        auto* head = retiredObservers.load();
        do { value->nextRetired = head; }
        while (!retiredObservers.compare_exchange_weak(head, value));
    }
    ULONG STDMETHODCALLTYPE AddRef() noexcept override { return ++references; }
    ULONG STDMETHODCALLTYPE Release() noexcept override { auto count = --references; if (!count) delete this; return count; }
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID iid, void** object) noexcept override {
        if (!object) return E_POINTER;
        *object = nullptr;
        if (iid == __uuidof(IUnknown) || iid == __uuidof(IMMNotificationClient)) {
            *object = static_cast<IMMNotificationClient*>(this); AddRef(); return S_OK;
        }
        return E_NOINTERFACE;
    }
    HRESULT STDMETHODCALLTYPE OnDefaultDeviceChanged(EDataFlow flow, ERole role, LPCWSTR id) noexcept override {
        if (flow == eRender && role == eMultimedia) inbox->post(RouteInbox::Kind::DefaultChanged, id);
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE OnDeviceStateChanged(LPCWSTR id, DWORD state) noexcept override {
        if (id && state != DEVICE_STATE_ACTIVE) inbox->post(RouteInbox::Kind::Removed, id);
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE OnDeviceRemoved(LPCWSTR id) noexcept override { return OnDeviceStateChanged(id, DEVICE_STATE_NOTPRESENT); }
    HRESULT STDMETHODCALLTYPE OnDeviceAdded(LPCWSTR) noexcept override { return S_OK; }
    HRESULT STDMETHODCALLTYPE OnPropertyValueChanged(LPCWSTR, const PROPERTYKEY) noexcept override { return S_OK; }
};

struct Update {
    std::string title, artist, album, artwork, entry;
    int64_t duration, position;
    double rate;
    int status;
    uint32_t capabilities;
};

class Session {
    enum class Registration { Absent, Attempted, Registered };
    std::shared_ptr<CallbackState> callback;
    std::shared_ptr<RouteInbox> routes;
    std::mutex mutex;
    std::queue<std::function<void()>> commands;
    bool stopping = false;
    std::atomic<bool> healthy{false};
    std::atomic<bool> retiring{false};
    std::atomic<bool> closeAttempted{false};
    std::atomic<bool> cleanupSucceeded{false};
    std::thread owner;
    SystemMediaTransportControls controls{nullptr};
    ISystemMediaTransportControls controlEvents{nullptr};
    ISystemMediaTransportControls2 extendedControls{nullptr};
    event_token buttonToken{}, positionToken{}, rateToken{};
    Registration buttonRegistration = Registration::Absent;
    Registration positionRegistration = Registration::Absent;
    Registration rateRegistration = Registration::Absent;
    Registration outputRegistration = Registration::Absent;
    com_ptr<IMMDeviceEnumerator> devices;
    com_ptr<OutputNotifications> outputNotifications;

    template<class Interface>
    static auto* abi(const Interface& value) noexcept {
        return static_cast<winrt::impl::abi_t<Interface>*>(get_abi(value));
    }

    void initialize(HWND window) {
        auto factory = get_activation_factory<SystemMediaTransportControls, ISystemMediaTransportControlsInterop>();
        check_hresult(factory->GetForWindow(window, guid_of<SystemMediaTransportControls>(), put_abi(controls)));
        controlEvents = controls.as<ISystemMediaTransportControls>();
        extendedControls = controls.as<ISystemMediaTransportControls2>();
        controls.IsEnabled(true);
        controls.IsPlayEnabled(true); controls.IsPauseEnabled(true); controls.IsStopEnabled(true);
        auto cb = callback;
        auto button = Windows::Foundation::TypedEventHandler<SystemMediaTransportControls, SystemMediaTransportControlsButtonPressedEventArgs>(
            [cb](auto const&, SystemMediaTransportControlsButtonPressedEventArgs const& event) noexcept {
                attempt([&] {
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
            });
        buttonRegistration = Registration::Attempted;
        check_hresult(abi(controlEvents)->add_ButtonPressed(get_abi(button), &buttonToken));
        buttonRegistration = Registration::Registered;
        auto position = Windows::Foundation::TypedEventHandler<SystemMediaTransportControls, PlaybackPositionChangeRequestedEventArgs>(
            [cb](auto const&, PlaybackPositionChangeRequestedEventArgs const& event) noexcept {
                attempt([&] { cb->send(VEYRA_SEEK, static_cast<double>(event.RequestedPlaybackPosition().count()) / 10000.0); });
            });
        positionRegistration = Registration::Attempted;
        check_hresult(abi(extendedControls)->add_PlaybackPositionChangeRequested(get_abi(position), &positionToken));
        positionRegistration = Registration::Registered;
        auto rate = Windows::Foundation::TypedEventHandler<SystemMediaTransportControls, PlaybackRateChangeRequestedEventArgs>(
            [cb](auto const&, PlaybackRateChangeRequestedEventArgs const& event) noexcept {
                attempt([&] { cb->send(VEYRA_RATE, event.RequestedPlaybackRate()); });
            });
        rateRegistration = Registration::Attempted;
        check_hresult(abi(extendedControls)->add_PlaybackRateChangeRequested(get_abi(rate), &rateToken));
        rateRegistration = Registration::Registered;
        check_hresult(CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL, IID_PPV_ARGS(devices.put())));
        std::wstring initial;
        bool initialKnown = false;
        com_ptr<IMMDevice> device;
        if (SUCCEEDED(devices->GetDefaultAudioEndpoint(eRender, eMultimedia, device.put()))) {
            LPWSTR id = nullptr;
            const auto result = device->GetId(&id);
            std::unique_ptr<wchar_t, decltype(&CoTaskMemFree)> ownedId(id, &CoTaskMemFree);
            if (SUCCEEDED(result) && id) { initial = id; initialKnown = true; }
        }
        routes->setInitial(initial, initialKnown);
        outputNotifications.attach(new OutputNotifications(routes));
        outputRegistration = Registration::Attempted;
        check_hresult(devices->RegisterEndpointNotificationCallback(outputNotifications.get()));
        outputRegistration = Registration::Registered;
    }

    bool dispose() noexcept {
        routes->retire();
        bool succeeded = callback->clear();
        // An exact IMM callback identity is already owned even if its registration failed.
        if (outputRegistration != Registration::Absent && !attempt([&] {
            check_hresult(devices->UnregisterEndpointNotificationCallback(outputNotifications.get()));
            outputRegistration = Registration::Absent;
        })) succeeded = false;

        const auto remove = [&](Registration& registration, auto&& action) noexcept {
            if (registration == Registration::Attempted) {
                // An unsuccessful add did not yield a trustworthy token. Do not remove a
                // zero/default token that might belong to somebody else or claim a drain.
                succeeded = false;
            } else if (registration == Registration::Registered && !attempt([&] {
                action();
                registration = Registration::Absent;
            })) succeeded = false;
        };
        // C++/WinRT's projected event removers intentionally discard HRESULT. Call the ABI
        // directly so a failed unregister cannot become a successful native close.
        remove(buttonRegistration, [&] { check_hresult(abi(controlEvents)->remove_ButtonPressed(buttonToken)); });
        remove(positionRegistration, [&] { check_hresult(abi(extendedControls)->remove_PlaybackPositionChangeRequested(positionToken)); });
        remove(rateRegistration, [&] { check_hresult(abi(extendedControls)->remove_PlaybackRateChangeRequested(rateToken)); });
        if (controls) {
            if (!attempt([&] { controls.PlaybackStatus(MediaPlaybackStatus::Closed); })) succeeded = false;
            if (!attempt([&] { controls.IsEnabled(false); })) succeeded = false;
            SystemMediaTransportControlsDisplayUpdater display{nullptr};
            if (!attempt([&] { display = controls.DisplayUpdater(); })) succeeded = false;
            if (display) {
                if (!attempt([&] { display.ClearAll(); })) succeeded = false;
                if (!attempt([&] { display.Update(); })) succeeded = false;
            }
        }
        // Keep failed native registrations and their exact owner alive on close0. In
        // particular IMMDeviceEnumerator does not own the caller's callback lifetime.
        if (succeeded) {
            OutputNotifications::retainUntilProcessExit(outputNotifications.detach());
            if (!attempt([&] { devices = nullptr; })) succeeded = false;
            if (!attempt([&] { controlEvents = nullptr; })) succeeded = false;
            if (!attempt([&] { extendedControls = nullptr; })) succeeded = false;
            if (!attempt([&] { controls = nullptr; })) succeeded = false;
        }
        return succeeded;
    }

    bool requestStop() noexcept {
        retiring = true;
        healthy = false;
        routes->retire();
        const bool requested = attempt([&] {
            std::lock_guard<std::mutex> lock(mutex);
            stopping = true;
            while (!commands.empty()) commands.pop();
        });
        routes->available.notify_all();
        return requested;
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
        // Native callbacks cannot reach JNA during partially owned initialization, and a
        // queued projection finishing after retirement can never reopen admission.
        callback->activate();
    }
public:
    // Construction cannot register callbacks or start a thread. Once start() is called,
    // even partial initialization must return this owned handle to the host, not throw it away.
    explicit Session(VeyraCommandCallback cb) : callback(std::make_shared<CallbackState>(cb)), routes(std::make_shared<RouteInbox>()) {}
    void start(HWND window) noexcept {
        if (!attempt([&] {
            std::promise<bool> ready;
            auto result = ready.get_future();
            owner = std::thread([this, window, ready = std::move(ready)]() mutable noexcept {
                bool initialized = false;
                bool announced = false;
                try {
                    init_apartment(apartment_type::multi_threaded);
                    initialized = true;
                    initialize(window);
                    healthy = true;
                    ready.set_value(true);
                    announced = true;
                    for (;;) {
                        std::function<void()> next;
                        {
                            std::unique_lock<std::mutex> lock(mutex);
                            // Route producers cannot take this mutex. A bounded timed wake
                            // also covers notification arrival between predicate and wait.
                            routes->available.wait_for(lock, std::chrono::milliseconds(50), [this] {
                                return stopping || retiring || !commands.empty() || (callback->active() && routes->pending());
                            });
                            if (stopping || retiring) break;
                            if (!commands.empty()) { next = std::move(commands.front()); commands.pop(); }
                        }
                        // An asynchronous projection failure closes native admission rather
                        // than leaving commands active while the host reports unavailable.
                        if (next && !attempt([&] { next(); })) { healthy = false; callback->retire(); break; }
                        if (callback->active() && routes->pending() && !attempt([&] {
                            if (routes->consume()) callback->send(VEYRA_OUTPUT_CHANGED);
                        })) {
                            // Even an exceptional mailbox read cannot silently lose a route
                            // loss; request pause before retiring the disconnected session.
                            callback->send(VEYRA_OUTPUT_CHANGED);
                            healthy = false; callback->retire(); break;
                        }
                    }
                } catch (...) {
                    healthy = false;
                    callback->retire();
                    if (!announced) attempt([&] { ready.set_value(false); });
                }
                healthy = false;
                bool succeeded = dispose();
                if (initialized && !attempt([] { uninit_apartment(); })) succeeded = false;
                cleanupSucceeded = succeeded;
            });
            // Failure still leaves a valid, inactive owned handle for the host's first
            // update0 -> close/retain path. The worker owns any partial native rollback.
            if (!result.get()) callback->retire();
        })) {
            callback->retire();
            requestStop();
        }
    }
    bool submit(Update value) {
        std::lock_guard<std::mutex> lock(mutex);
        if (stopping || retiring || !healthy) return false;
        // Metadata/position are projections; retain only the latest pending update.
        while (!commands.empty()) commands.pop();
        commands.push([this, value = std::move(value)] { update(value); });
        routes->available.notify_one();
        return true;
    }
    void failProjection() noexcept { callback->retire(); requestStop(); }
    bool close() noexcept {
        if (closeAttempted.exchange(true)) return false;
        callback->retire();
        const bool reentrant = callback->onCurrentThread() || owner.get_id() == std::this_thread::get_id();
        const bool requested = requestStop();
        if (reentrant) return false;
        const bool drained = callback->clear();
        if (!requested || !drained) return false;
        if (!attempt([&] {
            if (owner.joinable()) owner.join();
            else cleanupSucceeded = dispose(); // start failed before creating a worker.
        })) return false;
        return cleanupSucceeded;
    }
    // Only close1 may delete the object: its worker is joined and native references cleared.
    ~Session() noexcept = default;
};
}

int veyra_session_abi_version() { return 2; }
void* veyra_session_create(uint64_t window, VeyraCommandCallback callback) {
    if (!window || !callback) return nullptr;
    try {
        auto* session = new Session(callback);
        session->start(reinterpret_cast<HWND>(static_cast<uintptr_t>(window)));
        return session;
    } catch (...) { return nullptr; } // Construction has not registered native callbacks.
}
int veyra_session_update(void* session, const char* title, const char* artist, const char* album,
    const char* artwork, const char* entry, int64_t duration, int64_t position, double rate, int status, uint32_t capabilities) {
    if (!session) return 0;
    auto* value = static_cast<Session*>(session);
    try {
        if (value->submit({title ? title : "", artist ? artist : "", album ? album : "",
            artwork ? artwork : "", entry ? entry : "", duration, position, rate, status, capabilities})) return 1;
    } catch (...) { /* Even failure while copying the projection must close admission. */ }
    value->failProjection();
    return 0;
}
int veyra_session_close(void* session) {
    if (!session) return 1;
    try {
        auto* value = static_cast<Session*>(session);
        if (!value->close()) return 0;
        delete value;
        return 1;
    } catch (...) { return 0; }
}
