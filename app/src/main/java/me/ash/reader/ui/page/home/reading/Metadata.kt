package me.ash.reader.ui.page.home.reading

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.ash.reader.infrastructure.preference.LocalReadingFonts
import me.ash.reader.infrastructure.preference.LocalReadingTitleAlign
import me.ash.reader.infrastructure.preference.LocalReadingTitleBold
import me.ash.reader.infrastructure.preference.LocalReadingTitleUpperCase
import me.ash.reader.ui.ext.formatAsString
import me.ash.reader.ui.ext.requiresBidi
import me.ash.reader.ui.theme.applyTextDirection
import java.util.Date

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Metadata(
    feedName: String,
    title: String,
    publishedDate: Date,
    modifier: Modifier = Modifier,
    author: String? = null,
) {
    val context = LocalContext.current
    val titleBold = LocalReadingTitleBold.current
    val titleUpperCase = LocalReadingTitleUpperCase.current
    val titleAlign = LocalReadingTitleAlign.current.toTextAlign()
    val dateString =
        remember(publishedDate) { publishedDate.formatAsString(context, atHourMinute = true) }
    val fontFamily = LocalReadingFonts.current.asFontFamily(context)

    val titleUpperCaseString by remember { derivedStateOf { title.uppercase() } }

    // REFRESH-v0.2 section 3: eyebrow arrangement and accent-rule alignment
    // follow the title alignment preference.
    val eyebrowArrangement =
        when (titleAlign) {
            TextAlign.Center -> Arrangement.Center
            TextAlign.End, TextAlign.Right -> Arrangement.End
            else -> Arrangement.Start
        }
    val ruleAlignment =
        when (titleAlign) {
            TextAlign.Center -> Alignment.Center
            TextAlign.End, TextAlign.Right -> Alignment.CenterEnd
            else -> Alignment.CenterStart
        }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(12.dp)
    ) {
        // Eyebrow: feed name + " · " + date; the separator travels with the
        // date as one nowrap unit so the date group wraps as a whole.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = eyebrowArrangement,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = feedName,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium.merge(fontFamily = fontFamily),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row {
                Text(
                    text = " · ",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = dateString,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium.merge(fontFamily = fontFamily),
                    maxLines = 1,
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics { heading() },
            text = if (titleUpperCase.value) titleUpperCaseString else title,
            color = MaterialTheme.colorScheme.onSurface,
            style =
                MaterialTheme.typography.headlineLarge
                    .merge(
                        fontFamily = fontFamily,
                        fontWeight = if (titleBold.value) FontWeight.Bold else FontWeight.Medium,
                        lineHeight = 38.sp,
                    )
                    .applyTextDirection(requiresBidi = title.requiresBidi()),
            textAlign = titleAlign,
        )
        // Author only when present: no reserved height when absent.
        if (!author.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                modifier = Modifier.fillMaxWidth(),
                text = author,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium.merge(fontFamily = fontFamily),
                textAlign = titleAlign,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        // Accent rule: header/body separator and the reader's brand gesture.
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = ruleAlignment,
        ) {
            Box(
                modifier =
                    Modifier
                        .height(2.dp)
                        .width(32.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}
