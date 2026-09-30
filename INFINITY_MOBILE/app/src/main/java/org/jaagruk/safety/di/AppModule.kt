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
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Scope for work that must outlive any screen.
 *
 * A certificate being issued, or a queue entry being written, must not be cancelled because a
 * worker backgrounded the app mid-save. `SupervisorJob` so one failed repository call cannot tear
 * down the others.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * Everything that is a singleton, and why - since "everything is a singleton" is usually a smell.
 *
 *  * **The database** - Room requires one instance, and two would mean two write connections
 *    fighting over one file.
 *  * **[SiteKeyStore]** - holds the site's signing identity. A second instance could generate a
 *    second key for the same site, which forks the chain and makes every certificate issued after
 *    the fork unverifiable against the other branch.
 *  * **[DeviceProfile]** - a cheap cache of facts about the handset that would otherwise be
 *    re-read on every screen.
 *
 * Repositories are singletons for cheapness rather than correctness; they hold no mutable state
 * beyond their DAOs.
 *
 * ## Scope of this module
 *
 * Phase 4: the data layer, the keystore, the repositories and PIN sign-in. Deliberately **not** the
 * sync workers, the network client, Nearby, or the AR and input layers - those arrive with the
 * phases that use them, and pulling Retrofit, OkHttp, WorkManager and Play Services in now would
 * mean four dependency surfaces added to satisfy a gate that tests none of them.
 *
 * The two things the repositories genuinely need from `sync` - `SyncKind` and the upload DTOs - are
 * plain Kotlin and came across with them. A record is built complete at the moment it is created,
 * because a certificate minted underground has to sit in the queue fully formed until there is
 * signal; deferring payload construction to send time would mean it could fail where nobody can
 * see it.
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
     * Every date that ends up inside a signed certificate comes through here, which is what lets a
     * test pin issuance to a fixed instant and assert the exact bytes.
     */
    @Provides
    @Singleton
    fun wallClock(): WallClock = SystemWallClock

    /**
     * Monotonic clock, separate from the wall clock on purpose.
     *
     * Every latency measurement - decision time, drill timing, PIN lockout floors - uses this. On a
     * shared site phone whose clock is corrected mid-shift, a wall-clock delta can go negative, and
     * a negative decision latency would corrupt the one measurement this platform rests on.
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

    // ── Repositories ─────────────────────────────────────────────────────────

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
}
