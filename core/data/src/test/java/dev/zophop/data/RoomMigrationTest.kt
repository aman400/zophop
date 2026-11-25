package dev.zophop.data

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

class RoomMigrationTest {
    @Test
    fun testMigrationConstantsAndVersion() {
        assertNotNull(MIGRATION_4_5)

        assertEquals(1, ALL_MIGRATIONS.size)

        assertEquals(4, MIGRATION_4_5.startVersion)
        assertEquals(5, MIGRATION_4_5.endVersion)
    }

    @Test
    fun testMigration4to5ExecutesAltersWhenColumnsMissing() {
        val executedSql = mutableListOf<String>()

        val fakeDbHandler =
            InvocationHandler { _, method, args ->
                when (method.name) {
                    "execSQL" -> {
                        executedSql.add(args[0] as String)
                        null
                    }
                    "query" -> {
                        Proxy.newProxyInstance(
                            android.database.Cursor::class.java.classLoader,
                            arrayOf(android.database.Cursor::class.java),
                        ) { _, cMethod, _ ->
                            when (cMethod.name) {
                                "getColumnIndex" -> 0
                                "moveToNext" -> false
                                "close" -> null
                                else -> null
                            }
                        }
                    }
                    else -> null
                }
            }

        val fakeDb =
            Proxy.newProxyInstance(
                SupportSQLiteDatabase::class.java.classLoader,
                arrayOf(SupportSQLiteDatabase::class.java),
                fakeDbHandler,
            ) as SupportSQLiteDatabase

        MIGRATION_4_5.migrate(fakeDb)

        assertEquals(8, executedSql.size)
        assertTrue(executedSql.any { it.contains("ALTER TABLE `preconditions` ADD COLUMN `minBatteryPercent`") })
        assertTrue(executedSql.any { it.contains("ALTER TABLE `preconditions` ADD COLUMN `requireCharging`") })
        assertTrue(executedSql.any { it.contains("ALTER TABLE `preconditions` ADD COLUMN `retryStrategy`") })
        assertTrue(executedSql.any { it.contains("ALTER TABLE `preconditions` ADD COLUMN `queueTimeoutMinutes`") })
        assertTrue(executedSql.any { it.contains("ALTER TABLE `preconditions` ADD COLUMN `icon`") })
        assertTrue(executedSql.any { it.contains("ALTER TABLE `workflows` ADD COLUMN `icon`") })
        assertTrue(executedSql.any { it.contains("ALTER TABLE `workflows` ADD COLUMN `createdAt`") })
        assertTrue(executedSql.any { it.contains("UPDATE `workflows` SET `createdAt` = `updatedAt`") })
    }

    @Test
    fun testMigration4to5SkipsAltersWhenColumnsAlreadyPresent() {
        val executedSql = mutableListOf<String>()
        val columns =
            listOf(
                "id",
                "name",
                "minBatteryPercent",
                "requireCharging",
                "retryStrategy",
                "queueTimeoutMinutes",
                "icon",
                "createdAt",
            )
        var cursorIndex: Int

        val fakeDbHandler =
            InvocationHandler { _, method, args ->
                when (method.name) {
                    "execSQL" -> {
                        executedSql.add(args[0] as String)
                        null
                    }
                    "query" -> {
                        cursorIndex = -1
                        Proxy.newProxyInstance(
                            android.database.Cursor::class.java.classLoader,
                            arrayOf(android.database.Cursor::class.java),
                        ) { _, cMethod, _ ->
                            when (cMethod.name) {
                                "getColumnIndex" -> 0
                                "moveToNext" -> {
                                    cursorIndex++
                                    cursorIndex < columns.size
                                }
                                "getString" -> columns[cursorIndex]
                                "close" -> null
                                else -> null
                            }
                        }
                    }
                    else -> null
                }
            }

        val fakeDb =
            Proxy.newProxyInstance(
                SupportSQLiteDatabase::class.java.classLoader,
                arrayOf(SupportSQLiteDatabase::class.java),
                fakeDbHandler,
            ) as SupportSQLiteDatabase

        MIGRATION_4_5.migrate(fakeDb)

        // All columns were already present, so 0 alters should be executed!
        assertEquals(0, executedSql.size)
    }
}
