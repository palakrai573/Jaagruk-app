package org.jaagruk.safety.data.library

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey

enum class EntryType(val label: String) {
    PDF_SUMMARY   ("PDF Summary"),
    OCR           ("OCR Scan"),
    SCREENSHOT    ("Screenshot"),

    /**
     * Legacy. Nothing writes this any more — the quiz generator was removed in favour of the
     * assessment engine.
     *
     * The constant stays because Room persists this enum by name, and a handset that used the old
     * build has rows saying `QUIZ`. Deleting the constant would turn every one of those rows into an
     * exception on read, which is a crash in the library screen rather than a tidy-up. It goes when
     * phase 4 replaces this table with a migration that can rewrite the rows.
     */
    QUIZ          ("Quiz"),
    NOTE          ("Note")
}

@Entity(tableName = "library_entries")
data class LibraryEntry(
    @PrimaryKey(autoGenerate = true)
    val id         : Long   = 0,
    val type       : EntryType,
    val title      : String,
    val content    : String,
    val sourceInfo : String = "",   // original filename or hint
    val createdAt  : Long   = System.currentTimeMillis()
)

/**
 * FTS4 virtual table — mirrors title + content for full-text search.
 * Room keeps it in sync via triggers when library_entries is modified.
 * rowid in FTS table == id in library_entries.
 */
@Fts4(contentEntity = LibraryEntry::class)
@Entity(tableName = "library_entries_fts")
data class LibraryEntryFts(
    val title  : String,
    val content: String
)
