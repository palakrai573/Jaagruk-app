package org.jaagruk.core.ai

import kotlin.math.ln

/**
 * Tokeniser for retrieval over mixed English and Devanagari text.
 *
 * The one thing this has to get right is that **Devanagari combining marks are part of a word**.
 * `Char.isLetterOrDigit()` is false for a matra: "गैस" is ग + ◌ै + स, so splitting on
 * letters-or-digits alone yields "ग" and "स" and Hindi retrieval quietly stops working — every
 * query matches everything, because every token is a single consonant. Marks (Mn/Mc), and the
 * zero-width joiners Devanagari uses to form conjuncts, are therefore treated as word characters.
 * `DevanagariTokenizerTest` exists to keep it that way.
 */
object AiTokenizer {

    private const val ZWJ = '\u200D'
    private const val ZWNJ = '\u200C'

    /** Minimum token length. Below this a token carries no retrieval signal in either script. */
    private const val MIN_TOKEN_LENGTH = 2

    /** Minimum stem length. Guards the suffix strippers from turning words into noise. */
    private const val MIN_STEM_LENGTH = 4

    /**
     * Function words, dropped before scoring.
     *
     * Negations ("not", "नहीं") are included, which looks wrong in a safety context and is not.
     * These are *retrieval* tokens: near every passage in this corpus contains a prohibition, so
     * their IDF is near zero and they cost query terms without narrowing anything. The model is
     * shown the untokenised passage body, where the negation is intact and load-bearing.
     */
    private val STOPWORDS: Set<String> = setOf(
        // English, two letters. The minimum token length is 2, so these reach the filter.
        "is", "of", "to", "in", "on", "at", "be", "as", "by", "or", "if", "it", "an", "no",
        "do", "we", "he", "so", "up", "my", "me", "us", "am", "any", "was",
        // English
        "the", "and", "for", "are", "but", "not", "you", "all", "can", "her", "one",
        "our", "out", "his", "has", "had", "him", "how", "its", "who", "did", "yes", "why",
        "with", "this", "that", "from", "they", "have", "been", "were", "what", "when",
        "will", "your", "them", "then", "than", "there", "these", "those", "which", "would",
        "should", "could", "about", "does", "into", "onto", "very", "just", "also", "such",
        "only", "same", "each", "more", "most", "some", "any", "own", "too", "use", "used",
        // Hindi
        "और", "का", "की", "के", "को", "में", "से", "पर", "है", "हैं", "था", "थे", "थी", "या",
        "नहीं", "यह", "वह", "ये", "वे", "कि", "जो", "तो", "ही", "भी", "एक", "इस", "उस",
        "हो", "होता", "होती", "होते", "लिए", "साथ", "तक", "द्वारा", "क्या", "क्यों", "कैसे",
        "कब", "कहाँ", "कौन", "मैं", "आप", "हम", "अपने", "अपनी", "अपना", "कोई", "सकता",
        "सकते", "सकती", "गया", "गई", "गए", "रहा", "रही", "रहे", "करें", "कर", "किया",
    )

    /** Conservative English suffixes, longest first so "ies" wins over "s". */
    private val ENGLISH_SUFFIXES: List<String> = listOf("ing", "ies", "ed", "es", "s")

    /**
     * Conservative Hindi inflectional endings, longest first.
     *
     * Only noun-oblique and plural endings that attach to a stem. Postpositions (का/की/के/से/में)
     * are separate words in Hindi and are handled as stopwords, not stripped as suffixes.
     */
    private val HINDI_SUFFIXES: List<String> = listOf("ियों", "ाओं", "ुओं", "ओं", "ों", "ें", "ाएँ", "ाँ", "ीं")

    fun tokenize(text: String): List<String> {
        val raw = split(text)
        val out = ArrayList<String>(raw.size)
        for (token in raw) {
            if (token.length < MIN_TOKEN_LENGTH) continue
            if (token in STOPWORDS) continue
            val stemmed = stem(token)
            if (stemmed.length < MIN_TOKEN_LENGTH) continue
            if (stemmed in STOPWORDS) continue
            out += stemmed
        }
        return out
    }

    /** Distinct tokens, order preserved. Used for the matched-term ratio. */
    fun tokenSet(text: String): Set<String> = LinkedHashSet(tokenize(text))

    private fun split(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            val isWord = when {
                ch.isLetterOrDigit() -> true
                ch == ZWJ || ch == ZWNJ -> true
                ch.category == CharCategory.NON_SPACING_MARK -> true
                ch.category == CharCategory.COMBINING_SPACING_MARK -> true
                // A decimal point only belongs to a word between two digits, so "1.25" survives
                // intact while a sentence-ending full stop still separates.
                ch == '.' || ch == ',' ->
                    index > 0 && text[index - 1].isDigit() &&
                        index + 1 < text.length && text[index + 1].isDigit()
                else -> false
            }
            if (isWord) {
                current.append(ch.lowercaseChar())
            } else if (current.isNotEmpty()) {
                tokens += current.toString()
                current.setLength(0)
            }
            index++
        }
        if (current.isNotEmpty()) tokens += current.toString()
        return tokens
    }

    private fun stem(token: String): String {
        // Numbers are never stemmed: "1.25" must not become "1.2".
        if (token.any { it.isDigit() }) return token
        for (suffix in HINDI_SUFFIXES) {
            if (token.length - suffix.length >= 3 && token.endsWith(suffix)) {
                return token.dropLast(suffix.length)
            }
        }
        for (suffix in ENGLISH_SUFFIXES) {
            if (token.length - suffix.length >= MIN_STEM_LENGTH && token.endsWith(suffix)) {
                val stem = token.dropLast(suffix.length)
                return if (suffix == "ies") "${stem}y" else dropSilentE(stem)
            }
        }
        return dropSilentE(token)
    }

    /**
     * Drops a trailing "e" on longer words so a verb and its participle land on one stem.
     *
     * Without it "ventilate" and "ventilating" are different terms — the participle loses "ing" and
     * becomes "ventilat", which never matches the infinitive. Since both sides pass through here the
     * result is consistent, which is all retrieval requires; it is not trying to be linguistically
     * correct. Guarded at 5 characters so short words keep their shape.
     */
    private fun dropSilentE(token: String): String =
        if (token.length >= 5 && token.endsWith("e")) token.dropLast(1) else token
}

/**
 * BM25 parameters.
 *
 * The Okapi defaults. They are defaults rather than tuned values because tuning them against a
 * corpus this size would be fitting noise, and `docs/CALIBRATION.md` says what would justify
 * changing them.
 */
class Bm25Config(
    val k1: Double = 1.2,
    val b: Double = 0.75,
) {
    init {
        require(k1 > 0.0) { "k1 must be positive, got $k1" }
        require(b in 0.0..1.0) { "b must be 0.0..1.0, got $b" }
    }
}

/** A passage with its retrieval score and how much of the query it actually accounted for. */
class ScoredPassage(
    val passage: CorpusPassage,
    val score: Double,
    val matchedTerms: Set<String>,
    val queryTermCount: Int,
) {
    /**
     * Fraction of distinct query terms this passage contains, 0.0..1.0.
     *
     * This, not the raw score, is what the relevance floor is expressed in. Raw BM25 is unbounded
     * and grows with query length and corpus size, so a threshold in raw units silently changes
     * meaning every time a passage is added. A bounded ratio does not, and it is explainable to
     * somebody reviewing why an answer was refused.
     */
    val matchedTermRatio: Double
        get() = if (queryTermCount == 0) 0.0 else matchedTerms.size.toDouble() / queryTermCount

    override fun toString(): String =
        "ScoredPassage(${passage.passageId}, score=%.3f, ratio=%.2f)".format(score, matchedTermRatio)
}

/**
 * Inverted BM25 index over one language's passages.
 *
 * One index per language, so document frequencies are language-local. A single mixed index would
 * give Devanagari terms inflated IDF purely because the English half of the corpus never contains
 * them, which would make Hindi scores incomparable with English ones.
 */
class Bm25Index(
    passages: List<CorpusPassage>,
    private val config: Bm25Config = Bm25Config(),
) {
    private class Posting(val documentIndex: Int, val termFrequency: Int)

    private val documents: List<CorpusPassage> = passages.toList()
    private val postings: Map<String, List<Posting>>
    private val documentLengths: IntArray
    private val averageDocumentLength: Double

    init {
        documentLengths = IntArray(documents.size)
        val accumulator = HashMap<String, MutableList<Posting>>()
        documents.forEachIndexed { documentIndex, passage ->
            val tokens = AiTokenizer.tokenize(passage.indexedText)
            documentLengths[documentIndex] = tokens.size
            val frequencies = HashMap<String, Int>()
            for (token in tokens) frequencies[token] = (frequencies[token] ?: 0) + 1
            for ((term, frequency) in frequencies) {
                accumulator.getOrPut(term) { mutableListOf() } += Posting(documentIndex, frequency)
            }
        }
        postings = accumulator
        averageDocumentLength =
            if (documents.isEmpty()) 0.0 else documentLengths.sum().toDouble() / documents.size
    }

    val documentCount: Int get() = documents.size

    val termCount: Int get() = postings.size

    /**
     * Ranks passages for [query], highest first.
     *
     * [accept] is applied before scoring, so an out-of-scope passage cannot occupy a slot in the
     * result. Ties break on passageId, so the ranking is deterministic and a snapshot test of a
     * prompt built from it cannot flake.
     */
    fun search(
        query: String,
        limit: Int,
        accept: (CorpusPassage) -> Boolean = { true },
    ): List<ScoredPassage> {
        require(limit > 0) { "limit must be positive, got $limit" }
        val queryTerms = AiTokenizer.tokenSet(query)
        if (queryTerms.isEmpty() || documents.isEmpty()) return emptyList()

        val eligible = BooleanArray(documents.size)
        var anyEligible = false
        documents.forEachIndexed { index, passage ->
            val ok = accept(passage)
            eligible[index] = ok
            if (ok) anyEligible = true
        }
        if (!anyEligible) return emptyList()

        val scores = DoubleArray(documents.size)
        val matched = Array(documents.size) { mutableSetOf<String>() }

        for (term in queryTerms) {
            val termPostings = postings[term] ?: continue
            // Document frequency is counted over eligible documents only, so narrowing the scope
            // does not leave IDF describing a corpus the query was never allowed to see.
            val documentFrequency = termPostings.count { eligible[it.documentIndex] }
            if (documentFrequency == 0) continue
            val eligibleCount = eligible.count { it }
            val idf = ln(
                1.0 + (eligibleCount - documentFrequency + 0.5) / (documentFrequency + 0.5),
            )
            for (posting in termPostings) {
                if (!eligible[posting.documentIndex]) continue
                val length = documentLengths[posting.documentIndex].toDouble()
                val normaliser = if (averageDocumentLength <= 0.0) {
                    1.0
                } else {
                    1.0 - config.b + config.b * (length / averageDocumentLength)
                }
                val frequency = posting.termFrequency.toDouble()
                scores[posting.documentIndex] +=
                    idf * (frequency * (config.k1 + 1.0)) / (frequency + config.k1 * normaliser)
                matched[posting.documentIndex] += term
            }
        }

        return documents.indices
            .filter { eligible[it] && scores[it] > 0.0 }
            .map { ScoredPassage(documents[it], scores[it], matched[it], queryTerms.size) }
            .sortedWith(compareByDescending<ScoredPassage> { it.score }.thenBy { it.passage.passageId })
            .take(limit)
    }
}

/** Why retrieval declined to supply grounding. Each one becomes a different thing said to a user. */
enum class InsufficientReason {
    /** The query reduced to nothing but stopwords. */
    NO_QUERY_TERMS,

    /** No passage in scope shares a single term with the query. */
    NO_MATCH,

    /** Something matched, but too little of the query for the answer to be trustworthy. */
    BELOW_RELEVANCE_FLOOR,

    /** The filter excluded every passage — a module or step with no authored content. */
    NO_PASSAGES_IN_SCOPE,
}

/**
 * Grounding for one generation, or an explicit refusal to supply any.
 *
 * [Insufficient] is not an error path. It is the single most important behaviour in this package:
 * no grounding means no generation, and the worker is told the site's safety documents do not cover
 * their question and to ask a supervisor. That is a useful answer. An invented one is not.
 */
sealed interface RetrievalResult {

    class Grounded(
        val passages: List<ScoredPassage>,
        val queryTermCount: Int,
    ) : RetrievalResult {
        init {
            require(passages.isNotEmpty()) { "Grounded must carry at least one passage" }
        }

        val bestRatio: Double get() = passages.maxOf { it.matchedTermRatio }

        val citations: List<String> get() = passages.map { it.passage.sourceLabel }.distinct()

        val passageIds: List<String> get() = passages.map { it.passage.passageId }
    }

    class Insufficient(
        val reason: InsufficientReason,
        val bestRatio: Double = 0.0,
    ) : RetrievalResult
}

class RetrievalConfig(
    /**
     * How many passages a prompt may carry.
     *
     * Four, against a 4096-token window: the instruction block, four passages at up to 1,200 chars
     * and the answer all have to fit, and Devanagari costs two to four times more tokens per
     * character than Latin in a BPE vocabulary. [PromptBuilder] enforces the byte budget on top of
     * this, so exceeding the window drops the lowest-ranked passage rather than truncating text
     * mid-sentence.
     */
    val maxPassages: Int = 4,
    /**
     * The relevance floor: a third of the query's distinct terms.
     *
     * Set where it is because a one-in-three overlap is where a passage stops being about the same
     * subject as the question. Below it, a small model given the passage anyway will still write a
     * fluent paragraph — that is the failure this number exists to prevent.
     */
    val minMatchedTermRatio: Double = 0.34,
) {
    init {
        require(maxPassages > 0) { "maxPassages must be positive, got $maxPassages" }
        require(minMatchedTermRatio in 0.0..1.0) {
            "minMatchedTermRatio must be 0.0..1.0, got $minMatchedTermRatio"
        }
    }
}

/**
 * Turns a question into grounding, or refuses.
 *
 * Pure and synchronous. It holds one prebuilt index per language and does no I/O, so the whole
 * retrieval path is exercised on a plain JVM in microseconds.
 */
class Retriever(
    private val corpus: Corpus,
    private val config: RetrievalConfig = RetrievalConfig(),
    bm25: Bm25Config = Bm25Config(),
) {
    private val indexes: Map<AiLanguage, Bm25Index> =
        corpus.languages().associateWith { language ->
            Bm25Index(corpus.forLanguage(language), bm25)
        }

    fun retrieve(query: String, filter: PassageFilter): RetrievalResult {
        val queryTerms = AiTokenizer.tokenSet(query)
        if (queryTerms.isEmpty()) {
            return RetrievalResult.Insufficient(InsufficientReason.NO_QUERY_TERMS)
        }
        val index = indexes[filter.language]
            ?: return RetrievalResult.Insufficient(InsufficientReason.NO_PASSAGES_IN_SCOPE)

        val inScope = corpus.forLanguage(filter.language).count { filter.allows(it) }
        if (inScope == 0) {
            return RetrievalResult.Insufficient(InsufficientReason.NO_PASSAGES_IN_SCOPE)
        }

        val hits = index.search(query, config.maxPassages, filter::allows)
        if (hits.isEmpty()) {
            return RetrievalResult.Insufficient(InsufficientReason.NO_MATCH)
        }

        val best = hits.maxOf { it.matchedTermRatio }
        if (best < config.minMatchedTermRatio) {
            return RetrievalResult.Insufficient(InsufficientReason.BELOW_RELEVANCE_FLOOR, best)
        }

        // Keep only passages that clear the floor themselves. A weak passage riding along on a
        // strong one is context the model will still quote from.
        val kept = hits.filter { it.matchedTermRatio >= config.minMatchedTermRatio }
        return RetrievalResult.Grounded(kept, queryTerms.size)
    }

    /** Direct lookup for coaching, where the step is known and no free-text query exists. */
    fun passagesForStep(stepId: String, language: AiLanguage, limit: Int = 2): List<CorpusPassage> =
        corpus.forLanguage(language)
            .filter { stepId in it.stepIds }
            .sortedBy { it.passageId }
            .take(limit)

    /**
     * Grounding for any task, dispatched by what kind of task it is.
     *
     * The relevance floor is strict for exactly one case and deliberately not for the others, because
     * the four tasks are not the same shape of problem:
     *
     *  * [AiTask.SafetyQuestion] is a question. If the corpus does not cover it, refusing is the
     *    correct and useful answer — "the site's documents do not cover this, ask your supervisor" —
     *    and generating anyway is precisely the failure this whole design exists to prevent.
     *  * [AiTask.StepCoaching] names a step, so the authored passage for that step is selected by
     *    identity. Whether it is on topic is settled before retrieval starts.
     *  * [AiTask.ShiftBriefing] and [AiTask.HazardSummary] are not questions at all. They restate
     *    structured data the app already holds — counts the supervisor's own dashboard computed, a
     *    note a worker typed. Grounding supplies vocabulary and a relevant rule to reference, not the
     *    facts. Refusing to summarise a hazard report because the corpus has no passage about that
     *    particular cable would be a worse outcome than summarising it against general practice.
     *
     * So the last two fall back to the cross-cutting [PassageScope.GENERAL] passages rather than
     * refusing. Their numeric claims are still checked by [AnswerGuard] against the whole prompt, so
     * the fallback widens what the model may reference and does not loosen what it may assert.
     */
    fun retrieveForTask(task: AiTask): RetrievalResult = when (task) {
        is AiTask.StepCoaching -> retrieveForCoaching(task)
        is AiTask.SafetyQuestion -> retrieve(task.retrievalQuery(), task.passageFilter())
        is AiTask.ShiftBriefing, is AiTask.HazardSummary ->
            retrieveWithGeneralFallback(task.retrievalQuery(), task.passageFilter())
    }

    /**
     * Term matching first, cross-cutting practice as a floor.
     *
     * Never returns [RetrievalResult.Insufficient] while the corpus holds any general passage in the
     * requested language, which is the point: these tasks always have something legitimate to say.
     */
    private fun retrieveWithGeneralFallback(
        query: String,
        filter: PassageFilter,
    ): RetrievalResult {
        val matched = retrieve(query, filter)
        if (matched is RetrievalResult.Grounded) return matched

        val queryTerms = AiTokenizer.tokenSet(query)
        val general = corpus.forLanguage(filter.language)
            .filter { it.scope == PassageScope.GENERAL }
            .sortedBy { it.passageId }
            .take(config.maxPassages)
        if (general.isEmpty()) {
            return matched
        }
        return RetrievalResult.Grounded(
            general.map { passage ->
                ScoredPassage(
                    passage = passage,
                    score = GENERAL_FALLBACK_SCORE,
                    matchedTerms = queryTerms,
                    queryTermCount = queryTerms.size,
                )
            },
            queryTerms.size.coerceAtLeast(1),
        )
    }

    /**
     * Grounding for coaching one step.
     *
     * A passage authored *for this step* is selected by identity, which is a stronger signal than
     * any term overlap, so it is placed first and never dropped by the relevance floor. Remaining
     * slots are filled by ranking the step's own wording against the rest of the module — usually
     * the statutory rule behind it, which is what turns "that was wrong" into "that was wrong, and
     * here is the rule".
     *
     * Term-matched passages still have to clear the floor. Only the identity-matched ones bypass it,
     * because for those the question of whether they are on-topic is already settled.
     */
    fun retrieveForCoaching(task: AiTask.StepCoaching): RetrievalResult {
        val direct = passagesForStep(task.stepId, task.language)
        val queryTerms = AiTokenizer.tokenSet(task.retrievalQuery())

        val anchored = direct.map { passage ->
            ScoredPassage(
                passage = passage,
                score = IDENTITY_MATCH_SCORE,
                matchedTerms = queryTerms,
                queryTermCount = queryTerms.size,
            )
        }

        val remaining = config.maxPassages - anchored.size
        val supporting = if (remaining <= 0) {
            emptyList()
        } else {
            val index = indexes[task.language]
            val taken = direct.map { it.passageId }.toSet()
            index?.search(task.retrievalQuery(), config.maxPassages) { candidate ->
                candidate.passageId !in taken && task.passageFilter().allows(candidate)
            }
                ?.filter { it.matchedTermRatio >= config.minMatchedTermRatio }
                ?.take(remaining)
                .orEmpty()
        }

        val all = anchored + supporting
        if (all.isEmpty()) {
            return RetrievalResult.Insufficient(
                if (queryTerms.isEmpty()) InsufficientReason.NO_QUERY_TERMS
                else InsufficientReason.NO_MATCH,
            )
        }
        return RetrievalResult.Grounded(all, queryTerms.size.coerceAtLeast(1))
    }

    val corpusSize: Int get() = corpus.size()

    private companion object {
        /**
         * Score given to a passage selected by step identity rather than term overlap.
         *
         * Above any achievable BM25 score for this corpus, so an authored explanation always leads
         * the sources handed to the model.
         */
        const val IDENTITY_MATCH_SCORE = 1_000.0

        /**
         * Score for a passage supplied as the general fallback.
         *
         * Below [IDENTITY_MATCH_SCORE] and below any real BM25 hit, because it was not matched to the
         * query — it is there so a briefing or a hazard summary has something to reference rather than
         * nothing.
         */
        const val GENERAL_FALLBACK_SCORE = 0.001
    }
}
