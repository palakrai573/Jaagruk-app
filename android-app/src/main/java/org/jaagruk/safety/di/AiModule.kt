package org.jaagruk.safety.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.jaagruk.ai.AiCoach
import org.jaagruk.ai.LlamaLlmEngine
import org.jaagruk.ai.LlmEngine
import org.jaagruk.ai.LlmSessionGuard
import org.jaagruk.ai.ModelStore
import org.jaagruk.core.ai.SafetyCorpus
import org.jaagruk.safety.ai.CatalogResolver
import javax.inject.Singleton

/**
 * The on-device assistance graph.
 *
 * Separate from [AppModule] so the boundary is visible in the file list: nothing in here is on the
 * training, assessment, certification or sync path. Every binding could be removed and the app would
 * still train workers, score them, sign certificates and verify them offline. That is the property
 * worth being able to see at a glance.
 */
@Module
@InstallIn(SingletonComponent::class)
object AiModule {

    /**
     * Singleton because it is the interlock.
     *
     * A second instance would keep its own count, and the engine would be listening to one of them
     * while the drill flow incremented the other — which is precisely the state where the model stays
     * resident through an AR session.
     */
    @Provides
    @Singleton
    fun llmSessionGuard(): LlmSessionGuard = LlmSessionGuard()

    @Provides
    @Singleton
    fun modelStore(@ApplicationContext context: Context): ModelStore = ModelStore(context)

    /**
     * Singleton because the model is hundreds of megabytes of mapped weights.
     *
     * Two engines would mean two `llama_context` allocations against one native library whose model
     * pointer is process-global, and the second load would free the first out from under it.
     */
    @Provides
    @Singleton
    fun llmEngine(
        modelStore: ModelStore,
        sessionGuard: LlmSessionGuard,
    ): LlmEngine = LlamaLlmEngine(modelStore, sessionGuard)

    @Provides
    @Singleton
    fun catalogResolver(@ApplicationContext context: Context): CatalogResolver =
        CatalogResolver(context)

    /**
     * The one entry point every feature uses.
     *
     * Given [SafetyCorpus.retriever] explicitly rather than letting the default apply, so the fact that
     * grounding comes from the bundled corpus — and only from there — is stated in the graph rather
     * than hidden in a default argument.
     */
    @Provides
    @Singleton
    fun aiCoach(engine: LlmEngine): AiCoach = AiCoach(engine, SafetyCorpus.retriever)
}
