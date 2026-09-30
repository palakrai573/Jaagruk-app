package org.jaagruk.safety.ai.prompts

import org.jaagruk.safety.model.ChatMessage
import org.jaagruk.core.ai.GemmaChatTemplate

/**
 * Builds a Gemma 3 prompt for the free-form chat screen.
 *
 * This used to emit ChatML for Qwen 2.5. Gemma 3's template is a different shape, and two
 * differences matter enough that `:core` owns the template and this file only calls it:
 *
 *  1. **There is no system role.** Gemma 3 instruction-tuned models have `user` and `model` turns
 *     only. A `system` turn produces a prompt shape the model never saw in training, and the
 *     symptom is bland, instruction-ignoring output rather than an error you could notice.
 *  2. **Never write `<bos>`.** llama.cpp tokenises with `add_special = true`, so BOS is added for
 *     us. Writing it here as well gives the model two, which degrades the opening tokens and is
 *     invisible without dumping token ids.
 *
 * `PromptBuilderTest` in `:core` pins both, so there is deliberately one template in this codebase
 * rather than a second copy here that could drift away from the one the tests cover.
 *
 * Note on scope: this is the *ungrounded* chat path inherited from the Infinity base. The grounded,
 * cited, retrieval-backed path that Jaagruk actually certifies against is `:core`'s `PromptBuilder`
 * plus `AnswerGuard`, wired up in phase 9. Until then this screen is a developer affordance, not a
 * worker-facing feature, and it is not allowed anywhere near a score or a certificate.
 */
object PromptFormatter {

    /**
     * Prepended to the first user turn, because Gemma has nowhere else to put it.
     *
     * ## Why this is worded the way it is
     *
     * The first version said "if you are not sure, say so and tell the worker to ask their
     * supervisor", and the result was that it said exactly that to almost everything. That is the
     * characteristic failure of a 1B instruction-tuned model: hand it a conditional escape hatch
     * and it takes the hatch, because deferring satisfies the instruction for every input while
     * answering only satisfies it for some. Gemma 3 1B is the weakest instruction-follower in its
     * family, which is the known cost of choosing it (docs/REVAMP_PLAN.md section 7).
     *
     * So the rules are written as positives, in priority order, with the escape hatch demoted to a
     * closing line and explicitly barred from being the whole reply:
     *
     *  * **Lead with the obligation to answer.** A small model weights the first instruction most.
     *  * **Name a length.** "Answer briefly" produced eleven-token replies. A sentence count gets
     *    something a worker can act on.
     *  * **State the language rule.** Otherwise a Hindi question gets an English answer.
     *  * **Condition the supervisor line and place it last.** It is useful advice when the answer
     *    genuinely depends on the site's own permit or machine, and useless as a reflex.
     *  * **Say "do not refuse" out loud.** Blunt, and it works on models this size.
     *
     * On figures: this path has no retrieval behind it, so the honest instruction is to give the
     * steps without inventing a threshold rather than to state one confidently. The real defence -
     * every number in the output having to appear in a retrieved source - is `AnswerGuard` in
     * phase 9. A prompt cannot enforce that, and pretending otherwise would be the wrong kind of
     * confidence.
     */
    private const val PREAMBLE =
        "You are Jaagruk, a safety assistant for workers in Jharkhand's mines, steel plants and " +
            "factories. You run entirely on this phone, with no internet.\n\n" +
            "ALWAYS answer the question. Say what the worker should do, step by step, in 4 to 6 " +
            "short sentences of plain language they can act on straight away. Reply in the same " +
            "language the worker used.\n\n" +
            "Add a final short line asking them to confirm with their supervisor ONLY when the " +
            "answer really depends on their own site, permit or machine. Never make that line the " +
            "whole answer, and never put it first.\n\n" +
            "If you are unsure of an exact figure or legal limit, give the practical steps without " +
            "the number and say which part you are unsure of. Do not refuse to answer."

    /**
     * How many past messages to carry.
     *
     * Eight is comfortable now the engine loads with a 4096-token context rather than the base
     * build's 2048, and the preamble plus eight turns still leaves most of the window for the
     * answer.
     */
    private const val HISTORY_LIMIT = 8

    /**
     * Builds the full prompt.
     *
     * Every piece of interpolated text is passed through [GemmaChatTemplate.sanitize] so a message
     * containing `<end_of_turn>` — typed, pasted from a captured screen, or read off a document by
     * OCR — cannot close the turn early and inject a turn of its own.
     */
    fun buildPrompt(history: List<ChatMessage>, newInput: String): String {
        val content = buildString {
            appendLine(PREAMBLE)
            appendLine()

            val recent = history.takeLast(HISTORY_LIMIT)
            if (recent.isNotEmpty()) {
                appendLine("### EARLIER IN THIS CONVERSATION")
                for (message in recent) {
                    val speaker = if (message.isUser) "Worker" else "You"
                    appendLine("$speaker: ${GemmaChatTemplate.sanitize(message.text)}")
                }
                appendLine()
            }

            appendLine("### THE WORKER'S QUESTION")
            append(GemmaChatTemplate.sanitize(newInput))
        }

        return GemmaChatTemplate.singleTurn(content)
    }
}
