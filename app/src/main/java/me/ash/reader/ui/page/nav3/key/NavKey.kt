package me.ash.reader.ui.page.nav3.key

import androidx.compose.runtime.saveable.Saver
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    // Startup
    @Serializable data object Startup : Route

    // Home
    @Serializable data object Feeds : Route

    // Feed / group detail (UI-CONTRACT §2.2: full management and displaced
    // drawer actions; parent Feeds, Back pops to Feeds per §2.3.6)
    @Serializable data class FeedDetail(val feedId: String) : Route

    @Serializable data class GroupDetail(val groupId: String) : Route

    //    @Serializable data object Flow : Route

    @Serializable
    data class Reading(val articleId: String?) : Route {
        companion object {
            val Saver = Saver<Reading, String>(save = { it.articleId }, restore = { Reading(it) })
        }
    }

    // Settings
    @Serializable data object Settings : Route

    // Rules & mutes (Wave D, S17a/S18a)
    @Serializable data object Rules : Route

    @Serializable data class RuleEdit(val ruleId: Long) : Route

    // Downloads (Wave F, S27a/S28/S29/S30a)
    @Serializable data object Downloads : Route

    // Import & export (Wave G, S32a/S33 — FeedBucket migration + OPML)
    @Serializable data object Migration : Route

    // Feed discovery (FD2 — browse/search/preview/subscribe new feeds)
    @Serializable data object DiscoverFeeds : Route

    // Accounts
    @Serializable data object Accounts : Route

    @Serializable data class AccountDetails(val accountId: Int) : Route

    @Serializable data object AddAccounts : Route

    // Color & Style
    @Serializable data object ColorAndStyle : Route

    @Serializable data object DarkTheme : Route

    @Serializable data object FeedsPageStyle : Route

    @Serializable data object FlowPageStyle : Route

    @Serializable data object ReadingPageStyle : Route

    @Serializable data object ReadingBoldCharacters : Route

    @Serializable data object ReadingPageTitle : Route

    @Serializable data object ReadingPageText : Route

    @Serializable data object ReadingPageImage : Route

    @Serializable data object ReadingPageVideo : Route

    // Interaction
    @Serializable data object Interaction : Route

    // AI Summaries
    @Serializable data object AiSummaries : Route

    // Languages
    @Serializable data object Languages : Route

    // Troubleshooting
    @Serializable data object Troubleshooting : Route

    // Tips & Support
    @Serializable data object LicenseList : Route
}
