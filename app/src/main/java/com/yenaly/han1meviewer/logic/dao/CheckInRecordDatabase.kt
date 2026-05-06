package com.yenaly.han1meviewer.logic.dao

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yenaly.han1meviewer.logic.entity.CheckInRecordEntity

@Database(
    entities = [CheckInRecordEntity::class],
    version = 3,
    exportSchema = false
)
abstract class CheckInRecordDatabase : RoomDatabase() {
    abstract fun checkInDao(): CheckInRecordDao

    companion object {
        @Volatile
        private var INSTANCE: CheckInRecordDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE check_in_records_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        date TEXT NOT NULL,
                        type TEXT NOT NULL DEFAULT '自慰',
                        sideDishes TEXT NOT NULL DEFAULT '',
                        feeling TEXT NOT NULL DEFAULT ''
                    )
                    """.trimIndent()
                )
                val cursor = db.query("SELECT date, count FROM check_in_records")
                while (cursor.moveToNext()) {
                    val date = cursor.getString(0)
                    val count = cursor.getInt(1)
                    for (i in 0 until count.coerceAtMost(20)) {
                        db.execSQL(
                            "INSERT INTO check_in_records_new (date, type, sideDishes, feeling) VALUES (?, '自慰', '', '')",
                            arrayOf(date)
                        )
                    }
                }
                cursor.close()
                db.execSQL("DROP TABLE check_in_records")
                db.execSQL("ALTER TABLE check_in_records_new RENAME TO check_in_records")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE check_in_records ADD COLUMN time TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        fun getDatabase(context: Context): CheckInRecordDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    CheckInRecordDatabase::class.java,
                    "check_in_records"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigration(true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}