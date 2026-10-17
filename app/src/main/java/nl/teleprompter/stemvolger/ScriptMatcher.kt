package nl.teleprompter.stemvolger

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Tracks where the speaker is in the script by matching recognized speech against the script words.
 *
 * The recognizer delivers growing hypotheses ("goede", "goedemorgen allemaal", ...). Each update the
 * last few heard words are aligned (longest common subsequence, fuzzy word equality) against the
 * script just before every candidate end position in a window ahead of the current position. The
 * best scoring candidate becomes the new position. Only small backwards moves are allowed, so a
 * misrecognition can never throw the prompter far back.
 */
class ScriptMatcher(script: String) {

    /** A script word with its character range in the original text. */
    data class Word(val normalized: String, val start: Int, val end: Int)

    val words: List<Word> = tokenize(script)

    /** Index of the next word the speaker is expected to say (0..words.size). */
    var position: Int = 0
        set(value) {
            field = value.coerceIn(0, words.size)
        }

    val isFinished: Boolean get() = position >= words.size

    /**
     * Feed the latest recognizer hypothesis. Returns true when the position changed.
     */
    fun onHeard(hypothesis: String): Boolean {
        val heard = tokenize(hypothesis).map { it.normalized }
        if (heard.isEmpty() || words.isEmpty()) return false
        val tail = heard.takeLast(TAIL_WORDS)

        val from = max(0, position - BACK_WINDOW)
        val to = min(words.size - 1, position + FORWARD_WINDOW)
        var bestEnd = -1
        var bestScore = 0.0
        for (end in from..to) {
            // The newest heard word has to be the word at this candidate position.
            if (!similar(tail.last(), words[end].normalized)) continue
            val segStart = max(0, end - tail.size - SLACK + 1)
            val matched = lcs(tail, segStart, end)
            val distance = end - (position - 1)
            if (!acceptable(matched, tail.size, distance)) continue
            val score = matched - abs(distance) * DISTANCE_PENALTY
            if (score > bestScore) {
                bestScore = score
                bestEnd = end
            }
        }
        if (bestEnd < 0) return false
        val newPosition = bestEnd + 1
        if (newPosition == position) return false
        position = newPosition
        return true
    }

    /** Index of the word containing (or following) the given character offset. */
    fun wordIndexAt(charOffset: Int): Int {
        val i = words.indexOfFirst { it.end > charOffset }
        return if (i < 0) words.size else i
    }

    private fun acceptable(matched: Int, tailSize: Int, distance: Int): Boolean = when {
        // Moving forward right next to where we are: a single word is enough.
        distance in 1..2 -> matched >= 1
        // Staying put or a tiny step back.
        distance in -BACK_WINDOW..0 -> matched >= min(2, tailSize)
        // Jumping ahead (skipped a sentence) needs more evidence the further we go.
        distance <= 8 -> matched >= min(2, tailSize)
        else -> matched >= min(3, tailSize) && tailSize >= 3
    }

    /** LCS length between [tail] and script words [segStart..segEnd]. */
    private fun lcs(tail: List<String>, segStart: Int, segEnd: Int): Int {
        val n = tail.size
        val m = segEnd - segStart + 1
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in 1..n) for (j in 1..m) {
            dp[i][j] = if (similar(tail[i - 1], words[segStart + j - 1].normalized)) {
                dp[i - 1][j - 1] + 1
            } else {
                max(dp[i - 1][j], dp[i][j - 1])
            }
        }
        return dp[n][m]
    }

    companion object {
        private const val TAIL_WORDS = 6
        private const val SLACK = 3
        private const val BACK_WINDOW = 3
        private const val FORWARD_WINDOW = 40
        private const val DISTANCE_PENALTY = 0.03

        private val WORD = Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)*")
        private val MARKS = Regex("\\p{M}+")

        fun normalize(s: String): String =
            MARKS.replace(Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD), "")
                .replace("’", "'")

        fun tokenize(text: String): List<Word> =
            WORD.findAll(text).map { Word(normalize(it.value), it.range.first, it.range.last + 1) }
                .toList()

        /** Fuzzy word equality, tolerant of small recognition errors. */
        fun similar(a: String, b: String): Boolean {
            if (a == b) return true
            val shorter = min(a.length, b.length)
            if (shorter < 4) return false
            val allowed = if (shorter >= 7) 2 else 1
            if (abs(a.length - b.length) > allowed) {
                // Compound words: "goedemorgen" vs "goede". Accept a long shared prefix.
                return shorter >= 5 && (a.startsWith(b) || b.startsWith(a))
            }
            return levenshtein(a, b, allowed) <= allowed
        }

        private fun levenshtein(a: String, b: String, limit: Int): Int {
            var prev = IntArray(b.length + 1) { it }
            var cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                cur[0] = i
                var rowMin = cur[0]
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
                    rowMin = min(rowMin, cur[j])
                }
                if (rowMin > limit) return limit + 1
                val t = prev; prev = cur; cur = t
            }
            return prev[b.length]
        }
    }
}
