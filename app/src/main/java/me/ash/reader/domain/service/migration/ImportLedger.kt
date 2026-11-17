package me.ash.reader.domain.service.migration

/**
 * S32a step 5 — the counts ledger written to `import_run.countsJson`:
 * imported / skipped (identity-map hits) / failed (per-row errors,
 * itemized). A row-level failure never aborts the feed chunk unless it is
 * a schema/constraint error, in which case the whole feed chunk rolls back
 * and is counted as one failed item (bounded blast radius).
 *
 * Hand-rolled JSON (no new dependency): the shape is internal to the
 * import_run ledger and read back by the result screen.
 */
data class ImportLedger(
    val imported: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val searchesImported: Int = 0,
    val rulesImported: Int = 0,
    val failures: List<String> = emptyList(),
) {

    fun plusImported(delta: Int = 1): ImportLedger = copy(imported = imported + delta)

    fun plusSkipped(delta: Int = 1): ImportLedger = copy(skipped = skipped + delta)

    fun plusFailed(item: String): ImportLedger =
        copy(failed = failed + 1, failures = failures + item)

    fun toJson(): String = buildString {
        append("""{"imported":$imported,"skipped":$skipped,"failed":$failed,""")
        append(""""searchesImported":$searchesImported,"rulesImported":$rulesImported,""")
        append(""""failures":[""")
        append(failures.joinToString(",") { "\"${escape(it)}\"" })
        append("]}")
    }

    companion object {
        fun fromJson(json: String): ImportLedger {
            fun num(key: String): Int =
                Regex(""""$key":(\d+)""").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0

            val failures = Regex(""""failures":\[(.*)\]""")
                .find(json)?.groupValues?.get(1).orEmpty()
                .splitToSequence(",")
                .map { it.trim() }
                .filter { it.length >= 2 }
                .map { unescape(it.substring(1, it.length - 1)) }
                .toList()
            return ImportLedger(
                imported = num("imported"),
                skipped = num("skipped"),
                failed = num("failed"),
                searchesImported = num("searchesImported"),
                rulesImported = num("rulesImported"),
                failures = failures,
            )
        }

        internal fun escape(value: String): String = buildString(value.length) {
            for (ch in value) {
                when (ch) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
                }
            }
        }

        internal fun unescape(value: String): String = buildString(value.length) {
            var i = 0
            while (i < value.length) {
                if (value[i] == '\\' && i + 1 < value.length) {
                    when (value[i + 1]) {
                        '"' -> append('"')
                        '\\' -> append('\\')
                        'n' -> append('\n')
                        'r' -> append('\r')
                        't' -> append('\t')
                        'u' -> {
                            append(value.substring(i + 2, i + 6).toInt(16).toChar())
                            i += 4
                        }
                        else -> append(value[i + 1])
                    }
                    i += 2
                } else {
                    append(value[i])
                    i += 1
                }
            }
        }
    }
}
