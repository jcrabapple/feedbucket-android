package me.ash.reader.infrastructure.html

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Single source of truth for responsive article-table rendering, shared by
 * ALL THREE reader renderers (card t_1ed5c518):
 *
 * - Live WebView renderer: [TABLE_CSS] is folded into `WebViewStyle.get(...)`
 *   and [TABLE_LABEL_SCRIPT] into `WebViewScript.get(...)`; the script runs in
 *   the page (JavaScript is enabled there) and derives the column labels.
 * - Offline (downloaded-article) renderer: the hardened WebView has
 *   JavaScript DISABLED, so [offlinePage] derives the exact same `data-label`
 *   attributes and scroll wrappers in Kotlin (jsoup) at download-write time
 *   and embeds [TABLE_CSS] in a self-contained document. Zero network calls:
 *   everything is inline.
 * - Native Compose renderer: [labeledDataRows] feeds the `"Header: value"`
 *   row-block flattening in `HtmlToComposable.appendTable`.
 *
 * Layout contract: below [PHONE_BREAKPOINT_CSS_PX] CSS px each row stacks
 * into a card whose cells show the column header above the value via
 * `td::before { content: attr(data-label) }`; at or above the breakpoint a
 * real table layout is kept and every table sits inside a
 * `div.table-scroll { overflow-x: auto }` wrapper so nothing ever clips.
 *
 * The CSS and JS in this file must stay behaviorally in sync with the Kotlin
 * DOM helpers ([deriveLabels], [labeledDataRows], [annotateTables]): the JS
 * is the runtime path for streamed articles, the Kotlin helpers are the
 * runtime path for downloaded articles and the native renderer.
 */
object ReaderTables {

    /** Below this CSS-pixel width rows stack into cards; at/above it a real table is kept. */
    const val PHONE_BREAKPOINT_CSS_PX = 600

    /** Class added to a table once column labels were derived for it. */
    const val TABLE_CLASS = "reader-table"

    /** Class of the horizontal-scroll wrapper every table is placed in. */
    const val SCROLL_WRAPPER_CLASS = "table-scroll"

    /** Attribute marking the header row so phone CSS can hide it. */
    const val HEADER_ROW_ATTR = "data-reader-header-row"

    /** Attribute carrying a cell's column label for the `::before` rule. */
    const val LABEL_ATTR = "data-label"

    /** Idempotence guard so labeling never runs twice on the same table. */
    const val DONE_ATTR = "data-reader-table-done"

    /**
     * Responsive table CSS. Mobile-first: the base rules stack rows into
     * cards; the trailing min-width media query restores the real table at
     * [PHONE_BREAKPOINT_CSS_PX] and above. Every custom property carries a
     * concrete fallback so the block renders identically in the live reader
     * (variables defined by `WebViewStyle`) and in the offline document
     * (variables defined by [offlinePage] or absent).
     */
    val TABLE_CSS: String =
        """
/* Responsive article tables: stacked cards on phones, real table when wide,
   horizontal-scroll wrapper as the clip-free fallback at every width. */
.${SCROLL_WRAPPER_CLASS} {
    overflow-x: auto !important;
    -webkit-overflow-scrolling: touch;
    max-width: 100% !important;
    margin: 0.5em 0 !important;
}

table {
    width: 100% !important;
    max-width: 100% !important;
    border-collapse: collapse !important;
    font-size: inherit !important;
}

table caption {
    display: block;
    text-align: start;
    padding: 0.3em 0;
}

table th,
table td {
    line-height: var(--line-height, 1.5) !important;
    letter-spacing: var(--letter-spacing, normal) !important;
    overflow-wrap: anywhere !important;
    word-break: normal !important;
}

table th img,
table td img {
    margin: 0 !important;
    max-width: 100% !important;
    height: auto !important;
}

/* Phone width (default): stack every row into a card. */
table,
table thead,
table tbody,
table tfoot,
table tr,
table th,
table td {
    display: block !important;
}

table tr {
    margin: 0 0 0.9em 0 !important;
    border: var(--table-border-width, 1px) solid var(--table-border-color, rgba(128, 128, 128, 0.45)) !important;
    border-radius: 10px !important;
}

table th,
table td {
    border-top: none !important;
    border-left: none !important;
    border-right: none !important;
    border-bottom: var(--table-border-width, 1px) solid var(--table-border-color, rgba(128, 128, 128, 0.25)) !important;
    padding: var(--table-cell-padding, 0.5em 0.7em) !important;
    text-align: start !important;
    vertical-align: top !important;
}

table tr th:last-child,
table tr td:last-child {
    border-bottom: none !important;
}

table td[data-label]::before,
table th[data-label]::before {
    content: attr(data-label) !important;
    display: block !important;
    font-weight: 600 !important;
    margin-bottom: 0.2em !important;
    color: var(--bold-text-color, inherit) !important;
}

table.${TABLE_CLASS} thead,
table.${TABLE_CLASS} tr[${HEADER_ROW_ATTR}] {
    display: none !important;
}

/* Wide width: keep a real table; the wrapper scrolls if it still overflows. */
@media (min-width: ${PHONE_BREAKPOINT_CSS_PX}px) {
    table {
        display: table !important;
        width: 100% !important;
        table-layout: auto !important;
        border-collapse: collapse !important;
    }
    table caption {
        display: table-caption;
    }
    table thead {
        display: table-header-group !important;
    }
    table tbody {
        display: table-row-group !important;
    }
    table tfoot {
        display: table-footer-group !important;
    }
    table tr {
        display: table-row !important;
        margin: 0 !important;
        border: none !important;
        border-radius: 0 !important;
    }
    table th,
    table td {
        display: table-cell !important;
        vertical-align: top !important;
        border: var(--table-border-width, 1px) solid var(--table-border-color, rgba(128, 128, 128, 0.45)) !important;
        padding: var(--table-cell-padding, 0.45em) !important;
        text-align: var(--text-align, start) !important;
    }
    table td[data-label]::before,
    table th[data-label]::before {
        content: none !important;
    }
    table.${TABLE_CLASS} thead {
        display: table-header-group !important;
    }
    table.${TABLE_CLASS} tr[${HEADER_ROW_ATTR}] {
        display: table-row !important;
    }
    table tbody tr:nth-child(even) {
        background-color: var(--table-alt-row-bg-color, rgba(128, 128, 128, 0.07)) !important;
    }
}
"""

    /**
     * Label + wrapper script for the live WebView renderer (JavaScript is
     * enabled there; the offline renderer reproduces this in Kotlin via
     * [annotateTables]). Derives column labels from the first `thead` row, or
     * from the first row when there is no `thead`; sets `data-label` on each
     * data cell of that column; skips labeling for tables with fewer than two
     * columns or with no non-empty header cell; wraps every top-level table
     * in a `div.table-scroll` overflow container. Runs once per table
     * ([DONE_ATTR] guard), tolerates nested tables (`:scope`-equivalent
     * direct-children traversal) and malformed markup (try/catch per table).
     * Deliberately avoids JS template literals so it embeds cleanly in a
     * Kotlin raw string.
     */
    val TABLE_LABEL_SCRIPT: String =
        """
(function () {
    function cellsOf(row) {
        return Array.prototype.filter.call(row.children, function (c) {
            return c.tagName === 'TD' || c.tagName === 'TH';
        });
    }
    function theadRows(table) {
        var rows = [];
        Array.prototype.forEach.call(table.children, function (child) {
            if (child.tagName === 'THEAD') {
                Array.prototype.forEach.call(child.children, function (r) {
                    if (r.tagName === 'TR') { rows.push(r); }
                });
            }
        });
        return rows;
    }
    function allRows(table) {
        var rows = [];
        Array.prototype.forEach.call(table.children, function (child) {
            if (child.tagName === 'TR') {
                rows.push(child);
            } else if (child.tagName === 'THEAD' || child.tagName === 'TBODY' || child.tagName === 'TFOOT') {
                Array.prototype.forEach.call(child.children, function (r) {
                    if (r.tagName === 'TR') { rows.push(r); }
                });
            }
        });
        return rows;
    }
    function wrapInScrollDiv(table) {
        var ancestorTable = table.parentElement ? table.parentElement.closest('table') : null;
        if (ancestorTable) { return; }
        var parent = table.parentElement;
        if (parent && parent.classList && parent.classList.contains('${SCROLL_WRAPPER_CLASS}')) { return; }
        var div = document.createElement('div');
        div.className = '${SCROLL_WRAPPER_CLASS}';
        table.parentNode.insertBefore(div, table);
        div.appendChild(table);
    }
    function labelTable(table) {
        if (table.getAttribute('${DONE_ATTR}') === '1') { return; }
        table.setAttribute('${DONE_ATTR}', '1');
        try {
            var headRows = theadRows(table);
            var rows = allRows(table);
            var headerRow = headRows.length > 0 ? headRows[0] : (rows.length > 0 ? rows[0] : null);
            wrapInScrollDiv(table);
            if (!headerRow) { return; }
            var labels = cellsOf(headerRow).map(function (c) {
                return (c.textContent || '').replace(/\s+/g, ' ').trim();
            });
            var hasLabel = labels.some(function (l) { return l.length > 0; });
            if (labels.length < 2 || !hasLabel) { return; }
            table.classList.add('${TABLE_CLASS}');
            headerRow.setAttribute('${HEADER_ROW_ATTR}', '1');
            rows.forEach(function (row) {
                if (row === headerRow) { return; }
                if (headRows.indexOf(row) >= 0) { return; }
                var cells = cellsOf(row);
                for (var i = 0; i < cells.length; i++) {
                    if (i < labels.length && labels[i].length > 0) {
                        cells[i].setAttribute('${LABEL_ATTR}', labels[i]);
                    }
                }
            });
        } catch (e) {
            console.error('ReaderTables: could not label table', e);
        }
    }
    window.labelReaderTables = function () {
        var tables = document.querySelectorAll('table');
        Array.prototype.forEach.call(tables, labelTable);
    };
    window.labelReaderTables();
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', window.labelReaderTables);
    }
})();
"""

    // --- pure table-structure helpers (native renderer + offline annotation) ---

    /** One data cell of a row: the label to show for its column, plus the cell element. */
    data class LabeledCell(
        val label: String?,
        val element: Element,
    )

    fun cellsOf(row: Element): List<Element> =
        row.children().filter { it.tagName() == "td" || it.tagName() == "th" }

    /** Rows of a table by document order, EXCLUDING rows of nested tables. */
    fun rowsOf(table: Element): List<Element> {
        val rows = mutableListOf<Element>()
        table.children().forEach { child ->
            when (child.tagName()) {
                "tr" -> rows.add(child)
                "thead", "tbody", "tfoot" ->
                    child.children().filter { it.tagName() == "tr" }.forEach { rows.add(it) }
            }
        }
        return rows
    }

    /** Rows inside a direct `thead` child, if any. */
    fun theadRowsOf(table: Element): List<Element> =
        table.children()
            .filter { it.tagName() == "thead" }
            .flatMap { it.children().filter { row -> row.tagName() == "tr" } }

    /**
     * Column labels derived from the first `thead` row, or from the first row
     * when there is no `thead`. Returns an EMPTY label list (labels skipped)
     * for tables with fewer than two columns or when every header cell is
     * blank; the header row is still returned so callers can exclude it.
     */
    fun deriveLabels(table: Element): Pair<List<String>, Element?> {
        val headerRow =
            theadRowsOf(table).firstOrNull() ?: rowsOf(table).firstOrNull() ?: return emptyList<String>() to null
        val labels = cellsOf(headerRow).map { it.text().trim() }
        return if (labels.size >= 2 && labels.any { it.isNotEmpty() }) {
            labels to headerRow
        } else {
            emptyList<String>() to headerRow
        }
    }

    /**
     * The table's DATA rows (header row and any `thead` rows excluded) with
     * per-column labels resolved. Cells carry a null label when the table has
     * no usable headers or that column's header is blank. When no labels were
     * derived at all, every row is a data row (nothing is dropped).
     */
    fun labeledDataRows(table: Element): List<List<LabeledCell>> {
        val headRows = theadRowsOf(table)
        val (labels, headerRow) = deriveLabels(table)
        val hasLabels = labels.isNotEmpty()
        return rowsOf(table)
            .filter { row ->
                headRows.none { it === row } && !(hasLabels && row === headerRow)
            }.map { row ->
                cellsOf(row).mapIndexed { index, cell ->
                    LabeledCell(
                        label = if (hasLabels) labels.getOrNull(index)?.takeIf { it.isNotEmpty() } else null,
                        element = cell,
                    )
                }
            }
    }

    // --- Kotlin-side DOM annotation (offline renderer: JavaScript disabled) ---

    /**
     * Sets `data-label` on every data cell of every table under [root], marks
     * header rows, and wraps each top-level table in a `div.table-scroll`
     * overflow container — the exact DOM the [TABLE_LABEL_SCRIPT] produces in
     * the live renderer. Idempotent per table via [DONE_ATTR].
     */
    fun annotateTables(root: Element) {
        root.select("table").forEach { table -> annotateTable(table) }
    }

    private fun annotateTable(table: Element) {
        if (table.attr(DONE_ATTR) == "1") return
        table.attr(DONE_ATTR, "1")
        wrapInScrollDiv(table)
        val headRows = theadRowsOf(table)
        val (labels, headerRow) = deriveLabels(table)
        if (labels.isEmpty() || headerRow == null) return
        table.addClass(TABLE_CLASS)
        headerRow.attr(HEADER_ROW_ATTR, "1")
        rowsOf(table)
            .filter { row -> headRows.none { it === row } && row !== headerRow }
            .forEach { row ->
                cellsOf(row).forEachIndexed { index, cell ->
                    labels.getOrNull(index)?.takeIf { it.isNotEmpty() }?.let { cell.attr(LABEL_ATTR, it) }
                }
            }
    }

    private fun wrapInScrollDiv(table: Element) {
        if (table.parents().any { it.tagName() == "table" }) return // nested: the outer wrapper scrolls
        if (table.parent()?.hasClass(SCROLL_WRAPPER_CLASS) == true) return
        table.wrap("<div class=\"$SCROLL_WRAPPER_CLASS\"></div>")
    }

    /**
     * The complete offline `index.html` document for a downloaded article:
     * the sanitized body fragment with tables annotated in Kotlin (the
     * hardened offline WebView runs NO JavaScript), wrapped in a
     * self-contained page carrying the shared [TABLE_CSS]. Everything is
     * inline — the document makes zero network calls.
     */
    fun offlinePage(bodyHtml: String): String {
        val doc = Jsoup.parseBodyFragment(bodyHtml)
        annotateTables(doc.body())
        val body = doc.body().html()
        // Built by concatenation, NOT a trimMargin template: article text may
        // contain pipe characters and trimMargin would eat everything before
        // the first pipe on any interpolated content line.
        val head =
            "<!DOCTYPE html>\n" +
                "<html dir=\"auto\">\n" +
                "<head>\n" +
                "<meta charset=\"utf-8\">\n" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">\n" +
                // Inline empty icon: suppresses Chromium's automatic /favicon.ico
                // probe (which would sit OUTSIDE the appassets /app/ scope and
                // break the zero-non-local offline contract). data: makes no request.
                "<link rel=\"icon\" href=\"data:,\">\n" +
                "<style>\n" +
                ":root {\n" +
                "    color-scheme: light dark;\n" +
                "    --line-height: 1.6;\n" +
                "    --letter-spacing: normal;\n" +
                "    --text-align: start;\n" +
                "    --bold-text-color: #1c1b1f;\n" +
                "    --table-border-width: 1px;\n" +
                "    --table-border-color: rgba(128, 128, 128, 0.45);\n" +
                "    --table-cell-padding: 0.5em 0.7em;\n" +
                "    --table-alt-row-bg-color: rgba(128, 128, 128, 0.07);\n" +
                "}\n" +
                "@media (prefers-color-scheme: dark) {\n" +
                "    :root {\n" +
                "        --bold-text-color: #e6e1e5;\n" +
                "    }\n" +
                "}\n" +
                "body {\n" +
                "    margin: 0;\n" +
                "    padding: 8px 16px;\n" +
                "    font-family: sans-serif;\n" +
                "    font-size: 16px;\n" +
                "    line-height: var(--line-height);\n" +
                "}\n" +
                "img, video, iframe, embed, object {\n" +
                "    max-width: 100% !important;\n" +
                "    height: auto !important;\n" +
                "}\n" +
                "pre {\n" +
                "    white-space: pre-wrap !important;\n" +
                "    overflow-wrap: anywhere !important;\n" +
                "}\n" +
                "p {\n" +
                "    overflow-wrap: anywhere;\n" +
                "}\n" +
                TABLE_CSS +
                "</style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<article>\n"
        val tail = "\n</article>\n</body>\n</html>\n"
        return head + body + tail
    }
}
