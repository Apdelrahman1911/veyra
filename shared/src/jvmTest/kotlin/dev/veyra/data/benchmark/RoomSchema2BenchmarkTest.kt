package dev.veyra.data.benchmark

import dev.veyra.data.room.toRows
import dev.veyra.data.room.toSnapshot
import dev.veyra.data.roomWorkDirectory
import dev.veyra.data.validateLibrarySnapshot
import dev.veyra.data.validateLibraryTransition
import dev.veyra.model.AppSnapshot
import dev.veyra.model.MediaKind
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

/** Harness unit checks only. These never invoke the benchmark or claim measured Room performance. */
class RoomSchema2BenchmarkTest {
    @Test fun authoredFixtureIsDeterministicAndContainsNoFinalAssetClaim() {
        val fixture = RoomSchema2Fixture.create(1_000)
        assertEquals(fixture, RoomSchema2Fixture.create(1_000))
        assertEquals(500, fixture.media.values.count { it.kind == MediaKind.MUSIC })
        assertEquals(500, fixture.media.values.count { it.kind == MediaKind.EPISODE })
        assertEquals(1_000, fixture.playbackBindings.size)
        assertTrue(fixture.downloads.values.all { it.asset == null })
        assertTrue(fixture.downloadFinalizations.isEmpty())
        assertTrue(fixture.media.values.all { it.artworkUrl == null && it.localArtworkPath == null })
    }

    @Test fun bothShapesRoundTripThroughTheActualSchema2RowMappersWithoutExtraGraphSize() {
        for (size in listOf(1_000, 10_000)) {
            val fixture = RoomSchema2Fixture.create(size)
            validateLibrarySnapshot(fixture)
            assertEquals(size, fixture.media.size)
            assertTrue(fixture.queues.values.all { it.entries.size == 200 })
            assertEquals(1_000, fixture.history.size)
            assertEquals(1_000, fixture.downloads.size)
            assertEquals(1_000, fixture.downloadAttempts.size)
            assertEquals(10, fixture.playlists.size)
            assertTrue(fixture.playlists.all { it.entries.size == 100 })
            assertEquals(fixture, fixture.toRows().toSnapshot())
        }
    }

    @Test fun everyAuthoredMutationChangesValuesAndKeepsTheExistingRelationalInvariants() {
        val old = RoomSchema2Fixture.create(1_000)
        for (workload in listOf("progress_history", "playback_checkpoint", "settings", "queue_order", "download_heartbeat")) {
            for (producer in 0..3) {
                val next = RoomSchema2Fixture.change(old, workload, producer)
                assertNotEquals(old, next, "$workload/$producer is not a timed no-op")
                assertEquals(old.revision, next.revision) // Room, not the fixture transform, owns increments.
                validateLibraryTransition(old, next)
                assertEquals(next, next.toRows().toSnapshot())
                assertEquals(old.media, next.media)
                assertEquals(old.playbackBindings, next.playbackBindings)
            }
        }
    }

    @Test fun invalidSizesAndUnknownWorkloadsFailInsteadOfQuietlyReducingTheFixture() {
        assertFailsWith<IllegalArgumentException> { RoomSchema2Fixture.create(999) }
        assertFailsWith<IllegalStateException> { RoomSchema2Fixture.change(RoomSchema2Fixture.create(1_000), "no_op") }
    }

    @Test fun empiricalNearestRankTailsAreNotInterpolatedOrSilentlyAveraged() {
        assertNull(RoomLatencyStats.of(emptyList()))
        assertEquals(RoomLatencyStats(50, 25, 48, 50, 50), RoomLatencyStats.of((1L..50L).toList().reversed()))
        assertEquals(RoomLatencyStats(1, 7, 7, 7, 7), RoomLatencyStats.of(listOf(7)))
        assertFailsWith<IllegalArgumentException> { RoomLatencyStats.of(listOf(-1)) }
    }

    @Test fun stopIsStickyAndTheClockLimitStopsOnlyAdmission() {
        var now = 0L
        val gate = RoomAdmissionGate(0) { now }
        assertTrue(gate.admit())
        now = RoomBenchmarkLimits.ADMISSION_NS
        assertFalse(gate.admit())
        assertEquals("admission_time_limit", gate.stop?.reason)
        gate.requestStop("later_reason")
        assertEquals("admission_time_limit", gate.stop?.reason)
        // There is deliberately no claim that a clock trigger cancels/rolls back a SQLite commit.
    }

    @Test fun sustainedDeadlineMissMeansThreeConsecutiveSlotsAndSuccessfulSlotResetsIt() {
        val streak = RoomDeadlineStreak()
        assertFalse(streak.record(true))
        assertFalse(streak.record(true))
        assertFalse(streak.record(false))
        assertFalse(streak.record(true))
        assertFalse(streak.record(true))
        assertTrue(streak.record(true))
        assertEquals(3, streak.maximum)
    }

    @Test fun outOfOrderCallerResumptionCannotRegressTheExpectedCommittedSnapshot() {
        val expected = RoomExpectedSnapshot(AppSnapshot())
        val later = AppSnapshot(revision = 2, navigation = mapOf("authored" to "later"))
        expected.committed(later)
        expected.committed(AppSnapshot(revision = 1))
        assertEquals(later, expected.get())
    }

    @Test fun defaultInvocationIsBareFullAndRequiresAnExternalInputSeal() {
        val config = RoomBenchmarkConfig.parse(arrayOf("--run-id", "authored-control", "--input-sha256", "a".repeat(64)))
        assertEquals("bare", config.driver)
        assertEquals("full", config.profile)
        assertFailsWith<IllegalArgumentException> { RoomBenchmarkConfig.parse(arrayOf("--run-id", "missing-seal")) }
    }

    @Test fun invocationRejectsPathEscapesDuplicateOptionsAndImplicitInstrumentation() {
        val valid = arrayOf("--run-id", "authored", "--input-sha256", "a".repeat(64))
        assertFailsWith<IllegalArgumentException> { RoomBenchmarkConfig.parse(valid + arrayOf("--driver", "automatic")) }
        assertFailsWith<IllegalArgumentException> { RoomBenchmarkConfig.parse(valid + arrayOf("--run-id", "duplicate")) }
        assertFailsWith<IllegalArgumentException> { RoomBenchmarkConfig.parse(arrayOf("--run-id", "../escape", "--input-sha256", "a".repeat(64))) }
        assertFailsWith<IllegalArgumentException> { RoomBenchmarkConfig.parse(valid + arrayOf("--extra", "yes")) }
    }

    @Test fun provisionalSummaryDoesNotClaimEvidenceCloseOrActualProcessExit() {
        for (code in 0..3) {
            val status = roomBenchmarkProvisionalStatus(code)
            assertTrue(status.getValue("summary_provisional").jsonPrimitive.boolean)
            assertEquals(code, status.getValue("provisional_exit_code").jsonPrimitive.int)
            assertFalse(status.getValue("evidence_stream_close_confirmed").jsonPrimitive.boolean)
            assertFalse(status.getValue("process_exit_confirmed").jsonPrimitive.boolean)
            assertFalse("exit_code" in status)
            assertFalse("result" in status)
        }
        assertEquals("completed", roomBenchmarkProvisionalStatus(0).getValue("provisional_result").jsonPrimitive.content)
        assertFailsWith<IllegalArgumentException> { roomBenchmarkProvisionalStatus(4) }
    }

    @Test fun evidenceUsesFreshFilesAndEscapesCsvWithoutPersistingSnapshots() {
        val directory = roomWorkDirectory("benchmark-evidence")
        RoomBenchmarkEvidence(directory, RoomAdmissionGate(System.nanoTime())).use { evidence ->
            evidence.csv("authored", listOf("a", "b"), listOf("comma,quote\"", 1))
            evidence.finalSummary("{\"authored\":true}")
            assertEquals(0L, evidence.dataBytes())
            assertFailsWith<IllegalStateException> { evidence.finalSummary("{}") }
        }
        assertEquals("\"a\",\"b\"\n\"comma,quote\"\"\",\"1\"\n", Files.readString(directory.resolve("authored.csv")))
        assertFalse(Files.exists(directory.resolve("snapshot.json")))
        assertEquals(0L, roomBenchmarkFileSize(directory.resolve("missing-wal")))
    }
}
