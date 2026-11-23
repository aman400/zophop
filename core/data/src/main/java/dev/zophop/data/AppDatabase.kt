package dev.zophop.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_4_5 =
    object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val cursor = db.query("PRAGMA table_info(`preconditions`)")
            val existingColumns = mutableSetOf<String>()
            cursor.use {
                val nameIndex = it.getColumnIndex("name")
                while (it.moveToNext()) {
                    if (nameIndex != -1) {
                        existingColumns.add(it.getString(nameIndex))
                    }
                }
            }
            if ("minBatteryPercent" !in existingColumns) {
                db.execSQL("ALTER TABLE `preconditions` ADD COLUMN `minBatteryPercent` INTEGER NOT NULL DEFAULT 15")
            }
            if ("requireCharging" !in existingColumns) {
                db.execSQL("ALTER TABLE `preconditions` ADD COLUMN `requireCharging` INTEGER NOT NULL DEFAULT 0")
            }
            if ("retryStrategy" !in existingColumns) {
                db.execSQL("ALTER TABLE `preconditions` ADD COLUMN `retryStrategy` TEXT NOT NULL DEFAULT 'FIXED'")
            }
            if ("queueTimeoutMinutes" !in existingColumns) {
                db.execSQL("ALTER TABLE `preconditions` ADD COLUMN `queueTimeoutMinutes` INTEGER NOT NULL DEFAULT 10")
            }
        }
    }

val ALL_MIGRATIONS = arrayOf(MIGRATION_4_5)

@Database(
    entities = [
        MessageEntity::class,
        WorkflowEntity::class,
        WorkflowExecutionLogEntity::class,
        CredentialEntity::class,
        PreconditionEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    abstract fun workflowDao(): WorkflowDao

    abstract fun credentialDao(): CredentialDao

    abstract fun preconditionDao(): PreconditionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                val instance =
                    Room.databaseBuilder(
                        context.applicationContext,
                        AppDatabase::class.java,
                        "zophop_db",
                    ).addMigrations(MIGRATION_4_5)
                        .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1, 2, 3)
                        .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                        .build()
                INSTANCE = instance
                instance
            }
    }
}
