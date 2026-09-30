package org.jaagruk.safety.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The local database.
 *
 * Version 1. Schemas are exported to `android-app/schemas`, so a future migration is reviewable in
 * a diff rather than taken on trust.
 *
 * `fallbackToDestructiveMigration` is deliberately **not** enabled. A destructive migration on this
 * app would delete certificates that have not synced yet — training a worker has already
 * happened, and losing the proof is not an acceptable upgrade path. A missing migration must be a
 * loud crash in development, not silent data loss in a mine.
 */
@Database(
    entities = [
        WorkerEntity::class,
        SiteEntity::class,
        SiteAnchorEntity::class,
        ModuleEntity::class,
        TrainingProgressEntity::class,
        AssessmentRunEntity::class,
        CertificateEntity::class,
        ChainHeadEntity::class,
        HazardTagEntity::class,
        SyncQueueEntity::class,
        RefresherScheduleEntity::class,
        VoiceTemplateEntity::class,
        AppKeyValueEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class JaagrukDatabase : RoomDatabase() {

    abstract fun workerDao(): WorkerDao

    abstract fun siteDao(): SiteDao

    abstract fun siteAnchorDao(): SiteAnchorDao

    abstract fun moduleDao(): ModuleDao

    abstract fun trainingProgressDao(): TrainingProgressDao

    abstract fun assessmentRunDao(): AssessmentRunDao

    abstract fun certificateDao(): CertificateDao

    abstract fun chainHeadDao(): ChainHeadDao

    abstract fun chainQueryDao(): ChainQueryDao

    abstract fun hazardTagDao(): HazardTagDao

    abstract fun syncQueueDao(): SyncQueueDao

    abstract fun refresherScheduleDao(): RefresherScheduleDao

    abstract fun voiceTemplateDao(): VoiceTemplateDao

    abstract fun appKeyValueDao(): AppKeyValueDao

    companion object {
        const val NAME = "jaagruk.db"

        fun build(context: Context): JaagrukDatabase =
            Room.databaseBuilder(context, JaagrukDatabase::class.java, NAME)
                // Main-thread queries stay forbidden (Room's default). A blocking read on the UI
                // thread would stutter the AR frame loop, and the frame loop is where decision
                // latency is measured.
                .build()
    }
}
