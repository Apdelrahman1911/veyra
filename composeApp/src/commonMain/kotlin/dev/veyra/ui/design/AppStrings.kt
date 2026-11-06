package dev.veyra.ui.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import dev.veyra.model.AppFailure
import dev.veyra.model.DownloadStatus
import dev.veyra.model.FailureCode
import dev.veyra.model.LanguagePreference
import dev.veyra.model.SourceProvider
import dev.veyra.model.ThemeMode

/**
 * The complete shared UI catalogue. Metadata is never translated by this catalogue.
 * Each typed key must supply both languages; backend prose is not a resource key.
 * Arabic copy is intentionally kept central for subsequent native-language review.
 */
enum class TextKey(internal val english: String, internal val arabic: String) {
    AppName("Veyra", "ڤيرا"),
    Tagline("Your listening room", "مساحتك للاستماع"),
    Listen("Listen", "استمع"),
    Search("Search", "بحث"),
    Podcasts("Podcasts", "بودكاست"),
    Library("Library", "المكتبة"),
    Music("Music", "موسيقى"),
    Settings("Settings", "الإعدادات"),
    Navigation("Navigation", "التنقل"),
    OpenNavigation("Open navigation", "فتح قائمة التنقل"),
    CloseNavigation("Close navigation", "إغلاق قائمة التنقل"),
    All("All", "الكل"),
    Back("Back", "رجوع"),
    Close("Close", "إغلاق"),
    Cancel("Cancel", "إلغاء"),
    Confirm("Confirm", "تأكيد"),
    Done("Done", "تم"),
    Save("Save", "حفظ"),
    Add("Add", "إضافة"),
    Remove("Remove", "إزالة"),
    Delete("Delete", "حذف"),
    Edit("Edit", "تعديل"),
    Rename("Rename", "إعادة التسمية"),
    Refresh("Refresh", "تحديث"),
    Retry("Retry", "إعادة المحاولة"),
    Undo("Undo", "تراجع"),
    UndoQueueEdit("Undo queue change", "التراجع عن تغيير قائمة الانتظار"),
    UndoLibraryEdit("Undo library change", "التراجع عن تغيير المكتبة"),
    Details("Details", "التفاصيل"),
    MoreOptions("More options", "المزيد من الخيارات"),
    MoreActions("More actions", "المزيد من الإجراءات"),
    MoreActionsFor("More actions for {0}", "المزيد من الإجراءات لـ {0}"),
    Apply("Apply", "تطبيق"),
    Reset("Reset", "إعادة الضبط"),
    Select("Select", "تحديد"),
    SelectAll("Select all", "تحديد الكل"),
    ClearSelection("Clear selection", "إلغاء التحديد"),
    SelectedCount("{0} selected", "المحدد: {0}"),
    Loading("Loading…", "جارٍ التحميل…"),
    LoadMore("Load more", "تحميل المزيد"),
    Updating("Updating…", "جارٍ التحديث…"),
    Unavailable("Unavailable", "غير متاح"),
    Unknown("Unknown", "غير معروف"),
    InformationUnavailable("Information unavailable", "المعلومات غير متاحة"),
    ContinueListening("Continue listening", "متابعة الاستماع"),
    ContinueListeningDescription("Pick up an unfinished episode from your saved position.", "تابع حلقة غير مكتملة من موضع استماعك المحفوظ."),
    RecentlyPlayed("Recently played", "استُمِع إليه مؤخرًا"),
    DiscoverMusic("Find your next listen", "اكتشف ما تستمع إليه تاليًا"),
    OpenMusic("Open music", "موسيقى مفتوحة الترخيص"),
    OpenMusicDescription("Explore openly licensed listings. Their licences do not automatically apply to the audio found through kmpYT.", "استكشف محتوى مدرجًا بتراخيص مفتوحة. لا تنطبق هذه التراخيص تلقائيًا على الصوت الذي يعثر عليه kmpYT."),
    YouTube("YouTube", "YouTube"),
    LocalLibrary("Local library", "المكتبة المحلية"),
    LocalFiles("Local files", "الملفات المحلية"),
    Everywhere("Everywhere", "كل المصادر"),
    YourLibrary("Your library", "مكتبتك"),
    SearchHint("Songs, artists, podcasts…", "أغانٍ، فنانون، بودكاست…"),
    SearchMusic("Search music", "البحث عن موسيقى"),
    SearchResults("Search results", "نتائج البحث"),
    SearchSource("Search source", "مصدر البحث"),
    SearchDescription("Search your library or choose an online source.", "ابحث في مكتبتك أو اختر مصدرًا عبر الإنترنت."),
    SearchOnline("Search online", "البحث عبر الإنترنت"),
    SearchLocal("Search on this device", "البحث على هذا الجهاز"),
    SearchUnavailable("Online search is unavailable. Your local library is still here.", "البحث عبر الإنترنت غير متاح. ما زالت مكتبتك المحلية متاحة."),
    SearchingFor("Searching for {0}", "جارٍ البحث عن {0}"),
    ResultsFor("Results for {0}", "نتائج البحث عن {0}"),
    ResultsCount("{0} results", "النتائج: {0}"),
    NoResults("No results", "لا توجد نتائج"),
    NoResultsDescription("Try another search or clear your filters.", "جرّب كلمات أخرى أو أزل عوامل التصفية."),
    ClearSearch("Clear search", "مسح البحث"),
    ClearFilters("Clear filters", "إزالة عوامل التصفية"),
    Filters("Filters", "عوامل التصفية"),
    Sort("Sort", "الترتيب"),
    SortByName("Name", "الاسم"),
    SortByRecent("Recently added", "المضاف مؤخرًا"),
    SortByCreator("Creator", "صاحب العمل"),
    Songs("Songs", "الأغاني"),
    Albums("Albums", "الألبومات"),
    Artists("Artists", "الفنانون"),
    Album("Album", "الألبوم"),
    Artist("Artist", "الفنان"),
    TrackDetails("Track details", "تفاصيل المقطع"),
    AboutTrack("About this track", "حول هذا المقطع"),
    Favorites("Favorites", "المفضلة"),
    Favorite("Add to favorites", "إضافة إلى المفضلة"),
    Unfavorite("Remove from favorites", "إزالة من المفضلة"),
    Saved("Saved", "محفوظ"),
    Playlists("Playlists", "قوائم التشغيل"),
    Downloads("Downloads", "التنزيلات"),
    OnThisDevice("On this device", "على هذا الجهاز"),
    ImportAudio("Import audio", "استيراد ملفات صوتية"),
    ImportDescription("Copy audio you own or may use into Veyra's local storage.", "انسخ ملفات صوتية تملكها أو يُسمح لك باستخدامها إلى تخزين ڤيرا المحلي."),
    Importing("Importing audio…", "جارٍ استيراد الملفات الصوتية…"),
    ImportComplete("Audio imported", "تم استيراد الملفات الصوتية"),
    ImportFailed("Some files could not be imported.", "تعذّر استيراد بعض الملفات."),
    EmptyLibrary("Your library starts here", "ابدأ بناء مكتبتك"),
    EmptyLibraryDescription("Find something to save, or import your own audio.", "ابحث عمّا تريد حفظه، أو استورد ملفاتك الصوتية."),
    EmptyFavorites("No favorites yet", "لا توجد مفضلة بعد"),
    EmptyFavoritesDescription("Add music to favorites to find it here.", "أضف الموسيقى إلى المفضلة لتجدها هنا."),
    AddMusic("Add music", "إضافة موسيقى"),
    NewPlaylist("New playlist", "قائمة تشغيل جديدة"),
    PlaylistName("Playlist name", "اسم قائمة التشغيل"),
    CreatePlaylist("Create playlist", "إنشاء قائمة تشغيل"),
    RenamePlaylist("Rename playlist", "إعادة تسمية قائمة التشغيل"),
    DeletePlaylist("Delete playlist", "حذف قائمة التشغيل"),
    ConfirmDeletePlaylist("Delete {0}? Your audio files and favorites will stay.", "هل تريد حذف {0}؟ ستبقى ملفاتك الصوتية والمفضلة."),
    RemoveFromPlaylist("Remove from playlist", "إزالة من قائمة التشغيل"),
    AddToPlaylist("Add to playlist", "إضافة إلى قائمة تشغيل"),
    NoPlaylists("No playlists yet", "لا توجد قوائم تشغيل بعد"),
    NoPlaylistsDescription("Create a playlist for the music you want together.", "أنشئ قائمة تجمع الموسيقى التي تريدها."),
    EmptyPlaylist("This playlist is empty", "قائمة التشغيل فارغة"),
    EmptyPlaylistDescription("Add music from Search or your library.", "أضف موسيقى من البحث أو مكتبتك."),
    TrackCount("{0} tracks", "المقاطع: {0}"),
    TrackCountDuration("{0} tracks · {1}", "المقاطع: {0} · {1}"),
    PlaylistUpdated("Playlist updated", "تم تحديث قائمة التشغيل"),
    SavedPlaylist("Playlist saved", "تم حفظ قائمة التشغيل"),
    PlaylistMissing("This playlist is no longer available.", "قائمة التشغيل هذه لم تعد متاحة."),
    DuplicateTrack("This track is already in the playlist.", "هذا المقطع موجود بالفعل في قائمة التشغيل."),
    AddAgain("Add again", "إضافة مرة أخرى"),
    AlreadyAdded("Already added", "مضاف بالفعل"),
    Play("Play", "تشغيل"),
    PlayAll("Play all", "تشغيل الكل"),
    AddAllToQueue("Add all to queue", "إضافة الكل إلى قائمة الانتظار"),
    FavoriteAllTracks("Add all tracks to favorites", "إضافة كل المقاطع إلى المفضلة"),
    UnfavoriteAllTracks("Remove all tracks from favorites", "إزالة كل المقاطع من المفضلة"),
    DownloadAll("Download all", "تنزيل الكل"),
    CollectionEmpty("This collection is empty", "هذه المجموعة فارغة"),
    CollectionLoadingRemaining("Load the remaining tracks to use whole-collection actions.", "حمّل المقاطع المتبقية لاستخدام إجراءات المجموعة كاملةً."),
    Pause("Pause", "إيقاف مؤقت"),
    Resume("Resume", "متابعة الاستماع"),
    ResumeAt("Resume at {0}", "متابعة الاستماع من {0}"),
    Stop("Stop", "إيقاف"),
    Replay("Replay", "إعادة التشغيل"),
    PlayItem("Play {0}", "تشغيل {0}"),
    PauseItem("Pause {0}", "إيقاف {0} مؤقتًا"),
    NowPlaying("Now playing", "قيد التشغيل"),
    NothingPlaying("Nothing playing", "لا يوجد شيء قيد التشغيل"),
    NothingPlayingDescription("Choose something to listen to. Your player will stay with you.", "اختر ما تريد الاستماع إليه. سيبقى المشغّل متاحًا أثناء التنقل."),
    OpenPlayer("Open player", "فتح المشغّل"),
    ClosePlayer("Close player", "إغلاق المشغّل"),
    Previous("Previous track", "المقطع السابق"),
    Next("Next track", "المقطع التالي"),
    SkipBack("Back 15 seconds", "رجوع 15 ثانية"),
    SkipForward("Forward 30 seconds", "تقديم 30 ثانية"),
    SkipBackSeconds("Back {0} seconds", "رجوع {0} ثانية"),
    SkipForwardSeconds("Forward {0} seconds", "تقديم {0} ثانية"),
    NextEpisode("Next episode", "الحلقة التالية"),
    PreparingAudio("Preparing audio…", "جارٍ تجهيز الصوت…"),
    Buffering("Buffering…", "جارٍ التخزين المؤقت…"),
    Playing("Playing", "قيد التشغيل"),
    Paused("Paused", "متوقف مؤقتًا"),
    PlaybackEnded("Playback finished", "انتهى التشغيل"),
    PlaybackInterrupted("Playback interrupted. Your position is saved.", "توقف التشغيل. تم حفظ موضع استماعك."),
    PlaybackUnavailable("Playback is unavailable for this item.", "التشغيل غير متاح لهذا العنصر."),
    NoPlayableItems("No playable items are available here.", "لا توجد هنا عناصر متاحة للتشغيل."),
    Seek("Playback position", "موضع التشغيل"),
    SeekTo("Seek to {0}", "الانتقال إلى {0}"),
    Elapsed("Elapsed", "الوقت المنقضي"),
    Duration("Duration", "المدة"),
    Remaining("Remaining", "الوقت المتبقي"),
    UnknownDuration("Duration unknown", "المدة غير معروفة"),
    Live("Live", "مباشر"),
    NotSeekable("Seeking is not available for this audio.", "الانتقال إلى موضع محدد غير متاح لهذا الصوت."),
    Volume("Volume", "مستوى الصوت"),
    Mute("Mute", "كتم الصوت"),
    Unmute("Unmute", "إلغاء كتم الصوت"),
    Shuffle("Shuffle", "تشغيل عشوائي"),
    ShuffleOn("Shuffle on", "التشغيل العشوائي مفعّل"),
    ShuffleOff("Shuffle off", "التشغيل العشوائي متوقف"),
    Repeat("Repeat", "تكرار"),
    RepeatOff("Repeat off", "التكرار متوقف"),
    RepeatAll("Repeat all", "تكرار الكل"),
    RepeatOne("Repeat one", "تكرار مقطع واحد"),
    Queue("Queue", "قائمة الانتظار"),
    ShowQueue("Show queue", "عرض قائمة الانتظار"),
    MusicQueue("Music queue", "قائمة انتظار الموسيقى"),
    PodcastQueue("Podcast queue", "قائمة انتظار البودكاست"),
    ViewingQueue("Viewing {0}", "عرض {0}"),
    ActiveQueue("Playing from {0}", "التشغيل من {0}"),
    CurrentItem("Current item", "العنصر الحالي"),
    Upcoming("Up next", "التالي في قائمة الانتظار"),
    QueueEmpty("Your queue is clear", "قائمة الانتظار فارغة"),
    QueueEmptyDescription("Add something to play next. The current item can keep playing.", "أضف ما تريد تشغيله تاليًا. يمكن أن يستمر تشغيل العنصر الحالي."),
    PlayNext("Play next", "تشغيل تاليًا"),
    AddToEnd("Add to end", "إضافة إلى نهاية القائمة"),
    InQueue("In queue", "في قائمة الانتظار"),
    RemoveFromQueue("Remove from queue", "إزالة من قائمة الانتظار"),
    ClearUpcoming("Clear upcoming", "إفراغ العناصر التالية"),
    ConfirmClearQueue("Clear upcoming items? The current item will keep playing.", "هل تريد إفراغ العناصر التالية؟ سيستمر تشغيل العنصر الحالي."),
    ReplaceQueueAndPlay("Replace queue and play", "استبدال قائمة الانتظار والتشغيل"),
    ConfirmReplaceQueue("This replaces upcoming items in this queue. The other queue is kept.", "سيؤدي هذا إلى استبدال العناصر التالية في هذه القائمة. ستبقى القائمة الأخرى."),
    MoveUp("Move up", "نقل إلى أعلى"),
    MoveDown("Move down", "نقل إلى أسفل"),
    MoveToTop("Move to top", "نقل إلى بداية القائمة"),
    MoveToPosition("Move to position", "نقل إلى الموضع"),
    MoveEntryUp("Move {0} up", "نقل {0} إلى أعلى"),
    MoveEntryDown("Move {0} down", "نقل {0} إلى أسفل"),
    MovedToPosition("Moved to position {0}", "تم النقل إلى الموضع {0}"),
    ReorderTurnsOffShuffle("Reordering turns shuffle off and keeps this order.", "إعادة الترتيب توقف التشغيل العشوائي وتحفظ هذا الترتيب."),
    PlayNextDownloaded("Play next downloaded item", "تشغيل العنصر المنزّل التالي"),
    SkipUnavailable("Skip unavailable items this session", "تخطي العناصر غير المتاحة في هذه الجلسة"),
    Follow("Follow", "متابعة"),
    Unfollow("Unfollow", "إلغاء المتابعة"),
    Following("Following", "تتابعها"),
    NewEpisodes("New episodes", "حلقات جديدة"),
    NewEpisode("New episode", "حلقة جديدة"),
    Episodes("Episodes", "الحلقات"),
    FindPodcasts("Find podcasts", "البحث عن بودكاست"),
    AddRssFeed("Add RSS feed", "إضافة موجز RSS"),
    FeedUrl("Feed URL", "رابط الموجز"),
    FeedUrlHint("https://example.org/podcast.xml", "https://example.org/podcast.xml"),
    AddFeed("Add feed", "إضافة الموجز"),
    AddFeedDescription("Paste a podcast's RSS feed link. Check the show and publisher before following.", "ألصق رابط موجز RSS للبودكاست. تحقّق من البرنامج والناشر قبل المتابعة."),
    FollowLocalDescription("Your follows and listening progress stay on this device. Following does not download episodes.", "تُحفظ متابعاتك وتقدّم استماعك على هذا الجهاز. المتابعة لا تنزّل الحلقات."),
    NoFollowedShows("Find a show to follow", "ابحث عن برنامج لمتابعته"),
    NoFollowedShowsDescription("Search for a podcast or add its RSS feed. No Veyra account needed.", "ابحث عن بودكاست أو أضف موجز RSS الخاص به. لا تحتاج إلى حساب في ڤيرا."),
    NothingInProgress("A fresh start", "بداية جديدة"),
    NothingInProgressDescription("Unfinished episodes will appear here when you start listening.", "ستظهر هنا الحلقات غير المكتملة عند بدء الاستماع."),
    NoNewEpisodes("You're caught up", "أنت مطّلع على آخر الحلقات"),
    NoNewEpisodesDescription("New episodes from shows you follow will appear here.", "ستظهر هنا الحلقات الجديدة من البرامج التي تتابعها."),
    NoEpisodes("No episodes yet", "لا توجد حلقات بعد"),
    NoEpisodesDescription("This feed currently has no episodes.", "لا يحتوي هذا الموجز على حلقات حاليًا."),
    NoMatchingEpisodes("No episodes match these filters.", "لا توجد حلقات تطابق عوامل التصفية هذه."),
    SearchEpisodes("Search episodes", "البحث في الحلقات"),
    AllEpisodes("All episodes", "كل الحلقات"),
    Unplayed("Unplayed", "لم يكتمل الاستماع إليها"),
    InProgress("In progress", "قيد الاستماع"),
    Played("Played", "تم الاستماع إليها"),
    New("New", "جديد"),
    NewestFirst("Newest first", "الأحدث أولًا"),
    OldestFirst("Oldest first", "الأقدم أولًا"),
    EpisodeDetails("Episode details", "تفاصيل الحلقة"),
    ShowDetails("Show details", "تفاصيل البرنامج"),
    EpisodeCount("{0} episodes", "الحلقات: {0}"),
    ShowCount("{0} shows", "البرامج: {0}"),
    EpisodeNotes("Episode notes", "ملاحظات الحلقة"),
    ShowDescription("About this show", "حول هذا البرنامج"),
    ShowMore("Show more", "عرض المزيد"),
    ShowLess("Show less", "عرض أقل"),
    Explicit("Explicit content", "محتوى صريح"),
    PublishedOn("Published {0}", "تاريخ النشر: {0}"),
    Publisher("Publisher", "الناشر"),
    StartOver("Start over", "البدء من جديد"),
    StartOverConfirm("Start this episode from the beginning? Your previous position can be restored with Undo.", "هل تريد بدء الحلقة من أولها؟ يمكنك استعادة الموضع السابق باستخدام التراجع."),
    PodcastProgressRecordingChanged("Saved position: {0}, from a previous recording. This recording will play from the beginning.", "موضع الاستماع المحفوظ: {0}، من تسجيل سابق. سيبدأ تشغيل هذا التسجيل من البداية."),
    PodcastProgressRecordingUnverified("Saved position: {0}. Playback resumes there only if the selected recording matches.", "موضع الاستماع المحفوظ: {0}. يُستأنف التشغيل من هذا الموضع فقط إذا تطابق التسجيل المختار مع التسجيل السابق."),
    MarkPlayed("Mark as played", "تحديد كمستمَع إليها"),
    MarkUnplayed("Mark as unplayed", "تحديد كغير مستمَع إليها"),
    SaveForLater("Save for later", "حفظ لوقت لاحق"),
    SavedForLater("Saved for later", "محفوظة لوقت لاحق"),
    Unsave("Remove from saved", "إزالة من المحفوظات"),
    OpenPublisher("Open publisher page", "فتح صفحة الناشر"),
    LastUpdated("Updated {0}", "آخر تحديث: {0}"),
    StaleFeed("Showing the last saved feed. Refresh when connected.", "يُعرض آخر موجز محفوظ. حدّثه عند الاتصال."),
    FeedRefreshFailed("Couldn't refresh this feed. Saved episodes are still available.", "تعذّر تحديث هذا الموجز. ما زالت الحلقات المحفوظة متاحة."),
    EpisodeUnavailable("This episode's audio is unavailable. Your notes and progress are kept.", "صوت هذه الحلقة غير متاح. ستبقى الملاحظات وتقدّم الاستماع محفوظة."),
    MediaRemaining("{0} of audio remaining", "متبقٍ من الصوت: {0}"),
    ListeningTimeRemaining("About {0} of listening at this speed", "نحو {0} من الاستماع بهذه السرعة"),
    NoLongerInFeed("No longer listed in this feed", "لم تعد مدرجة في هذا الموجز"),
    FeedInvalid("This link did not return a valid podcast feed. Check the URL and try again.", "لم يُرجع هذا الرابط موجز بودكاست صالحًا. تحقّق من الرابط وحاول مجددًا."),
    FeedPrivateWarning("A private feed link may contain credentials. Do not share it or send it to a directory.", "قد يحتوي رابط الموجز الخاص على بيانات دخول. لا تشاركه أو ترسله إلى دليل بحث."),
    FeedAlreadyAdded("This feed is already in your library.", "هذا الموجز موجود بالفعل في مكتبتك."),
    PlaybackSpeed("Playback speed", "سرعة التشغيل"),
    SpeedValue("{0}× speed", "سرعة {0}×"),
    ResetSpeed("Reset to 1×", "إعادة السرعة إلى 1×"),
    ThisShow("This show", "هذا البرنامج"),
    AllPodcasts("All podcasts", "كل برامج البودكاست"),
    SpeedScope("Apply speed to", "تطبيق السرعة على"),
    SkipSettings("Skip controls", "إعدادات التقديم والرجوع"),
    BackwardSkip("Skip back", "الرجوع"),
    ForwardSkip("Skip forward", "التقديم"),
    SleepTimer("Sleep timer", "مؤقّت النوم"),
    SetTimer("Set timer", "ضبط المؤقّت"),
    TimerOff("Timer off", "المؤقّت متوقف"),
    TimerActive("Timer: {0}", "المؤقّت: {0}"),
    TimerActiveDescription("The sleep timer follows the active player, including music.", "يعمل مؤقّت النوم مع المشغّل النشط، بما في ذلك الموسيقى."),
    EndOfEpisode("End of this episode", "نهاية هذه الحلقة"),
    TimerEndsWithEpisode("Pauses at the end of this episode", "يتوقف مؤقتًا عند نهاية هذه الحلقة"),
    CustomDuration("Custom duration", "مدة مخصصة"),
    Minutes("{0} minutes", "الدقائق: {0}"),
    Seconds("{0} seconds", "الثواني: {0}"),
    Hours("{0} hours", "الساعات: {0}"),
    CancelTimer("Cancel timer", "إلغاء المؤقّت"),
    ClearTimer("Clear timer", "إلغاء المؤقّت"),
    ExtendTimer("Extend timer", "تمديد المؤقّت"),
    TimerExpired("Sleep timer finished. Playback paused.", "انتهى مؤقّت النوم. توقف التشغيل مؤقتًا."),
    EpisodeTimerCancelled("Episode timer cancelled because the episode changed.", "أُلغي مؤقّت الحلقة لأن الحلقة تغيّرت."),
    InvalidTimer("Enter a valid duration in minutes.", "أدخل مدة صالحة بالدقائق."),
    TimerDurationHint("1–1,440 minutes", "من دقيقة واحدة إلى 1,440 دقيقة"),
    Crossfade("Crossfade", "تداخل المقاطع"),
    CrossfadeDescription("Blend the end of one music track into the next. Podcasts are never crossfaded.", "امزج نهاية مقطع موسيقي ببداية المقطع التالي. لا يُطبّق التداخل على البودكاست."),
    CrossfadeMusicOnly("Music only", "للموسيقى فقط"),
    CrossfadeEnabled("Crossfade enabled for music", "تداخل المقاطع مفعّل للموسيقى"),
    CrossfadeDisabled("Crossfade off", "تداخل المقاطع متوقف"),
    CrossfadeDuration("Crossfade duration", "مدة تداخل المقاطع"),
    CrossfadeDurationValue("{0} seconds", "المدة بالثواني: {0}"),
    CustomCrossfadeDuration("Custom crossfade duration", "مدة مخصصة لتداخل المقاطع"),
    CrossfadeDurationHint("Enter a supported duration in seconds.", "أدخل مدة مدعومة بالثواني."),
    CrossfadeDurationInvalid("Enter a valid crossfade duration in seconds.", "أدخل مدة صالحة لتداخل المقاطع بالثواني."),
    CrossfadeHeadroom("Crossfade headroom", "الهامش الصوتي لتداخل المقاطع"),
    CrossfadeHeadroomDescription("Music uses a constant -3.5 dB master level to leave headroom, whether crossfade is on or off. Podcasts are unchanged.", "يُخفَّض مستوى الموسيقى باستمرار بمقدار 3.5 ديسيبل لترك هامش صوتي، سواء كان تداخل المقاطع مفعّلًا أم متوقفًا. لا يتغيّر صوت البودكاست."),
    CrossfadeHeadroomValue("Headroom: {0} dB", "الهامش الصوتي: {0} ديسيبل"),
    CrossfadeStatus("Crossfade status", "حالة تداخل المقاطع"),
    CrossfadePreparing("Preparing next track…", "جارٍ تجهيز المقطع التالي…"),
    CrossfadeReady("Next track ready for crossfade", "المقطع التالي جاهز للتداخل"),
    CrossfadeActive("Crossfading", "جارٍ تداخل المقاطع"),
    CrossfadeNextTrack("Incoming track: {0}", "المقطع القادم: {0}"),
    CrossfadeProgress("Crossfade progress: {0}%", "تقدّم التداخل: {0}%"),
    CrossfadeUnavailable("Crossfade is unavailable for this transition.", "تداخل المقاطع غير متاح لهذا الانتقال."),
    CrossfadeUnsupported("Crossfade is not available on this device.", "تداخل المقاطع غير متاح على هذا الجهاز."),
    CrossfadeFallback("Playing without crossfade", "التشغيل دون تداخل المقاطع"),
    Download("Download", "تنزيل"),
    NotDownloaded("Not downloaded", "غير منزّل"),
    DownloadQueued("Queued for download", "في انتظار التنزيل"),
    DownloadsQueued("Downloads queued", "التنزيلات في قائمة الانتظار"),
    DownloadResolving("Preparing download…", "جارٍ تجهيز التنزيل…"),
    Downloading("Downloading…", "جارٍ التنزيل…"),
    DownloadPaused("Download paused", "التنزيل متوقف مؤقتًا"),
    DownloadFailed("Download failed", "تعذّر التنزيل"),
    DownloadVerifying("Verifying downloaded file…", "جارٍ التحقق من الملف المنزّل…"),
    DownloadValidating("Validating downloaded audio…", "جارٍ التحقق من سلامة الصوت المنزّل…"),
    DownloadRetrying("Retrying download…", "جارٍ إعادة محاولة التنزيل…"),
    DownloadRunning("Downloading…", "جارٍ التنزيل…"),
    DownloadReady("Ready offline", "متاح دون اتصال"),
    DownloadNeedsForeground("Open Veyra to continue this download.", "افتح ڤيرا لمتابعة هذا التنزيل."),
    ForegroundRequired("Open Veyra to continue. Background execution is currently restricted.", "افتح ڤيرا للمتابعة. التشغيل في الخلفية مقيّد حاليًا."),
    Downloaded("Downloaded", "تم التنزيل"),
    DownloadCancelled("Download cancelled", "أُلغي التنزيل"),
    DownloadRetryAttempt("Retry attempt {0}", "محاولة إعادة التنزيل: {0}"),
    DownloadRetryAfter("Retrying in {0}", "إعادة المحاولة بعد {0}"),
    DownloadValidationFailed("The downloaded audio did not pass validation. It is not ready for offline playback.", "لم يجتز الصوت المنزّل التحقق من السلامة. ليس جاهزًا للتشغيل دون اتصال."),
    DownloadRemoving("Removing download…", "جارٍ إزالة التنزيل…"),
    DownloadComplete("Download ready offline", "التنزيل متاح دون اتصال"),
    DownloadCount("{0} downloads", "التنزيلات: {0}"),
    PauseDownload("Pause download", "إيقاف التنزيل مؤقتًا"),
    ResumeDownload("Resume download", "متابعة التنزيل"),
    CancelDownload("Cancel download", "إلغاء التنزيل"),
    RemoveDownload("Remove download", "إزالة التنزيل"),
    ConfirmRemoveDownload("Remove the downloaded file for {0}? Saved items and listening progress will stay.", "هل تريد إزالة الملف المنزّل لـ {0}؟ ستبقى العناصر المحفوظة وتقدّم الاستماع."),
    RemoveDownloadConfirm("Remove this downloaded file? Saved items and listening progress will stay.", "هل تريد إزالة هذا الملف المنزّل؟ ستبقى العناصر المحفوظة وتقدّم الاستماع."),
    RemoveAllDownloads("Remove all downloads", "إزالة كل التنزيلات"),
    ConfirmRemoveAllDownloads("Remove all downloaded audio? You will need a connection to download it again.", "هل تريد إزالة كل الملفات الصوتية المنزّلة؟ ستحتاج إلى اتصال لتنزيلها مجددًا."),
    EmptyDownloads("Take your listening with you", "استمع أينما ذهبت"),
    EmptyDownloadsDescription("Download available audio to listen without a connection.", "نزّل الصوت المتاح للاستماع دون اتصال."),
    ReadyOffline("Ready offline", "متاح دون اتصال"),
    WaitingForNetwork("Waiting for a connection", "في انتظار الاتصال"),
    WaitingForWifi("Waiting for Wi-Fi", "في انتظار شبكة Wi-Fi"),
    UseMobileData("Use mobile data", "استخدام بيانات الهاتف"),
    NeedsAttention("Needs attention", "تحتاج إلى مراجعة"),
    Completed("Completed", "مكتملة"),
    DownloadProgress("Downloaded {0} of {1}", "تم تنزيل {0} من {1}"),
    DownloadProgressUnknown("Downloaded {0}", "تم تنزيل {0}"),
    EstimatedSize("Estimated size: {0}", "الحجم التقريبي: {0}"),
    StorageUsed("Storage used: {0}", "التخزين المستخدم: {0}"),
    Bytes("{0} bytes", "بايت: {0}"),
    ByteSizeValue("{0} {1}", "{0} {1}"),
    ByteUnit("B", "بايت"),
    KibibyteUnit("KiB", "KiB"),
    MebibyteUnit("MiB", "MiB"),
    GibibyteUnit("GiB", "GiB"),
    TebibyteUnit("TiB", "TiB"),
    ManageStorage("Manage storage", "إدارة التخزين"),
    Storage("Storage", "التخزين"),
    FileUnavailable("The local file is missing or inaccessible. Your library entry is kept.", "الملف المحلي مفقود أو يتعذّر الوصول إليه. سيبقى العنصر في المكتبة."),
    Source("Source", "المصدر"),
    SourceAttribution("Source: {0}", "المصدر: {0}"),
    OriginalMetadataSource("Original listing: {0}", "مصدر المعلومات الأصلي: {0}"),
    AudioSource("Audio source", "مصدر الصوت"),
    AudioSourceValue("Audio source: {0}", "مصدر الصوت: {0}"),
    CurrentAudioResolution("Current audio", "الصوت الحالي"),
    IncomingAudioResolution("Incoming audio", "الصوت القادم"),
    DownloadedAudioResolution("Downloaded audio", "الصوت المنزّل"),
    PreviousAudioResolution("Previous search result", "نتيجة البحث السابقة"),
    RequestedTitle("Requested title", "العنوان المطلوب"),
    RequestedTitleValue("Requested: {0}", "المطلوب: {0}"),
    ResolvedTitle("Resolved title", "عنوان نتيجة البحث"),
    ResolvedTitleValue("Resolved: {0}", "نتيجة البحث: {0}"),
    FirstSearchResult("First search result", "نتيجة البحث الأولى"),
    ResolvingTitle("Searching for the complete title…", "جارٍ البحث باستخدام العنوان الكامل…"),
    ResolvedByTitle("Resolved using the complete title", "تم العثور عليه باستخدام العنوان الكامل"),
    SourceResolutionDescription("Playback and downloads use the first kmpYT search result for the complete title. It may be a different recording or episode from the item you selected.", "يستخدم التشغيل والتنزيل نتيجة البحث الأولى من kmpYT للعنوان الكامل. قد تكون تسجيلًا أو حلقة مختلفة عن العنصر الذي اخترته."),
    SourceMismatchNotice("The first result may differ from your selection. Check its resolved title and audio source before listening.", "قد تختلف النتيجة الأولى عن اختيارك. تحقّق من عنوان نتيجة البحث ومصدر الصوت قبل الاستماع."),
    SourceMismatchDetected("The resolved title differs from your selection. This is the first search result, not a verified recording match.", "يختلف عنوان نتيجة البحث عن اختيارك. هذه نتيجة البحث الأولى، ولم يُتحقَّق من تطابق التسجيل."),
    SourceMatchUnverified("Recording match not verified", "لم يُتحقَّق من تطابق التسجيل"),
    OriginalDescriptionNotice("These notes describe the original listing, not necessarily the resolved audio.", "تصف هذه الملاحظات المحتوى الأصلي، وليس بالضرورة الصوت الناتج عن البحث."),
    OriginalDuration("Listing duration: {0}", "مدة المحتوى الأصلي: {0}"),
    CurrentAudioDuration("Current audio duration: {0}", "مدة الصوت الحالي: {0}"),
    DownloadedAudioDuration("Downloaded audio duration: {0}", "مدة الصوت المنزّل: {0}"),
    NoTitleSearchResults("No result was found for the complete title.", "لم يُعثر على نتيجة للعنوان الكامل."),
    FirstResultUnavailable("The first search result has no usable audio. No later result or different audio source was selected.", "لا تحتوي نتيجة البحث الأولى على صوت قابل للاستخدام. لم تُحدَّد نتيجة لاحقة أو مصدر صوت آخر."),
    FirstResultRestricted("The first search result requires access that is unavailable here. No later result was selected.", "تتطلب نتيجة البحث الأولى صلاحية وصول غير متاحة هنا. لم تُحدَّد نتيجة لاحقة."),
    ByCreator("By {0}", "بواسطة {0}"),
    License("Licence", "الترخيص"),
    OriginalListingLicense("Original listing licence", "ترخيص المحتوى الأصلي"),
    ResolvedAudioRightsNotice("The original listing's licence does not establish the rights to the resolved audio.", "لا يثبت ترخيص المحتوى الأصلي حقوق استخدام الصوت الناتج عن البحث."),
    OpenSourcePage("Open source page", "فتح صفحة المصدر"),
    SourceRestricted("This source requires access that is not available here. Metadata may still be shown; playback is unavailable.", "يتطلب هذا المصدر صلاحية وصول غير متاحة هنا. قد تظهر المعلومات، لكن التشغيل غير متاح."),
    SourceUnavailable("This source is currently unavailable. Other sources and local files are unaffected.", "هذا المصدر غير متاح حاليًا. لا تتأثر المصادر الأخرى والملفات المحلية."),
    SourceMetadataOnly("Metadata only — audio unavailable", "معلومات فقط — الصوت غير متاح"),
    SourceRightsRequired("Downloading is not available for this source or recording.", "التنزيل غير متاح لهذا المصدر أو التسجيل."),
    Offline("Offline", "دون اتصال"),
    OfflineDescription("Online sources are unavailable. Your saved library and downloaded audio remain here.", "المصادر عبر الإنترنت غير متاحة. ما زالت مكتبتك المحفوظة والملفات المنزّلة هنا."),
    OfflineMode("Offline mode", "وضع عدم الاتصال"),
    OfflineModeDescription("Use local content only. No catalog, feed, artwork or audio network requests.", "استخدم المحتوى المحلي فقط، دون طلبات شبكة للفهرس أو الموجزات أو الصور أو الصوت."),
    OfflinePolicy("Offline mode is on. Connect and turn it off to use online sources.", "وضع عدم الاتصال مفعّل. اتصل بالشبكة وأوقفه لاستخدام المصادر عبر الإنترنت."),
    ConnectionUnavailable("Couldn't connect. Check your connection and try again.", "تعذّر الاتصال. تحقّق من اتصالك وحاول مجددًا."),
    RequestTimedOut("The request took too long. Try again.", "استغرق الطلب وقتًا طويلًا. حاول مجددًا."),
    RateLimited("This source is receiving too many requests. Wait before trying again.", "يتلقى هذا المصدر طلبات كثيرة. انتظر قبل المحاولة مجددًا."),
    RetryAfter("Try again in {0}", "حاول مجددًا بعد {0}"),
    NotFound("This item was not found. Your saved information is kept.", "لم يُعثر على هذا العنصر. ستبقى معلوماتك المحفوظة."),
    UnsupportedSource("This source is not supported.", "هذا المصدر غير مدعوم."),
    UnsupportedTransport("This stream type is not supported on this device.", "نوع البث هذا غير مدعوم على هذا الجهاز."),
    UnsupportedCodec("This audio format is not supported on this device.", "صيغة الصوت هذه غير مدعومة على هذا الجهاز."),
    PermissionDenied("Veyra cannot access this file or location. Check its permissions.", "يتعذّر على ڤيرا الوصول إلى هذا الملف أو الموقع. تحقّق من الأذونات."),
    StorageFull("There is not enough storage. Free some space and try again.", "لا توجد مساحة تخزين كافية. أفرغ بعض المساحة وحاول مجددًا."),
    StorageError("Couldn't save to this location. Check that it is available and writable.", "تعذّر الحفظ في هذا الموقع. تحقّق من توفره وإمكانية الكتابة فيه."),
    CorruptMedia("This file is incomplete or damaged. Download or import it again.", "هذا الملف غير مكتمل أو تالف. نزّله أو استورده مجددًا."),
    ParseFailure("The source returned unreadable data. Your saved information is kept.", "أرجع المصدر بيانات يتعذّر قراءتها. ستبقى معلوماتك المحفوظة."),
    RecoveryRequired("Saved data needs recovery. Keep your files and restore a valid backup before continuing.", "تحتاج البيانات المحفوظة إلى استعادة. احتفظ بملفاتك واستعد نسخة احتياطية سليمة قبل المتابعة."),
    RecoveryBackupPrompt("Restore the last valid backup? Your library will return to that backup, so later changes may be missing. Veyra keeps the damaged files and does not reset your library.", "هل تريد الاستعادة من آخر نسخة احتياطية سليمة؟ ستعود مكتبتك إلى حالة تلك النسخة، وقد لا تظهر التغييرات اللاحقة. سيحتفظ ڤيرا بالملفات التالفة ولن يعِد ضبط مكتبتك."),
    RecoveryRestoreBackup("Restore last valid backup", "استعادة آخر نسخة احتياطية سليمة"),
    FutureSchema("This library was saved by a newer Veyra version. Update the app; do not reset the library.", "حُفظت هذه المكتبة بإصدار أحدث من ڤيرا. حدّث التطبيق ولا تعِد ضبط المكتبة."),
    InvalidState("This action is not available in the current state.", "هذا الإجراء غير متاح في الحالة الحالية."),
    Cancelled("Cancelled", "أُلغي"),
    UnexpectedError("Something went wrong. Try again, or return to your library.", "حدث خطأ. حاول مجددًا أو عُد إلى مكتبتك."),
    ErrorTitle("Couldn't complete that", "تعذّر إكمال الإجراء"),
    Language("Language", "اللغة"),
    English("English", "English"),
    Arabic("العربية", "العربية"),
    SystemDefault("System default", "إعداد النظام"),
    Appearance("Appearance", "المظهر"),
    Theme("Theme", "السمة"),
    LightTheme("Light", "فاتح"),
    DarkTheme("Dark", "داكن"),
    TextSize("Text size", "حجم النص"),
    TextSizeValue("Text size: {0}%", "حجم النص: {0}%"),
    TextSizeDescription("Adjust interface text size. Your device's text-size settings also apply.", "اضبط حجم نص الواجهة. تُطبّق أيضًا إعدادات حجم النص على جهازك."),
    SmallerText("Smaller text", "نص أصغر"),
    LargerText("Larger text", "نص أكبر"),
    Playback("Playback", "التشغيل"),
    PlaybackTools("Playback tools", "أدوات التشغيل"),
    PlaybackToolsRequired("The first online play or audio download fetches the tools Veyra needs from their published release sources. Opening Veyra offline does not fetch them.", "عند أول تشغيل أو تنزيل للصوت عبر الإنترنت، يجلب ڤيرا الأدوات اللازمة من مصادر إصداراتها المنشورة. لا يجلبها عند فتح التطبيق دون اتصال."),
    PlaybackToolsPrepare("Prepare playback tools", "تجهيز أدوات التشغيل"),
    PlaybackToolsPreparing("Preparing playback tools…", "جارٍ تجهيز أدوات التشغيل…"),
    PlaybackToolsVerifying("Verifying playback tools…", "جارٍ التحقق من أدوات التشغيل…"),
    PlaybackToolsDownloading("Downloading playback tools…", "جارٍ تنزيل أدوات التشغيل…"),
    PlaybackToolsInstalling("Installing playback tools…", "جارٍ تثبيت أدوات التشغيل…"),
    PlaybackToolsReady("Playback tools are ready", "أدوات التشغيل جاهزة"),
    PlaybackToolsFailed("Could not prepare playback tools.", "تعذّر تجهيز أدوات التشغيل."),
    PlaybackToolsDownloadProgress("Tool download: {0} of {1}", "تنزيل الأدوات: {0} من {1}"),
    PlaybackToolsDownloadedBytes("Tool download: {0}", "تنزيل الأدوات: {0}"),
    PlaybackToolsPrivacy("These no-cost tools are software, not music or podcast downloads. Setup uses internet data.", "هذه الأدوات برامج مجانية، وليست تنزيلات للموسيقى أو البودكاست. يستهلك إعدادها بيانات الإنترنت."),
    PlaybackToolsComponent("Tool: {0}", "الأداة: {0}"),
    PlaybackToolNode("Node.js", "Node.js"),
    PlaybackToolChromium("Chromium", "Chromium"),
    PlaybackToolFfmpeg("FFmpeg", "FFmpeg"),
    PodcastDefaults("Podcast defaults", "إعدادات البودكاست الافتراضية"),
    DefaultPodcastSpeed("Default podcast speed", "سرعة البودكاست الافتراضية"),
    DefaultPodcastSpeedDescription("Used for shows without a saved speed preference. A show's saved speed takes priority.", "تُستخدم للبرامج التي لم تُحفظ لها سرعة مفضّلة. تكون الأولوية للسرعة المحفوظة للبرنامج."),
    SpeedPreferenceUnavailable("This device cannot use the saved speed. Choose a supported speed below.", "لا يمكن لهذا الجهاز استخدام السرعة المحفوظة. اختر سرعة مدعومة أدناه."),
    SkipBackSetting("Skip back interval", "مدة الرجوع"),
    SkipForwardSetting("Skip forward interval", "مدة التقديم"),
    SettingOn("On", "مفعّل"),
    SettingOff("Off", "متوقف"),
    CrossfadeSavedUnsupported("The saved crossfade preference is not active on this device. You can turn it off; enabling requires native support.", "تفضيل التداخل المحفوظ غير نشط على هذا الجهاز. يمكنك إيقافه؛ يتطلب تفعيله دعمًا أصليًا."),
    CrossfadeCustomRange("0.1–30 seconds, with up to three decimal places.", "من 0.1 إلى 30 ثانية، وحتى ثلاث خانات عشرية."),
    ApplyCrossfadeDuration("Apply crossfade duration", "تطبيق مدة تداخل المقاطع"),
    ParallelDownloads("Parallel downloads", "التنزيلات المتزامنة"),
    ParallelDownloadsDescription("Maximum simultaneous downloads. Existing work may finish before a lower limit takes effect.", "الحد الأقصى للتنزيلات في الوقت نفسه. قد يكتمل العمل الجاري قبل تطبيق حد أقل."),
    DownloadExecutionNotice("Background downloads depend on your device. Veyra may need to stay open to continue.", "تعتمد التنزيلات في الخلفية على جهازك. قد يلزم إبقاء ڤيرا مفتوحًا للمتابعة."),
    StorageUsageUnavailable("Storage usage is not available yet.", "حجم التخزين المستخدم غير متاح بعد."),
    About("About Veyra", "حول ڤيرا"),
    Version("Version {0}", "الإصدار {0}"),
    Licenses("Open-source licences", "تراخيص المصادر المفتوحة"),
    LegalOriginalTerms("These licences and notices are provided in their original language.", "تُعرض هذه التراخيص والإشعارات بلغتها الأصلية."),
    FontLicense("IBM Plex Sans Arabic · SIL Open Font License 1.1", "IBM Plex Sans Arabic · ترخيص SIL Open Font License 1.1"),
    LocalDataNotice("Library, follows and listening progress are stored on this device. There is no automatic cross-device sync.", "تُحفظ المكتبة والمتابعات وتقدّم الاستماع على هذا الجهاز. لا توجد مزامنة تلقائية بين الأجهزة."),
    Privacy("Privacy", "الخصوصية"),
    PublisherNetworkNotice("Online discovery may contact catalogues, publishers and artwork hosts. Audio playback and downloads use kmpYT.", "قد يتصل الاستكشاف عبر الإنترنت بالفهارس والناشرين ومضيفي الصور. يستخدم تشغيل الصوت وتنزيله kmpYT."),
    CompleteTitlePrivacyNotice("Online audio resolution sends the complete listing title to kmpYT search.", "يرسل العثور على الصوت عبر الإنترنت عنوان المحتوى الكامل إلى بحث kmpYT."),
    KeyboardShortcuts("Keyboard shortcuts", "اختصارات لوحة المفاتيح"),
    ShortcutSearch("Search: Ctrl/Cmd + K", "البحث: Ctrl/Cmd + K"),
    ShortcutClose("Close the current dialog: Escape", "إغلاق مربع الحوار الحالي: Escape"),
    ShortcutPlayback("Play/pause: Space outside text fields and other focused controls", "تشغيل أو إيقاف مؤقت: Space خارج حقول النص وعناصر التحكم الأخرى المحددة"),
    KeyboardHelp("Use Tab to move between controls, and Enter or Space to activate a focused control.", "استخدم Tab للتنقل بين عناصر التحكم، وEnter أو Space لتفعيل العنصر المحدد."),
    CoverFor("Cover for {0}", "غلاف {0}"),
    NoArtwork("No cover artwork", "لا توجد صورة غلاف"),
    OpenDetails("Open details for {0}", "فتح تفاصيل {0}"),
    ItemPosition("Item {0} of {1}", "العنصر {0} من {1}"),
    ProgressValue("{0} of {1}", "{0} من {1}"),
    PercentValue("{0}%", "{0}%"),
    ItemCount("{0} items", "العناصر: {0}"),
    DisabledReason("Unavailable: {0}", "غير متاح: {0}"),
    CurrentSource("Current source: {0}", "المصدر الحالي: {0}"),
    SampleContent("Sample content", "محتوى توضيحي"),
    UnverifiedCapability("Not yet verified on this platform", "لم يُتحقق منه بعد على هذه المنصة"),
}

enum class QuantityKey { Tracks, Episodes, Shows, Items, Downloads, Minutes, Seconds, Hours }
enum class ArabicPluralCategory { Zero, One, Two, Few, Many, Other }

/** CLDR's integer Arabic cardinal categories. Long.MIN_VALUE is handled without overflow. */
fun arabicPluralCategory(count: Long): ArabicPluralCategory {
    if (count == 0L) return ArabicPluralCategory.Zero
    if (count == 1L || count == -1L) return ArabicPluralCategory.One
    if (count == 2L || count == -2L) return ArabicPluralCategory.Two
    val mod100 = kotlin.math.abs(count % 100)
    return when (mod100) {
        in 3L..10L -> ArabicPluralCategory.Few
        in 11L..99L -> ArabicPluralCategory.Many
        else -> ArabicPluralCategory.Other
    }
}

private val placeholderPattern = Regex("\\{([0-9]+)}")
private const val FSI = '\u2068'
private const val LRI = '\u2066'
private const val PDI = '\u2069'

/**
 * Render-only protection against metadata escaping its surrounding bidi isolate.
 * Does not mutate stored metadata, Arabic characters, diacritics, ZWJ or ZWNJ.
 */
fun displayMetadata(value: String): String = value.filterNot {
    it in '\u202A'..'\u202E' || it in '\u2066'..'\u2069'
}

fun isolateMetadata(value: String): String = "$FSI${displayMetadata(value)}$PDI"
fun isolateLtr(value: String): String = "$LRI${displayMetadata(value)}$PDI"

private fun isolatedArgument(value: String): String {
    // Preserve the explicit direction of values produced by mediaTime/number helpers.
    val numeric = value.firstOrNull() == LRI || value.none { it.isLetter() }
    return if (numeric) isolateLtr(value) else isolateMetadata(value)
}

@Immutable
class AppStrings(
    language: LanguagePreference,
    /** Region/user preference, not an assumption that every Arabic reader uses these digits. */
    val arabicIndicDigits: Boolean = false,
) {
    /** The shell must resolve SYSTEM from the real platform locale before constructing the theme. */
    val language: LanguagePreference = if (language == LanguagePreference.ARABIC) language else LanguagePreference.ENGLISH
    val isRtl: Boolean get() = language == LanguagePreference.ARABIC

    fun text(key: TextKey): String = if (isRtl) key.arabic else key.english

    /** Safe for a dynamic *key name*. Unknown backend text is never echoed to the UI. */
    fun textOrFallback(keyName: String, fallback: TextKey = TextKey.InformationUnavailable): String =
        text(TextKey.entries.firstOrNull { it.name == keyName } ?: fallback)

    /**
     * Positional resources, without String.format's platform/locale differences.
     * Replacement visits the template once: braces inside metadata are not interpolated again.
     * A bad call gets a safe localized fallback rather than exposing a template or crashing a screen.
     */
    fun format(key: TextKey, vararg arguments: String): String {
        val template = text(key)
        val indices = placeholderPattern.findAll(template).map { it.groupValues[1].toIntOrNull() }.toSet()
        if (indices.any { it == null } || indices != arguments.indices.toSet()) {
            return text(TextKey.InformationUnavailable)
        }
        val quantityKey = when (key) {
            TextKey.TrackCount -> QuantityKey.Tracks
            TextKey.EpisodeCount -> QuantityKey.Episodes
            TextKey.ShowCount -> QuantityKey.Shows
            TextKey.ItemCount -> QuantityKey.Items
            TextKey.DownloadCount -> QuantityKey.Downloads
            TextKey.Minutes -> QuantityKey.Minutes
            TextKey.Seconds -> QuantityKey.Seconds
            TextKey.Hours -> QuantityKey.Hours
            else -> null
        }
        if (quantityKey != null && arguments.size == 1) {
            arguments[0].toLongOrNull()?.let { return quantity(quantityKey, it) }
        }
        return interpolate(template, arguments)
    }

    private fun interpolate(template: String, arguments: Array<out String>): String {
        val indices = placeholderPattern.findAll(template).map { it.groupValues[1].toIntOrNull() }.toList()
        if (indices.any { it == null || it !in arguments.indices }) return text(TextKey.InformationUnavailable)
        return placeholderPattern.replace(template) { match ->
            isolatedArgument(arguments[match.groupValues[1].toInt()])
        }
    }

    fun number(value: Long): String = localizeDigits(value.toString())

    fun localizeDigits(value: String): String = if (!arabicIndicDigits) value else buildString(value.length) {
        value.forEach { append(if (it in '0'..'9') '\u0660' + (it - '0') else it) }
    }

    fun quantity(key: QuantityKey, count: Int): String = quantity(key, count.toLong())

    fun quantity(key: QuantityKey, count: Long): String {
        val template = if (!isRtl) {
            val noun = when (key) {
                QuantityKey.Tracks -> "track"
                QuantityKey.Episodes -> "episode"
                QuantityKey.Shows -> "show"
                QuantityKey.Items -> "item"
                QuantityKey.Downloads -> "download"
                QuantityKey.Minutes -> "minute"
                QuantityKey.Seconds -> "second"
                QuantityKey.Hours -> "hour"
            }
            "{0} $noun${if (count == 1L || count == -1L) "" else "s"}"
        } else {
            val forms = when (key) {
                QuantityKey.Tracks -> listOf("لا مقاطع", "مقطع واحد", "مقطعان", "{0} مقاطع", "{0} مقطعًا", "{0} مقطع")
                QuantityKey.Episodes -> listOf("لا حلقات", "حلقة واحدة", "حلقتان", "{0} حلقات", "{0} حلقة", "{0} حلقة")
                QuantityKey.Shows -> listOf("لا برامج", "برنامج واحد", "برنامجان", "{0} برامج", "{0} برنامجًا", "{0} برنامج")
                QuantityKey.Items -> listOf("لا عناصر", "عنصر واحد", "عنصران", "{0} عناصر", "{0} عنصرًا", "{0} عنصر")
                QuantityKey.Downloads -> listOf("لا تنزيلات", "تنزيل واحد", "تنزيلان", "{0} تنزيلات", "{0} تنزيلًا", "{0} تنزيل")
                QuantityKey.Minutes -> listOf("صفر دقيقة", "دقيقة واحدة", "دقيقتان", "{0} دقائق", "{0} دقيقة", "{0} دقيقة")
                QuantityKey.Seconds -> listOf("صفر ثانية", "ثانية واحدة", "ثانيتان", "{0} ثوانٍ", "{0} ثانية", "{0} ثانية")
                QuantityKey.Hours -> listOf("صفر ساعة", "ساعة واحدة", "ساعتان", "{0} ساعات", "{0} ساعة", "{0} ساعة")
            }
            forms[arabicPluralCategory(count).ordinal]
        }
        return interpolate(template, arrayOf(number(count)))
    }

    /** A visual, explicitly LTR media time. Null is unknown, not a pretend 0:00. */
    fun mediaTime(milliseconds: Long?): String {
        if (milliseconds == null) return text(TextKey.UnknownDuration)
        val seconds = milliseconds.coerceAtLeast(0) / 1000
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val remainder = seconds % 60
        val raw = if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:${remainder.toString().padStart(2, '0')}"
            else "$minutes:${remainder.toString().padStart(2, '0')}"
        return isolateLtr(localizeDigits(raw))
    }

    /** Use in slider semantics instead of asking a screen reader to interpret a colon. */
    fun spokenDuration(milliseconds: Long?): String {
        if (milliseconds == null) return text(TextKey.UnknownDuration)
        val seconds = milliseconds.coerceAtLeast(0) / 1000
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val remainder = seconds % 60
        val parts = buildList {
            if (hours > 0) add(quantity(QuantityKey.Hours, hours))
            if (minutes > 0) add(quantity(QuantityKey.Minutes, minutes))
            if (remainder > 0 || isEmpty()) add(quantity(QuantityKey.Seconds, remainder))
        }
        return parts.joinToString(if (isRtl) "، " else ", ")
    }

    /** Actual bytes, binary units named honestly, rounded to at most one fractional digit. */
    fun byteSize(bytes: Long): String {
        if (bytes < 0) return text(TextKey.Unknown)
        val units = listOf(TextKey.ByteUnit, TextKey.KibibyteUnit, TextKey.MebibyteUnit, TextKey.GibibyteUnit, TextKey.TebibyteUnit)
        var index = 0
        var divisor = 1L
        while (index < units.lastIndex && bytes >= divisor * 1024L) {
            divisor *= 1024L
            index++
        }
        val numeric = if (index == 0) number(bytes) else {
            // Splitting before multiplication avoids overflow even for Long.MAX_VALUE.
            val tenths = (bytes / divisor) * 10L + ((bytes % divisor) * 10L + divisor / 2L) / divisor
            val whole = tenths / 10
            val fraction = tenths % 10
            if (fraction == 0L) number(whole)
            else number(whole) + (if (arabicIndicDigits) "٫" else ".") + number(fraction)
        }
        return format(TextKey.ByteSizeValue, numeric, text(units[index]))
    }

    /** Only typed, source-independent failure categories are shown. Never use failure.detail. */
    fun errorFor(failure: AppFailure): String = text(when (failure.code) {
        FailureCode.NETWORK_UNAVAILABLE -> TextKey.ConnectionUnavailable
        FailureCode.OFFLINE_POLICY -> TextKey.OfflinePolicy
        FailureCode.PROVIDER_ACCESS_REQUIRED -> TextKey.SourceRestricted
        FailureCode.RATE_LIMITED -> TextKey.RateLimited
        FailureCode.NOT_FOUND -> TextKey.NotFound
        FailureCode.UNSUPPORTED_SOURCE -> TextKey.UnsupportedSource
        FailureCode.UNSUPPORTED_TRANSPORT -> TextKey.UnsupportedTransport
        FailureCode.UNSUPPORTED_CODEC -> TextKey.UnsupportedCodec
        FailureCode.PERMISSION_DENIED -> TextKey.PermissionDenied
        FailureCode.BACKGROUND_RESTRICTED -> TextKey.ForegroundRequired
        FailureCode.STORAGE_FULL -> TextKey.StorageFull
        FailureCode.STORAGE_IO -> TextKey.StorageError
        FailureCode.CORRUPT_MEDIA -> TextKey.CorruptMedia
        FailureCode.PARSE_FAILURE -> TextKey.ParseFailure
        FailureCode.RECOVERY_REQUIRED -> TextKey.RecoveryRequired
        FailureCode.FUTURE_SCHEMA -> TextKey.FutureSchema
        FailureCode.INVALID_STATE -> TextKey.InvalidState
        FailureCode.CANCELLED -> TextKey.Cancelled
        FailureCode.UNEXPECTED -> TextKey.UnexpectedError
    })

    fun downloadStatus(status: DownloadStatus): String = text(when (status) {
        DownloadStatus.QUEUED -> TextKey.DownloadQueued
        DownloadStatus.RESOLVING -> TextKey.DownloadResolving
        DownloadStatus.RUNNING -> TextKey.Downloading
        DownloadStatus.PAUSED -> TextKey.DownloadPaused
        DownloadStatus.WAITING_NETWORK -> TextKey.WaitingForNetwork
        DownloadStatus.NEEDS_FOREGROUND -> TextKey.DownloadNeedsForeground
        DownloadStatus.VERIFYING -> TextKey.DownloadVerifying
        DownloadStatus.COMPLETE -> TextKey.Downloaded
        DownloadStatus.FAILED -> TextKey.DownloadFailed
        DownloadStatus.CANCELLED -> TextKey.DownloadCancelled
    })

    fun sourceName(provider: SourceProvider): String = text(when (provider) {
        SourceProvider.YOUTUBE -> TextKey.YouTube
        SourceProvider.RSS -> TextKey.Podcasts
        SourceProvider.LOCAL -> TextKey.LocalFiles
        SourceProvider.COMMONS -> TextKey.OpenMusic
    })

    fun languageName(preference: LanguagePreference): String = text(when (preference) {
        LanguagePreference.SYSTEM -> TextKey.SystemDefault
        LanguagePreference.ENGLISH -> TextKey.English
        LanguagePreference.ARABIC -> TextKey.Arabic
    })

    fun themeName(preference: ThemeMode): String = text(when (preference) {
        ThemeMode.SYSTEM -> TextKey.SystemDefault
        ThemeMode.LIGHT -> TextKey.LightTheme
        ThemeMode.DARK -> TextKey.DarkTheme
    })
}

val LocalStrings = staticCompositionLocalOf { AppStrings(LanguagePreference.ENGLISH) }
