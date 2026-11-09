package dev.veyra.ui.music

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.veyra.model.DownloadStatus
import dev.veyra.model.LanguagePreference
import dev.veyra.model.MediaItem
import dev.veyra.model.MediaKind
import dev.veyra.model.SourceProvider
import dev.veyra.model.SourceRef
import dev.veyra.ui.design.AppStrings
import dev.veyra.ui.design.TextKey
import dev.veyra.ui.design.VeyraTheme
import dev.veyra.ui.downloads.DownloadSurface
import dev.veyra.ui.downloads.DownloadSurfaceEntry
import dev.veyra.ui.downloads.DownloadSurfaceOrigin
import dev.veyra.ui.downloads.RetainedValidationTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule

/** Compose action-selection checks for retained validation on the existing Downloads screen. */
@OptIn(ExperimentalTestApi::class)
class RetainedDownloadSurfacesTest {
    @get:Rule val ui = createComposeRule()
    private val strings = AppStrings(LanguagePreference.ENGLISH)
    private val item = MediaItem("retained-music", "Retained local music", MediaKind.MUSIC,
        SourceRef(SourceProvider.YOUTUBE, "AAAAAAAAAAA"), durationMs = 100_000L)

    private fun entry(origin: DownloadSurfaceOrigin, bindingId: String?,
        target: RetainedValidationTarget? = null,
        choices: List<RetainedValidationTarget> = emptyList()) = DownloadSurfaceEntry(
        id = "retained-job", origin = origin,
        mediaId = if (origin == DownloadSurfaceOrigin.TYPED_CATALOGUE) item.id else null,
        displayTitle = if (origin == DownloadSurfaceOrigin.TYPED_SOURCE) "Captured source title" else null,
        status = DownloadStatus.FAILED, bytesDownloaded = 1_024, totalBytes = 1_024,
        hasOwnedBytes = true, bindingId = bindingId, retainedValidation = target,
        retainedValidationChoices = choices,
    )

    @Test
    fun catalogueRetainedFailureOffersValidateAndSeparateConfirmedRemove() {
        val actions = mutableListOf<MusicUiAction>()
        val surface = DownloadSurface(listOf(entry(DownloadSurfaceOrigin.TYPED_CATALOGUE, "binding-r1",
            RetainedValidationTarget("retained-job", "binding-r1"))))
        ui.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                VeyraTheme(false, LanguagePreference.ENGLISH) {
                    LibraryScreen(dev.veyra.model.AppSnapshot(media = mapOf(item.id to item)),
                        LibrarySection.DOWNLOADS, { actions += it }, Modifier.size(900.dp, 700.dp),
                        MusicUiOptions(downloads = surface))
                }
            }
        }
        ui.onNodeWithTag("downloads-list").performScrollToNode(hasTestTag("download-retained-job"))
        ui.onNodeWithText(strings.text(TextKey.ValidateDownload)).assertIsDisplayed().performClick()
        assertEquals(listOf<MusicUiAction>(MusicUiAction.ValidateRetainedDownload("retained-job", "binding-r1")), actions)
        ui.onNodeWithTag("remove-retained-retained-job").performClick()
        assertTrue(actions.size == 1, "Removal must require the separate confirmation")
        ui.onNodeWithTag("confirm-action").performClick()
        assertEquals(MusicUiAction.RemoveDownload("retained-job"), actions.last())
    }

    @Test
    fun batchMemberRetainedFailureCarriesExactBatchMemberAndRevision() {
        val actions = mutableListOf<MusicUiAction>()
        val target = RetainedValidationTarget("retained-job", "binding-r1", "batch-7", 2, 11)
        val surface = DownloadSurface(listOf(entry(DownloadSurfaceOrigin.TYPED_CATALOGUE, "binding-r1", target)))
        ui.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                VeyraTheme(false, LanguagePreference.ENGLISH) {
                    LibraryScreen(dev.veyra.model.AppSnapshot(media = mapOf(item.id to item)),
                        LibrarySection.DOWNLOADS, { actions += it }, Modifier.size(900.dp, 700.dp),
                        MusicUiOptions(downloads = surface))
                }
            }
        }
        ui.onNodeWithTag("downloads-list").performScrollToNode(hasTestTag("download-retained-job"))
        ui.onNodeWithText(strings.text(TextKey.ValidateDownload)).assertIsDisplayed().performClick()
        assertEquals(listOf<MusicUiAction>(MusicUiAction.ValidateRetainedDownload(
            "retained-job", "binding-r1", "batch-7", 2, 11)), actions)
    }

    @Test
    fun repeatedMemberRetainedFailureOffersExplicitLocalizedMemberChoices() {
        val actions = mutableListOf<MusicUiAction>()
        val choices = listOf(
            RetainedValidationTarget("retained-job", "binding-r1", "batch-a", 0, 4),
            RetainedValidationTarget("retained-job", "binding-r1", "batch-b", 1, 7),
        )
        val surface = DownloadSurface(listOf(entry(DownloadSurfaceOrigin.TYPED_CATALOGUE, "binding-r1",
            choices = choices)))
        ui.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                VeyraTheme(false, LanguagePreference.ENGLISH) {
                    LibraryScreen(dev.veyra.model.AppSnapshot(media = mapOf(item.id to item)),
                        LibrarySection.DOWNLOADS, { actions += it }, Modifier.size(900.dp, 700.dp),
                        MusicUiOptions(downloads = surface))
                }
            }
        }
        ui.onNodeWithTag("downloads-list").performScrollToNode(hasTestTag("download-retained-job"))
        ui.onNodeWithText(strings.text(TextKey.ValidateDownload)).performClick()
        ui.onNodeWithTag("validate-member-retained-job-1").assertIsDisplayed().performClick()
        assertEquals(listOf<MusicUiAction>(MusicUiAction.ValidateRetainedDownload(
            "retained-job", "binding-r1", "batch-b", 1, 7)), actions)
        assertTrue(actions.none { it is MusicUiAction.ValidateRetainedDownload &&
            it.batchId == "batch-a" }, "The first projected member is never chosen implicitly")
    }

    @Test
    fun neutralRetainedFailureOffersExactLocalValidateInArabicWithoutCatalogueAuthority() {
        val actions = mutableListOf<MusicUiAction>()
        val target = RetainedValidationTarget("retained-job", "neutral-binding")
        val surface = DownloadSurface(listOf(entry(DownloadSurfaceOrigin.TYPED_SOURCE, null, target)))
        ui.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.75f)) {
                VeyraTheme(true, LanguagePreference.ARABIC) {
                    LibraryScreen(dev.veyra.model.AppSnapshot(media = mapOf(item.id to item)),
                        LibrarySection.DOWNLOADS, { actions += it }, Modifier.size(360.dp, 700.dp),
                        MusicUiOptions(downloads = surface))
                }
            }
        }
        val arabic = AppStrings(LanguagePreference.ARABIC)
        ui.onNodeWithTag("downloads-list").performScrollToNode(hasTestTag("download-retained-job"))
        ui.onNodeWithText(arabic.text(TextKey.ValidateDownload)).assertIsDisplayed().performClick()
        assertEquals(listOf<MusicUiAction>(MusicUiAction.ValidateRetainedDownload(
            "retained-job", "neutral-binding")), actions)
        ui.onNodeWithText(arabic.text(TextKey.Play)).assertDoesNotExist()
        ui.onNodeWithText(arabic.text(TextKey.ReadyOffline)).assertDoesNotExist()
    }
}
