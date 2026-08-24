package com.vikash.voicescribe.summarize

import kotlin.math.sqrt

/**
 * Extractive summarizer: frequency-scored sentence selection.
 * Language-agnostic enough for whisper output in most scripts; runs on any device
 * with zero model overhead — the graceful-degradation path for summaries.
 */
object Summarizer {

    private val EN_STOPWORDS = setOf(
        "the", "a", "an", "and", "or", "but", "if", "so", "of", "to", "in", "on", "at", "for",
        "with", "by", "from", "as", "is", "are", "was", "were", "be", "been", "being", "it",
        "its", "this", "that", "these", "those", "i", "you", "he", "she", "we", "they", "them",
        "his", "her", "our", "your", "their", "my", "me", "us", "do", "does", "did", "will",
        "would", "can", "could", "should", "have", "has", "had", "not", "no", "yes", "there",
        "what", "which", "who", "when", "where", "how", "why", "all", "any", "some", "just",
        "than", "then", "very", "about", "into", "over", "also", "um", "uh", "like", "okay",
        "yeah", "know", "going", "get", "got", "one", "two", "really", "actually", "basically"
    )

    fun summarize(text: String, maxBullets: Int = 5): List<String> {
        val sentences = splitSentences(text)
        if (sentences.size <= maxBullets) return sentences

        val freq = HashMap<String, Int>()
        for (s in sentences) {
            for (w in tokenize(s)) {
                if (w !in EN_STOPWORDS && w.length > 1) freq[w] = (freq[w] ?: 0) + 1
            }
        }
        if (freq.isEmpty()) return sentences.take(maxBullets)
        val maxFreq = freq.values.max().toFloat()

        val scored = sentences.mapIndexed { idx, s ->
            val words = tokenize(s).filter { it !in EN_STOPWORDS && it.length > 1 }
            val score = if (words.isEmpty()) 0f
            else words.sumOf { ((freq[it] ?: 0) / maxFreq).toDouble() }.toFloat() / sqrt(words.size.toFloat())
            Triple(idx, s, score)
        }

        return scored.sortedByDescending { it.third }
            .take(maxBullets)
            .sortedBy { it.first } // restore chronological order
            .map { it.second }
    }

    private fun splitSentences(text: String): List<String> =
        text.split(Regex("(?<=[.!?।。？！])\\s+|\\n+"))
            .map { it.trim() }
            .filter { it.length > 12 }

    private fun tokenize(s: String): List<String> =
        s.lowercase().split(Regex("[^\\p{L}\\p{N}']+")).filter { it.isNotBlank() }
}
