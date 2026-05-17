package com.swordfish.lemuroid.metadata.libretrodb.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase

class LibretroDBManager(private val context: Context) {
    companion object {
        private const val DB_NAME = "libretro-db"
    }

    val dbInstance: LibretroDatabase by lazy {
        Room.databaseBuilder(context, LibretroDatabase::class.java, DB_NAME)
            .createFromAsset("libretro-db.sqlite")
            .fallbackToDestructiveMigration()
            // WAL mode allows concurrent readers without blocking each other,
            // which is important now that metadata lookups run in parallel.
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            // Increase page cache to reduce I/O for the large libretro DB during a full scan.
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    db.execSQL("PRAGMA cache_size = -8000") // ~8 MB page cache
                    db.execSQL("PRAGMA temp_store = MEMORY")
                }
            })
            .build()
    }
}
