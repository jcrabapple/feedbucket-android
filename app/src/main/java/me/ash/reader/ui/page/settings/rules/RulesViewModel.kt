package me.ash.reader.ui.page.settings.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ash.reader.domain.model.group.GroupWithFeed
import me.ash.reader.domain.model.rule.ActionRule
import me.ash.reader.domain.model.rule.StateApplyRun
import me.ash.reader.domain.repository.ActionRuleDao
import me.ash.reader.domain.repository.StateApplyDao
import me.ash.reader.domain.service.RssService
import me.ash.reader.domain.service.StateApplyLedger
import me.ash.reader.infrastructure.di.IODispatcher

/**
 * Wave D — rules/mutes settings ViewModel: CRUD over action_rule, the S18a
 * preview/apply/undo hooks (delegated to the active account's repository)
 * and undo availability for the UI.
 */
@HiltViewModel
class RulesViewModel
@Inject
constructor(
    private val actionRuleDao: ActionRuleDao,
    private val rssService: RssService,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    val rules = actionRuleDao.queryAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _undoableRun = MutableStateFlow<StateApplyRun?>(null)
    val undoableRun: StateFlow<StateApplyRun?> = _undoableRun

    init {
        refreshUndoableRun()
    }

    fun refreshUndoableRun() {
        viewModelScope.launch(ioDispatcher) {
            _undoableRun.value = rssService.get().latestUndoableRun()
        }
    }

    fun save(rule: ActionRule) {
        viewModelScope.launch(ioDispatcher) {
            if (rule.id == 0L) actionRuleDao.insert(rule) else actionRuleDao.update(rule)
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch(ioDispatcher) {
            actionRuleDao.deleteById(id)
        }
    }

    /** S13 scope picker data: groups with their feeds. */
    suspend fun groupsWithFeeds(): List<GroupWithFeed> =
        withContext(ioDispatcher) { rssService.get().queryAllGroupWithFeeds() }

    /** S18a preview: total count + 20 samples over the rule's S13 scope. */
    suspend fun preview(rule: ActionRule): StateApplyLedger.Preview =
        withContext(ioDispatcher) { rssService.get().previewRule(rule) }

    /** S18a apply-to-existing → exact affected count. */
    suspend fun applyToExisting(rule: ActionRule): Int =
        withContext(ioDispatcher) { rssService.get().applyRuleToExisting(rule) }

    /** S18a undo of the latest run; refreshes availability afterwards. */
    suspend fun undo(): StateApplyDao.UndoCounts? =
        withContext(ioDispatcher) {
            rssService.get().undoLatestApply().also { refreshUndoableRun() }
        }
}
