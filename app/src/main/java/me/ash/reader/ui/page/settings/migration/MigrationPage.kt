package me.ash.reader.ui.page.settings.migration

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.ImportExport
import androidx.compose.material.icons.outlined.SyncAlt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStream
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.ash.reader.R
import me.ash.reader.domain.model.importing.ImportRun
import me.ash.reader.domain.service.OpmlService
import me.ash.reader.domain.service.RssService
import me.ash.reader.domain.service.migration.FeedBucketImporter
import me.ash.reader.domain.service.migration.ImportLedger
import me.ash.reader.domain.service.migration.ImportPreview
import me.ash.reader.infrastructure.di.ApplicationScope
import me.ash.reader.ui.component.base.DisplayText
import me.ash.reader.ui.component.base.FeedbackIconButton
import me.ash.reader.ui.component.base.RYScaffold
import me.ash.reader.ui.ext.currentAccountId
import me.ash.reader.ui.page.settings.SelectableSettingGroupItem

/**
 * Wave G — Migration/Import-export settings surface (S32a/S33, UI-CONTRACT
 * §6.10). The page offers the FeedBucket package/raw-copy import flow
 * (Choose -> retain/validate -> preview -> apply -> result) plus separate
 * OPML import/export actions. Processing is device-local; the page never
 * asks for server credentials (D14).
 */
@Composable
fun MigrationPage(
    viewModel: MigrationViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()

    val packagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.importPickedFile(
                openStream = { contentResolver(it, context) },
                uri = uri,
            )
        }
    }
    val opmlPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.importOpml(
                openStream = { contentResolver(it, context) },
                uri = uri,
            )
        }
    }

    // OPML export: share the produced document.
    LaunchedEffect(state.opmlExport) {
        val export = state.opmlExport ?: return@LaunchedEffect
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/x-opml"
            putExtra(Intent.EXTRA_SUBJECT, "feedbucket-export.opml")
            putExtra(Intent.EXTRA_TEXT, export)
        }
        context.startActivity(Intent.createChooser(send, null))
        viewModel.consumeOpmlExport()
    }

    RYScaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        navigationIcon = {
            FeedbackIconButton(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.back),
                tint = MaterialTheme.colorScheme.onSurface,
                onClick = onBack,
            )
        },
        content = {
            LazyColumn {
                item {
                    DisplayText(text = stringResource(R.string.import_export), desc = "")
                }
                item {
                    SelectableSettingGroupItem(
                        title = stringResource(R.string.migration_choose_package),
                        desc = stringResource(R.string.migration_desc),
                        icon = Icons.Outlined.FileUpload,
                        onClick = {
                            packagePicker.launch(arrayOf("*/*"))
                        },
                    )
                }
                item {
                    // Resumable-run indicator (S32a step 6).
                    state.resumableRun?.let { run ->
                        SelectableSettingGroupItem(
                            title = stringResource(R.string.migration_resumable),
                            desc = stringResource(R.string.migration_resume),
                            icon = Icons.Outlined.SyncAlt,
                            onClick = { viewModel.retryImport() },
                        )
                    }
                }
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.migration_opml_section),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.height(48.dp),
                    )
                }
                item {
                    SelectableSettingGroupItem(
                        title = stringResource(R.string.migration_opml_import),
                        desc = stringResource(R.string.migration_opml_import_desc),
                        icon = Icons.Outlined.ImportExport,
                        onClick = { opmlPicker.launch(arrayOf("text/xml", "text/x-opml", "*/*")) },
                    )
                }
                item {
                    SelectableSettingGroupItem(
                        title = stringResource(R.string.migration_opml_export),
                        desc = stringResource(R.string.migration_opml_desc),
                        icon = Icons.Outlined.ImportExport,
                        onClick = { viewModel.exportOpml() },
                    )
                }
                item {
                    // OPML result / error lines (durable inline representation).
                    state.opmlResult?.let { result ->
                        Text(
                            text = stringResource(
                                R.string.migration_opml_result,
                                result.imported,
                                result.skipped,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.height(48.dp),
                        )
                    }
                    state.opmlError?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.height(48.dp),
                        )
                    }
                }
                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                }
            }
        },
    )

    // Sheet states (UI-CONTRACT §6.10 sequence).
    when (state.stage) {
        MigrationViewModel.Stage.PREVIEW ->
            ImportPreviewSheet(
                preview = state.preview,
                onImport = { viewModel.confirmImport() },
                onCancel = { viewModel.cancelImport() },
            )
        MigrationViewModel.Stage.APPLYING ->
            ImportResultSheet(
                applying = true,
                result = null,
                onRetry = { viewModel.retryImport() },
                onDone = { viewModel.done() },
                onRemoveImported = { viewModel.removeImportedData() },
                removalReport = state.removalReport,
            )
        MigrationViewModel.Stage.RESULT ->
            ImportResultSheet(
                applying = false,
                result = state.result,
                onRetry = { viewModel.retryImport() },
                onDone = { viewModel.done() },
                onRemoveImported = { viewModel.removeImportedData() },
                removalReport = state.removalReport,
            )
        else -> Unit
    }
}

private fun contentResolver(uri: Uri, context: Context): InputStream? =
    runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()

/**
 * Wave G — migration ViewModel (kept in this file per the wave's owned-file
 * list). Owns the S32a pipeline state machine and S33 OPML counts.
 */
@HiltViewModel
class MigrationViewModel
@Inject
constructor(
    private val importer: FeedBucketImporter,
    private val opmlService: OpmlService,
    private val rssService: RssService,
    @ApplicationContext private val context: Context,
    @ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel() {

    enum class Stage { IDLE, RETAINING, VALIDATING, PREVIEW, APPLYING, RESULT }

    sealed interface RunResult {
        data class Completed(val ledger: ImportLedger) : RunResult
        data class Failed(val reason: String) : RunResult
    }

    data class UiState(
        val stage: Stage = Stage.IDLE,
        val runId: Long? = null,
        val preview: ImportPreview? = null,
        val result: RunResult? = null,
        val removalReport: FeedBucketImporter.RemovalReport? = null,
        val resumableRun: ImportRun? = null,
        val opmlResult: OpmlService.OpmlImportResult? = null,
        val opmlError: String? = null,
        val opmlExport: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        // Resumable-run indicator (S32a step 6) + orphan GC (S32a step 8).
        viewModelScope.launch {
            _uiState.update { it.copy(resumableRun = importer.runningRun()) }
            applicationScope.launch { importer.gcOrphanCopies() }
        }
    }

    /** Choose FeedBucket package / raw copy -> stage -> validate -> preview. */
    fun importPickedFile(openStream: (Uri) -> InputStream?, uri: Uri) {
        if (_uiState.value.stage == Stage.APPLYING) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    stage = Stage.RETAINING,
                    runId = null,
                    preview = null,
                    result = null,
                    removalReport = null,
                    resumableRun = null,
                )
            }
            val stream = openStream(uri) ?: run {
                _uiState.update {
                    it.copy(stage = Stage.IDLE, result = RunResult.Failed("Cannot open picked file"))
                }
                return@launch
            }
            // S32a step 1: durable retained copy + run ledger.
            val runId = importer.stageCopy(stream, uri.toString())
            _uiState.update { it.copy(stage = Stage.VALIDATING, runId = runId) }
            when (val outcome = importer.preview(runId)) {
                is FeedBucketImporter.PreviewOutcome.Ready ->
                    _uiState.update {
                        it.copy(stage = Stage.PREVIEW, preview = outcome.preview, runId = runId)
                    }
                is FeedBucketImporter.PreviewOutcome.Rejected ->
                    _uiState.update {
                        it.copy(stage = Stage.RESULT, runId = runId, result = RunResult.Failed(outcome.reason))
                    }
                is FeedBucketImporter.PreviewOutcome.SourceLost ->
                    _uiState.update {
                        it.copy(stage = Stage.RESULT, result = RunResult.Failed("Retained copy missing"))
                    }
            }
        }
    }

    /** Preview confirmed -> apply (re-fingerprints; aborts on mismatch). */
    fun confirmImport() {
        val state = _uiState.value
        val runId = state.runId ?: return
        val preview = state.preview ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(stage = Stage.APPLYING) }
            when (val outcome = importer.apply(runId, preview.fingerprint)) {
                is FeedBucketImporter.ApplyOutcome.Completed ->
                    _uiState.update {
                        it.copy(stage = Stage.RESULT, result = RunResult.Completed(outcome.ledger))
                    }
                is FeedBucketImporter.ApplyOutcome.FingerprintMismatch ->
                    _uiState.update {
                        it.copy(
                            stage = Stage.RESULT,
                            result = RunResult.Failed(
                                "The file changed between preview and import. Nothing was imported.",
                            ),
                        )
                    }
                is FeedBucketImporter.ApplyOutcome.Rejected ->
                    _uiState.update {
                        it.copy(stage = Stage.RESULT, result = RunResult.Failed(outcome.reason))
                    }
                is FeedBucketImporter.ApplyOutcome.SourceLost ->
                    _uiState.update {
                        it.copy(stage = Stage.RESULT, result = RunResult.Failed("Retained copy missing"))
                    }
            }
        }
    }

    /** Cancel before apply: nothing written, retained copy purged. */
    fun cancelImport() {
        val runId = _uiState.value.runId
        viewModelScope.launch {
            runId?.let { importer.cancelBeforeApply(it) }
            _uiState.update {
                it.copy(stage = Stage.IDLE, runId = null, preview = null, result = null)
            }
        }
    }

    /** Retry/resume a failed or interrupted run against the retained copy. */
    fun retryImport() {
        viewModelScope.launch {
            _uiState.update { it.copy(stage = Stage.APPLYING, result = null) }
            when (val outcome = importer.resume()) {
                null -> _uiState.update { it.copy(stage = Stage.IDLE) }
                is FeedBucketImporter.ApplyOutcome.Completed ->
                    _uiState.update {
                        it.copy(stage = Stage.RESULT, result = RunResult.Completed(outcome.ledger))
                    }
                is FeedBucketImporter.ApplyOutcome.FingerprintMismatch ->
                    _uiState.update {
                        it.copy(
                            stage = Stage.RESULT,
                            result = RunResult.Failed(
                                "The retained source changed. Nothing was imported.",
                            ),
                        )
                    }
                is FeedBucketImporter.ApplyOutcome.Rejected ->
                    _uiState.update {
                        it.copy(stage = Stage.RESULT, result = RunResult.Failed(outcome.reason))
                    }
                is FeedBucketImporter.ApplyOutcome.SourceLost ->
                    _uiState.update {
                        it.copy(stage = Stage.RESULT, result = RunResult.Failed("Retained copy missing"))
                    }
            }
        }
    }

    /** S32a step 7 — remove ONLY unchanged run-created rows. */
    fun removeImportedData() {
        val runId = _uiState.value.runId ?: return
        viewModelScope.launch {
            val report = importer.removeImported(runId)
            _uiState.update { it.copy(removalReport = report) }
        }
    }

    /** Leave the result surface: purge the retained copy at terminal state. */
    fun done() {
        val runId = _uiState.value.runId
        viewModelScope.launch {
            runId?.let { importer.purgeOnTerminalLeave(it) }
            _uiState.update {
                it.copy(
                    stage = Stage.IDLE,
                    runId = null,
                    preview = null,
                    result = null,
                    removalReport = null,
                    resumableRun = importer.runningRun(),
                )
            }
        }
    }

    fun importOpml(openStream: (Uri) -> InputStream?, uri: Uri) {
        viewModelScope.launch {
            val stream = openStream(uri) ?: run {
                _uiState.update { it.copy(opmlError = "Cannot open picked file") }
                return@launch
            }
            runCatching {
                val result = opmlService.saveToDatabase(stream)
                rssService.get().doSyncOneTime()
                result
            }.onSuccess { result ->
                _uiState.update { it.copy(opmlResult = result, opmlError = null) }
            }.onFailure { e ->
                _uiState.update { it.copy(opmlError = e.message ?: "OPML import failed") }
            }
        }
    }

    fun exportOpml() {
        viewModelScope.launch {
            runCatching { opmlService.saveToString(context.currentAccountId, attachInfo = false) }
                .onSuccess { exported -> _uiState.update { it.copy(opmlExport = exported) } }
                .onFailure { e -> _uiState.update { it.copy(opmlError = e.message ?: "OPML export failed") } }
        }
    }

    fun consumeOpmlExport() {
        _uiState.update { it.copy(opmlExport = null) }
    }
}
