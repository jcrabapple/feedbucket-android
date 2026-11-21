package me.ash.reader.infrastructure.html

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Card t_1ed5c518 — responsive reader tables: pure-Kotlin table structure
 * helpers shared by the native Compose renderer ("Header: value" row blocks)
 * and the offline renderer (Kotlin-derived `data-label` annotations, because
 * the hardened offline WebView runs no JavaScript).
 *
 * The fixture mirrors the 4-column Azure tier table from the reported
 * dev.to article plus a 2-column table, a table with no thead and a
 * single-column table. KEEP IN SYNC with the androidTest mirror
 * (ReaderTableInstrumentedTest.FIXTURE_HTML).
 */
internal const val READER_TABLE_FIXTURE_HTML =
    """
<h2>Cost-effective generative AI development on Azure</h2>
<p>Pick A | B wisely.</p>
<table id="tiers">
  <thead>
    <tr><th>Tier</th><th>When it shines</th><th>Cost driver</th><th>Gotchas</th></tr>
  </thead>
  <tbody>
    <tr>
      <td><strong>Azure VM (NC/D series)</strong></td>
      <td>Short, unpredictable bursts (e.g., on-demand text generation)</td>
      <td>Pay-as-you-go per-second billing</td>
      <td>Must provision and de-provision manually or via Scale Sets; GPU driver version lock-in.</td>
    </tr>
    <tr>
      <td><strong>Azure Machine Learning managed compute</strong></td>
      <td>Repeated training runs with predictable cadence (daily/weekly)</td>
      <td>Cluster auto-scale + per-node hourly rate</td>
      <td>Limited to ML-MLflow-compatible workloads; custom Docker images need to be built in AML workspace.</td>
    </tr>
    <tr>
      <td><strong>Azure Kubernetes Service (AKS)</strong></td>
      <td>Mixed workload: concurrent inference pods + background training jobs</td>
      <td>Consolidates VMs across workloads; can use Spot VMs for cheap training</td>
      <td>Requires Helm/K8s expertise; pod-eviction handling for Spot instances must be coded.</td>
    </tr>
  </tbody>
</table>
<table id="twocol">
  <thead><tr><th>Option</th><th>Monthly cost</th></tr></thead>
  <tbody>
    <tr><td>Spot VMs</td><td>Up to 90 percent cheaper</td></tr>
    <tr><td>Reserved instances</td><td>Up to 72 percent cheaper</td></tr>
  </tbody>
</table>
<table id="nothead">
  <tr><td>Service</td><td>Region</td><td>Status</td></tr>
  <tr><td>OpenAI proxy</td><td>East US</td><td>Healthy</td></tr>
  <tr><td>MLflow tracking</td><td>West Europe</td><td>Degraded</td></tr>
</table>
<table id="singlecol">
  <tbody>
    <tr><td>Only one column here</td></tr>
    <tr><td>So no labels are derived</td></tr>
  </tbody>
</table>
"""

private fun fixture(): Element = Jsoup.parseBodyFragment(READER_TABLE_FIXTURE_HTML).body()

private fun tableById(id: String): Element =
    fixture().select("table#$id").first()
        ?: throw AssertionError("fixture table #$id missing")

private fun labeledLines(table: Element): List<List<String>> =
    ReaderTables.labeledDataRows(table).map { row ->
        row.map { cell ->
            val label = cell.label
            if (label != null) "$label: ${cell.element.text()}" else cell.element.text()
        }
    }

class ReaderTablesTest {

    // --- label derivation ----------------------------------------------------

    @Test
    fun derivesLabelsFromFirstTheadRow() {
        val (labels, headerRow) = ReaderTables.deriveLabels(tableById("tiers"))
        assertEquals(listOf("Tier", "When it shines", "Cost driver", "Gotchas"), labels)
        assertEquals("thead", headerRow?.parent()?.tagName())
    }

    @Test
    fun derivesLabelsFromFirstRowWhenNoThead() {
        val (labels, headerRow) = ReaderTables.deriveLabels(tableById("nothead"))
        assertEquals(listOf("Service", "Region", "Status"), labels)
        assertEquals("Service", headerRow?.text()?.substringBefore(" "))
    }

    @Test
    fun skipsLabelsForFewerThanTwoColumns() {
        val (labels, _) = ReaderTables.deriveLabels(tableById("singlecol"))
        assertTrue("single-column tables must not derive labels", labels.isEmpty())
        // every row stays a data row when nothing is labeled
        val rows = ReaderTables.labeledDataRows(tableById("singlecol"))
        assertEquals(2, rows.size)
        assertTrue(rows.all { row -> row.all { it.label == null } })
        assertEquals(
            listOf(listOf("Only one column here"), listOf("So no labels are derived")),
            rows.map { it.map { cell -> cell.element.text() } },
        )
    }

    @Test
    fun skipsLabelsWhenEveryHeaderCellIsEmpty() {
        val table =
            Jsoup.parseBodyFragment(
                "<table><thead><tr><th></th><th> </th></tr></thead>" +
                    "<tbody><tr><td>a</td><td>b</td></tr></tbody></table>",
            ).body().select("table").first()!!
        val (labels, _) = ReaderTables.deriveLabels(table)
        assertTrue(labels.isEmpty())
    }

    @Test
    fun malformedTablesDoNotCrash() {
        // no rows at all
        val empty = Jsoup.parseBodyFragment("<table></table>").body().select("table").first()!!
        val (labels, headerRow) = ReaderTables.deriveLabels(empty)
        assertTrue(labels.isEmpty())
        assertNull(headerRow)
        assertTrue(ReaderTables.labeledDataRows(empty).isEmpty())
        // thead only, no data rows
        val headOnly =
            Jsoup.parseBodyFragment("<table><thead><tr><th>a</th><th>b</th></tr></thead></table>")
                .body().select("table").first()!!
        assertTrue(ReaderTables.labeledDataRows(headOnly).isEmpty())
    }

    // --- native renderer flattening ("Header: value" lines) ------------------

    @Test
    fun flattensFixtureRowsToHeaderColonValueLines() {
        val rows = labeledLines(tableById("tiers"))
        assertEquals(3, rows.size)
        assertEquals(
            listOf(
                "Tier: Azure VM (NC/D series)",
                "When it shines: Short, unpredictable bursts (e.g., on-demand text generation)",
                "Cost driver: Pay-as-you-go per-second billing",
                "Gotchas: Must provision and de-provision manually or via Scale Sets; " +
                    "GPU driver version lock-in.",
            ),
            rows[0],
        )
        assertEquals("Tier: Azure Machine Learning managed compute", rows[1][0])
        assertEquals(
            "Gotchas: Requires Helm/K8s expertise; pod-eviction handling for Spot " +
                "instances must be coded.",
            rows[2][3],
        )
    }

    @Test
    fun flattensTwoColumnAndNoTheadTables() {
        assertEquals(
            listOf(
                listOf("Option: Spot VMs", "Monthly cost: Up to 90 percent cheaper"),
                listOf("Option: Reserved instances", "Monthly cost: Up to 72 percent cheaper"),
            ),
            labeledLines(tableById("twocol")),
        )
        assertEquals(
            listOf(
                listOf("Service: OpenAI proxy", "Region: East US", "Status: Healthy"),
                listOf("Service: MLflow tracking", "Region: West Europe", "Status: Degraded"),
            ),
            labeledLines(tableById("nothead")),
        )
    }

    @Test
    fun headerRowIsNeverADataRow() {
        val rows = ReaderTables.labeledDataRows(tableById("tiers"))
        assertFalse(
            "the thead row must not appear as a data row",
            rows.flatten().any { it.element.tagName() == "th" },
        )
        val noThead = ReaderTables.labeledDataRows(tableById("nothead"))
        assertEquals(2, noThead.size) // first row is the header, not data
    }

    // --- offline annotation (JavaScript-free data-label derivation) ----------

    @Test
    fun annotateSetsDataLabelsHeaderMarkAndScrollWrapper() {
        val body = fixture()
        ReaderTables.annotateTables(body)
        val tiers = body.select("table#tiers").first()!!
        assertTrue(tiers.hasClass(ReaderTables.TABLE_CLASS))
        assertEquals("1", tiers.select("thead tr").first()!!.attr(ReaderTables.HEADER_ROW_ATTR))
        val firstDataCell = tiers.select("tbody tr").first()!!.select("td").first()!!
        assertEquals("Tier", firstDataCell.attr(ReaderTables.LABEL_ATTR))
        val lastRowLastCell = tiers.select("tbody tr").last()!!.select("td").last()!!
        assertEquals("Gotchas", lastRowLastCell.attr(ReaderTables.LABEL_ATTR))
        // header cells themselves never carry data-label
        assertTrue(tiers.select("thead td[data-label], thead th[data-label]").isEmpty())
        // every top-level table sits inside a scroll wrapper
        for (id in listOf("tiers", "twocol", "nothead", "singlecol")) {
            val table = body.select("table#$id").first()!!
            assertTrue(
                "#$id must be wrapped in a scroll div",
                table.parent()!!.hasClass(ReaderTables.SCROLL_WRAPPER_CLASS),
            )
        }
        // single-column table: wrapped but NOT labeled
        val single = body.select("table#singlecol").first()!!
        assertFalse(single.hasClass(ReaderTables.TABLE_CLASS))
        assertTrue(single.select("td[data-label]").isEmpty())
    }

    @Test
    fun annotateIsIdempotentAndSafeForNestedTables() {
        val body =
            Jsoup.parseBodyFragment(
                """
                <table id="outer">
                  <thead><tr><th>Col A</th><th>Col B</th></tr></thead>
                  <tbody>
                    <tr>
                      <td>outer value</td>
                      <td>
                        <table id="inner">
                          <thead><tr><th>In X</th><th>In Y</th></tr></thead>
                          <tbody><tr><td>ix</td><td>iy</td></tr></tbody>
                        </table>
                      </td>
                    </tr>
                  </tbody>
                </table>
                """.trimIndent(),
            ).body()
        ReaderTables.annotateTables(body)
        ReaderTables.annotateTables(body) // second pass must change nothing

        val outer = body.select("table#outer").first()!!
        val inner = body.select("table#inner").first()!!
        // outer data cell labeled from the OUTER header only
        val outerCell = outer.select("tbody > tr > td").first()!!
        assertEquals("Col A", outerCell.attr(ReaderTables.LABEL_ATTR))
        // inner table labeled from its own header
        assertEquals("In X", inner.select("tbody td").first()!!.attr(ReaderTables.LABEL_ATTR))
        // only the top-level table gets a scroll wrapper
        assertTrue(outer.parent()!!.hasClass(ReaderTables.SCROLL_WRAPPER_CLASS))
        assertFalse(inner.parent()!!.hasClass(ReaderTables.SCROLL_WRAPPER_CLASS))
        // outer data rows exclude the nested table's rows
        assertEquals(1, ReaderTables.labeledDataRows(outer).size)
    }

    // --- offline page template ------------------------------------------------

    @Test
    fun offlinePageIsSelfContainedAndAnnotated() {
        val page = ReaderTables.offlinePage(READER_TABLE_FIXTURE_HTML)
        assertTrue(page.startsWith("<!DOCTYPE html>"))
        assertTrue("viewport meta drives the phone breakpoint", page.contains("width=device-width"))
        assertTrue("shared table CSS is embedded", page.contains(ReaderTables.TABLE_CSS))
        assertTrue("labels derived without JavaScript", page.contains("data-label=\"Tier\""))
        assertTrue(
            "scroll wrapper present",
            page.contains("class=\"${ReaderTables.SCROLL_WRAPPER_CLASS}\""),
        )
        assertTrue(page.contains("</html>"))
        // no external references: the offline path makes zero network calls
        assertFalse(page.contains("<script"))
        assertFalse(page.contains("http://"))
        assertFalse(page.contains("https://"))
    }

    @Test
    fun offlinePagePreservesPipeCharactersInContent() {
        // regression guard: the template must never run interpolated article
        // text through trimMargin (a pipe in content would eat the line start)
        val page = ReaderTables.offlinePage("<p>Pick A | B wisely.</p>")
        assertTrue(page.contains("Pick A | B wisely."))
    }
}
