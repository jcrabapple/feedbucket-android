package me.ash.reader.ui.component.webview

import org.json.JSONObject

/**
 * Card t_1ed5c518 — shared instrumented fixture for the reader-table tests.
 * Mirrors the dev.to Azure tier table from the bug report plus a 2-column
 * table, a table with no thead and a single-column table.
 * KEEP IN SYNC with the JVM fixture (ReaderTablesTest.READER_TABLE_FIXTURE_HTML).
 */
internal object ReaderTableFixture {

    val HTML: String =
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

    /** td elements that must carry a data-label after the label script ran:
     *  tiers 3x4 + twocol 2x2 + nothead 2x3 = 22 (singlecol is skipped). */
    const val EXPECTED_LABELED_TDS = 22

    val EXPECTED_TIER_LABELS = listOf("Tier", "When it shines", "Cost driver", "Gotchas")

    /** Layout probe evaluated in the WebView; returns one JSON object. */
    val PROBE: String =
        """
(function () {
  var tds = document.querySelectorAll('td');
  function disp(el) { return el ? getComputedStyle(el).display : 'missing'; }
  var tiers = document.querySelector('#tiers');
  var wrapper = tiers ? tiers.parentElement : null;
  var overflowing = 0;
  for (var i = 0; i < tds.length; i++) {
    var r = tds[i].getBoundingClientRect();
    if (r.right > window.innerWidth + 1 || r.left < -1) { overflowing++; }
  }
  var firstTd = document.querySelector('#tiers tbody td');
  return JSON.stringify({
    ready: document.readyState,
    labelCount: document.querySelectorAll('td[data-label]').length,
    innerWidth: window.innerWidth,
    scrollWidth: document.documentElement.scrollWidth,
    tiersTableDisplay: disp(tiers),
    tiersRowDisplay: disp(document.querySelector('#tiers tbody tr')),
    theadDisplay: disp(document.querySelector('#tiers thead')),
    wrapperClass: wrapper ? wrapper.className : '',
    wrapperOverflowX: wrapper ? getComputedStyle(wrapper).overflowX : '',
    labels: Array.prototype.map.call(document.querySelectorAll('#tiers tbody td'), function (t) {
      return t.getAttribute('data-label') || '';
    }),
    singleColLabeled: document.querySelectorAll('#singlecol td[data-label]').length,
    beforeContent: firstTd ? getComputedStyle(firstTd, '::before').content : 'missing',
    overflowingCells: overflowing
  });
})();
"""

    fun labelsOf(json: JSONObject): List<String> {
        val arr = json.getJSONArray("labels")
        return (0 until arr.length()).map { arr.getString(it) }
    }
}
