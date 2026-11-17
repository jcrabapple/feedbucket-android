package me.ash.reader.domain.model.query

/**
 * S10 — shared query grammar (exact port of web `parse_search`, db.py:1047).
 *
 * One parser for search UI, saved searches, rule previews, rule ingest
 * application and mute filtering. No second grammar may exist (D7).
 *
 * Tokens: POSIX-shell-style (a Kotlin port of Python `shlex.split`
 * semantics, pinned against recorded Python outputs in
 * `SearchQueryParserTest`); on unbalanced quotes the fallback is a plain
 * whitespace split (web `except ValueError: query.split()`).
 *
 * `sort:asc|desc|relevance` — case-insensitive value, last valid wins,
 * invalid ignored. `tag:NAME` — NAME must be non-empty, last wins.
 * All other tokens are search terms, AND-ed, order preserved.
 */
data class SearchQuery(
    val terms: List<String>,
    val tag: String?,
    val sort: Sort,
) {

    enum class Sort { ASC, DESC, RELEVANCE }

    companion object {
        val EMPTY = SearchQuery(terms = emptyList(), tag = null, sort = Sort.DESC)

        fun parse(query: String, defaultSort: Sort = Sort.DESC): SearchQuery {
            val tokens = tokenize(query)
            var sort = defaultSort
            var tag: String? = null
            val terms = mutableListOf<String>()
            for (token in tokens) {
                when {
                    token.startsWith("sort:") -> {
                        val candidate = token.substring(5).lowercase()
                        when (candidate) {
                            "asc" -> sort = Sort.ASC
                            "desc" -> sort = Sort.DESC
                            "relevance" -> sort = Sort.RELEVANCE
                        }
                    }
                    token.startsWith("tag:") && token.substring(4).isNotEmpty() ->
                        tag = token.substring(4)
                    else -> terms.add(token)
                }
            }
            return SearchQuery(terms = terms, tag = tag, sort = sort)
        }

        /**
         * Raw token stream: `shlex.split` semantics, whitespace-split
         * fallback on unbalanced quotes (web `parse_search`). Exposed for
         * parity testing against recorded Python `shlex.split` outputs.
         */
        fun tokenize(query: String): List<String> = try {
            shlexSplit(query)
        } catch (e: UnbalancedQueryException) {
            whitespaceSplit(query)
        }

        private fun whitespaceSplit(query: String): List<String> =
            query.split(Regex("\\s+")).filter { it.isNotEmpty() }

        /**
         * Port of Python `shlex.split` (posix mode, no comments): double
         * quotes, single quotes, backslash escapes. Inside single quotes the
         * backslash is LITERAL; inside double quotes (and outside quotes) it
         * escapes the next character. An empty quoted section yields an
         * empty token (`""` -> `[""]`). A trailing lone backslash or an
         * unclosed quote throws [UnbalancedQueryException] (web ValueError).
         */
        internal fun shlexSplit(input: String): List<String> {
            val tokens = mutableListOf<String>()
            val current = StringBuilder()
            // ' ' = in/between token (whitespace ends the token), '\'' / '"' = inside
            // quotes, 'a' = escaped character (returns to [escapeReturn]).
            var state = ' '
            var escapeReturn = ' '
            var tokenStarted = false

            fun emit() {
                if (tokenStarted) {
                    tokens.add(current.toString())
                    current.clear()
                    tokenStarted = false
                }
            }

            for (c in input) {
                when (state) {
                    ' ' -> when {
                        c.isWhitespace() -> emit()
                        c == '\\' -> {
                            tokenStarted = true
                            escapeReturn = ' '
                            state = 'a'
                        }
                        c == '\'' || c == '"' -> {
                            tokenStarted = true
                            state = c
                        }
                        else -> {
                            tokenStarted = true
                            current.append(c)
                        }
                    }
                    '\'' -> when (c) {
                        '\'' -> state = ' '
                        else -> current.append(c)
                    }
                    '"' -> when (c) {
                        '"' -> state = ' '
                        '\\' -> {
                            escapeReturn = '"'
                            state = 'a'
                        }
                        else -> current.append(c)
                    }
                    'a' -> {
                        current.append(c)
                        state = escapeReturn
                    }
                }
            }
            when (state) {
                'a' -> throw UnbalancedQueryException() // "No escaped character"
                '\'', '"' -> throw UnbalancedQueryException() // "No closing quotation"
            }
            emit()
            return tokens
        }
    }
}

internal class UnbalancedQueryException : Exception()
