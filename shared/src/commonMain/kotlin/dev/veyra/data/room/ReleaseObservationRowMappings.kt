package dev.veyra.data.room

import dev.veyra.model.CapturedReleaseObservation
import dev.veyra.model.ReleaseObservationId
import dev.veyra.source.release.ReleaseArtistCredit
import dev.veyra.source.release.ReleaseCoreMetadata
import dev.veyra.source.release.ReleaseEventMetadata
import dev.veyra.source.release.ReleaseGroupMetadata
import dev.veyra.source.release.ReleaseLabelMetadata
import dev.veyra.source.release.ReleaseMediumObservation
import dev.veyra.source.release.ReleaseObservation
import dev.veyra.source.release.ReleaseSummaryMetadata
import dev.veyra.source.release.ReleaseTrackObservation

/**
 * Transaction-local normalized immutable observations, not a persistence blob or cache.
 * Request links and mutable catalogue/saved pointers belong to the enclosing Room owner.
 * List order here is not database order; the explicit position columns preserve DTO order.
 */
internal data class ReleaseObservationRows(
    val observations: List<ReleaseObservationEntity> = emptyList(),
    val groups: List<ReleaseObservationGroupEntity> = emptyList(),
    val groupSecondaryTypes: List<ReleaseGroupSecondaryTypeEntity> = emptyList(),
    val artistCredits: List<ReleaseArtistCreditEntity> = emptyList(),
    val events: List<ReleaseEventEntity> = emptyList(),
    val eventCountries: List<ReleaseEventCountryEntity> = emptyList(),
    val labels: List<ReleaseLabelEntity> = emptyList(),
    val media: List<ReleaseMediumEntity> = emptyList(),
    val tracks: List<ReleaseTrackEntity> = emptyList(),
    val trackArtistCredits: List<ReleaseTrackArtistCreditEntity> = emptyList(),
    val summaryProblems: List<ReleaseSummaryProblemEntity> = emptyList(),
    val coreProblems: List<ReleaseCoreProblemEntity> = emptyList(),
    val mediumProblems: List<ReleaseMediumProblemEntity> = emptyList(),
    val trackProblems: List<ReleaseTrackProblemEntity> = emptyList(),
)

/** Gather only. The caller owns the transaction; reconstruction validates the complete graph. */
internal suspend fun AcquisitionDao.readCapturedReleaseRows(): ReleaseObservationRows = ReleaseObservationRows(
    observations = readReleaseObservationRows(),
    groups = readReleaseObservationGroupRows(),
    groupSecondaryTypes = readReleaseGroupSecondaryTypeRows(),
    artistCredits = readReleaseArtistCreditRows(),
    events = readReleaseEventRows(),
    eventCountries = readReleaseEventCountryRows(),
    labels = readReleaseLabelRows(),
    media = readReleaseMediumRows(),
    tracks = readReleaseTrackRows(),
    trackArtistCredits = readReleaseTrackArtistCreditRows(),
    summaryProblems = readReleaseSummaryProblemRows(),
    coreProblems = readReleaseCoreProblemRows(),
    mediumProblems = readReleaseMediumProblemRows(),
    trackProblems = readReleaseTrackProblemRows(),
)

/**
 * Only exact repeated captures may share an observation ID. Edition/recording IDs, digests,
 * source ordinals on credits/events/labels, and repeated problems never deduplicate values.
 * Admission/title policy is separate: persistence does not repair malformed captured metadata.
 */
internal fun Iterable<CapturedReleaseObservation>.toReleaseObservationRows(): ReleaseObservationRows {
    val captures = linkedMapOf<ReleaseObservationId, CapturedReleaseObservation>()
    for (capture in this) {
        val previous = captures[capture.id]
        require(previous == null || previous == capture) { "Conflicting captured release observation" }
        if (previous == null) captures[capture.id] = capture
    }

    val observations = mutableListOf<ReleaseObservationEntity>()
    val groups = mutableListOf<ReleaseObservationGroupEntity>()
    val groupSecondaryTypes = mutableListOf<ReleaseGroupSecondaryTypeEntity>()
    val artistCredits = mutableListOf<ReleaseArtistCreditEntity>()
    val events = mutableListOf<ReleaseEventEntity>()
    val eventCountries = mutableListOf<ReleaseEventCountryEntity>()
    val labels = mutableListOf<ReleaseLabelEntity>()
    val media = mutableListOf<ReleaseMediumEntity>()
    val tracks = mutableListOf<ReleaseTrackEntity>()
    val trackArtistCredits = mutableListOf<ReleaseTrackArtistCreditEntity>()
    val summaryProblems = mutableListOf<ReleaseSummaryProblemEntity>()
    val coreProblems = mutableListOf<ReleaseCoreProblemEntity>()
    val mediumProblems = mutableListOf<ReleaseMediumProblemEntity>()
    val trackProblems = mutableListOf<ReleaseTrackProblemEntity>()

    for (capture in captures.values) {
        val id = capture.id.value
        val observation = capture.value
        val core = observation.metadata
        val release = core.release
        observations += ReleaseObservationEntity(
            id = id,
            requestedReleaseMbid = observation.requestedReleaseMbid,
            observedReleaseMbid = release.releaseMbid,
            completeTitle = release.completeTitle,
            status = release.status,
            date = release.date,
            country = release.country,
            barcode = release.barcode,
            disambiguation = release.disambiguation,
            textLanguage = release.textLanguage,
            textScript = release.textScript,
            packaging = release.packaging,
            packagingMbid = release.packagingMbid,
            membershipStatus = core.membershipStatus,
            fetchedAtEpochMs = observation.fetchedAtEpochMs,
            coreDigest = observation.coreDigest,
            etag = observation.etag,
        )
        release.releaseGroup?.let { group ->
            groups += ReleaseObservationGroupEntity(id, group.mbid, group.title, group.primaryType)
            group.secondaryTypes.forEachIndexed { position, value ->
                groupSecondaryTypes += ReleaseGroupSecondaryTypeEntity(id, position, value)
            }
        }
        release.artistCredits.forEachIndexed { position, credit ->
            artistCredits += ReleaseArtistCreditEntity(
                id, position, credit.sourceArrayOrdinal, credit.artistMbid,
                credit.creditedName, credit.joinPhrase, credit.artistName,
            )
        }
        release.releaseEvents.forEachIndexed { eventPosition, event ->
            events += ReleaseEventEntity(
                id, eventPosition, event.sourceArrayOrdinal, event.date, event.areaMbid, event.areaName,
            )
            event.countryCodes.forEachIndexed { countryPosition, country ->
                eventCountries += ReleaseEventCountryEntity(id, eventPosition, countryPosition, country)
            }
        }
        release.labels.forEachIndexed { position, label ->
            labels += ReleaseLabelEntity(
                id, position, label.sourceArrayOrdinal, label.labelMbid, label.name, label.catalogueNumber,
            )
        }
        core.media.forEachIndexed { mediumPosition, medium ->
            val mediumOrdinal = medium.sourceArrayOrdinal
            media += ReleaseMediumEntity(
                observationId = id,
                sourceArrayOrdinal = mediumOrdinal,
                listPosition = mediumPosition,
                mediumMbid = medium.mediumMbid,
                declaredPosition = medium.declaredPosition,
                title = medium.title,
                format = medium.format,
                declaredTrackCount = medium.declaredTrackCount,
                trackOffset = medium.trackOffset,
                membershipStatus = medium.membershipStatus,
            )
            medium.tracks.forEachIndexed { trackPosition, track ->
                val trackOrdinal = track.sourceArrayOrdinal
                tracks += ReleaseTrackEntity(
                    observationId = id,
                    mediumArrayOrdinal = mediumOrdinal,
                    sourceArrayOrdinal = trackOrdinal,
                    listPosition = trackPosition,
                    declaredPosition = track.declaredPosition,
                    printedNumber = track.printedNumber,
                    trackMbid = track.trackMbid,
                    completeTrackTitle = track.completeTrackTitle,
                    lengthMs = track.lengthMs,
                    recordingMbid = track.recordingMbid,
                    recordingTitle = track.recordingTitle,
                )
                track.artistCredits.forEachIndexed { creditPosition, credit ->
                    trackArtistCredits += ReleaseTrackArtistCreditEntity(
                        id, mediumOrdinal, trackOrdinal, creditPosition, credit.sourceArrayOrdinal,
                        credit.artistMbid, credit.creditedName, credit.joinPhrase, credit.artistName,
                    )
                }
                track.problems.forEachIndexed { position, problem ->
                    trackProblems += ReleaseTrackProblemEntity(id, mediumOrdinal, trackOrdinal, position, problem)
                }
            }
            medium.problems.forEachIndexed { position, problem ->
                mediumProblems += ReleaseMediumProblemEntity(id, mediumOrdinal, position, problem)
            }
        }
        release.problems.forEachIndexed { position, problem ->
            summaryProblems += ReleaseSummaryProblemEntity(id, position, problem)
        }
        core.problems.forEachIndexed { position, problem ->
            coreProblems += ReleaseCoreProblemEntity(id, position, problem)
        }
    }

    val rows = ReleaseObservationRows(
        observations, groups, groupSecondaryTypes, artistCredits, events, eventCountries, labels,
        media, tracks, trackArtistCredits, summaryProblems, coreProblems, mediumProblems, trackProblems,
    )
    // Also rejects source-ordinal key collisions before any normalized graph can be written.
    require(rows.toCapturedReleaseObservations() == captures) { "Lossy captured release normalization" }
    return rows
}

/**
 * Reconstruct only from the supplied relational graph. Check every key, owner and list position
 * before associating rows; a malformed graph must not lose duplicates/orphans through map lookup.
 * Source-array ordinals are nonnegative identities, NOT inferred contiguous list positions.
 */
internal fun ReleaseObservationRows.toCapturedReleaseObservations(): Map<ReleaseObservationId, CapturedReleaseObservation> {
    val observationsById = observations.uniqueReleaseRowsBy { it.id }
    val groupsById = groups.uniqueReleaseRowsBy { it.observationId }
    require(groupsById.keys.all { it in observationsById }) { "Orphan release row" }
    require(
        artistCredits.all { it.sourceArrayOrdinal >= 0 } &&
            events.all { it.sourceArrayOrdinal >= 0 } &&
            labels.all { it.sourceArrayOrdinal >= 0 } &&
            media.all { it.sourceArrayOrdinal >= 0 } &&
            tracks.all { it.sourceArrayOrdinal >= 0 } &&
            trackArtistCredits.all { it.sourceArrayOrdinal >= 0 },
    ) { "Negative release source-array ordinal" }

    val secondaryByGroup = groupSecondaryTypes.orderedReleaseChildren(
        groupsById.keys, { it.observationId }, { it.position },
    )
    val creditsByObservation = artistCredits.orderedReleaseChildren(
        observationsById.keys, { it.observationId }, { it.position },
    )
    val eventsByObservation = events.orderedReleaseChildren(
        observationsById.keys, { it.observationId }, { it.eventPosition },
    )
    val eventKeys = events.map { ReleaseEventKey(it.observationId, it.eventPosition) }.toSet()
    val countriesByEvent = eventCountries.orderedReleaseChildren(
        eventKeys, { ReleaseEventKey(it.observationId, it.eventPosition) }, { it.countryPosition },
    )
    val labelsByObservation = labels.orderedReleaseChildren(
        observationsById.keys, { it.observationId }, { it.position },
    )
    val mediaByKey = media.uniqueReleaseRowsBy { ReleaseMediumKey(it.observationId, it.sourceArrayOrdinal) }
    val mediaByObservation = media.orderedReleaseChildren(
        observationsById.keys, { it.observationId }, { it.listPosition },
    )
    val tracksByKey = tracks.uniqueReleaseRowsBy {
        ReleaseTrackKey(it.observationId, it.mediumArrayOrdinal, it.sourceArrayOrdinal)
    }
    val tracksByMedium = tracks.orderedReleaseChildren(
        mediaByKey.keys, { ReleaseMediumKey(it.observationId, it.mediumArrayOrdinal) }, { it.listPosition },
    )
    val creditsByTrack = trackArtistCredits.orderedReleaseChildren(
        tracksByKey.keys,
        { ReleaseTrackKey(it.observationId, it.mediumArrayOrdinal, it.trackArrayOrdinal) },
        { it.creditPosition },
    )
    val summaryProblemsByObservation = summaryProblems.orderedReleaseChildren(
        observationsById.keys, { it.observationId }, { it.problemPosition },
    )
    val coreProblemsByObservation = coreProblems.orderedReleaseChildren(
        observationsById.keys, { it.observationId }, { it.problemPosition },
    )
    val problemsByMedium = mediumProblems.orderedReleaseChildren(
        mediaByKey.keys, { ReleaseMediumKey(it.observationId, it.ownerMediumOrdinal) }, { it.problemPosition },
    )
    val problemsByTrack = trackProblems.orderedReleaseChildren(
        tracksByKey.keys,
        { ReleaseTrackKey(it.observationId, it.ownerMediumOrdinal, it.ownerTrackOrdinal) },
        { it.problemPosition },
    )

    return observationsById.values.associate { row ->
        val id = ReleaseObservationId(row.id)
        val group = groupsById[row.id]
        val release = ReleaseSummaryMetadata(
            releaseMbid = row.observedReleaseMbid,
            completeTitle = row.completeTitle,
            releaseGroup = group?.let {
                ReleaseGroupMetadata(it.mbid, it.title, it.primaryType,
                    secondaryByGroup[row.id].orEmpty().map { secondary -> secondary.value })
            },
            artistCredits = creditsByObservation[row.id].orEmpty().map {
                ReleaseArtistCredit(it.sourceArrayOrdinal, it.artistMbid, it.creditedName, it.joinPhrase, it.artistName)
            },
            status = row.status,
            date = row.date,
            country = row.country,
            releaseEvents = eventsByObservation[row.id].orEmpty().map { event ->
                ReleaseEventMetadata(
                    sourceArrayOrdinal = event.sourceArrayOrdinal,
                    date = event.date,
                    areaMbid = event.areaMbid,
                    areaName = event.areaName,
                    countryCodes = countriesByEvent[ReleaseEventKey(row.id, event.eventPosition)]
                        .orEmpty().map { it.countryCode },
                )
            },
            labels = labelsByObservation[row.id].orEmpty().map {
                ReleaseLabelMetadata(it.sourceArrayOrdinal, it.labelMbid, it.name, it.catalogueNumber)
            },
            barcode = row.barcode,
            disambiguation = row.disambiguation,
            textLanguage = row.textLanguage,
            textScript = row.textScript,
            packaging = row.packaging,
            packagingMbid = row.packagingMbid,
            problems = summaryProblemsByObservation[row.id].orEmpty().map { it.problem },
        )
        val core = ReleaseCoreMetadata(
            release = release,
            media = mediaByObservation[row.id].orEmpty().map { medium ->
                val mediumKey = ReleaseMediumKey(row.id, medium.sourceArrayOrdinal)
                ReleaseMediumObservation(
                    sourceArrayOrdinal = medium.sourceArrayOrdinal,
                    mediumMbid = medium.mediumMbid,
                    declaredPosition = medium.declaredPosition,
                    title = medium.title,
                    format = medium.format,
                    declaredTrackCount = medium.declaredTrackCount,
                    trackOffset = medium.trackOffset,
                    tracks = tracksByMedium[mediumKey].orEmpty().map { track ->
                        val trackKey = ReleaseTrackKey(row.id, medium.sourceArrayOrdinal, track.sourceArrayOrdinal)
                        ReleaseTrackObservation(
                            sourceArrayOrdinal = track.sourceArrayOrdinal,
                            declaredPosition = track.declaredPosition,
                            printedNumber = track.printedNumber,
                            trackMbid = track.trackMbid,
                            completeTrackTitle = track.completeTrackTitle,
                            artistCredits = creditsByTrack[trackKey].orEmpty().map {
                                ReleaseArtistCredit(it.sourceArrayOrdinal, it.artistMbid, it.creditedName,
                                    it.joinPhrase, it.artistName)
                            },
                            lengthMs = track.lengthMs,
                            recordingMbid = track.recordingMbid,
                            recordingTitle = track.recordingTitle,
                            problems = problemsByTrack[trackKey].orEmpty().map { it.problem },
                        )
                    },
                    membershipStatus = medium.membershipStatus,
                    problems = problemsByMedium[mediumKey].orEmpty().map { it.problem },
                )
            },
            membershipStatus = row.membershipStatus,
            problems = coreProblemsByObservation[row.id].orEmpty().map { it.problem },
        )
        id to CapturedReleaseObservation(id, ReleaseObservation(
            requestedReleaseMbid = row.requestedReleaseMbid,
            metadata = core,
            fetchedAtEpochMs = row.fetchedAtEpochMs,
            coreDigest = row.coreDigest,
            etag = row.etag,
        ))
    }
}

/**
 * Append whole NEW observation IDs only. Both inputs are full transaction-local graphs, not
 * deltas. Preflight all keys/relations and existing captures before the first DAO suspension.
 * The detached addition rows cannot change if a caller-owned input list changes during an insert.
 *
 * No transaction, upsert, delete, retry or compensating write starts here. The enclosing owner
 * must roll back any DAO failure and verify its entire intended graph/revision before commit.
 */
internal suspend fun AcquisitionDao.appendCapturedReleaseRows(
    before: ReleaseObservationRows,
    next: ReleaseObservationRows,
) {
    val previous = before.toCapturedReleaseObservations()
    val desired = next.toCapturedReleaseObservations()
    require(previous.all { (id, capture) -> desired[id] == capture }) { "Captured release observation changed or removed" }
    val added = desired.filterKeys { it !in previous }.values.toReleaseObservationRows()

    if (added.observations.isNotEmpty()) insertReleaseObservationRows(added.observations)
    if (added.groups.isNotEmpty()) insertReleaseObservationGroupRows(added.groups)
    if (added.groupSecondaryTypes.isNotEmpty()) insertReleaseGroupSecondaryTypeRows(added.groupSecondaryTypes)
    if (added.artistCredits.isNotEmpty()) insertReleaseArtistCreditRows(added.artistCredits)
    if (added.events.isNotEmpty()) insertReleaseEventRows(added.events)
    if (added.eventCountries.isNotEmpty()) insertReleaseEventCountryRows(added.eventCountries)
    if (added.labels.isNotEmpty()) insertReleaseLabelRows(added.labels)
    if (added.media.isNotEmpty()) insertReleaseMediumRows(added.media)
    if (added.tracks.isNotEmpty()) insertReleaseTrackRows(added.tracks)
    if (added.trackArtistCredits.isNotEmpty()) insertReleaseTrackArtistCreditRows(added.trackArtistCredits)
    if (added.summaryProblems.isNotEmpty()) insertReleaseSummaryProblemRows(added.summaryProblems)
    if (added.coreProblems.isNotEmpty()) insertReleaseCoreProblemRows(added.coreProblems)
    if (added.mediumProblems.isNotEmpty()) insertReleaseMediumProblemRows(added.mediumProblems)
    if (added.trackProblems.isNotEmpty()) insertReleaseTrackProblemRows(added.trackProblems)
}

private data class ReleaseEventKey(val observationId: String, val eventPosition: Int)
private data class ReleaseMediumKey(val observationId: String, val mediumOrdinal: Int)
private data class ReleaseTrackKey(val observationId: String, val mediumOrdinal: Int, val trackOrdinal: Int)

private fun <T, K> List<T>.uniqueReleaseRowsBy(key: (T) -> K): Map<K, T> {
    val result = linkedMapOf<K, T>()
    for (row in this) {
        val identity = key(row)
        require(!result.containsKey(identity)) { "Duplicate release row key" }
        result[identity] = row
    }
    return result
}

private fun <T, K> List<T>.orderedReleaseChildren(
    parents: Set<K>,
    owner: (T) -> K,
    position: (T) -> Int,
): Map<K, List<T>> {
    val grouped = groupBy(owner)
    require(grouped.keys.all { it in parents }) { "Orphan release row" }
    return grouped.mapValues { (_, children) ->
        children.sortedBy(position).also { ordered ->
            ordered.forEachIndexed { index, row ->
                require(position(row) == index) { "Broken release occurrence order" }
            }
        }
    }
}
