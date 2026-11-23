package dev.zophop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class RoomMigrationTest {
    @Test
    fun testMigrationConstantsAndVersion() {
        assertNotNull(MIGRATION_1_2)
        assertNotNull(MIGRATION_2_3)
        assertNotNull(MIGRATION_3_4)

        assertEquals(1, MIGRATION_1_2.startVersion)
        assertEquals(2, MIGRATION_1_2.endVersion)

        assertEquals(2, MIGRATION_2_3.startVersion)
        assertEquals(3, MIGRATION_2_3.endVersion)

        assertEquals(3, MIGRATION_3_4.startVersion)
        assertEquals(4, MIGRATION_3_4.endVersion)
    }
}
