package dev.veyra.source.release

import kotlinx.serialization.json.*

/** Wholly authored metadata. No API capture, recording, artwork, URL capability or live lookup. */
internal object ReleaseMetadataFixtures {
    val releaseId = id(1)
    val groupId = id(2)
    val artistId = id(3)
    val recordingId = id(4)
    val mediumId = id(5)
    val packagingId = id(6)
    const val title = "  ڤيرا – أمواج / Quiet & Currents (إصدار أصلي)  "
    fun id(number: Int) = "00000000-0000-4000-8000-${number.toString().padStart(12, '0')}"
    fun credit(name: String = "الاسم المعتمد", join: String = " & ", artist: String = artistId) = buildJsonObject {
        put("name", name); put("joinphrase", join)
        put("artist", buildJsonObject { put("id", artist); put("name", "Canonical artist name") })
    }
    fun summary(id: String = releaseId, title: String = this.title, type: String = "EP") = buildJsonObject {
        put("id", id); put("title", title); put("status", "Official"); put("date", "2026-11"); put("country", "GB")
        put("release-group", buildJsonObject {
            put("id", groupId); put("title", "A different conceptual group title"); put("primary-type", type)
            put("secondary-types", JsonArray(listOf(JsonPrimitive("Live"))))
        })
        put("artist-credit", JsonArray(listOf(credit(), credit("Second credit", "", ReleaseMetadataFixtures.id(7)))))
        put("release-events", JsonArray(listOf(buildJsonObject {
            put("date", "2026-11"); put("area", buildJsonObject {
                put("id", ReleaseMetadataFixtures.id(8)); put("name", "Authored area"); put("iso-3166-1-codes", JsonArray(listOf(JsonPrimitive("GB"))))
            })
        })))
        put("label-info", JsonArray(listOf(buildJsonObject {
            put("catalog-number", "QC-001-A"); put("label", buildJsonObject { put("id", ReleaseMetadataFixtures.id(9)); put("name", "Authored label") })
        })))
        put("barcode", "0000012345678"); put("disambiguation", "authored two-medium edition")
        put("text-representation", buildJsonObject { put("language", "ara"); put("script", "Arab") })
        put("packaging", "Digipak"); put("packaging-id", packagingId)
    }
    fun track(position: Int = 1, id: String = ReleaseMetadataFixtures.id(100 + position), title: String? = "الموجة $position", recordingId: String = this.recordingId) = buildJsonObject {
        put("id", id); put("position", position); put("number", "A$position")
        title?.let { put("title", it) }; put("length", 123_456)
        put("artist-credit", JsonArray(listOf(credit(join = ""))))
        put("recording", buildJsonObject { put("id", recordingId); put("title", "Different recording title") })
    }
    fun medium(position: Int = 1, id: String = mediumId, tracks: List<JsonElement> = listOf(track()), count: Int = tracks.size, offset: Int = 0) = buildJsonObject {
        put("id", id); put("position", position); put("title", ""); put("format", "CD")
        put("track-count", count); put("track-offset", offset); put("tracks", JsonArray(tracks))
    }
    fun release(id: String = releaseId, title: String = this.title, media: List<JsonElement> = listOf(medium())) =
        JsonObject(summary(id, title) + ("media" to JsonArray(media)))
    fun search(rows: List<JsonElement> = listOf(summary()), offset: Int = 0, count: Long = rows.size.toLong()) = buildJsonObject {
        put("offset", offset); put("count", count); put("releases", JsonArray(rows))
    }
}
