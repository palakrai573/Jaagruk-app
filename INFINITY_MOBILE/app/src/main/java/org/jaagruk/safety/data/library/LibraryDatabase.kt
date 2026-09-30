package org.jaagruk.safety.data.library

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

@TypeConverters(EntryTypeConverter::class)
@Database(
    entities  = [LibraryEntry::class, LibraryEntryFts::class],
    version   = 1,
    exportSchema = false
)
abstract class LibraryDatabase : RoomDatabase() {

    abstract fun libraryDao(): LibraryDao

    companion object {
        @Volatile private var INSTANCE: LibraryDatabase? = null

        fun getInstance(context: Context): LibraryDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LibraryDatabase::class.java,
                    "jaagruk_library.db"
                )
                .fallbackToDestructiveMigration()
                .build()
                .also { INSTANCE = it }
            }
    }
}

class EntryTypeConverter {
    @TypeConverter fun fromEntryType(type: EntryType): String = type.name
    @TypeConverter fun toEntryType(name: String): EntryType   = EntryType.valueOf(name)
}
