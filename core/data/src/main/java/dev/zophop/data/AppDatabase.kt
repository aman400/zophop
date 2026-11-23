package dev.zophop.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 =
    object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `credentials` (
                    `id` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `type` TEXT NOT NULL,
                    `dataJson` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
        }
    }

val MIGRATION_2_3 =
    object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `preconditions` (
                    `id` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `type` TEXT NOT NULL,
                    `isEnabled` INTEGER NOT NULL DEFAULT 1,
                    `targetUrl` TEXT NOT NULL DEFAULT '',
                    `method` TEXT NOT NULL DEFAULT 'GET',
                    `headers` TEXT NOT NULL DEFAULT '',
                    `expectedStatus` INTEGER NOT NULL DEFAULT 200,
                    `requiredCarrier` TEXT NOT NULL DEFAULT '',
                    `requiredPermission` TEXT NOT NULL DEFAULT '',
                    `credentialId` TEXT NOT NULL DEFAULT '',
                    `pipelineJson` TEXT NOT NULL DEFAULT '',
                    `onFailureAction` TEXT NOT NULL DEFAULT 'FAIL_IMMEDIATELY',
                    `maxRetries` INTEGER NOT NULL DEFAULT 3,
                    `retryIntervalSeconds` INTEGER NOT NULL DEFAULT 30,
                    `showNotificationOnFailure` INTEGER NOT NULL DEFAULT 0,
                    `updatedAt` INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
        }
    }

val MIGRATION_3_4 =
    object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `workflow_execution_logs` (
                    `id` TEXT NOT NULL,
                    `workflowId` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `timestamp` INTEGER NOT NULL,
                    `messageId` TEXT,
                    `logs` TEXT NOT NULL DEFAULT '',
                    `errorReason` TEXT,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
        }
    }

@Database(
    entities = [MessageEntity::class, WorkflowEntity::class, WorkflowExecutionLogEntity::class, CredentialEntity::class, PreconditionEntity::class],
    version = 4,
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
                    Room
                        .databaseBuilder(
                            context.applicationContext,
                            AppDatabase::class.java,
                            "zophop_db",
                        ).fallbackToDestructiveMigration()
                        .build()
                INSTANCE = instance
                instance
            }
    }
}
