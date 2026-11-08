package dev.veyra.data.room

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Room owns the enclosing transaction. There is no source, native, filesystem or clock work.
 *
 * Schema2 had neither immutable requests nor occurrence-bound selection. Its facts are retained
 * in an explicit legacy variant; a current catalogue title/progress/download is never used to
 * manufacture a request, a raw observation, or a queue binding. New generation zero values are
 * only schema baselines, never acknowledgements that an old physical writer has stopped.
 *
 * Migration-only scalar backups live in the MAIN database transaction, not SQLite's potentially
 * external TEMP store. They are compared before any destructive DDL and removed before commit.
 * Empty replacement tables are populated under their final names: renaming a populated parent
 * is not relied on to settle SQLite's deferred-foreign-key counters. Any failure rolls back to
 * schema2, including every old physical recovery field. Foreign keys remain enabled throughout.
 */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(connection: SQLiteConnection) {
        connection.requireMigrationIntegrity("schema2")
        ACQUISITION_SCHEMA_3_CREATE.forEach { connection.execSQL(it) }
        connection.backfillSchema2Identity()
        connection.backfillSchema2Library()
        connection.rebuildSchema2PhysicalRows()
        connection.requireMigrationIntegrity("schema3")
    }
}

private fun SQLiteConnection.requireMigrationIntegrity(stage: String) {
    prepare("PRAGMA foreign_keys").use { check(it.step() && it.getLong(0) == 1L) { "${stage}_foreign_keys_disabled" } }
    prepare("PRAGMA integrity_check").use {
        check(it.step() && it.getText(0) == "ok" && !it.step()) { "${stage}_integrity_check_failed" }
    }
    prepare("PRAGMA foreign_key_check").use { check(!it.step()) { "${stage}_foreign_key_check_failed" } }
}

/** All compared projections contain a primary key; counts plus both EXCEPT directions are exact. */
private fun SQLiteConnection.requireSameMigrationRows(expected: String, actual: String, label: String) {
    fun count(query: String): Long = prepare("SELECT count(*) FROM ($query)").use {
        check(it.step()) { "${label}_count_missing" }
        it.getLong(0)
    }
    check(count(expected) == count(actual)) { "${label}_count_mismatch" }
    prepare("SELECT 1 FROM ($expected EXCEPT $actual) LIMIT 1").use {
        check(!it.step()) { "${label}_expected_row_missing" }
    }
    prepare("SELECT 1 FROM ($actual EXCEPT $expected) LIMIT 1").use {
        check(!it.step()) { "${label}_unexpected_row_present" }
    }
}

private fun SQLiteConnection.backfillSchema2Identity() {
    execSQL("""
        INSERT INTO executable_subjects(id,kind,mediaId,recordingId)
        SELECT id,'CATALOG_MEDIA',id,NULL FROM media
    """.trimIndent())
    requireSameMigrationRows(
        "SELECT id,'CATALOG_MEDIA',id,NULL FROM media",
        "SELECT id,kind,mediaId,recordingId FROM executable_subjects", "schema2_subjects",
    )
    // Typed-only fields stay NULL. firstResultUrl is the stored legacy canonical locator, not
    // evidence that a raw renderer URL was observed. Even a NULL URL is preserved verbatim.
    val receiptColumns = "id,origin,requestId,legacyMediaId,queryTitle,rawOrdinal,rawKind,rawId,rawTitle," +
        "rawPublicUrl,rawIssue,canonicalUrl,observedEpochMs,legacyFirstResultKind,legacyFirstResultId," +
        "legacyFirstResultTitle,legacySelectedVideoId,legacyResolvedEpochMs,legacySelectionOrdinal"
    val receiptProjection = "SELECT id,'SCHEMA2',NULL,mediaId,queryTitle,NULL,NULL,NULL,NULL,NULL,NULL," +
        "firstResultUrl,NULL,firstResultKind,firstResultId,firstResultTitle,selectedVideoId,resolvedEpochMs,selectionOrdinal " +
        "FROM playback_bindings"
    execSQL("INSERT INTO first_result_receipts($receiptColumns) $receiptProjection")
    requireSameMigrationRows(receiptProjection, "SELECT $receiptColumns FROM first_result_receipts", "schema2_receipts")

    prepare("""
        SELECT id,mediaId,queryTitle,firstResultKind,firstResultId,firstResultTitle,
               firstResultUrl,selectedVideoId,selectionOrdinal
        FROM playback_bindings ORDER BY id
    """.trimIndent()).use { legacy ->
        while (legacy.step()) {
            val id = legacy.getText(0)
            val mediaId = legacy.getText(1)
            val query = legacy.getText(2)
            val kind = legacy.getText(3)
            val firstId = legacy.getText(4)
            val title = legacy.getText(5)
            val url = if (legacy.isNull(6)) null else legacy.getText(6)
            val selected = if (legacy.isNull(7)) null else legacy.getText(7)
            val executable = kind == "VIDEO" && legacy.getLong(8) == 0L && firstId == selected &&
                schema2VideoId(firstId) && schema2CompleteTitle(query) && schema2CompleteTitle(title) &&
                (url == null || url == "https://www.youtube.com/watch?v=$firstId")
            val recordingId = if (executable) "schema2:youtube:$firstId" else null
            if (recordingId != null) {
                // Deterministic identity locator from the actually stored C. This is not a
                // manufactured historical raw URL/title and never alters the legacy receipt.
                prepare("""
                    INSERT INTO source_recordings(id,provider,canonicalId,canonicalUrl)
                    SELECT ?,'YOUTUBE',?,?
                    WHERE NOT EXISTS(SELECT 1 FROM source_recordings WHERE provider='YOUTUBE' AND canonicalId=?)
                """.trimIndent()).use {
                    it.bindText(1, recordingId)
                    it.bindText(2, firstId)
                    it.bindText(3, "https://www.youtube.com/watch?v=$firstId")
                    it.bindText(4, firstId)
                    it.step()
                }
                prepare("SELECT id,canonicalUrl FROM source_recordings WHERE provider='YOUTUBE' AND canonicalId=?").use {
                    it.bindText(1, firstId)
                    check(it.step() && it.getText(0) == recordingId &&
                        it.getText(1) == "https://www.youtube.com/watch?v=$firstId" && !it.step()) {
                        "schema2_recording_readback_mismatch"
                    }
                }
            }
            val origin = if (executable) "SCHEMA2_EXECUTABLE" else "SCHEMA2_NON_EXECUTABLE"
            prepare("INSERT INTO execution_bindings(id,subjectId,firstReceiptId,recordingId,origin) VALUES(?,?,?,?,?)").use {
                it.bindText(1, id)
                it.bindText(2, mediaId)
                it.bindText(3, id)
                if (recordingId == null) it.bindNull(4) else it.bindText(4, recordingId)
                it.bindText(5, origin)
                it.step()
            }
            prepare("SELECT subjectId,firstReceiptId,recordingId,origin FROM execution_bindings WHERE id=?").use {
                it.bindText(1, id)
                check(it.step() && it.getText(0) == mediaId && it.getText(1) == id &&
                    (if (it.isNull(2)) null else it.getText(2)) == recordingId && it.getText(3) == origin && !it.step()) {
                    "schema2_binding_readback_mismatch"
                }
            }
        }
    }
    requireSameMigrationRows(
        "SELECT id,mediaId,id FROM playback_bindings",
        "SELECT id,subjectId,firstReceiptId FROM execution_bindings", "schema2_binding_identity",
    )
}

// Versioned migration predicates are deliberately frozen, not today's display/catalogue rules.
private fun schema2VideoId(value: String): Boolean = value.length == 11 && value.all {
    it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-'
}

private fun schema2CompleteTitle(value: String): Boolean {
    if (value.length !in 1..16_384 || value.isBlank()) return false
    var offset = 0
    while (offset < value.length) {
        val character = value[offset++]
        if (character.code < 32 || character.code in 127..159) return false
        if (character.isHighSurrogate()) {
            if (offset == value.length || !value[offset++].isLowSurrogate()) return false
        } else if (character.isLowSurrogate()) return false
    }
    return true
}

private data class Schema2Backfill(val table: String, val columns: String, val projection: String)

private fun SQLiteConnection.backfillSchema2Library() {
    val copies = listOf(
        Schema2Backfill("execution_occurrences",
            "id,origin,requestId,snapshotId,snapshotOrdinal,scopeId,scopePosition,subjectId,bindingId," +
                "controlsMode,selectionState,admittedEpochMs",
            "SELECT id,'SCHEMA2',NULL,NULL,NULL,NULL,NULL,mediaId,NULL,contextId,'LEGACY_UNBOUND',NULL FROM queue_entries"),
        Schema2Backfill("execution_queue_revisions", "contextId,editRevision", "SELECT id,0 FROM queue_contexts"),
        Schema2Backfill("queue_members", "occurrenceId,contextId,position,originalPosition",
            "SELECT id,contextId,position,originalPosition FROM queue_entries"),
        Schema2Backfill("queue_heads", "contextId,occurrenceId", "SELECT contextId,entryId FROM queue_cursors"),
        Schema2Backfill("catalog_progress", "mediaId,positionMs,durationMs,played,updatedEpochMs,sessionId,generation," +
            "sequence,executionBindingId,lastOccurrenceId",
            "SELECT mediaId,positionMs,durationMs,played,updatedEpochMs,sessionId,generation,sequence,executionBindingId,NULL FROM media_progress"),
        Schema2Backfill("listening_history", "id,subjectId,origin,occurrenceId,bindingId,startedEpochMs,lastPositionMs,endedNaturally,position",
            "SELECT id,mediaId,'SCHEMA2',NULL,executionBindingId,startedEpochMs,lastPositionMs,endedNaturally,position FROM playback_history"),
        Schema2Backfill("favorite_subjects", "subjectId,origin,requestId,bindingId,occurrenceId,controlsMode",
            "SELECT mediaId,'SCHEMA2',NULL,NULL,NULL,NULL FROM favorites"),
        Schema2Backfill("playlist_members", "id,playlistId,subjectId,origin,requestId,bindingId,occurrenceId,controlsMode,position",
            "SELECT id,playlistId,mediaId,'SCHEMA2',NULL,NULL,NULL,NULL,position FROM playlist_entries"),
    )
    copies.forEach { copy ->
        execSQL("INSERT INTO ${copy.table}(${copy.columns}) ${copy.projection}")
        requireSameMigrationRows(copy.projection, "SELECT ${copy.columns} FROM ${copy.table}", "schema2_${copy.table}")
    }
}

private data class Schema2PhysicalCopy(
    val table: String,
    val oldColumns: String,
    val newColumns: String,
    val projection: String,
) {
    val columns: String get() = "$oldColumns,$newColumns"
    val backup: String get() = "_migration_2_3_$table"
}

private fun SQLiteConnection.rebuildSchema2PhysicalRows() {
    val assetColumns = "id,mediaId,uri,sizeBytes,kind,mimeType,codecs,sha256,representationId,validatorGeneration," +
        "completedEpochMs,executionBindingId,measuredDurationMs"
    val downloadColumns = "id,mediaId,status,bytesDownloaded,totalBytes,assetId,failure_code,failure_detail,failure_retryAfterMs," +
        "stagingPath,representationId,etag,lastModified,validatorGeneration,updatedEpochMs,executionBindingId,attemptCount," +
        "activeAttemptId,nextRetryEpochMs,priority,createdEpochMs,userPaused,leaseOwner,leaseExpiresEpochMs,lastProgressEpochMs," +
        "automaticRetryCount,cleanupPending,removeWhenClean"
    val attemptColumns = "id,downloadId,number,status,startedEpochMs,finishedEpochMs,bytesDownloaded,totalBytes," +
        "failure_code,failure_detail,failure_retryAfterMs,stagingPath,representationId,executionBindingId,etag,lastModified," +
        "validatorGeneration,backgroundTaskId"
    val finalizationColumns = "downloadId,mediaId,stagingPath,finalPath,sha256,sizeBytes,attemptId,mediaDurationMs,mimeType," +
        "codecs,executionBindingId,representationId,validatorGeneration"
    fun qualified(columns: String): String = columns.split(',').joinToString(",") { "old.$it" }
    val producerColumns = "subjectId,producerOrigin,recordingId,ownershipGeneration"
    val producerProjection = "old.mediaId AS subjectId,'SCHEMA2' AS producerOrigin,b.recordingId AS recordingId,0 AS ownershipGeneration"
    val copies = listOf(
        Schema2PhysicalCopy("offline_assets", assetColumns, "$producerColumns,materializerDownloadId,materializerAttemptId",
            "SELECT ${qualified(assetColumns)},$producerProjection,NULL AS materializerDownloadId,NULL AS materializerAttemptId " +
                "FROM offline_assets old LEFT JOIN execution_bindings b ON b.id=old.executionBindingId"),
        Schema2PhysicalCopy("downloads", downloadColumns, "$producerColumns,maintenanceKind",
            "SELECT ${qualified(downloadColumns)},$producerProjection,NULL AS maintenanceKind " +
                "FROM downloads old LEFT JOIN execution_bindings b ON b.id=old.executionBindingId"),
        // Schema2 attempts stored the parent job and their own binding, not a separate media ID.
        // The parent's media identity was immutable; never take the job's latest binding/C.
        Schema2PhysicalCopy("download_attempts", attemptColumns, "mediaId,$producerColumns",
            "SELECT ${qualified(attemptColumns)},job.mediaId AS mediaId,job.mediaId AS subjectId,'SCHEMA2' AS producerOrigin," +
                "b.recordingId AS recordingId,0 AS ownershipGeneration FROM download_attempts old " +
                "JOIN downloads job ON job.id=old.downloadId LEFT JOIN execution_bindings b ON b.id=old.executionBindingId"),
        Schema2PhysicalCopy("download_finalizations", finalizationColumns, "$producerColumns,assetId",
            "SELECT ${qualified(finalizationColumns)},$producerProjection,NULL AS assetId " +
                "FROM download_finalizations old LEFT JOIN execution_bindings b ON b.id=old.executionBindingId"),
    )
    copies.forEach { copy ->
        execSQL("CREATE TABLE ${copy.backup} AS ${copy.projection}")
        requireSameMigrationRows("SELECT ${copy.oldColumns} FROM ${copy.table}",
            "SELECT ${copy.oldColumns} FROM ${copy.backup}", "schema2_${copy.table}_backup")
        requireSameMigrationRows(copy.projection, "SELECT ${copy.columns} FROM ${copy.backup}", "schema3_${copy.table}_projection")
    }

    // Break only the old nullable active-attempt edge, AFTER its exact value is in a verified
    // backup. Delete old children before parents, while every referenced table still exists.
    // No new physical rows or claims are present yet, so no old CASCADE/SET_NULL can reach them.
    execSQL("UPDATE downloads SET activeAttemptId=NULL")
    listOf("download_finalizations", "download_attempts", "downloads", "offline_assets").forEach {
        execSQL("DELETE FROM $it")
    }
    // The new exact owner FK needs this pair to be a legal parent while old empty table names
    // still exist. The PK already guarantees uniqueness. The index disappears with that table.
    execSQL("CREATE UNIQUE INDEX _migration_2_3_attempt_owner ON download_attempts(downloadId,id)")
    PHYSICAL_SCHEMA_3_CREATE.forEach { execSQL(it) }
    listOf("download_finalizations", "download_attempts", "downloads", "offline_assets").forEach { table ->
        execSQL("DROP TABLE $table")
        execSQL("ALTER TABLE ${table}_schema3 RENAME TO $table")
        PHYSICAL_SCHEMA_3_INDEXES.filter { " ON `$table` " in it }.forEach { execSQL(it) }
    }

    // A retained immutable receipt is not file validation and creates NO AssetUse. The old
    // asset did not record a materializer job/attempt; a current owner's IDs cannot fill it.
    val receiptColumns = "id,mediaId,subjectId,producerOrigin,recordingId,executionBindingId,sizeBytes,kind,mimeType,codecs," +
        "sha256,representationId,validatorGeneration,completedEpochMs,measuredDurationMs,materializerDownloadId," +
        "materializerAttemptId,ownershipGeneration"
    val receiptProjection = "SELECT $receiptColumns FROM _migration_2_3_offline_assets"
    execSQL("INSERT INTO materialization_receipts($receiptColumns) $receiptProjection")
    requireSameMigrationRows(receiptProjection, "SELECT $receiptColumns FROM materialization_receipts", "schema2_materialization_receipts")
    // Parent restoration is actual INSERT under final names. The download→active attempt cycle
    // is deferred until its recorded attempts are restored; exact cross-job mismatches fail.
    copies.forEach { copy ->
        execSQL("INSERT INTO ${copy.table}(${copy.columns}) SELECT ${copy.columns} FROM ${copy.backup}")
    }
    copies.forEach { copy ->
        requireSameMigrationRows("SELECT ${copy.columns} FROM ${copy.backup}",
            "SELECT ${copy.columns} FROM ${copy.table}", "schema3_${copy.table}_readback")
    }

    // Every legacy job has an ACTUAL standalone owner. Claim IDs share text with the existing
    // job only in this separate ID namespace; there is no new guessed request/occurrence.
    // Host-policy PAUSED is not explicit user intent. FAILED/WAITING/NEEDS_FOREGROUND retain
    // ownership but their unchanged physical status/gates still deny an automatic retry.
    val claimColumns = "id,ownerKind,physicalJobId,standaloneJobId,batchId,memberPosition,requestedBindingId,intent," +
        "retainCompletedAsset,updatedEpochMs,generation,ownerIntentRevision"
    val claimProjection = "SELECT id,'SCHEMA2_STANDALONE',id,id,NULL,NULL,executionBindingId," +
        "CASE WHEN status='CANCELLED' OR removeWhenClean<>0 THEN 'CANCELLED' " +
        "WHEN userPaused<>0 THEN 'PAUSED' ELSE 'ACTIVE' END," +
        "CASE WHEN removeWhenClean<>0 THEN 0 ELSE 1 END,updatedEpochMs,0,0 FROM downloads"
    execSQL("INSERT INTO download_claims($claimColumns) $claimProjection")
    requireSameMigrationRows(claimProjection, "SELECT $claimColumns FROM download_claims", "schema2_standalone_claims")
    copies.forEach { execSQL("DROP TABLE ${it.backup}") }
}

/** Exact schema3 DDL; Room validates against its real KSP export at migration/open time. */
internal val ACQUISITION_SCHEMA_3_CREATE = listOf(
    "CREATE TABLE `download_producer_adoptions` (`jobId` TEXT NOT NULL, `newOwnershipGeneration` INTEGER NOT NULL, `oldOwnershipGeneration` INTEGER NOT NULL, `oldMediaId` TEXT NOT NULL, `oldBindingId` TEXT, `oldRecordingId` TEXT, `newRequestId` TEXT NOT NULL, `newBindingId` TEXT NOT NULL, `newRecordingId` TEXT NOT NULL, `subjectId` TEXT NOT NULL, `adoptedEpochMs` INTEGER NOT NULL, PRIMARY KEY(`jobId`, `newOwnershipGeneration`), FOREIGN KEY(`jobId`) REFERENCES `downloads`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`oldMediaId`) REFERENCES `media`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`oldBindingId`) REFERENCES `execution_bindings`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`oldRecordingId`) REFERENCES `source_recordings`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`newRequestId`) REFERENCES `acquisition_requests`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`newBindingId`) REFERENCES `execution_bindings`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`newRecordingId`) REFERENCES `source_recordings`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`subjectId`) REFERENCES `executable_subjects`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_download_producer_adoptions_jobId` ON `download_producer_adoptions` (`jobId`)",
    "CREATE INDEX `index_download_producer_adoptions_oldMediaId` ON `download_producer_adoptions` (`oldMediaId`)",
    "CREATE INDEX `index_download_producer_adoptions_oldBindingId` ON `download_producer_adoptions` (`oldBindingId`)",
    "CREATE INDEX `index_download_producer_adoptions_oldRecordingId` ON `download_producer_adoptions` (`oldRecordingId`)",
    "CREATE INDEX `index_download_producer_adoptions_newRequestId` ON `download_producer_adoptions` (`newRequestId`)",
    "CREATE INDEX `index_download_producer_adoptions_newBindingId` ON `download_producer_adoptions` (`newBindingId`)",
    "CREATE INDEX `index_download_producer_adoptions_newRecordingId` ON `download_producer_adoptions` (`newRecordingId`)",
    "CREATE INDEX `index_download_producer_adoptions_subjectId` ON `download_producer_adoptions` (`subjectId`)",
    "CREATE TABLE `acquisition_requests` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `completeTitle` TEXT NOT NULL, `capturedEpochMs` INTEGER NOT NULL, `catalogRevision` INTEGER, `providerRevision` TEXT, PRIMARY KEY(`id`))",
    "CREATE TABLE `request_media_metadata` (`requestId` TEXT NOT NULL, `media_id` TEXT NOT NULL, `media_title` TEXT NOT NULL, `media_kind` TEXT NOT NULL, `media_source_provider` TEXT NOT NULL, `media_source_canonicalId` TEXT NOT NULL, `media_source_canonicalUrl` TEXT, `media_source_feedUrl` TEXT, `media_source_episodeGuid` TEXT, `media_source_enclosureUrl` TEXT, `media_source_mimeType` TEXT, `media_source_localAssetId` TEXT, `media_creator` TEXT NOT NULL, `media_artworkUrl` TEXT, `media_advisoryDurationMs` INTEGER, `media_showId` TEXT, `media_description` TEXT NOT NULL, `media_publishedEpochMs` INTEGER, `media_explicit` INTEGER NOT NULL, `media_albumLabel` TEXT, `media_artistLabel` TEXT, `media_episodeNumber` INTEGER, `media_seasonNumber` INTEGER, `media_albumId` TEXT, `media_trackNumber` INTEGER, `media_discNumber` INTEGER, `media_localArtworkPath` TEXT, `media_attribution_creator` TEXT, `media_attribution_sourceUrl` TEXT, `media_attribution_licenseName` TEXT, `media_attribution_licenseUrl` TEXT, PRIMARY KEY(`requestId`), FOREIGN KEY(`requestId`) REFERENCES `acquisition_requests`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_request_media_metadata_requestId` ON `request_media_metadata` (`requestId`)",
    "CREATE TABLE `request_media_artists` (`requestId` TEXT NOT NULL, `position` INTEGER NOT NULL, `artistId` TEXT NOT NULL, `metadata_id` TEXT, `metadata_name` TEXT, `metadata_sortName` TEXT, `metadata_description` TEXT, `metadata_artworkUrl` TEXT, `metadata_source_provider` TEXT, `metadata_source_canonicalId` TEXT, `metadata_source_canonicalUrl` TEXT, `metadata_source_feedUrl` TEXT, `metadata_source_episodeGuid` TEXT, `metadata_source_enclosureUrl` TEXT, `metadata_source_mimeType` TEXT, `metadata_source_localAssetId` TEXT, PRIMARY KEY(`requestId`, `position`), FOREIGN KEY(`requestId`) REFERENCES `request_media_metadata`(`requestId`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_request_media_artists_requestId` ON `request_media_artists` (`requestId`)",
    "CREATE TABLE `request_album_metadata` (`requestId` TEXT NOT NULL, `album_id` TEXT NOT NULL, `album_title` TEXT NOT NULL, `album_artworkUrl` TEXT, `album_description` TEXT NOT NULL, `album_releaseYear` INTEGER, `album_source_provider` TEXT, `album_source_canonicalId` TEXT, `album_source_canonicalUrl` TEXT, `album_source_feedUrl` TEXT, `album_source_episodeGuid` TEXT, `album_source_enclosureUrl` TEXT, `album_source_mimeType` TEXT, `album_source_localAssetId` TEXT, PRIMARY KEY(`requestId`), FOREIGN KEY(`requestId`) REFERENCES `acquisition_requests`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_request_album_metadata_requestId` ON `request_album_metadata` (`requestId`)",
    "CREATE TABLE `request_album_artists` (`requestId` TEXT NOT NULL, `position` INTEGER NOT NULL, `artistId` TEXT NOT NULL, `metadata_id` TEXT, `metadata_name` TEXT, `metadata_sortName` TEXT, `metadata_description` TEXT, `metadata_artworkUrl` TEXT, `metadata_source_provider` TEXT, `metadata_source_canonicalId` TEXT, `metadata_source_canonicalUrl` TEXT, `metadata_source_feedUrl` TEXT, `metadata_source_episodeGuid` TEXT, `metadata_source_enclosureUrl` TEXT, `metadata_source_mimeType` TEXT, `metadata_source_localAssetId` TEXT, PRIMARY KEY(`requestId`, `position`), FOREIGN KEY(`requestId`) REFERENCES `request_album_metadata`(`requestId`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_request_album_artists_requestId` ON `request_album_artists` (`requestId`)",
    "CREATE TABLE `request_album_members` (`requestId` TEXT NOT NULL, `position` INTEGER NOT NULL, `mediaId` TEXT NOT NULL, `discNumber` INTEGER, `trackNumber` INTEGER, `media_id` TEXT, `media_title` TEXT, `media_kind` TEXT, `media_source_provider` TEXT, `media_source_canonicalId` TEXT, `media_source_canonicalUrl` TEXT, `media_source_feedUrl` TEXT, `media_source_episodeGuid` TEXT, `media_source_enclosureUrl` TEXT, `media_source_mimeType` TEXT, `media_source_localAssetId` TEXT, `media_creator` TEXT, `media_artworkUrl` TEXT, `media_advisoryDurationMs` INTEGER, `media_showId` TEXT, `media_description` TEXT, `media_publishedEpochMs` INTEGER, `media_explicit` INTEGER, `media_albumLabel` TEXT, `media_artistLabel` TEXT, `media_episodeNumber` INTEGER, `media_seasonNumber` INTEGER, `media_albumId` TEXT, `media_trackNumber` INTEGER, `media_discNumber` INTEGER, `media_localArtworkPath` TEXT, `media_attribution_creator` TEXT, `media_attribution_sourceUrl` TEXT, `media_attribution_licenseName` TEXT, `media_attribution_licenseUrl` TEXT, PRIMARY KEY(`requestId`, `position`), FOREIGN KEY(`requestId`) REFERENCES `request_album_metadata`(`requestId`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_request_album_members_requestId` ON `request_album_members` (`requestId`)",
    "CREATE TABLE `request_album_member_artists` (`requestId` TEXT NOT NULL, `memberPosition` INTEGER NOT NULL, `artistPosition` INTEGER NOT NULL, `artistId` TEXT NOT NULL, PRIMARY KEY(`requestId`, `memberPosition`, `artistPosition`), FOREIGN KEY(`requestId`, `memberPosition`) REFERENCES `request_album_members`(`requestId`, `position`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_request_album_member_artists_requestId_memberPosition` ON `request_album_member_artists` (`requestId`, `memberPosition`)",
    "CREATE TABLE `request_show_metadata` (`requestId` TEXT NOT NULL, `show_id` TEXT NOT NULL, `show_title` TEXT NOT NULL, `show_feedUrl` TEXT NOT NULL, `show_publisher` TEXT NOT NULL, `show_description` TEXT NOT NULL, `show_artworkUrl` TEXT, `show_websiteUrl` TEXT, `show_language` TEXT, `show_explicit` INTEGER NOT NULL, `show_etag` TEXT, `show_lastModified` TEXT, `show_lastRefreshEpochMs` INTEGER, `show_copyright` TEXT, `show_localArtworkPath` TEXT, `show_attribution_creator` TEXT, `show_attribution_sourceUrl` TEXT, `show_attribution_licenseName` TEXT, `show_attribution_licenseUrl` TEXT, PRIMARY KEY(`requestId`), FOREIGN KEY(`requestId`) REFERENCES `acquisition_requests`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_request_show_metadata_requestId` ON `request_show_metadata` (`requestId`)",
    "CREATE TABLE `request_show_aliases` (`requestId` TEXT NOT NULL, `feedUrl` TEXT NOT NULL, PRIMARY KEY(`requestId`, `feedUrl`), FOREIGN KEY(`requestId`) REFERENCES `request_show_metadata`(`requestId`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_request_show_aliases_requestId` ON `request_show_aliases` (`requestId`)",
    "CREATE TABLE `request_show_members` (`requestId` TEXT NOT NULL, `position` INTEGER NOT NULL, `episodeId` TEXT NOT NULL, PRIMARY KEY(`requestId`, `position`), FOREIGN KEY(`requestId`) REFERENCES `request_show_metadata`(`requestId`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_request_show_members_requestId` ON `request_show_members` (`requestId`)",
    "CREATE TABLE `request_source_collection_metadata` (`requestId` TEXT NOT NULL, `provider` TEXT NOT NULL, `collectionId` TEXT NOT NULL, `completeTitle` TEXT NOT NULL, `publicUrl` TEXT, `declaredKind` TEXT, `declaredScope` TEXT, `creator` TEXT, `artworkUrl` TEXT, `description` TEXT, `observationProvenance` TEXT, PRIMARY KEY(`requestId`), FOREIGN KEY(`requestId`) REFERENCES `acquisition_requests`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_request_source_collection_metadata_requestId` ON `request_source_collection_metadata` (`requestId`)",
    "CREATE TABLE `release_observations` (`id` TEXT NOT NULL, `requestedReleaseMbid` TEXT NOT NULL, `observedReleaseMbid` TEXT NOT NULL, `completeTitle` TEXT NOT NULL, `status` TEXT, `date` TEXT, `country` TEXT, `barcode` TEXT, `disambiguation` TEXT, `textLanguage` TEXT, `textScript` TEXT, `packaging` TEXT, `packagingMbid` TEXT, `membershipStatus` TEXT NOT NULL, `fetchedAtEpochMs` INTEGER NOT NULL, `coreDigest` TEXT NOT NULL, `etag` TEXT, PRIMARY KEY(`id`))",
    "CREATE INDEX `index_release_observations_requestedReleaseMbid` ON `release_observations` (`requestedReleaseMbid`)",
    "CREATE INDEX `index_release_observations_observedReleaseMbid` ON `release_observations` (`observedReleaseMbid`)",
    "CREATE INDEX `index_release_observations_coreDigest` ON `release_observations` (`coreDigest`)",
    "CREATE TABLE `release_observation_groups` (`observationId` TEXT NOT NULL, `mbid` TEXT, `title` TEXT, `primaryType` TEXT, PRIMARY KEY(`observationId`), FOREIGN KEY(`observationId`) REFERENCES `release_observations`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_release_observation_groups_observationId` ON `release_observation_groups` (`observationId`)",
    "CREATE TABLE `release_group_secondary_types` (`observationId` TEXT NOT NULL, `position` INTEGER NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`observationId`, `position`), FOREIGN KEY(`observationId`) REFERENCES `release_observation_groups`(`observationId`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_release_group_secondary_types_observationId` ON `release_group_secondary_types` (`observationId`)",
    "CREATE TABLE `release_artist_credits` (`observationId` TEXT NOT NULL, `position` INTEGER NOT NULL, `sourceArrayOrdinal` INTEGER NOT NULL, `artistMbid` TEXT, `creditedName` TEXT, `joinPhrase` TEXT, `artistName` TEXT, PRIMARY KEY(`observationId`, `position`), FOREIGN KEY(`observationId`) REFERENCES `release_observations`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_release_artist_credits_observationId` ON `release_artist_credits` (`observationId`)",
    "CREATE TABLE `release_events` (`observationId` TEXT NOT NULL, `eventPosition` INTEGER NOT NULL, `sourceArrayOrdinal` INTEGER NOT NULL, `date` TEXT, `areaMbid` TEXT, `areaName` TEXT, PRIMARY KEY(`observationId`, `eventPosition`), FOREIGN KEY(`observationId`) REFERENCES `release_observations`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_release_events_observationId` ON `release_events` (`observationId`)",
    "CREATE TABLE `release_event_countries` (`observationId` TEXT NOT NULL, `eventPosition` INTEGER NOT NULL, `countryPosition` INTEGER NOT NULL, `countryCode` TEXT NOT NULL, PRIMARY KEY(`observationId`, `eventPosition`, `countryPosition`), FOREIGN KEY(`observationId`, `eventPosition`) REFERENCES `release_events`(`observationId`, `eventPosition`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_release_event_countries_observationId_eventPosition` ON `release_event_countries` (`observationId`, `eventPosition`)",
    "CREATE TABLE `release_labels` (`observationId` TEXT NOT NULL, `position` INTEGER NOT NULL, `sourceArrayOrdinal` INTEGER NOT NULL, `labelMbid` TEXT, `name` TEXT, `catalogueNumber` TEXT, PRIMARY KEY(`observationId`, `position`), FOREIGN KEY(`observationId`) REFERENCES `release_observations`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_release_labels_observationId` ON `release_labels` (`observationId`)",
    "CREATE TABLE `release_media` (`observationId` TEXT NOT NULL, `sourceArrayOrdinal` INTEGER NOT NULL, `listPosition` INTEGER NOT NULL, `mediumMbid` TEXT, `declaredPosition` INTEGER, `title` TEXT, `format` TEXT, `declaredTrackCount` INTEGER, `trackOffset` INTEGER, `membershipStatus` TEXT NOT NULL, PRIMARY KEY(`observationId`, `sourceArrayOrdinal`), FOREIGN KEY(`observationId`) REFERENCES `release_observations`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_release_media_observationId` ON `release_media` (`observationId`)",
    "CREATE UNIQUE INDEX `index_release_media_observationId_listPosition` ON `release_media` (`observationId`, `listPosition`)",
    "CREATE TABLE `release_tracks` (`observationId` TEXT NOT NULL, `mediumArrayOrdinal` INTEGER NOT NULL, `sourceArrayOrdinal` INTEGER NOT NULL, `listPosition` INTEGER NOT NULL, `declaredPosition` INTEGER, `printedNumber` TEXT, `trackMbid` TEXT, `completeTrackTitle` TEXT, `lengthMs` INTEGER, `recordingMbid` TEXT, `recordingTitle` TEXT, PRIMARY KEY(`observationId`, `mediumArrayOrdinal`, `sourceArrayOrdinal`), FOREIGN KEY(`observationId`, `mediumArrayOrdinal`) REFERENCES `release_media`(`observationId`, `sourceArrayOrdinal`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_release_tracks_observationId_mediumArrayOrdinal` ON `release_tracks` (`observationId`, `mediumArrayOrdinal`)",
    "CREATE UNIQUE INDEX `index_release_tracks_observationId_mediumArrayOrdinal_listPosition` ON `release_tracks` (`observationId`, `mediumArrayOrdinal`, `listPosition`)",
    "CREATE TABLE `release_track_artist_credits` (`observationId` TEXT NOT NULL, `mediumArrayOrdinal` INTEGER NOT NULL, `trackArrayOrdinal` INTEGER NOT NULL, `creditPosition` INTEGER NOT NULL, `sourceArrayOrdinal` INTEGER NOT NULL, `artistMbid` TEXT, `creditedName` TEXT, `joinPhrase` TEXT, `artistName` TEXT, PRIMARY KEY(`observationId`, `mediumArrayOrdinal`, `trackArrayOrdinal`, `creditPosition`), FOREIGN KEY(`observationId`, `mediumArrayOrdinal`, `trackArrayOrdinal`) REFERENCES `release_tracks`(`observationId`, `mediumArrayOrdinal`, `sourceArrayOrdinal`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_release_track_artist_credits_observationId_mediumArrayOrdinal_trackArrayOrdinal` ON `release_track_artist_credits` (`observationId`, `mediumArrayOrdinal`, `trackArrayOrdinal`)",
    "CREATE TABLE `release_summary_problems` (`observationId` TEXT NOT NULL, `problemPosition` INTEGER NOT NULL, `problem_code` TEXT NOT NULL, `problem_field` TEXT NOT NULL, `problem_mediumOrdinal` INTEGER, `problem_trackOrdinal` INTEGER, PRIMARY KEY(`observationId`, `problemPosition`), FOREIGN KEY(`observationId`) REFERENCES `release_observations`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_release_summary_problems_observationId` ON `release_summary_problems` (`observationId`)",
    "CREATE TABLE `release_core_problems` (`observationId` TEXT NOT NULL, `problemPosition` INTEGER NOT NULL, `problem_code` TEXT NOT NULL, `problem_field` TEXT NOT NULL, `problem_mediumOrdinal` INTEGER, `problem_trackOrdinal` INTEGER, PRIMARY KEY(`observationId`, `problemPosition`), FOREIGN KEY(`observationId`) REFERENCES `release_observations`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_release_core_problems_observationId` ON `release_core_problems` (`observationId`)",
    "CREATE TABLE `release_medium_problems` (`observationId` TEXT NOT NULL, `ownerMediumOrdinal` INTEGER NOT NULL, `problemPosition` INTEGER NOT NULL, `problem_code` TEXT NOT NULL, `problem_field` TEXT NOT NULL, `problem_mediumOrdinal` INTEGER, `problem_trackOrdinal` INTEGER, PRIMARY KEY(`observationId`, `ownerMediumOrdinal`, `problemPosition`), FOREIGN KEY(`observationId`, `ownerMediumOrdinal`) REFERENCES `release_media`(`observationId`, `sourceArrayOrdinal`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_release_medium_problems_observationId_ownerMediumOrdinal` ON `release_medium_problems` (`observationId`, `ownerMediumOrdinal`)",
    "CREATE TABLE `release_track_problems` (`observationId` TEXT NOT NULL, `ownerMediumOrdinal` INTEGER NOT NULL, `ownerTrackOrdinal` INTEGER NOT NULL, `problemPosition` INTEGER NOT NULL, `problem_code` TEXT NOT NULL, `problem_field` TEXT NOT NULL, `problem_mediumOrdinal` INTEGER, `problem_trackOrdinal` INTEGER, PRIMARY KEY(`observationId`, `ownerMediumOrdinal`, `ownerTrackOrdinal`, `problemPosition`), FOREIGN KEY(`observationId`, `ownerMediumOrdinal`, `ownerTrackOrdinal`) REFERENCES `release_tracks`(`observationId`, `mediumArrayOrdinal`, `sourceArrayOrdinal`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_release_track_problems_observationId_ownerMediumOrdinal_ownerTrackOrdinal` ON `release_track_problems` (`observationId`, `ownerMediumOrdinal`, `ownerTrackOrdinal`)",
    "CREATE TABLE `request_release_metadata` (`requestId` TEXT NOT NULL, `observationId` TEXT NOT NULL, PRIMARY KEY(`requestId`), FOREIGN KEY(`requestId`) REFERENCES `acquisition_requests`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`observationId`) REFERENCES `release_observations`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_request_release_metadata_requestId` ON `request_release_metadata` (`requestId`)",
    "CREATE INDEX `index_request_release_metadata_observationId` ON `request_release_metadata` (`observationId`)",
    "CREATE TABLE `request_media_release_occurrences` (`requestId` TEXT NOT NULL, `observationId` TEXT NOT NULL, `mediumArrayOrdinal` INTEGER NOT NULL, `trackArrayOrdinal` INTEGER NOT NULL, PRIMARY KEY(`requestId`), FOREIGN KEY(`requestId`) REFERENCES `request_media_metadata`(`requestId`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`observationId`, `mediumArrayOrdinal`, `trackArrayOrdinal`) REFERENCES `release_tracks`(`observationId`, `mediumArrayOrdinal`, `sourceArrayOrdinal`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_request_media_release_occurrences_requestId` ON `request_media_release_occurrences` (`requestId`)",
    "CREATE INDEX `index_request_media_release_occurrences_observationId_mediumArrayOrdinal_trackArrayOrdinal` ON `request_media_release_occurrences` (`observationId`, `mediumArrayOrdinal`, `trackArrayOrdinal`)",
    "CREATE TABLE `release_catalog_entries` (`requestedReleaseMbid` TEXT NOT NULL, `latestObservationId` TEXT NOT NULL, `updatedEpochMs` INTEGER NOT NULL, PRIMARY KEY(`requestedReleaseMbid`), FOREIGN KEY(`latestObservationId`) REFERENCES `release_observations`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_release_catalog_entries_latestObservationId` ON `release_catalog_entries` (`latestObservationId`)",
    "CREATE TABLE `saved_releases` (`requestedReleaseMbid` TEXT NOT NULL, `observationId` TEXT NOT NULL, `pinnedEpochMs` INTEGER NOT NULL, PRIMARY KEY(`requestedReleaseMbid`), FOREIGN KEY(`observationId`) REFERENCES `release_observations`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_saved_releases_observationId` ON `saved_releases` (`observationId`)",
    "CREATE TABLE `first_result_receipts` (`id` TEXT NOT NULL, `origin` TEXT NOT NULL, `requestId` TEXT, `legacyMediaId` TEXT, `queryTitle` TEXT NOT NULL, `rawOrdinal` INTEGER, `rawKind` TEXT, `rawId` TEXT, `rawTitle` TEXT, `rawPublicUrl` TEXT, `rawIssue` TEXT, `canonicalUrl` TEXT, `observedEpochMs` INTEGER, `legacyFirstResultKind` TEXT, `legacyFirstResultId` TEXT, `legacyFirstResultTitle` TEXT, `legacySelectedVideoId` TEXT, `legacyResolvedEpochMs` INTEGER, `legacySelectionOrdinal` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`requestId`) REFERENCES `acquisition_requests`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`legacyMediaId`) REFERENCES `media`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE UNIQUE INDEX `index_first_result_receipts_requestId` ON `first_result_receipts` (`requestId`)",
    "CREATE INDEX `index_first_result_receipts_legacyMediaId` ON `first_result_receipts` (`legacyMediaId`)",
    "CREATE TABLE `source_recordings` (`id` TEXT NOT NULL, `provider` TEXT NOT NULL, `canonicalId` TEXT NOT NULL, `canonicalUrl` TEXT NOT NULL, PRIMARY KEY(`id`))",
    "CREATE UNIQUE INDEX `index_source_recordings_provider_canonicalId` ON `source_recordings` (`provider`, `canonicalId`)",
    "CREATE TABLE `executable_subjects` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `mediaId` TEXT, `recordingId` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`mediaId`) REFERENCES `media`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`recordingId`) REFERENCES `source_recordings`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE UNIQUE INDEX `index_executable_subjects_mediaId` ON `executable_subjects` (`mediaId`)",
    "CREATE UNIQUE INDEX `index_executable_subjects_recordingId` ON `executable_subjects` (`recordingId`)",
    "CREATE TABLE `execution_bindings` (`id` TEXT NOT NULL, `subjectId` TEXT NOT NULL, `firstReceiptId` TEXT NOT NULL, `recordingId` TEXT, `origin` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`subjectId`) REFERENCES `executable_subjects`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`firstReceiptId`) REFERENCES `first_result_receipts`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`recordingId`) REFERENCES `source_recordings`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_execution_bindings_subjectId` ON `execution_bindings` (`subjectId`)",
    "CREATE UNIQUE INDEX `index_execution_bindings_firstReceiptId` ON `execution_bindings` (`firstReceiptId`)",
    "CREATE INDEX `index_execution_bindings_recordingId` ON `execution_bindings` (`recordingId`)",
    "CREATE TABLE `acquisition_outcomes` (`requestId` TEXT NOT NULL, `kind` TEXT NOT NULL, `receiptId` TEXT, `bindingId` TEXT, `collectionKind` TEXT, `collectionId` TEXT, `collectionCanonicalUrl` TEXT, `collectionScope` TEXT, `failureStage` TEXT, `problemCode` TEXT, `sourceProblemCode` TEXT, `sourceHttpStatus` INTEGER, PRIMARY KEY(`requestId`), FOREIGN KEY(`requestId`) REFERENCES `acquisition_requests`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`receiptId`) REFERENCES `first_result_receipts`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`bindingId`) REFERENCES `execution_bindings`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_acquisition_outcomes_requestId` ON `acquisition_outcomes` (`requestId`)",
    "CREATE INDEX `index_acquisition_outcomes_receiptId` ON `acquisition_outcomes` (`receiptId`)",
    "CREATE INDEX `index_acquisition_outcomes_bindingId` ON `acquisition_outcomes` (`bindingId`)",
    "CREATE TABLE `acquisition_jobs` (`requestId` TEXT NOT NULL, `state` TEXT NOT NULL, `leaseToken` TEXT, `leaseOwner` TEXT, `claimedEpochMs` INTEGER, `expiresEpochMs` INTEGER, `updatedEpochMs` INTEGER NOT NULL, PRIMARY KEY(`requestId`), FOREIGN KEY(`requestId`) REFERENCES `acquisition_requests`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_acquisition_jobs_requestId` ON `acquisition_jobs` (`requestId`)",
    "CREATE TABLE `collection_runs` (`id` TEXT NOT NULL, `parentRequestId` TEXT NOT NULL, `parentReceiptId` TEXT NOT NULL, `collectionKind` TEXT NOT NULL, `collectionId` TEXT NOT NULL, `collectionCanonicalUrl` TEXT NOT NULL, `scope` TEXT NOT NULL, `nextPageIndex` INTEGER NOT NULL, `nextOrdinal` INTEGER NOT NULL, `knownHoleCount` INTEGER NOT NULL, `traversal` TEXT NOT NULL, `observedMemberCount` INTEGER NOT NULL, `nonPlayableMemberCount` INTEGER NOT NULL, `problemCode` TEXT, `sourceProblemCode` TEXT, `sourceHttpStatus` INTEGER, `retryableFailuresAtFrontier` INTEGER NOT NULL, `stoppedReason` TEXT, `startedEpochMs` INTEGER NOT NULL, `updatedEpochMs` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`parentRequestId`) REFERENCES `acquisition_requests`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`parentReceiptId`) REFERENCES `first_result_receipts`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_collection_runs_parentRequestId` ON `collection_runs` (`parentRequestId`)",
    "CREATE INDEX `index_collection_runs_parentReceiptId` ON `collection_runs` (`parentReceiptId`)",
    "CREATE TABLE `collection_observations` (`runId` TEXT NOT NULL, `parentRequestId` TEXT NOT NULL, `collectionKind` TEXT NOT NULL, `collectionId` TEXT NOT NULL, `collectionCanonicalUrl` TEXT NOT NULL, `scope` TEXT NOT NULL, `title` TEXT NOT NULL, `titleOrigin` TEXT NOT NULL, `sourceUrl` TEXT NOT NULL, `consistency` TEXT NOT NULL, PRIMARY KEY(`runId`), FOREIGN KEY(`runId`) REFERENCES `collection_runs`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`parentRequestId`) REFERENCES `acquisition_requests`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_collection_observations_runId` ON `collection_observations` (`runId`)",
    "CREATE INDEX `index_collection_observations_parentRequestId` ON `collection_observations` (`parentRequestId`)",
    "CREATE TABLE `collection_identity_evidence` (`runId` TEXT NOT NULL, `evidence` TEXT NOT NULL, PRIMARY KEY(`runId`, `evidence`), FOREIGN KEY(`runId`) REFERENCES `collection_observations`(`runId`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_collection_identity_evidence_runId` ON `collection_identity_evidence` (`runId`)",
    "CREATE TABLE `collection_page_commits` (`runId` TEXT NOT NULL, `pageIndex` INTEGER NOT NULL, `startOrdinal` INTEGER NOT NULL, `memberCount` INTEGER NOT NULL, `traversal` TEXT NOT NULL, `observedMemberCount` INTEGER NOT NULL, `nonPlayableMemberCount` INTEGER NOT NULL, `problemCode` TEXT, `sourceProblemCode` TEXT, `sourceHttpStatus` INTEGER, `receivedEpochMs` INTEGER NOT NULL, PRIMARY KEY(`runId`, `pageIndex`), FOREIGN KEY(`runId`) REFERENCES `collection_runs`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_collection_page_commits_runId` ON `collection_page_commits` (`runId`)",
    "CREATE TABLE `collection_page_failures` (`id` TEXT NOT NULL, `runId` TEXT NOT NULL, `pageIndex` INTEGER NOT NULL, `startOrdinal` INTEGER NOT NULL, `traversal` TEXT NOT NULL, `observedMemberCount` INTEGER NOT NULL, `nonPlayableMemberCount` INTEGER NOT NULL, `problemCode` TEXT, `sourceProblemCode` TEXT, `sourceHttpStatus` INTEGER, `receivedEpochMs` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`runId`) REFERENCES `collection_runs`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_collection_page_failures_runId_pageIndex_startOrdinal` ON `collection_page_failures` (`runId`, `pageIndex`, `startOrdinal`)",
    "CREATE INDEX `index_collection_page_failures_runId` ON `collection_page_failures` (`runId`)",
    "CREATE TABLE `collection_members` (`runId` TEXT NOT NULL, `ordinal` INTEGER NOT NULL, `pageIndex` INTEGER NOT NULL, `raw_kind` TEXT, `raw_id` TEXT, `raw_title` TEXT, `raw_publicUrl` TEXT, `raw_issue` TEXT, `raw_ordinal` INTEGER NOT NULL, `display_id` TEXT, `display_title` TEXT, `display_publicUrl` TEXT, `display_kind` TEXT, `display_thumbnailUrl` TEXT, `display_subtitle` TEXT, `display_durationText` TEXT, PRIMARY KEY(`runId`, `ordinal`), FOREIGN KEY(`runId`) REFERENCES `collection_observations`(`runId`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`runId`, `pageIndex`) REFERENCES `collection_page_commits`(`runId`, `pageIndex`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_collection_members_runId` ON `collection_members` (`runId`)",
    "CREATE INDEX `index_collection_members_runId_pageIndex` ON `collection_members` (`runId`, `pageIndex`)",
    "CREATE TABLE `collection_snapshots` (`id` TEXT NOT NULL, `runId` TEXT NOT NULL, `observationCaptured` INTEGER NOT NULL, `lastCommittedPageIndex` INTEGER, `traversal` TEXT NOT NULL, `observedMemberCount` INTEGER NOT NULL, `nonPlayableMemberCount` INTEGER NOT NULL, `problemCode` TEXT, `sourceProblemCode` TEXT, `sourceHttpStatus` INTEGER, `sealedEpochMs` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`runId`) REFERENCES `collection_runs`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_collection_snapshots_runId` ON `collection_snapshots` (`runId`)",
    "CREATE UNIQUE INDEX `index_collection_snapshots_id_runId` ON `collection_snapshots` (`id`, `runId`)",
    "CREATE TABLE `collection_snapshot_members` (`snapshotId` TEXT NOT NULL, `ordinal` INTEGER NOT NULL, `runId` TEXT NOT NULL, `sourceOrdinal` INTEGER NOT NULL, PRIMARY KEY(`snapshotId`, `ordinal`), FOREIGN KEY(`snapshotId`, `runId`) REFERENCES `collection_snapshots`(`id`, `runId`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`runId`, `sourceOrdinal`) REFERENCES `collection_members`(`runId`, `ordinal`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_collection_snapshot_members_snapshotId_runId` ON `collection_snapshot_members` (`snapshotId`, `runId`)",
    "CREATE INDEX `index_collection_snapshot_members_runId_sourceOrdinal` ON `collection_snapshot_members` (`runId`, `sourceOrdinal`)",
    "CREATE TABLE `request_collection_member_metadata` (`requestId` TEXT NOT NULL, `snapshotId` TEXT NOT NULL, `ordinal` INTEGER NOT NULL, PRIMARY KEY(`requestId`), FOREIGN KEY(`requestId`) REFERENCES `acquisition_requests`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`snapshotId`, `ordinal`) REFERENCES `collection_snapshot_members`(`snapshotId`, `ordinal`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_request_collection_member_metadata_requestId` ON `request_collection_member_metadata` (`requestId`)",
    "CREATE INDEX `index_request_collection_member_metadata_snapshotId_ordinal` ON `request_collection_member_metadata` (`snapshotId`, `ordinal`)",
    "CREATE TABLE `admission_scopes` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `parentRequestId` TEXT, `snapshotId` TEXT, `controlsMode` TEXT NOT NULL, `acknowledgesUnobservedSuffix` INTEGER NOT NULL, `admittedEpochMs` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`parentRequestId`) REFERENCES `acquisition_requests`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`snapshotId`) REFERENCES `collection_snapshots`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_admission_scopes_parentRequestId` ON `admission_scopes` (`parentRequestId`)",
    "CREATE INDEX `index_admission_scopes_snapshotId` ON `admission_scopes` (`snapshotId`)",
    "CREATE TABLE `admission_scope_members` (`scopeId` TEXT NOT NULL, `position` INTEGER NOT NULL, `snapshotId` TEXT, `snapshotOrdinal` INTEGER, `requestId` TEXT, `disposition` TEXT NOT NULL, `exclusion` TEXT, PRIMARY KEY(`scopeId`, `position`), FOREIGN KEY(`scopeId`) REFERENCES `admission_scopes`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`snapshotId`, `snapshotOrdinal`) REFERENCES `collection_snapshot_members`(`snapshotId`, `ordinal`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`requestId`) REFERENCES `acquisition_requests`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_admission_scope_members_scopeId` ON `admission_scope_members` (`scopeId`)",
    "CREATE INDEX `index_admission_scope_members_snapshotId_snapshotOrdinal` ON `admission_scope_members` (`snapshotId`, `snapshotOrdinal`)",
    "CREATE INDEX `index_admission_scope_members_requestId` ON `admission_scope_members` (`requestId`)",
    "CREATE TABLE `execution_occurrences` (`id` TEXT NOT NULL, `origin` TEXT NOT NULL, `requestId` TEXT, `snapshotId` TEXT, `snapshotOrdinal` INTEGER, `scopeId` TEXT, `scopePosition` INTEGER, `subjectId` TEXT, `bindingId` TEXT, `controlsMode` TEXT NOT NULL, `selectionState` TEXT NOT NULL, `admittedEpochMs` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`requestId`) REFERENCES `acquisition_requests`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`snapshotId`, `snapshotOrdinal`) REFERENCES `collection_snapshot_members`(`snapshotId`, `ordinal`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`scopeId`, `scopePosition`) REFERENCES `admission_scope_members`(`scopeId`, `position`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`subjectId`) REFERENCES `executable_subjects`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`bindingId`) REFERENCES `execution_bindings`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_execution_occurrences_requestId` ON `execution_occurrences` (`requestId`)",
    "CREATE INDEX `index_execution_occurrences_snapshotId_snapshotOrdinal` ON `execution_occurrences` (`snapshotId`, `snapshotOrdinal`)",
    "CREATE INDEX `index_execution_occurrences_scopeId_scopePosition` ON `execution_occurrences` (`scopeId`, `scopePosition`)",
    "CREATE INDEX `index_execution_occurrences_subjectId` ON `execution_occurrences` (`subjectId`)",
    "CREATE INDEX `index_execution_occurrences_bindingId` ON `execution_occurrences` (`bindingId`)",
    "CREATE TABLE `execution_queue_revisions` (`contextId` TEXT NOT NULL, `editRevision` INTEGER NOT NULL, PRIMARY KEY(`contextId`), FOREIGN KEY(`contextId`) REFERENCES `queue_contexts`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_execution_queue_revisions_contextId` ON `execution_queue_revisions` (`contextId`)",
    "CREATE TABLE `queue_members` (`occurrenceId` TEXT NOT NULL, `contextId` TEXT NOT NULL, `position` INTEGER NOT NULL, `originalPosition` INTEGER NOT NULL, PRIMARY KEY(`occurrenceId`), FOREIGN KEY(`occurrenceId`) REFERENCES `execution_occurrences`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`contextId`) REFERENCES `queue_contexts`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_queue_members_occurrenceId` ON `queue_members` (`occurrenceId`)",
    "CREATE INDEX `index_queue_members_contextId` ON `queue_members` (`contextId`)",
    "CREATE UNIQUE INDEX `index_queue_members_contextId_position` ON `queue_members` (`contextId`, `position`)",
    "CREATE UNIQUE INDEX `index_queue_members_contextId_originalPosition` ON `queue_members` (`contextId`, `originalPosition`)",
    "CREATE UNIQUE INDEX `index_queue_members_contextId_occurrenceId` ON `queue_members` (`contextId`, `occurrenceId`)",
    "CREATE TABLE `queue_heads` (`contextId` TEXT NOT NULL, `occurrenceId` TEXT NOT NULL, PRIMARY KEY(`contextId`), FOREIGN KEY(`contextId`) REFERENCES `queue_contexts`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`contextId`, `occurrenceId`) REFERENCES `queue_members`(`contextId`, `occurrenceId`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_queue_heads_contextId` ON `queue_heads` (`contextId`)",
    "CREATE INDEX `index_queue_heads_contextId_occurrenceId` ON `queue_heads` (`contextId`, `occurrenceId`)",
    "CREATE TABLE `occurrence_checkpoints` (`occurrenceId` TEXT NOT NULL, `bindingId` TEXT NOT NULL, `positionMs` INTEGER NOT NULL, `measuredDurationMs` INTEGER, `observedEpochMs` INTEGER NOT NULL, `sessionId` TEXT NOT NULL, `generation` INTEGER NOT NULL, `sequence` INTEGER NOT NULL, `endedNaturally` INTEGER NOT NULL, PRIMARY KEY(`occurrenceId`), FOREIGN KEY(`occurrenceId`) REFERENCES `execution_occurrences`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`bindingId`) REFERENCES `execution_bindings`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_occurrence_checkpoints_occurrenceId` ON `occurrence_checkpoints` (`occurrenceId`)",
    "CREATE INDEX `index_occurrence_checkpoints_bindingId` ON `occurrence_checkpoints` (`bindingId`)",
    "CREATE TABLE `source_recording_resume` (`subjectId` TEXT NOT NULL, `controlsMode` TEXT NOT NULL, `occurrenceId` TEXT NOT NULL, PRIMARY KEY(`subjectId`, `controlsMode`), FOREIGN KEY(`subjectId`) REFERENCES `executable_subjects`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`occurrenceId`) REFERENCES `execution_occurrences`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_source_recording_resume_subjectId` ON `source_recording_resume` (`subjectId`)",
    "CREATE INDEX `index_source_recording_resume_occurrenceId` ON `source_recording_resume` (`occurrenceId`)",
    "CREATE TABLE `catalog_progress` (`mediaId` TEXT NOT NULL, `positionMs` INTEGER NOT NULL, `durationMs` INTEGER, `played` INTEGER NOT NULL, `updatedEpochMs` INTEGER NOT NULL, `sessionId` TEXT NOT NULL, `generation` INTEGER NOT NULL, `sequence` INTEGER NOT NULL, `executionBindingId` TEXT, `lastOccurrenceId` TEXT, PRIMARY KEY(`mediaId`), FOREIGN KEY(`mediaId`) REFERENCES `media`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`executionBindingId`) REFERENCES `execution_bindings`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`lastOccurrenceId`) REFERENCES `execution_occurrences`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_catalog_progress_mediaId` ON `catalog_progress` (`mediaId`)",
    "CREATE INDEX `index_catalog_progress_executionBindingId` ON `catalog_progress` (`executionBindingId`)",
    "CREATE INDEX `index_catalog_progress_lastOccurrenceId` ON `catalog_progress` (`lastOccurrenceId`)",
    "CREATE TABLE `listening_history` (`id` TEXT NOT NULL, `subjectId` TEXT NOT NULL, `origin` TEXT NOT NULL, `occurrenceId` TEXT, `bindingId` TEXT, `startedEpochMs` INTEGER NOT NULL, `lastPositionMs` INTEGER NOT NULL, `endedNaturally` INTEGER NOT NULL, `position` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`subjectId`) REFERENCES `executable_subjects`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`occurrenceId`) REFERENCES `execution_occurrences`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`bindingId`) REFERENCES `execution_bindings`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_listening_history_subjectId_startedEpochMs` ON `listening_history` (`subjectId`, `startedEpochMs`)",
    "CREATE INDEX `index_listening_history_subjectId` ON `listening_history` (`subjectId`)",
    "CREATE INDEX `index_listening_history_occurrenceId` ON `listening_history` (`occurrenceId`)",
    "CREATE INDEX `index_listening_history_bindingId` ON `listening_history` (`bindingId`)",
    "CREATE UNIQUE INDEX `index_listening_history_position` ON `listening_history` (`position`)",
    "CREATE TABLE `favorite_subjects` (`subjectId` TEXT NOT NULL, `origin` TEXT NOT NULL, `requestId` TEXT, `bindingId` TEXT, `occurrenceId` TEXT, `controlsMode` TEXT, PRIMARY KEY(`subjectId`), FOREIGN KEY(`subjectId`) REFERENCES `executable_subjects`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`requestId`) REFERENCES `acquisition_requests`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`bindingId`) REFERENCES `execution_bindings`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`occurrenceId`) REFERENCES `execution_occurrences`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_favorite_subjects_subjectId` ON `favorite_subjects` (`subjectId`)",
    "CREATE INDEX `index_favorite_subjects_requestId` ON `favorite_subjects` (`requestId`)",
    "CREATE INDEX `index_favorite_subjects_bindingId` ON `favorite_subjects` (`bindingId`)",
    "CREATE INDEX `index_favorite_subjects_occurrenceId` ON `favorite_subjects` (`occurrenceId`)",
    "CREATE TABLE `playlist_members` (`id` TEXT NOT NULL, `playlistId` TEXT NOT NULL, `subjectId` TEXT NOT NULL, `origin` TEXT NOT NULL, `requestId` TEXT, `bindingId` TEXT, `occurrenceId` TEXT, `controlsMode` TEXT, `position` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`subjectId`) REFERENCES `executable_subjects`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`requestId`) REFERENCES `acquisition_requests`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`bindingId`) REFERENCES `execution_bindings`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`occurrenceId`) REFERENCES `execution_occurrences`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_playlist_members_playlistId` ON `playlist_members` (`playlistId`)",
    "CREATE INDEX `index_playlist_members_subjectId` ON `playlist_members` (`subjectId`)",
    "CREATE INDEX `index_playlist_members_requestId` ON `playlist_members` (`requestId`)",
    "CREATE INDEX `index_playlist_members_bindingId` ON `playlist_members` (`bindingId`)",
    "CREATE INDEX `index_playlist_members_occurrenceId` ON `playlist_members` (`occurrenceId`)",
    "CREATE UNIQUE INDEX `index_playlist_members_playlistId_position` ON `playlist_members` (`playlistId`, `position`)",
    "CREATE TABLE `download_batches` (`id` TEXT NOT NULL, `scopeId` TEXT NOT NULL, `intent` TEXT NOT NULL, `intentRevision` INTEGER NOT NULL, `createdEpochMs` INTEGER NOT NULL, `updatedEpochMs` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`scopeId`) REFERENCES `admission_scopes`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_download_batches_scopeId` ON `download_batches` (`scopeId`)",
    "CREATE UNIQUE INDEX `index_download_batches_id_scopeId` ON `download_batches` (`id`, `scopeId`)",
    "CREATE TABLE `download_batch_members` (`batchId` TEXT NOT NULL, `position` INTEGER NOT NULL, `scopeId` TEXT NOT NULL, `scopeMemberPosition` INTEGER NOT NULL, `occurrenceId` TEXT NOT NULL, `requestId` TEXT NOT NULL, `requiredBindingId` TEXT, `claimId` TEXT, `state` TEXT NOT NULL, `failure_code` TEXT, `failure_detail` TEXT, `failure_retryAfterMs` INTEGER, PRIMARY KEY(`batchId`, `position`), FOREIGN KEY(`batchId`, `scopeId`) REFERENCES `download_batches`(`id`, `scopeId`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`scopeId`, `scopeMemberPosition`) REFERENCES `admission_scope_members`(`scopeId`, `position`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`occurrenceId`) REFERENCES `execution_occurrences`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`requestId`) REFERENCES `acquisition_requests`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`requiredBindingId`) REFERENCES `execution_bindings`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`claimId`) REFERENCES `download_claims`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_download_batch_members_batchId_scopeId` ON `download_batch_members` (`batchId`, `scopeId`)",
    "CREATE INDEX `index_download_batch_members_scopeId_scopeMemberPosition` ON `download_batch_members` (`scopeId`, `scopeMemberPosition`)",
    "CREATE INDEX `index_download_batch_members_occurrenceId` ON `download_batch_members` (`occurrenceId`)",
    "CREATE INDEX `index_download_batch_members_requestId` ON `download_batch_members` (`requestId`)",
    "CREATE INDEX `index_download_batch_members_requiredBindingId` ON `download_batch_members` (`requiredBindingId`)",
    "CREATE UNIQUE INDEX `index_download_batch_members_claimId` ON `download_batch_members` (`claimId`)",
    "CREATE TABLE `download_claims` (`id` TEXT NOT NULL, `ownerKind` TEXT NOT NULL, `physicalJobId` TEXT NOT NULL, `standaloneJobId` TEXT, `batchId` TEXT, `memberPosition` INTEGER, `requestedBindingId` TEXT, `intent` TEXT NOT NULL, `retainCompletedAsset` INTEGER NOT NULL, `updatedEpochMs` INTEGER NOT NULL, `generation` INTEGER NOT NULL, `ownerIntentRevision` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`physicalJobId`) REFERENCES `downloads`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`standaloneJobId`) REFERENCES `downloads`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`batchId`, `memberPosition`) REFERENCES `download_batch_members`(`batchId`, `position`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`requestedBindingId`) REFERENCES `execution_bindings`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_download_claims_physicalJobId` ON `download_claims` (`physicalJobId`)",
    "CREATE UNIQUE INDEX `index_download_claims_standaloneJobId` ON `download_claims` (`standaloneJobId`)",
    "CREATE UNIQUE INDEX `index_download_claims_batchId_memberPosition` ON `download_claims` (`batchId`, `memberPosition`)",
    "CREATE INDEX `index_download_claims_requestedBindingId` ON `download_claims` (`requestedBindingId`)",
    "CREATE TABLE `materialization_receipts` (`id` TEXT NOT NULL, `mediaId` TEXT, `subjectId` TEXT NOT NULL, `producerOrigin` TEXT NOT NULL, `recordingId` TEXT, `executionBindingId` TEXT, `sizeBytes` INTEGER NOT NULL, `kind` TEXT NOT NULL, `mimeType` TEXT, `codecs` TEXT, `sha256` TEXT, `representationId` TEXT, `validatorGeneration` INTEGER NOT NULL, `completedEpochMs` INTEGER NOT NULL, `measuredDurationMs` INTEGER, `materializerDownloadId` TEXT, `materializerAttemptId` TEXT, `ownershipGeneration` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`mediaId`) REFERENCES `media`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`subjectId`) REFERENCES `executable_subjects`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`recordingId`) REFERENCES `source_recordings`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`executionBindingId`) REFERENCES `execution_bindings`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_materialization_receipts_mediaId` ON `materialization_receipts` (`mediaId`)",
    "CREATE INDEX `index_materialization_receipts_subjectId` ON `materialization_receipts` (`subjectId`)",
    "CREATE INDEX `index_materialization_receipts_recordingId` ON `materialization_receipts` (`recordingId`)",
    "CREATE INDEX `index_materialization_receipts_executionBindingId` ON `materialization_receipts` (`executionBindingId`)",
    "CREATE TABLE `asset_uses` (`requestedBindingId` TEXT NOT NULL, `materializationAssetId` TEXT NOT NULL, `compatibilityPolicyVersion` INTEGER NOT NULL, `createdEpochMs` INTEGER NOT NULL, PRIMARY KEY(`requestedBindingId`, `materializationAssetId`), FOREIGN KEY(`requestedBindingId`) REFERENCES `execution_bindings`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`materializationAssetId`) REFERENCES `materialization_receipts`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE INDEX `index_asset_uses_requestedBindingId` ON `asset_uses` (`requestedBindingId`)",
    "CREATE INDEX `index_asset_uses_materializationAssetId` ON `asset_uses` (`materializationAssetId`)",
)

internal val PHYSICAL_SCHEMA_3_CREATE = listOf(
    "CREATE TABLE `offline_assets_schema3` (`id` TEXT NOT NULL, `mediaId` TEXT, `uri` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `kind` TEXT NOT NULL, `mimeType` TEXT, `codecs` TEXT, `sha256` TEXT, `representationId` TEXT, `validatorGeneration` INTEGER NOT NULL, `completedEpochMs` INTEGER NOT NULL, `executionBindingId` TEXT, `measuredDurationMs` INTEGER, `subjectId` TEXT NOT NULL, `producerOrigin` TEXT NOT NULL, `recordingId` TEXT, `ownershipGeneration` INTEGER NOT NULL, `materializerDownloadId` TEXT, `materializerAttemptId` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`mediaId`) REFERENCES `media`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`subjectId`) REFERENCES `executable_subjects`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`recordingId`) REFERENCES `source_recordings`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`executionBindingId`) REFERENCES `execution_bindings`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`id`) REFERENCES `materialization_receipts`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE TABLE `downloads_schema3` (`id` TEXT NOT NULL, `mediaId` TEXT, `status` TEXT NOT NULL, `bytesDownloaded` INTEGER NOT NULL, `totalBytes` INTEGER, `assetId` TEXT, `failure_code` TEXT, `failure_detail` TEXT, `failure_retryAfterMs` INTEGER, `stagingPath` TEXT, `representationId` TEXT, `etag` TEXT, `lastModified` TEXT, `validatorGeneration` INTEGER NOT NULL, `updatedEpochMs` INTEGER NOT NULL, `executionBindingId` TEXT, `attemptCount` INTEGER NOT NULL, `activeAttemptId` TEXT, `nextRetryEpochMs` INTEGER, `priority` INTEGER NOT NULL, `createdEpochMs` INTEGER NOT NULL, `userPaused` INTEGER NOT NULL, `leaseOwner` TEXT, `leaseExpiresEpochMs` INTEGER, `lastProgressEpochMs` INTEGER, `automaticRetryCount` INTEGER NOT NULL, `cleanupPending` INTEGER NOT NULL DEFAULT 0, `removeWhenClean` INTEGER NOT NULL DEFAULT 0, `subjectId` TEXT NOT NULL, `producerOrigin` TEXT NOT NULL, `recordingId` TEXT, `ownershipGeneration` INTEGER NOT NULL, `maintenanceKind` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`mediaId`) REFERENCES `media`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`subjectId`) REFERENCES `executable_subjects`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`recordingId`) REFERENCES `source_recordings`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`executionBindingId`) REFERENCES `execution_bindings`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`assetId`) REFERENCES `offline_assets`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`id`, `activeAttemptId`) REFERENCES `download_attempts`(`downloadId`, `id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE TABLE `download_attempts_schema3` (`id` TEXT NOT NULL, `downloadId` TEXT NOT NULL, `number` INTEGER NOT NULL, `status` TEXT NOT NULL, `startedEpochMs` INTEGER NOT NULL, `finishedEpochMs` INTEGER, `bytesDownloaded` INTEGER NOT NULL, `totalBytes` INTEGER, `failure_code` TEXT, `failure_detail` TEXT, `failure_retryAfterMs` INTEGER, `stagingPath` TEXT, `representationId` TEXT, `executionBindingId` TEXT, `etag` TEXT, `lastModified` TEXT, `validatorGeneration` INTEGER NOT NULL, `backgroundTaskId` TEXT, `mediaId` TEXT, `subjectId` TEXT NOT NULL, `producerOrigin` TEXT NOT NULL, `recordingId` TEXT, `ownershipGeneration` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`mediaId`) REFERENCES `media`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`subjectId`) REFERENCES `executable_subjects`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`recordingId`) REFERENCES `source_recordings`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`executionBindingId`) REFERENCES `execution_bindings`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`downloadId`) REFERENCES `downloads`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
    "CREATE TABLE `download_finalizations_schema3` (`downloadId` TEXT NOT NULL, `mediaId` TEXT, `stagingPath` TEXT NOT NULL, `finalPath` TEXT NOT NULL, `sha256` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `attemptId` TEXT, `mediaDurationMs` INTEGER, `mimeType` TEXT, `codecs` TEXT, `executionBindingId` TEXT, `representationId` TEXT, `validatorGeneration` INTEGER NOT NULL, `subjectId` TEXT NOT NULL, `producerOrigin` TEXT NOT NULL, `recordingId` TEXT, `ownershipGeneration` INTEGER NOT NULL, `assetId` TEXT, PRIMARY KEY(`downloadId`), FOREIGN KEY(`mediaId`) REFERENCES `media`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`subjectId`) REFERENCES `executable_subjects`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`recordingId`) REFERENCES `source_recordings`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`executionBindingId`) REFERENCES `execution_bindings`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`downloadId`) REFERENCES `downloads`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED, FOREIGN KEY(`downloadId`, `attemptId`) REFERENCES `download_attempts`(`downloadId`, `id`) ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED)",
)

internal val PHYSICAL_SCHEMA_3_INDEXES = listOf(
    "CREATE INDEX `index_offline_assets_mediaId` ON `offline_assets` (`mediaId`)",
    "CREATE INDEX `index_offline_assets_subjectId` ON `offline_assets` (`subjectId`)",
    "CREATE INDEX `index_offline_assets_recordingId` ON `offline_assets` (`recordingId`)",
    "CREATE INDEX `index_offline_assets_executionBindingId` ON `offline_assets` (`executionBindingId`)",
    "CREATE INDEX `index_offline_assets_id` ON `offline_assets` (`id`)",
    "CREATE UNIQUE INDEX `index_offline_assets_uri` ON `offline_assets` (`uri`)",
    "CREATE INDEX `index_downloads_status_nextRetryEpochMs_priority` ON `downloads` (`status`, `nextRetryEpochMs`, `priority`)",
    "CREATE INDEX `index_downloads_activeAttemptId` ON `downloads` (`activeAttemptId`)",
    "CREATE UNIQUE INDEX `index_downloads_mediaId` ON `downloads` (`mediaId`)",
    "CREATE UNIQUE INDEX `index_downloads_subjectId` ON `downloads` (`subjectId`)",
    "CREATE INDEX `index_downloads_recordingId` ON `downloads` (`recordingId`)",
    "CREATE INDEX `index_downloads_executionBindingId` ON `downloads` (`executionBindingId`)",
    "CREATE UNIQUE INDEX `index_downloads_assetId` ON `downloads` (`assetId`)",
    "CREATE INDEX `index_downloads_id_activeAttemptId` ON `downloads` (`id`, `activeAttemptId`)",
    "CREATE INDEX `index_download_attempts_mediaId` ON `download_attempts` (`mediaId`)",
    "CREATE INDEX `index_download_attempts_subjectId` ON `download_attempts` (`subjectId`)",
    "CREATE INDEX `index_download_attempts_recordingId` ON `download_attempts` (`recordingId`)",
    "CREATE INDEX `index_download_attempts_executionBindingId` ON `download_attempts` (`executionBindingId`)",
    "CREATE INDEX `index_download_attempts_downloadId` ON `download_attempts` (`downloadId`)",
    "CREATE UNIQUE INDEX `index_download_attempts_downloadId_number` ON `download_attempts` (`downloadId`, `number`)",
    "CREATE UNIQUE INDEX `index_download_attempts_downloadId_id` ON `download_attempts` (`downloadId`, `id`)",
    "CREATE INDEX `index_download_finalizations_attemptId` ON `download_finalizations` (`attemptId`)",
    "CREATE INDEX `index_download_finalizations_assetId` ON `download_finalizations` (`assetId`)",
    "CREATE INDEX `index_download_finalizations_mediaId` ON `download_finalizations` (`mediaId`)",
    "CREATE INDEX `index_download_finalizations_subjectId` ON `download_finalizations` (`subjectId`)",
    "CREATE INDEX `index_download_finalizations_recordingId` ON `download_finalizations` (`recordingId`)",
    "CREATE INDEX `index_download_finalizations_executionBindingId` ON `download_finalizations` (`executionBindingId`)",
    "CREATE INDEX `index_download_finalizations_downloadId` ON `download_finalizations` (`downloadId`)",
    "CREATE INDEX `index_download_finalizations_downloadId_attemptId` ON `download_finalizations` (`downloadId`, `attemptId`)",
)
