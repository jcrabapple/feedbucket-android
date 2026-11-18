package me.ash.reader.ui.page.nav3

import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import me.ash.reader.R
import me.ash.reader.ui.motion.materialSharedAxisXIn
import me.ash.reader.ui.motion.materialSharedAxisXOut
import me.ash.reader.ui.page.adaptive.ArticleData
import me.ash.reader.ui.page.adaptive.ArticleListReaderPage
import me.ash.reader.ui.page.adaptive.ArticleListReaderViewModel
import me.ash.reader.ui.page.home.feeds.FeedsPage
import me.ash.reader.ui.page.home.feeds.detail.FeedDetailPage
import me.ash.reader.ui.page.home.feeds.detail.GroupDetailPage
import me.ash.reader.ui.page.home.feeds.subscribe.SubscribeViewModel
import me.ash.reader.ui.page.nav3.key.Route
import me.ash.reader.ui.page.settings.SettingsPage
import me.ash.reader.ui.page.settings.accounts.AccountDetailsPage
import me.ash.reader.ui.page.settings.accounts.AccountViewModel
import me.ash.reader.ui.page.settings.accounts.AccountsPage
import me.ash.reader.ui.page.settings.accounts.AddAccountsPage
import me.ash.reader.ui.page.settings.color.ColorAndStylePage
import me.ash.reader.ui.page.settings.color.DarkThemePage
import me.ash.reader.ui.page.settings.color.feeds.FeedsPageStylePage
import me.ash.reader.ui.page.settings.color.flow.FlowPageStylePage
import me.ash.reader.ui.page.settings.color.reading.BoldCharactersPage
import me.ash.reader.ui.page.settings.color.reading.ReadingImagePage
import me.ash.reader.ui.page.settings.color.reading.ReadingStylePage
import me.ash.reader.ui.page.settings.color.reading.ReadingTextPage
import me.ash.reader.ui.page.settings.color.reading.ReadingTitlePage
import me.ash.reader.ui.page.settings.color.reading.ReadingVideoPage
import me.ash.reader.ui.page.settings.interaction.InteractionPage
import me.ash.reader.ui.page.settings.languages.LanguagesPage
import me.ash.reader.ui.page.settings.rules.RuleEditPage
import me.ash.reader.ui.page.settings.rules.RulesPage
import me.ash.reader.ui.page.settings.rules.RulesViewModel
import me.ash.reader.ui.page.settings.tips.LicenseListPage
import me.ash.reader.ui.page.settings.troubleshooting.TroubleshootingPage
import me.ash.reader.ui.page.startup.StartupPage

private const val INITIAL_OFFSET_FACTOR = 0.10f

/** At this font scale the detail pane drops below 320dp; Read goes one-pane. */
private const val FONT_SCALE_ONE_PANE_THRESHOLD = 1.6f

// UI-CONTRACT §2.1: three roots, NavigationSuiteScaffold bar/rail, no
// dashboard. Fixed order drives the per-root stack saver.
internal val suiteRootOrder: List<Route> =
    listOf(Route.Reading(null), Route.Feeds, Route.Settings)

internal val routeJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** JSON encoding of the per-root stacks (extracted for unit testing). */
internal fun saveRootStacks(map: Map<Route, List<Route>>): String? =
    runCatching { routeJson.encodeToString(suiteRootOrder.map { root -> map[root].orEmpty() }) }
        .getOrNull()

/** JSON decoding of the per-root stacks (extracted for unit testing). */
internal fun restoreRootStacks(str: String): MutableMap<Route, List<Route>>? =
    runCatching {
            val saved = routeJson.decodeFromString<List<List<Route>>>(str)
            mutableMapOf<Route, List<Route>>().apply {
                suiteRootOrder.forEachIndexed { index, root ->
                    if (index < saved.size && saved[index].isNotEmpty()) {
                        put(root, saved[index])
                    }
                }
            }
        }
        .getOrNull()

/**
 * Per-root back stack retention (UI-CONTRACT §2.1): top-level reselection is
 * single-top + restore-state; switching roots does not destroy the Read
 * generation (scope, query, selected article, list anchor, reading position).
 */
internal val RootStacksSaver =
    Saver<MutableMap<Route, List<Route>>, String>(
        save = { map -> saveRootStacks(map) },
        restore = { str -> restoreRootStacks(str) },
    )

private fun NavKey.isSuiteRoot(): Boolean =
    this == Route.Feeds || this == Route.Settings || (this as? Route.Reading)?.articleId == null

private tailrec fun android.content.Context.findActivity(): android.app.Activity? =
    when (this) {
        is android.app.Activity -> this
        is android.content.ContextWrapper -> baseContext.findActivity()
        else -> null
    }

private fun currentSuiteRoot(backStack: NavBackStack<NavKey>): Route =
    (backStack.lastOrNull { it.isSuiteRoot() } as? Route) ?: Route.Feeds

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun AppEntry(backStack: NavBackStack<NavKey>) {
    val subscribeViewModel = hiltViewModel<SubscribeViewModel>()

    val rootStacks =
        rememberSaveable(saver = RootStacksSaver) { mutableMapOf<Route, List<Route>>() }
    val currentRoot = currentSuiteRoot(backStack)

    fun switchRoot(root: Route) {
        if (currentRoot == root) return
        rootStacks[currentRoot] = backStack.filterIsInstance<Route>()
        backStack.clear()
        backStack.addAll(rootStacks[root] ?: listOf(root))
    }

    // UI-CONTRACT §2.3 Back hierarchy: subpage pops first; at Feeds or
    // Settings root Back selects Read; at Read root Back belongs to the
    // system. The app is a single activity, so the system behavior for Back
    // at the root is moving the task back (not finishing) — this is the
    // hierarchy's terminal step, reachable only after every pop level above.
    val context = LocalContext.current
    val onBack: () -> Unit = {
        when {
            backStack.size > 1 -> backStack.removeLastOrNull()
            currentRoot == Route.Feeds || currentRoot == Route.Settings ->
                switchRoot(Route.Reading(null))
            else -> context.findActivity()?.moveTaskToBack(true)
        }
    }

    val adaptiveInfo = currentWindowAdaptiveInfo()

    // UI-CONTRACT §3.1: at font scale >= 1.6 the available detail width is
    // <320dp, so Read uses one-pane list-detail even on wide windows. The
    // rail remains (content-availability decision, not device-name).
    val fontScale = LocalDensity.current.fontScale
    val baseDirective = calculatePaneScaffoldDirective(adaptiveInfo)
    val scaffoldDirective =
        if (fontScale >= FONT_SCALE_ONE_PANE_THRESHOLD) {
            baseDirective.copy(maxHorizontalPartitions = 1)
        } else {
            baseDirective
        }

    val navigator =
        rememberListDetailPaneScaffoldNavigator<ArticleData>(
            scaffoldDirective = scaffoldDirective,
            isDestinationHistoryAware = false,
        )

    NavigationSuiteScaffold(
        layoutType = NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(adaptiveInfo),
        navigationSuiteItems = {
            item(
                selected = currentRoot == Route.Reading(null),
                onClick = { switchRoot(Route.Reading(null)) },
                icon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Article,
                        contentDescription = null,
                    )
                },
                label = { Text(stringResource(R.string.read)) },
            )
            item(
                selected = currentRoot == Route.Feeds,
                onClick = { switchRoot(Route.Feeds) },
                icon = {
                    Icon(imageVector = Icons.Outlined.Folder, contentDescription = null)
                },
                label = { Text(stringResource(R.string.feeds)) },
            )
            item(
                selected = currentRoot == Route.Settings,
                onClick = { switchRoot(Route.Settings) },
                icon = {
                    Icon(imageVector = Icons.Outlined.Settings, contentDescription = null)
                },
                label = { Text(stringResource(R.string.settings)) },
            )
        },
    ) {
        NavDisplay(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
            backStack = backStack,
            entryDecorators =
                listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
            transitionSpec = {
                materialSharedAxisXIn(
                    initialOffsetX = { (it * INITIAL_OFFSET_FACTOR).toInt() }
                ) togetherWith
                    materialSharedAxisXOut(
                        targetOffsetX = { -(it * INITIAL_OFFSET_FACTOR).toInt() }
                    )
            },
            popTransitionSpec = {
                materialSharedAxisXIn(
                    initialOffsetX = { -(it * INITIAL_OFFSET_FACTOR).toInt() }
                ) togetherWith
                    materialSharedAxisXOut(targetOffsetX = { (it * INITIAL_OFFSET_FACTOR).toInt() })
            },
            predictivePopTransitionSpec = {
                materialSharedAxisXIn(
                    initialOffsetX = { -(it * INITIAL_OFFSET_FACTOR).toInt() }
                ) togetherWith
                    materialSharedAxisXOut(targetOffsetX = { (it * INITIAL_OFFSET_FACTOR).toInt() })
            },
            onBack = { onBack() },
            entryProvider = { key ->
                when (key) {
                    Route.Feeds -> {
                        NavEntry(key) {
                            FeedsPage(
                                subscribeViewModel = subscribeViewModel,
                                navigateToSettings = { backStack.add(Route.Settings) },
                                navigationToFlow = { backStack.add(Route.Reading(null)) },
                                navigateToAccountList = { backStack.add(Route.Accounts) },
                                navigateToAccountDetail = {
                                    backStack.add(Route.AccountDetails(it))
                                },
                                navigateToFeedDetail = {
                                    backStack.add(Route.FeedDetail(it))
                                },
                                navigateToGroupDetail = {
                                    backStack.add(Route.GroupDetail(it))
                                },
                            )
                        }
                    }
                    is Route.FeedDetail -> {
                        NavEntry(key) {
                            FeedDetailPage(
                                feedId = key.feedId,
                                onBack = onBack,
                            )
                        }
                    }
                    is Route.GroupDetail -> {
                        NavEntry(key) {
                            GroupDetailPage(
                                groupId = key.groupId,
                                onBack = onBack,
                            )
                        }
                    }
                    is Route.Reading -> {
                        NavEntry(key) {
                            val key = rememberSaveable(saver = Route.Reading.Saver) { key }

                            LaunchedEffect(key) {
                                if (key.articleId != null) {
                                    delay(50L)
                                    navigator.navigateTo(
                                        ListDetailPaneScaffoldRole.Detail,
                                        ArticleData(key.articleId),
                                    )
                                }
                            }

                            val viewModel = hiltViewModel<ArticleListReaderViewModel>()

                            ArticleListReaderPage(
                                scaffoldDirective = scaffoldDirective,
                                navigator = navigator,
                                viewModel = viewModel,
                                onBack = onBack,
                                onNavigateToStylePage = { backStack.add(Route.ReadingPageStyle) },
                            )
                        }
                    }
                    Route.Startup -> {
                        NavEntry(key) {
                            StartupPage(onNavigateToFeeds = { backStack.add(Route.Feeds) })
                        }
                    }
                    Route.Settings ->
                        NavEntry(key) {
                            SettingsPage(
                                onBack = onBack,
                                navigateToAccounts = { backStack.add(Route.Accounts) },
                                navigateToColorAndStyle = { backStack.add(Route.ColorAndStyle) },
                                navigateToInteraction = { backStack.add(Route.Interaction) },
                                navigateToLanguages = { backStack.add(Route.Languages) },
                                navigateToTroubleshooting = {
                                    backStack.add(Route.Troubleshooting)
                                },
                                navigateToLicenseList = { backStack.add(Route.LicenseList) },
                                navigateToRules = { backStack.add(Route.Rules) },
                                navigateToDownloads = { backStack.add(Route.Downloads) },
                                navigateToMigration = { backStack.add(Route.Migration) },
                            )
                        }
                    Route.Migration -> {
                        NavEntry(key) {
                            me.ash.reader.ui.page.settings.migration.MigrationPage(
                                onBack = onBack,
                            )
                        }
                    }
                    Route.Rules -> {
                        NavEntry(key) {
                            RulesPage(
                                viewModel = hiltViewModel<RulesViewModel>(),
                                onBack = onBack,
                                onNavigateToEdit = { backStack.add(Route.RuleEdit(it)) },
                            )
                        }
                    }
                    is Route.RuleEdit -> {
                        NavEntry(key) {
                            RuleEditPage(
                                ruleId = key.ruleId,
                                viewModel = hiltViewModel<RulesViewModel>(),
                                onBack = onBack,
                            )
                        }
                    }
                    Route.Downloads -> {
                        NavEntry(key) {
                            me.ash.reader.ui.page.settings.downloads.DownloadsPage(
                                viewModel = hiltViewModel<me.ash.reader.ui.page.settings.downloads.DownloadsViewModel>(),
                                onBack = onBack,
                            )
                        }
                    }
                    Route.Accounts -> {
                        NavEntry(key) {
                            AccountsPage(
                                onBack = onBack,
                                navigateToAddAccount = { backStack.add(Route.AddAccounts) },
                                navigateToAccountDetails = {
                                    backStack.add(Route.AccountDetails(it))
                                },
                            )
                        }
                    }
                    is Route.AccountDetails -> {
                        NavEntry(key) {
                            AccountDetailsPage(
                                viewModel =
                                    hiltViewModel<AccountViewModel>().also {
                                        it.initData(key.accountId)
                                    },
                                onBack = onBack,
                                navigateToFeeds = { backStack.add(Route.Feeds) },
                            )
                        }
                    }
                    Route.AddAccounts -> {
                        NavEntry(key) {
                            AddAccountsPage(
                                onBack = onBack,
                                navigateToAccountDetails = {
                                    backStack.add(Route.AccountDetails(it))
                                },
                            )
                        }
                    }
                    Route.ColorAndStyle -> {
                        NavEntry(key) {
                            ColorAndStylePage(
                                onBack = onBack,
                                navigateToDarkTheme = { backStack.add(Route.DarkTheme) },
                                navigateToFeedsPageStyle = { backStack.add(Route.FeedsPageStyle) },
                                navigateToFlowPageStyle = { backStack.add(Route.FlowPageStyle) },
                                navigateToReadingPageStyle = {
                                    backStack.add(Route.ReadingPageStyle)
                                },
                            )
                        }
                    }
                    Route.DarkTheme -> NavEntry(key) { DarkThemePage(onBack = onBack) }
                    Route.FeedsPageStyle -> NavEntry(key) { FeedsPageStylePage(onBack = onBack) }
                    Route.FlowPageStyle -> NavEntry(key) { FlowPageStylePage(onBack = onBack) }
                    Route.ReadingPageStyle ->
                        NavEntry(key) {
                            ReadingStylePage(
                                onBack = onBack,
                                navigateToReadingBoldCharacters = {
                                    backStack.add(Route.ReadingBoldCharacters)
                                },
                                navigateToReadingPageTitle = {
                                    backStack.add(Route.ReadingPageTitle)
                                },
                                navigateToReadingPageText = {
                                    backStack.add(Route.ReadingPageText)
                                },
                                navigateToReadingPageImage = {
                                    backStack.add(Route.ReadingPageImage)
                                },
                                navigateToReadingPageVideo = {
                                    backStack.add(Route.ReadingPageVideo)
                                },
                            )
                        }
                    Route.ReadingBoldCharacters ->
                        NavEntry(key) { BoldCharactersPage(onBack = onBack) }
                    Route.ReadingPageTitle -> NavEntry(key) { ReadingTitlePage(onBack = onBack) }
                    Route.ReadingPageText -> NavEntry(key) { ReadingTextPage(onBack = onBack) }
                    Route.ReadingPageImage -> NavEntry(key) { ReadingImagePage(onBack = onBack) }
                    Route.ReadingPageVideo -> NavEntry(key) { ReadingVideoPage(onBack = onBack) }
                    Route.Interaction -> NavEntry(key) { InteractionPage(onBack = onBack) }
                    Route.Languages -> NavEntry(key) { LanguagesPage(onBack = onBack) }
                    Route.Troubleshooting -> NavEntry(key) { TroubleshootingPage(onBack = onBack) }
                    Route.LicenseList -> NavEntry(key) { LicenseListPage(onBack = onBack) }
                    else -> NavEntry(key) { throw Exception("Unknown destination") }
                }
            },
        )
    }
}
