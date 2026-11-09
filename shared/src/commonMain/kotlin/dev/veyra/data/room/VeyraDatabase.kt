package dev.veyra.data.room

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

@Database(
    entities = [
        ArtistEntity::class,
        AlbumEntity::class,
        AlbumArtistEntity::class,
        MediaEntity::class,
        TrackEntity::class,
        TrackArtistEntity::class,
        AlbumTrackEntity::class,
        PodcastEntity::class,
        PodcastFeedAliasEntity::class,
        EpisodeEntity::class,
        PodcastEpisodeEntity::class,
        PlaylistEntity::class,
        PlaylistEntryEntity::class,
        QueueContextEntity::class,
        QueueEntryEntity::class,
        QueueCursorEntity::class,
        FavoriteEntity::class,
        SavedEpisodeEntity::class,
        FollowedPodcastEntity::class,
        PlaybackBindingEntity::class,
        MediaProgressEntity::class,
        HistoryEntity::class,
        OfflineAssetEntity::class,
        DownloadEntity::class,
        DownloadAttemptEntity::class,
        DownloadFinalizationEntity::class,
        PodcastSpeedEntity::class,
        NavigationEntity::class,
        StoreMetaEntity::class,
        SettingsEntity::class,
        AcquisitionRequestEntity::class,
        RequestMediaMetadataEntity::class,
        RequestMediaArtistEntity::class,
        RequestAlbumMetadataEntity::class,
        RequestAlbumArtistEntity::class,
        RequestAlbumMemberEntity::class,
        RequestAlbumMemberArtistEntity::class,
        RequestShowMetadataEntity::class,
        RequestShowAliasEntity::class,
        RequestShowMemberEntity::class,
        RequestSourceCollectionMetadataEntity::class,
        ReleaseObservationEntity::class,
        ReleaseObservationGroupEntity::class,
        ReleaseGroupSecondaryTypeEntity::class,
        ReleaseArtistCreditEntity::class,
        ReleaseEventEntity::class,
        ReleaseEventCountryEntity::class,
        ReleaseLabelEntity::class,
        ReleaseMediumEntity::class,
        ReleaseTrackEntity::class,
        ReleaseTrackArtistCreditEntity::class,
        ReleaseSummaryProblemEntity::class,
        ReleaseCoreProblemEntity::class,
        ReleaseMediumProblemEntity::class,
        ReleaseTrackProblemEntity::class,
        RequestReleaseMetadataEntity::class,
        RequestMediaReleaseOccurrenceEntity::class,
        ReleaseCatalogEntryEntity::class,
        SavedReleaseEntity::class,
        FirstResultReceiptEntity::class,
        SourceRecordingEntity::class,
        ExecutableSubjectEntity::class,
        ExecutionBindingEntity::class,
        AcquisitionOutcomeEntity::class,
        AcquisitionJobEntity::class,
        CollectionRunEntity::class,
        CollectionObservationEntity::class,
        CollectionIdentityEvidenceEntity::class,
        CollectionPageCommitEntity::class,
        CollectionPageFailureEntity::class,
        CollectionMemberEntity::class,
        CollectionSnapshotEntity::class,
        CollectionSnapshotMemberEntity::class,
        RequestCollectionMemberMetadataEntity::class,
        AdmissionScopeEntity::class,
        AdmissionScopeMemberEntity::class,
        ExecutionOccurrenceEntity::class,
        ExecutionQueueRevisionEntity::class,
        ExecutionQueueMemberEntity::class,
        ExecutionQueueHeadEntity::class,
        OccurrenceCheckpointEntity::class,
        SourceRecordingResumeEntity::class,
        CatalogProgressEntity::class,
        ListeningHistoryEntity::class,
        FavoriteSubjectEntity::class,
        PlaylistMemberEntity::class,
        DownloadBatchEntity::class,
        DownloadBatchMemberEntity::class,
        DownloadClaimEntity::class,
        PhysicalDownloadAdoptionEntity::class,
        MaterializationReceiptEntity::class,
        AssetUseEntity::class,
    ],
    version = VeyraDatabase.VERSION,
    exportSchema = true,
)
@ConstructedBy(VeyraDatabaseConstructor::class)
abstract class VeyraDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun acquisitionDao(): AcquisitionDao

    companion object { const val VERSION = 3 }
}

@Suppress("KotlinNoActualForExpect")
expect object VeyraDatabaseConstructor : RoomDatabaseConstructor<VeyraDatabase> {
    override fun initialize(): VeyraDatabase
}

/** The baseline fixture predates persisted crossfade preferences and durable cleanup intent. */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE settings ADD COLUMN crossfadeEnabled INTEGER NOT NULL DEFAULT 0")
        connection.execSQL("ALTER TABLE settings ADD COLUMN crossfadeDurationMs INTEGER NOT NULL DEFAULT 3000")
        connection.execSQL("ALTER TABLE downloads ADD COLUMN cleanupPending INTEGER NOT NULL DEFAULT 0")
        connection.execSQL("ALTER TABLE downloads ADD COLUMN removeWhenClean INTEGER NOT NULL DEFAULT 0")
    }
}

fun buildVeyraDatabase(
    builder: RoomDatabase.Builder<VeyraDatabase>,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): VeyraDatabase = builder
    .setDriver(VeyraSQLiteDriver())
    .setQueryCoroutineContext(dispatcher)
    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
    // No destructive fallback: corruption, incompatible schema, and missing migrations are surfaced.
    .build()
