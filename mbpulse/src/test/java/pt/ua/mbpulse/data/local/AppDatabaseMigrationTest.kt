package pt.ua.mbpulse.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager

/**
 * The database migrations, on the JVM (plain SQLite — the migrations are plain
 * SQL; SQLCipher only encrypts the file). Each test starts from a frozen copy
 * of an old schema, runs the migrations, and checks
 *  - the data: unsynced encrypted records survive (they exist nowhere else),
 *  - the schema: identical to what Room expects for the current version
 *    (`schemas/…/<version>.json`, exported by the build).
 * Add a frozen schema + a test here with every new migration.
 */
class AppDatabaseMigrationTest {

    private lateinit var conn: Connection

    @Before fun open() { conn = DriverManager.getConnection("jdbc:sqlite::memory:") }
    @After fun close() = conn.close()

    // -- frozen old schemas (never edit: they are what is on participants' phones) --

    /** Version 4: no unique index on encrypted_records; history rows without kind/category/series. */
    private val v4 = listOf(
        "CREATE TABLE `encrypted_records` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`participantPseudoId` TEXT NOT NULL, `studyId` TEXT NOT NULL, `epochStartMs` INTEGER NOT NULL, " +
            "`schemaName` TEXT NOT NULL, `ciphertextHex` TEXT NOT NULL, `participantWrappedDekHex` TEXT NOT NULL, " +
            "`piWrappedDekHex` TEXT NOT NULL, `syncState` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
        "CREATE TABLE `display_epochs` (`schemaName` TEXT NOT NULL, `epochStartMs` INTEGER NOT NULL, " +
            "`displayValue` TEXT NOT NULL, `unit` TEXT NOT NULL, PRIMARY KEY(`schemaName`))",
        "CREATE TABLE `epoch_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`schemaName` TEXT NOT NULL, `epochStartMs` INTEGER NOT NULL, `numericValue` REAL NOT NULL, " +
            "`displayValue` TEXT NOT NULL, `unit` TEXT NOT NULL)",
        "CREATE INDEX `index_epoch_history_schemaName_epochStartMs` ON `epoch_history` (`schemaName`, `epochStartMs`)"
    )

    // -- tests -------------------------------------------------------------------

    @Test fun `every version from 4 has a migration to the current schema`() {
        val steps = AppDatabase.MIGRATIONS.map { it.startVersion to it.endVersion }.sortedBy { it.first }
        assertEquals((4 until currentVersion).map { it to it + 1 }, steps)
    }

    @Test fun `4 to current keeps unsynced records and matches the exported schema`() {
        exec(v4)
        // Same (participant, epoch, schema) three times: a SYNCED copy must win.
        insertRecord(1, "p", 1000, "omh:heart-rate", "PENDING")
        insertRecord(2, "p", 1000, "omh:heart-rate", "SYNCED")
        insertRecord(3, "p", 1000, "omh:heart-rate", "FAILED")
        // Two unsynced duplicates: the oldest (lowest id) stays.
        insertRecord(4, "p", 2000, "omh:heart-rate", "PENDING")
        insertRecord(5, "p", 2000, "omh:heart-rate", "PENDING")
        // A lone FAILED record is queued again; an unrelated one is untouched.
        insertRecord(6, "p", 3000, "mbpulse:hrv", "FAILED")
        insertRecord(7, "p", 3000, "omh:heart-rate", "PENDING")
        exec(listOf(
            "INSERT INTO epoch_history (schemaName, epochStartMs, numericValue, displayValue, unit) " +
                "VALUES ('omh:sleep-episode', 1000, 1.0, 'Sleeping', '')",
            "INSERT INTO epoch_history (schemaName, epochStartMs, numericValue, displayValue, unit) " +
                "VALUES ('omh:heart-rate', 1000, 64.0, '64', 'bpm')"
        ))

        migrate(from = 4)

        assertEquals(
            listOf(2L to "SYNCED", 4L to "PENDING", 6L to "PENDING", 7L to "PENDING"),
            rows("SELECT id, syncState FROM encrypted_records ORDER BY id") { it.getLong(1) to it.getString(2) }
        )
        assertEquals(
            listOf(Triple("omh:sleep-episode", "CATEGORY", "sleeping"), Triple("omh:heart-rate", null, null)),
            rows("SELECT schemaName, kind, category FROM epoch_history ORDER BY id") {
                Triple(it.getString(1), it.getString(2), it.getString(3))
            }
        )
        assertSchemaMatchesExported()
    }

    @Test fun `the unique index rejects a duplicate after migrating`() {
        exec(v4)
        migrate(from = 4)
        insertRecord(1, "p", 1000, "omh:heart-rate", "PENDING")
        val duplicate = runCatching { insertRecord(2, "p", 1000, "omh:heart-rate", "PENDING") }
        assertEquals(true, duplicate.isFailure)
    }

    // -- helpers -----------------------------------------------------------------

    private val exported: JsonObject by lazy {
        val dir = File("schemas/pt.ua.mbpulse.data.local.AppDatabase")
        val latest = dir.listFiles { f -> f.extension == "json" }!!.maxBy { it.nameWithoutExtension.toInt() }
        Json.parseToJsonElement(latest.readText()).jsonObject
    }
    private val currentVersion: Int get() = exported["database"]!!.jsonObject["version"]!!.jsonPrimitive.int

    private fun migrate(from: Int) {
        val db = jdbcBackedDatabase()
        AppDatabase.MIGRATIONS.filter { it.startVersion >= from }.sortedBy { it.startVersion }
            .forEach { m: Migration -> m.migrate(db) }
    }

    /** The migrations only call execSQL — forward that to JDBC, refuse anything else. */
    private fun jdbcBackedDatabase(): SupportSQLiteDatabase =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SupportSQLiteDatabase::class.java)) { _, method, args ->
            require(method.name == "execSQL" && args?.size == 1) { "migration used ${method.name} — extend the test fake" }
            conn.createStatement().use { it.execute(args[0] as String) }
            null
        } as SupportSQLiteDatabase

    /** Every table's columns and indices equal Room's exported schema for the current version. */
    private fun assertSchemaMatchesExported() {
        exported["database"]!!.jsonObject["entities"]!!.jsonArray.map { it.jsonObject }.forEach { entity ->
            val table = entity["tableName"]!!.jsonPrimitive.content
            val pk = entity["primaryKey"]!!.jsonObject["columnNames"]!!.jsonArray.map { it.jsonPrimitive.content }
            val expectedColumns = entity["fields"]!!.jsonArray.map { it.jsonObject }.map { f ->
                val name = f["columnName"]!!.jsonPrimitive.content
                // Room omits "notNull" for nullable columns.
                listOf(name, f["affinity"]!!.jsonPrimitive.content, f["notNull"]?.jsonPrimitive?.boolean ?: false, name in pk)
            }.sortedBy { it[0] as String }
            val actualColumns = rows("PRAGMA table_info(`$table`)") {
                listOf(it.getString("name"), it.getString("type"), it.getInt("notnull") == 1, it.getInt("pk") > 0)
            }.sortedBy { it[0] as String }
            assertEquals("columns of $table", expectedColumns, actualColumns)

            val expectedIndices = entity["indices"]?.jsonArray.orEmpty().map { it.jsonObject }.map { ix ->
                Triple(ix["name"]!!.jsonPrimitive.content, ix["unique"]!!.jsonPrimitive.boolean,
                    ix["columnNames"]!!.jsonArray.map { it.jsonPrimitive.content })
            }.sortedBy { it.first }
            val actualIndices = rows("PRAGMA index_list(`$table`)") { it.getString("name") to (it.getInt("unique") == 1) }
                .filterNot { it.first.startsWith("sqlite_autoindex") }
                .map { (name, unique) ->
                    Triple(name, unique, rows("PRAGMA index_info(`$name`)") { it.getString("name") })
                }.sortedBy { it.first }
            assertEquals("indices of $table", expectedIndices, actualIndices)
        }
    }

    private fun exec(sql: List<String>) = conn.createStatement().use { st -> sql.forEach(st::execute) }

    private fun insertRecord(id: Long, participant: String, epochMs: Long, schema: String, state: String) =
        exec(listOf(
            "INSERT INTO encrypted_records (id, participantPseudoId, studyId, epochStartMs, schemaName, " +
                "ciphertextHex, participantWrappedDekHex, piWrappedDekHex, syncState, createdAt) " +
                "VALUES ($id, '$participant', 's', $epochMs, '$schema', 'c', 'w', 'k', '$state', 0)"
        ))

    private fun <T> rows(sql: String, map: (java.sql.ResultSet) -> T): List<T> =
        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(map(rs)) } }
        }
}
