package me.ash.reader.ui.page.settings.tips

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import me.ash.reader.R
import me.ash.reader.infrastructure.preference.OpenLinkPreference
import me.ash.reader.infrastructure.preference.ReadingFontsPreference
import me.ash.reader.ui.component.base.FeedbackIconButton
import me.ash.reader.ui.component.base.RYScaffold
import me.ash.reader.ui.ext.openURL
import me.ash.reader.ui.theme.GoogleSansFontFamily
import me.ash.reader.ui.theme.palette.onLight

/**
 * One bundled reading font with its attribution string and the full SIL OFL
 * 1.1 license text resource (card t_0f68461a). [preference] is null for
 * Google Sans Flex, which is the app UI face rather than a reading-font
 * dialog entry; its typeface comes from [GoogleSansFontFamily].
 */
private data class BundledFontEntry(
    val name: String,
    val attributionRes: Int,
    val licenseRes: Int,
    val preference: ReadingFontsPreference?,
)

private val bundledFontEntries =
    listOf(
        BundledFontEntry(
            name = "Google Sans Flex",
            attributionRes = R.string.font_google_sans_flex_attribution,
            licenseRes = R.raw.ofl_google_sans_flex,
            preference = null,
        ),
        BundledFontEntry(
            name = "Literata",
            attributionRes = R.string.font_literata_attribution,
            licenseRes = R.raw.ofl_literata,
            preference = ReadingFontsPreference.Literata,
        ),
        BundledFontEntry(
            name = "Source Serif 4",
            attributionRes = R.string.font_source_serif_4_attribution,
            licenseRes = R.raw.ofl_source_serif_4,
            preference = ReadingFontsPreference.SourceSerif,
        ),
        BundledFontEntry(
            name = "Atkinson Hyperlegible",
            attributionRes = R.string.font_atkinson_hyperlegible_attribution,
            licenseRes = R.raw.ofl_atkinson_hyperlegible,
            preference = ReadingFontsPreference.AtkinsonHyperlegible,
        ),
        BundledFontEntry(
            name = "Lexend",
            attributionRes = R.string.font_lexend_attribution,
            licenseRes = R.raw.ofl_lexend,
            preference = ReadingFontsPreference.Lexend,
        ),
    )

@Composable
fun LicenseListPage(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val githubLink = stringResource(R.string.github_link)
    var selectedFont by remember { mutableStateOf<BundledFontEntry?>(null) }

    RYScaffold(
        containerColor = MaterialTheme.colorScheme.surface onLight MaterialTheme.colorScheme.inverseOnSurface,
        navigationIcon = {
            FeedbackIconButton(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.back),
                tint = MaterialTheme.colorScheme.onSurface,
                onClick = onBack
            )
        },
        actions = {
            FeedbackIconButton(
                modifier = Modifier.size(20.dp),
                imageVector = Icons.Rounded.Star,
                contentDescription = stringResource(R.string.open_source_licenses),
                tint = MaterialTheme.colorScheme.onSurface
            ) {
                context.openURL(
                    url = githubLink + "/blob/main/LICENSE",
                    openLink = OpenLinkPreference.AutoPreferCustomTabs,
                )
            }
        },
        content = {
            Column {
                // Bundled reading fonts (card t_0f68461a): each entry shows the
                // font name in its own typeface and opens the full SIL OFL 1.1
                // text shipped in res/raw.
                Text(
                    text = stringResource(R.string.bundled_fonts),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 4.dp),
                )
                bundledFontEntries.forEach { entry ->
                    val fontFamily: FontFamily =
                        entry.preference?.asFontFamily(context) ?: GoogleSansFontFamily
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = fontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { selectedFont = entry }
                                .padding(horizontal = 24.dp, vertical = 10.dp),
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
                LibrariesContainer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = WindowInsets(0, 0, 0, 24)
                        .add(WindowInsets.navigationBars)
                        .asPaddingValues(),
                )
            }
        },
    )

    selectedFont?.let { entry ->
        val licenseText =
            remember(entry) {
                context.resources.openRawResource(entry.licenseRes)
                    .bufferedReader().use { it.readText() }
            }
        AlertDialog(
            onDismissRequest = { selectedFont = null },
            confirmButton = {
                TextButton(onClick = { selectedFont = null }) {
                    Text(text = stringResource(R.string.close))
                }
            },
            title = { Text(text = entry.name) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = stringResource(entry.attributionRes),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = licenseText,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            },
        )
    }
}
