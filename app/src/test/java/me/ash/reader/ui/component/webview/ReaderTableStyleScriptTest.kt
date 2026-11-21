package me.ash.reader.ui.component.webview

import me.ash.reader.infrastructure.html.ReaderTables
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Card t_1ed5c518 — the live WebView renderer's generated CSS/JS must carry
 * the responsive table contract: stacked label cards below the phone
 * breakpoint, a real table plus a horizontal-scroll wrapper at wide widths,
 * and the script that derives `data-label` column labels at runtime.
 */
class ReaderTableStyleScriptTest {

    private fun style(): String =
        WebViewStyle.get(
            fontSize = 17,
            lineHeight = 1f,
            letterSpacing = 0f,
            textMargin = 16,
            textColor = 0xFF202020.toInt(),
            textBold = false,
            textAlign = "left",
            boldTextColor = 0xFF000000.toInt(),
            subheadBold = false,
            subheadUpperCase = false,
            imgMargin = 16,
            imgBorderRadius = 8,
            linkTextColor = 0xFF0000FF.toInt(),
            codeTextColor = 0xFF444444.toInt(),
            codeBgColor = 0xFFCCCCCC.toInt(),
            tableMargin = 16,
            selectionTextColor = 0xFF000000.toInt(),
            selectionBgColor = 0xFFFFFF00.toInt(),
        )

    // --- CSS: phone stacking --------------------------------------------------

    @Test
    fun styleStacksRowsIntoLabeledCardsOnPhones() {
        val css = style()
        // rows and cells become blocks (phone-first base rules)
        assertTrue(css.contains("display: block !important"))
        // the column header is shown above the cell value
        assertTrue(css.contains("content: attr(data-label) !important"))
        assertTrue(css.contains("td[data-label]::before"))
        // the derived-header row is hidden once labels exist
        assertTrue(css.contains("table.${ReaderTables.TABLE_CLASS} thead"))
        assertTrue(css.contains("tr[${ReaderTables.HEADER_ROW_ATTR}]"))
        assertTrue(css.contains("display: none !important"))
        // cell text wraps instead of clipping
        assertTrue(css.contains("overflow-wrap: anywhere !important"))
    }

    // --- CSS: wide real table + scroll wrapper --------------------------------

    @Test
    fun styleKeepsRealTableAndScrollWrapperAtWideWidths() {
        val css = style()
        assertTrue(css.contains("@media (min-width: ${ReaderTables.PHONE_BREAKPOINT_CSS_PX}px)"))
        assertTrue(css.contains("display: table !important"))
        assertTrue(css.contains("width: 100% !important"))
        assertTrue(css.contains("table-layout: auto !important"))
        assertTrue(css.contains("border-collapse: collapse !important"))
        assertTrue(css.contains("vertical-align: top !important"))
        // every table is wrapped in a horizontal-scroll fallback container
        assertTrue(css.contains(".${ReaderTables.SCROLL_WRAPPER_CLASS}"))
        assertTrue(css.contains("overflow-x: auto !important"))
    }

    @Test
    fun styleNoLongerReferencesUndefinedContentWidthVariable() {
        assertFalse(style().contains("--content-width"))
    }

    @Test
    fun styleDefinesConcreteTableBorderVariables() {
        val css = style()
        assertTrue(css.contains("--table-border-width: 1px"))
        assertTrue(Regex("--table-border-color: #[0-9A-F]{8};").containsMatchIn(css))
        assertTrue(Regex("--table-alt-row-bg-color: #[0-9A-F]{8};").containsMatchIn(css))
    }

    // --- JS: label derivation --------------------------------------------------

    @Test
    fun scriptDerivesColumnLabelsAndWrapsTables() {
        for (bold in listOf(false, true)) {
            val script = WebViewScript.get(bold)
            assertTrue(script.contains("labelReaderTables"))
            // labels come from the first thead row or the first row
            assertTrue(script.contains("THEAD"))
            assertTrue(script.contains("setAttribute('${ReaderTables.LABEL_ATTR}'"))
            assertTrue(script.contains("classList.add('${ReaderTables.TABLE_CLASS}')"))
            assertTrue(script.contains("setAttribute('${ReaderTables.HEADER_ROW_ATTR}', '1')"))
            // tables are wrapped in the scroll container
            assertTrue(script.contains("${ReaderTables.SCROLL_WRAPPER_CLASS}"))
            // fewer than two columns -> no labels
            assertTrue(script.contains("labels.length < 2"))
            // runs once per table
            assertTrue(script.contains(ReaderTables.DONE_ATTR))
        }
    }
}
