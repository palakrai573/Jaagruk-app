package org.jaagruk.safety.data.library

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: LibraryEntry): Long

    @Query("DELETE FROM library_entries WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM library_entries ORDER BY createdAt DESC")
    fun getAll(): Flow<List<LibraryEntry>>

    @Query("SELECT * FROM library_entries WHERE type = :type ORDER BY createdAt DESC")
    fun getByType(type: EntryType): Flow<List<LibraryEntry>>

    @Query("""
        SELECT e.* FROM library_entries e
        INNER JOIN library_entries_fts fts ON e.id = fts.rowid
        WHERE library_entries_fts MATCH :query
        ORDER BY e.createdAt DESC
    """)
    fun search(query: String): Flow<List<LibraryEntry>>

    @Query("SELECT COUNT(*) FROM library_entries")
    suspend fun count(): Int
}
