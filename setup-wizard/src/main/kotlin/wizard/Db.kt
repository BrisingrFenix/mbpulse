package wizard

import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

/**
 * Runs the provisioning SQL and the study seed directly against the study's
 * Postgres (the Supabase "Session pooler" connection string, or the local
 * `supabase start` instance).
 */
object Db {

    data class FileResult(val file: String, val ok: Boolean, val error: String? = null)

    /** Accepts `postgres(ql)://user:pass@host:port/db` or a raw JDBC URL. */
    private fun connect(dbUrl: String): Connection {
        val props = Properties()
        val jdbc = if (dbUrl.startsWith("jdbc:")) dbUrl else {
            val uri = URI(dbUrl.replaceFirst("postgres://", "postgresql://"))
            require(uri.scheme == "postgresql") { "Expected a postgresql:// connection string" }
            uri.userInfo?.split(":", limit = 2)?.let {
                props["user"] = URLDecoder.decode(it[0], "UTF-8")
                if (it.size > 1) props["password"] = URLDecoder.decode(it[1], "UTF-8")
            }
            val port = if (uri.port == -1) 5432 else uri.port
            val db = uri.path.trimStart('/').ifBlank { "postgres" }
            // Hosted Supabase requires TLS; local instances don't speak it.
            if (uri.host !in setOf("localhost", "127.0.0.1")) props["sslmode"] = "require"
            uri.query?.split("&")?.forEach { p ->
                val (k, v) = p.split("=", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                props[k] = URLDecoder.decode(v, "UTF-8")
            }
            "jdbc:postgresql://${uri.host}:$port/$db"
        }
        return DriverManager.getConnection(jdbc, props)
    }

    fun testConnection(dbUrl: String): String = connect(dbUrl).use { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("select version()").use { rs -> rs.next(); rs.getString(1) }
        }
    }

    /**
     * Executes each SQL file in order (multi-statement, as the SQL editor
     * would). Stops at the first failure — later files depend on earlier ones —
     * and reports the rest as skipped.
     */
    fun provision(dbUrl: String, files: List<Path>): List<FileResult> {
        val results = mutableListOf<FileResult>()
        connect(dbUrl).use { conn ->
            var failed = false
            for (file in files) {
                val name = file.fileName.toString()
                if (failed) { results += FileResult(name, false, "skipped (earlier file failed)"); continue }
                if (!Files.exists(file)) { results += FileResult(name, false, "file not found: $file"); failed = true; continue }
                // One statement at a time, so a failure names its line in the file.
                var current: SqlStatement? = null
                try {
                    conn.createStatement().use { st ->
                        for (s in splitSql(Files.readString(file))) { current = s; st.execute(s.sql) }
                    }
                    results += FileResult(name, true)
                } catch (e: Exception) {
                    results += FileResult(name, false, describeFailure(e, current))
                    failed = true
                }
            }
        }
        return results
    }

    /** One SQL statement and the 1-based file line its first token is on. */
    data class SqlStatement(val sql: String, val line: Int)

    /**
     * Splits a SQL file into statements on top-level `;`, skipping string and
     * identifier literals, `--` / block comments and dollar-quoted bodies
     * (`$$`, `$do$`, …). Leading comments are dropped so [SqlStatement.line]
     * points at the statement itself.
     */
    fun splitSql(text: String): List<SqlStatement> {
        val out = mutableListOf<SqlStatement>()
        val buf = StringBuilder()
        var startLine = -1          // line of the first token in buf
        var line = 1
        var i = 0
        fun flush() {
            val sql = buf.toString().trim()
            if (sql.isNotEmpty()) out += SqlStatement(sql, startLine)
            buf.clear(); startLine = -1
        }
        fun take(n: Int) {          // copy n chars into the statement
            val chunk = text.substring(i, i + n)
            if (startLine == -1 && chunk.isNotBlank()) startLine = line + chunk.takeWhile { it.isWhitespace() }.count { it == '\n' }
            buf.append(chunk); line += chunk.count { it == '\n' }; i += n
        }
        fun skip(n: Int) { line += text.substring(i, i + n).count { it == '\n' }; i += n }
        val dollar = Regex("""\$([A-Za-z_][A-Za-z0-9_]*)?\$""")
        while (i < text.length) {
            val c = text[i]
            when {
                text.startsWith("--", i) -> {
                    val end = text.indexOf('\n', i).let { if (it == -1) text.length else it }
                    if (startLine == -1) skip(end - i) else take(end - i)
                }
                text.startsWith("/*", i) -> {
                    val end = text.indexOf("*/", i + 2).let { if (it == -1) text.length else it + 2 }
                    if (startLine == -1) skip(end - i) else take(end - i)
                }
                c == '\'' || c == '"' -> {
                    var j = i + 1
                    while (j < text.length) {
                        if (text[j] == c) { if (j + 1 < text.length && text[j + 1] == c) j += 2 else break } else j++
                    }
                    take(minOf(j + 1, text.length) - i)
                }
                c == '$' && dollar.matchAt(text, i) != null -> {
                    val tag = dollar.matchAt(text, i)!!.value
                    val end = text.indexOf(tag, i + tag.length).let { if (it == -1) text.length else it + tag.length }
                    take(end - i)
                }
                c == ';' -> { take(1); flush() }
                else -> take(1)
            }
        }
        flush()
        return out
    }

    /** Error message plus the file line (and text) where it happened. */
    private fun describeFailure(e: Exception, stmt: SqlStatement?): String {
        val msg = e.message ?: e.toString()
        if (stmt == null) return msg
        // Server position = 1-based offset into the statement as sent.
        val pos = (e as? org.postgresql.util.PSQLException)?.serverErrorMessage?.position ?: 0
        val errLine = if (pos in 1..stmt.sql.length)
            stmt.line + stmt.sql.substring(0, pos - 1).count { it == '\n' } else stmt.line
        val lineText = stmt.sql.lines().getOrNull(errLine - stmt.line)?.trim().orEmpty()
        return "$msg\n  at line $errLine: $lineText\n  in the statement starting at line ${stmt.line}: " +
            stmt.sql.lineSequence().first().trim()
    }

    private val EXPECTED_TABLES = listOf(
        "StudyConfig", "participants", "consent_records", "encrypted_records",
        "withdrawal_challenges", "deletion_receipts", "join_code_attempts", "retention_purges",
        "participant_rotation_keys", "key_rotation_challenges", "study_staff", "export_sessions",
        "questionnaires", "study_definitions", "join_tickets", "backend_components",
        "session_challenges"
    )
    private val EXPECTED_FUNCTIONS = listOf(
        "seed_study", "withdraw_participant", "request_withdrawal_challenge", "purge_expired_records",
        "verify_join_code", "get_active_studies", "is_username_available", "participant_login_id",
        "get_my_recovery_material", "rotate_participant_keys", "get_my_encrypted_records",
        "submit_consent_record", "get_my_consent_records", "consent_records_immutable",
        "enroll_participant", "request_key_rotation_challenge", "hmac_sha256",
        "export_study_records", "participant_username_token", "purge_orphan_accounts",
        "my_pseudo_id", "owns_participant", "belongs_to_study",
        "publish_questionnaire", "set_questionnaire_active", "get_study_questionnaires",
        "record_study_definition", "study_definitions_immutable",
        "issue_join_ticket", "consume_join_ticket", "study_open_for_enrolment", "study_has_ended",
        "record_before_study_end", "set_study_enrolment", "end_study",
        "backend_version", "stamp_backend_component",
        "current_session_id", "session_acts_for", "owns_participant_account", "my_session_status",
        "request_session_challenge", "bind_session", "register_device_key"
    )

    /**
     * The backend API version every SQL file must be stamped with
     * (backend/00_schema.sql; the library's BackendCompatibility.CLIENT_API).
     * Bump both with the SQL stamps.
     */
    const val BACKEND_API = 4

    /**
     * Checks that an existing backend has everything the app expects (tables +
     * functions from the five SQL files). Returns what is missing — empty means
     * fully provisioned, no setup run needed.
     */
    fun verify(dbUrl: String): List<String> = connect(dbUrl).use { conn ->
        val missing = mutableListOf<String>()
        conn.prepareStatement("select to_regclass('public.' || quote_ident(?)) is not null").use { st ->
            for (t in EXPECTED_TABLES) {
                st.setString(1, t)
                st.executeQuery().use { rs -> rs.next(); if (!rs.getBoolean(1)) missing += "table $t" }
            }
        }
        conn.prepareStatement(
            "select exists (select 1 from pg_proc p join pg_namespace n on n.oid = p.pronamespace " +
                "where n.nspname = 'public' and p.proname = ?)"
        ).use { st ->
            for (f in EXPECTED_FUNCTIONS) {
                st.setString(1, f)
                st.executeQuery().use { rs -> rs.next(); if (!rs.getBoolean(1)) missing += "function $f()" }
            }
        }
        // Files re-run at an older version (or never) — "run Database setup again".
        if ("function backend_version()" !in missing && "table backend_components" !in missing) {
            conn.prepareStatement(
                "select component, api_version from public.backend_components where api_version < ?"
            ).use { st ->
                st.setInt(1, BACKEND_API)
                st.executeQuery().use { rs ->
                    while (rs.next()) missing += "${rs.getString(1)}.sql at API ${rs.getInt(2)} (needs $BACKEND_API)"
                }
            }
            conn.createStatement().use { st ->
                st.executeQuery(
                    "select coalesce(string_agg(m, ','), '') from jsonb_array_elements_text(public.backend_version() -> 'missing') m"
                ).use { rs ->
                    rs.next()
                    rs.getString(1).split(',').filter { it.isNotBlank() }.forEach { missing += "$it.sql not applied" }
                }
            }
        }
        missing
    }

    /** Calls the validating `seed_study()` loader; returns its JSON summary. */
    /**
     * Links an existing Supabase Auth user (the PI's staff account) to a study
     * in study_staff — only staff can export the study's data. Fails with a
     * clear message if no such user exists yet.
     */
    fun linkStaff(dbUrl: String, studyId: String, email: String): String =
        connect(dbUrl).use { conn ->
            conn.prepareStatement(
                "insert into public.study_staff (user_id, study_id, role) " +
                    "select id, ?::uuid, 'pi' from auth.users where lower(email) = lower(?) " +
                    "on conflict (user_id, study_id) do nothing"
            ).use { st ->
                st.setString(1, studyId)
                st.setString(2, email)
                st.executeUpdate()
            }
            val linked = conn.prepareStatement(
                "select exists (select 1 from public.study_staff s join auth.users u on u.id = s.user_id " +
                    "where s.study_id = ?::uuid and lower(u.email) = lower(?))"
            ).use { st ->
                st.setString(1, studyId); st.setString(2, email)
                st.executeQuery().use { rs -> rs.next(); rs.getBoolean(1) }
            }
            require(linked) {
                "No Supabase user with email $email — create it under Authentication → Users first, then seed again."
            }
            "PI staff account $email linked to the study."
        }

    /**
     * Every study on the backend as a JSON array (definition columns + the
     * signed text + participant count), newest first — for "update a study".
     */
    fun listStudies(dbUrl: String): String = connect(dbUrl).use { conn ->
        conn.createStatement().use { st ->
            st.executeQuery(
                """
                select coalesce(json_agg(s order by s."issuedAt" desc nulls last), '[]'::json)::text from (
                  select c."studyId"::text as "studyId", c."studyName", c."studyVersion", c."isActive", c."endedAt",
                         c."piPublicKey", c."dataTypes", c."schemaDataTypes", c."customSchemas",
                         c."signedDefinition", c."definitionSignature",
                         (c."signedDefinition"::jsonb ->> 'issuedAt') as "issuedAt",
                         (select count(*) from public.participants p where p.study_id = c."studyId") as "participants"
                    from public."StudyConfig" c
                ) s
                """.trimIndent()
            ).use { rs -> rs.next(); rs.getString(1) }
        }
    }

    /**
     * Handover: removes every staff link of [studyId] except [keepEmail]'s, so
     * the previous investigator can no longer export. Returns how many were removed.
     */
    fun removeOtherStaff(dbUrl: String, studyId: String, keepEmail: String): Int =
        connect(dbUrl).use { conn ->
            conn.prepareStatement(
                "delete from public.study_staff s where s.study_id = ?::uuid and not exists " +
                    "(select 1 from auth.users u where u.id = s.user_id and lower(u.email) = lower(?))"
            ).use { st -> st.setString(1, studyId); st.setString(2, keepEmail); st.executeUpdate() }
        }

    fun seed(dbUrl: String, studyJson: String, piPublicKeyPem: String): String =
        connect(dbUrl).use { conn ->
            conn.prepareStatement("select public.seed_study(?::jsonb, ?)").use { st ->
                st.setString(1, studyJson)
                st.setString(2, piPublicKeyPem)
                st.executeQuery().use { rs -> rs.next(); rs.getString(1) }
            }
        }

    /** Opens or closes enrolment (`set_study_enrolment`); existing participants are unaffected. */
    fun setStudyEnrolment(dbUrl: String, studyId: String, open: Boolean): String =
        connect(dbUrl).use { conn ->
            conn.prepareStatement("select public.set_study_enrolment(?::uuid, ?)::text").use { st ->
                st.setString(1, studyId); st.setBoolean(2, open)
                st.executeQuery().use { rs -> rs.next(); rs.getString(1) }
            }
        }

    /** Ends the study for good (`end_study`); returns its JSON summary. */
    fun endStudy(dbUrl: String, studyId: String): String =
        connect(dbUrl).use { conn ->
            conn.prepareStatement("select public.end_study(?::uuid)::text").use { st ->
                st.setString(1, studyId)
                st.executeQuery().use { rs -> rs.next(); rs.getString(1) }
            }
        }

    /** Every questionnaire of [studyId] (active or retired) as a JSON array. */
    fun listQuestionnaires(dbUrl: String, studyId: String): String = connect(dbUrl).use { conn ->
        conn.prepareStatement(
            """
            select coalesce(json_agg(json_build_object(
                     'questionnaireId', questionnaire_id, 'version', version, 'dataType', data_type,
                     'definition', definition, 'signature', signature, 'isActive', is_active,
                     'publishedAt', published_at) order by questionnaire_id), '[]'::json)::text
              from public.questionnaires where study_id = ?::uuid
            """.trimIndent()
        ).use { st -> st.setString(1, studyId); st.executeQuery().use { rs -> rs.next(); rs.getString(1) } }
    }

    /** Calls the validating `publish_questionnaire()`; returns its JSON summary. */
    fun publishQuestionnaire(dbUrl: String, definition: String, signature: String): String =
        connect(dbUrl).use { conn ->
            conn.prepareStatement("select public.publish_questionnaire(?, ?)::text").use { st ->
                st.setString(1, definition)
                st.setString(2, signature)
                st.executeQuery().use { rs -> rs.next(); rs.getString(1) }
            }
        }

    fun setQuestionnaireActive(dbUrl: String, studyId: String, questionnaireId: String, active: Boolean) {
        connect(dbUrl).use { conn ->
            conn.prepareStatement("select public.set_questionnaire_active(?::uuid, ?, ?)").use { st ->
                st.setString(1, studyId); st.setString(2, questionnaireId); st.setBoolean(3, active)
                st.executeQuery().close()
            }
        }
    }
}
