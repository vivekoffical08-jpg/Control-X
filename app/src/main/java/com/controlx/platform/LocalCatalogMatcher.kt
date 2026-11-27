package com.controlx.platform

object LocalCatalogMatcher {
    fun <T> uniqueBestMatch(
        query: String,
        candidates: List<T>,
        label: (T) -> String,
        minimumScore: Double = 0.62,
        ambiguityMargin: Double = 0.08,
    ): T? {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isEmpty()) return null

        val ranked = candidates.mapNotNull { candidate ->
            val normalizedLabel = normalize(label(candidate))
            if (normalizedLabel.isEmpty()) return@mapNotNull null
            candidate to score(normalizedQuery, normalizedLabel)
        }.sortedByDescending { it.second }

        val best = ranked.firstOrNull() ?: return null
        if (best.second < minimumScore) return null
        val second = ranked.getOrNull(1)
        if (best.second < 1.0 && second != null &&
            best.second - second.second < ambiguityMargin
        ) return null
        return best.first
    }

    fun normalize(value: String): String =
        value.lowercase()
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .trim()
            .replace(Regex("""\s+"""), " ")

    private fun score(query: String, candidate: String): Double {
        if (query == candidate) return 1.0
        if (candidate.startsWith(query)) return 0.92
        if (candidate.contains(query)) return 0.84

        val queryTokens = query.split(' ').toSet()
        val candidateTokens = candidate.split(' ').toSet()
        val tokenRecall = queryTokens.intersect(candidateTokens).size.toDouble() /
            queryTokens.size
        if (tokenRecall == 1.0) return 0.88

        val distance = levenshtein(query, candidate)
        return 1.0 - distance.toDouble() / maxOf(query.length, candidate.length)
    }

    private fun levenshtein(left: String, right: String): Int {
        var previous = IntArray(right.length + 1) { it }
        for (leftIndex in left.indices) {
            val current = IntArray(right.length + 1)
            current[0] = leftIndex + 1
            for (rightIndex in right.indices) {
                val substitution = previous[rightIndex] +
                    if (left[leftIndex] == right[rightIndex]) 0 else 1
                current[rightIndex + 1] = minOf(
                    current[rightIndex] + 1,
                    previous[rightIndex + 1] + 1,
                    substitution,
                )
            }
            previous = current
        }
        return previous[right.length]
    }
}
