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
                .let { parseJsonStringArray(it) }
            return ImportLedger(
                imported = num("imported"),
                skipped = num("skipped"),
                failed = num("failed"),
                searchesImported = num("searchesImported"),
                rulesImported = num("rulesImported"),
                failures = failures,
            )
        }

        /**
         * Parses the hand-rolled `"a","b"` failures payload with FULL JSON
         * string semantics (review round 1 finding 4): commas, quotes,
         * backslashes and unicode escapes INSIDE a string never split or
         * corrupt neighbouring entries. The old split(",") implementation
         * turned `feed 2 (Alpha, News): ... feed.url, accountId` into
         * fragments.
         */
        internal fun parseJsonStringArray(payload: String): List<String> {
            val result = mutableListOf<String>()
            var i = 0
            while (i < payload.length) {
                when {
                    payload[i].isWhitespace() || payload[i] == ',' -> i += 1
                    payload[i] == '"' -> {
                        val sb = StringBuilder()
                        i += 1
                        while (i < payload.length && payload[i] != '"') {
                            if (payload[i] == '\\' && i + 1 < payload.length) {
                                when (payload[i + 1]) {
                                    '"' -> sb.append('"')
                                    '\\' -> sb.append('\\')
                                    'n' -> sb.append('\n')
                                    'r' -> sb.append('\r')
                                    't' -> sb.append('\t')
                                    'u' -> {
                                        sb.append(payload.substring(i + 2, i + 6).toInt(16).toChar())
                                        i += 4
                                    }
                                    else -> sb.append(payload[i + 1])
                                }
                                i += 2
                            } else {
                                sb.append(payload[i])
                                i += 1
                            }
                        }
                        i += 1 // closing quote
                        result.add(sb.toString())
                    }
                    else -> i += 1
                }
            }
            return result
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
    }
}
