package org.jaagruk.safety.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.jaagruk.core.util.MonotonicTimeSource
import org.jaagruk.core.util.SystemMonotonicTimeSource
import org.jaagruk.core.util.SystemWallClock
import org.jaagruk.core.util.WallClock
import org.jaagruk.safety.ar.AnchorResolver
import org.jaagruk.safety.ar.ArControllerFactory
import org.jaagruk.safety.data.DeviceProfile
import org.jaagruk.safety.data.LocalMediaStore
import org.jaagruk.safety.data.auth.PinAuthenticator
import org.jaagruk.safety.data.db.JaagrukDatabase
import org.jaagruk.safety.data.keys.SiteKeyStore
import org.jaagruk.safety.data.repo.AssessmentRepository
import org.jaagruk.safety.data.repo.CertificateRepository
import org.jaagruk.safety.data.repo.HazardRepository
import org.jaagruk.safety.data.repo.RetentionRepository
import org.jaagruk.safety.data.repo.SiteRepository
import org.jaagruk.safety.data.repo.WorkerRepository
import org.jaagruk.safety.input.GestureRecognizerSource
import org.jaagruk.safety.input.NarrationPlayer
import org.jaagruk.safety.input.VoiceCommandEngine
import org.jaagruk.safety.input.VoiceNoteRecorder
import org.jaagruk.safety.input.VoiceTemplateRepository
import org.jaagruk.safety.sync.ConnectivityObserver
import org.jaagruk.safety.sync.SyncPayloadFactory
import org.jaagruk.safety.sync.SyncScheduler
import org.jaagruk.safety.sync.SyncStatusProvider
import org.jaagruk.safety.sync.TimeSyncTracker
import org.jaagruk.safety.sync.nearby.NearbyGossipService
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Scope for work that must outlive any screen.
 *
 * A certificate being issued or a queue entry being written must not be cancelled because a worker
 * backgrounded the app mid-save. `SupervisorJob` so one failed repository call cannot tear down the
 * others.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * Everything that is a singleton for a good reason.
 *
 * The reasons, since "everything is a singleton" is usually a smell:
 *
 *  * **The database** — Room requires it, and two instances would mean two write connections fighting
 *    over the same file.
 *  * **[SiteKeyStore]** — holds the site signing identity. A second instance could generate a second
 *    key for the same site, which would fork the chain.
 *  * **[SyncStatusProvider]** — the worker publishes into it and the UI reads from it.
 *  * **[TimeSyncTracker]**, **[ConnectivityObserver]**, **[DeviceProfile]** — cheap caches of facts
 *    about the device that would otherwise be re-read on every screen.
 *
 * Repositories are singletons for cheapness rather than correctness; they hold no mutable state beyond
 * their DAOs.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("jaagruk"))

    /**
     * Wall clock, injected rather than called statically.
     *
     * Every date that ends up inside a signed certificate comes through here, which is what lets a test
     * pin issuance to a fixed instant and assert the exact bytes.
     */
    @Provides
    @Singleton
    fun wallClock(): WallClock = SystemWallClock

    /**
     * Monotonic clock, separate from the wall clock on purpose.
     *
     * Every latency measurement — decision time, drill timing, PIN lockout floors — uses this. A shared
     * site phone whose clock is corrected mid-shift would otherwise produce a negative decision latency,
     * corrupting the one measurement this platform is built on.
     */
    @Provides
    @Singleton
    fun monotonicTimeSource(): MonotonicTimeSource = SystemMonotonicTimeSource

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): JaagrukDatabase =
        JaagrukDatabase.build(context)

    @Provides
    @Singleton
    fun siteKeyStore(@ApplicationContext context: Context): SiteKeyStore = SiteKeyStore(context)

    @Provides
    @Singleton
    fun deviceProfile(database: JaagrukDatabase): DeviceProfile = DeviceProfile(database)

    @Provides
    @Singleton
    fun mediaStore(@ApplicationContext context: Context): LocalMediaStore =
        LocalMediaStore(context)

    @Provides
    @Singleton
    fun pinAuthenticator(database: JaagrukDatabase): PinAuthenticator =
        PinAuthenticator(database.workerDao())

    // -----------------------------------------------------------------------
    // Repositories
    // -----------------------------------------------------------------------

    @Provides
    @Singleton
    fun siteRepository(database: JaagrukDatabase, clock: WallClock): SiteRepository =
        SiteRepository(database, clock)

    @Provides
    @Singleton
    fun workerRepository(
        database: JaagrukDatabase,
        pinAuthenticator: PinAuthenticator,
        clock: WallClock,
    ): WorkerRepository = WorkerRepository(database, pinAuthenticator, clock)

    @Provides
    @Singleton
    fun certificateRepository(
        database: JaagrukDatabase,
        keyStore: SiteKeyStore,
        clock: WallClock,
    ): CertificateRepository = CertificateRepository(database, keyStore, clock)

    @Provides
    @Singleton
    fun retentionRepository(database: JaagrukDatabase, clock: WallClock): RetentionRepository =
        RetentionRepository(database, clock)

    @Provides
    @Singleton
    fun assessmentRepository(
        database: JaagrukDatabase,
        certificates: CertificateRepository,
        retention: RetentionRepository,
        clock: WallClock,
        monotonic: MonotonicTimeSource,
    ): AssessmentRepository =
        AssessmentRepository(database, certificates, retention, clock, monotonic)

    @Provides
    @Singleton
    fun hazardRepository(
        database: JaagrukDatabase,
        media: LocalMediaStore,
        clock: WallClock,
    ): HazardRepository = HazardRepository(database, media, clock)

    // -----------------------------------------------------------------------
    // Sync
    // -----------------------------------------------------------------------

    @Provides
    @Singleton
    fun connectivityObserver(@ApplicationContext context: Context): ConnectivityObserver =
        ConnectivityObserver(context)

    @Provides
    @Singleton
    fun syncStatusProvider(database: JaagrukDatabase): SyncStatusProvider =
        SyncStatusProvider(database)

    @Provides
    @Singleton
    fun timeSyncTracker(database: JaagrukDatabase, clock: WallClock): TimeSyncTracker =
        TimeSyncTracker(database, clock)

    @Provides
    @Singleton
    fun syncScheduler(@ApplicationContext context: Context): SyncScheduler =
        SyncScheduler(context)

    @Provides
    @Singleton
    fun syncPayloadFactory(
        database: JaagrukDatabase,
        assessments: AssessmentRepository,
    ): SyncPayloadFactory = SyncPayloadFactory(database, assessments)

    @Provides
    @Singleton
    fun nearbyGossipService(
        @ApplicationContext context: Context,
        database: JaagrukDatabase,
        deviceProfile: DeviceProfile,
        payloads: SyncPayloadFactory,
        scheduler: SyncScheduler,
        @ApplicationScope scope: CoroutineScope,
    ): NearbyGossipService = NearbyGossipService(
        context = context,
        database = database,
        deviceProfile = deviceProfile,
        payloads = payloads,
        scheduler = scheduler,
        scope = scope,
    )

    // -----------------------------------------------------------------------
    // AR and input
    // -----------------------------------------------------------------------

    @Provides
    @Singleton
    fun anchorResolver(): AnchorResolver = AnchorResolver()

    @Provides
    @Singleton
    fun arControllerFactory(
        @ApplicationContext context: Context,
        siteRepository: SiteRepository,
        anchorResolver: AnchorResolver,
        @ApplicationScope scope: CoroutineScope,
    ): ArControllerFactory = ArControllerFactory(context, siteRepository, anchorResolver, scope)

    @Provides
    @Singleton
    fun voiceTemplateRepository(
        database: JaagrukDatabase,
        clock: WallClock,
    ): VoiceTemplateRepository = VoiceTemplateRepository(database, clock)

    @Provides
    @Singleton
    fun voiceCommandEngine(
        @ApplicationContext context: Context,
        templates: VoiceTemplateRepository,
        @ApplicationScope scope: CoroutineScope,
    ): VoiceCommandEngine = VoiceCommandEngine(context, templates, scope)

    /**
     * Not a singleton.
     *
     * MediaPipe holds native memory and a camera-frame pipeline; keeping one alive for the process
     * lifetime would hold that memory through every screen that has nothing to do with gestures.
     */
    @Provides
    fun gestureRecognizerSource(
        @ApplicationContext context: Context,
        monotonic: MonotonicTimeSource,
    ): GestureRecognizerSource = GestureRecognizerSource(context, monotonic)

    /**
     * Singleton because `TextToSpeech` is expensive to initialise — a second or more on a mid-range
     * handset — and a per-screen instance would make the first prompt on every screen silent.
     */
    @Provides
    @Singleton
    fun narrationPlayer(@ApplicationContext context: Context): NarrationPlayer =
        NarrationPlayer(context)

    /** Not a singleton: it holds an open `MediaRecorder` and belongs to one hazard report. */
    @Provides
    fun voiceNoteRecorder(
        @ApplicationContext context: Context,
        mediaStore: LocalMediaStore,
        monotonic: MonotonicTimeSource,
    ): VoiceNoteRecorder = VoiceNoteRecorder(context, mediaStore, monotonic)
}
