package wizard

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util.concurrent.Executors

const val PORT = 8765

/** Short prompt handed to the agent — the payload lives in AGENT_BRIEF.md. */
const val AGENT_PROMPT =
    "Read AGENT_BRIEF.md in the repo root and build the study app it describes, in the app folder it names. " +
        "Follow the integration contract in AGENTS.md and the playbooks in docs/recipes/."

/**
 * Coding-agent CLIs the wizard can hand the brief to. Any other agent (IDE
 * agents, chat tools) works too: the wizard always shows the plain prompt.
 * Each opens interactively with [AGENT_PROMPT] as its first message.
 */
data class AgentCli(val id: String, val name: String, val exe: String, val promptFlag: String? = null) {
    fun args(prompt: String): List<String> = listOfNotNull(exe, promptFlag, prompt)
    fun command(prompt: String): String = listOfNotNull(exe, promptFlag, "\"$prompt\"").joinToString(" ")
}

val AGENT_CLIS = listOf(
    AgentCli("claude", "Claude Code", "claude"),
    AgentCli("codex", "OpenAI Codex CLI", "codex"),
    AgentCli("gemini", "Gemini CLI", "gemini", promptFlag = "-i")
)

/**
 * A schema the library ships, with what the wizard previews for it: the FHIR
 * terminology binding and an example OMH `body` (values illustrative, field
 * names and units exactly as the library serialises them).
 */
data class SchemaInfo(
    val id: String,
    val version: String,
    val name: String,
    val description: String,
    val loinc: String?,
    val ucum: String?,
    val category: String,
    val exampleBody: String,
    /** "event" = instant time frame (triggers; never sampled), else "measurement". */
    val role: String = "measurement",
    /** Enum fields → allowed values (mirror of the library's enum classes). */
    val enums: Map<String, List<String>> = emptyMap()
)

/**
 * The OMH schemas built into the library (mirror of `OmhSchemas.BUILT_IN` +
 * `OmhBody` in mbpulse — keep in sync). Study-specific schemas do not
 * belong here: they are custom schemas, declared by the app as `SchemaDefinition`s.
 */
val SCHEMA_REGISTRY = listOf(
    SchemaInfo("omh:heart-rate", "1.1", "Heart rate", "Mean heart rate per epoch (beats/min)",
        loinc = "8867-4", ucum = "/min", category = "vital-signs",
        exampleBody = """{"heart_rate": {"value": 64.2, "unit": "beats/min"}}"""),
    SchemaInfo("mbpulse:hrv", "1.0", "Heart-rate variability", "SDNN + RMSSD per epoch (ms)",
        loinc = null, ucum = "ms", category = "vital-signs",
        exampleBody = """{"sdnn": {"value": 48.7, "unit": "ms"}, "rmssd": {"value": 35.1, "unit": "ms"}}"""),
    SchemaInfo("omh:sleep-episode", "1.0", "Sleep stage", "Awake/sleeping classification + movement index",
        loinc = null, ucum = null, category = "activity",
        exampleBody = """{"sleep_stage": "sleeping", "movement_index": 0.02}"""),
    SchemaInfo("omh:body-temperature", "2.0", "Body temperature", "Mean skin temperature per epoch (°C)",
        loinc = "8310-5", ucum = "Cel", category = "vital-signs",
        exampleBody = """{"body_temperature": {"value": 33.4, "unit": "C"}, "measurement_location": "wrist"}"""),
    SchemaInfo("mbpulse:electrodermal-activity", "1.0", "Electrodermal activity",
        "Mean/SD/min/max skin conductance (μS)",
        loinc = null, ucum = "uS", category = "vital-signs",
        exampleBody = """{"mean_eda": {"value": 0.41, "unit": "μS"}, "sd_eda": {"value": 0.03, "unit": "μS"}, """ +
            """"min_eda": {"value": 0.36, "unit": "μS"}, "max_eda": {"value": 0.47, "unit": "μS"}}"""),
    SchemaInfo("mbpulse:stimulus-event", "1.0", "Stimulus trigger",
        "Marks the instant a stimulus was delivered (one point per trigger)",
        loinc = null, ucum = null, category = "procedure",
        exampleBody = """{"stimulus_type": "auditory", "stimulus_label": "trial-3", "stimulus_intensity": 2.0}""",
        role = "event",
        enums = mapOf("stimulus_type" to listOf("auditory", "visual", "tactile", "thermal", "cognitive")))
)

val SQL_ORDER = listOf(
    "00_schema.sql", "rls_policies.sql", "join_code.sql", "withdraw.sql", "seed_study.sql",
    "questionnaires.sql", "retention.sql"
)

/** The library release study apps pin: `def releaseVersion = "…"` in mbpulse/build.gradle. */
fun libraryVersion(): String =
    runCatching { Files.readString(repoRoot.resolve("mbpulse/build.gradle")) }.getOrNull()
        ?.let { Regex("""(?m)^\s*def\s+releaseVersion\s*=\s*"([^"]+)"""").find(it)?.groupValues?.get(1) }
        ?: "1.0.0"

lateinit var repoRoot: Path
val supabaseDir: Path get() = repoRoot.resolve("backend")
val keysDir: Path get() = supabaseDir.resolve("keys")
val publicDir: Path get() = repoRoot.resolve("setup-wizard/public")

fun main() {
    repoRoot = findRepoRoot()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", PORT), 0)
    server.executor = Executors.newFixedThreadPool(4)

    server.createContext("/api/state") { ex -> ex.handle { stateJson() } }
    server.createContext("/api/test-connection") { ex ->
        ex.handle { body ->
            val version = Db.testConnection(body.str("dbUrl"))
            buildJsonObject { put("ok", true); put("version", version) }
        }
    }
    server.createContext("/api/provision") { ex ->
        ex.handle { body ->
            val results = Db.provision(body.str("dbUrl"), SQL_ORDER.map { supabaseDir.resolve(it) })
            buildJsonObject {
                put("ok", results.all { it.ok })
                put("results", buildJsonArray {
                    results.forEach {
                        add(buildJsonObject {
                            put("file", it.file); put("ok", it.ok)
                            it.error?.let { e -> put("error", e) }
                        })
                    }
                })
            }
        }
    }
    server.createContext("/api/keys") { ex ->
        ex.handle {
            val r = Keys.generateOrLoad(keysDir)
            buildJsonObject {
                put("ok", true)
                put("created", r.created)
                put("keysDir", keysDir.toString())
                put("receiptPublicKeyB64", r.receiptPublicKeyB64)
                put("studySigningPublicKeyB64", r.studySigningPublicKeyB64)
                put("secretValue", r.receiptPrivatePkcs8B64)
                put("secretCommand",
                    "supabase secrets set DELETION_SIGNING_KEY_PKCS8_B64=${r.receiptPrivatePkcs8B64}")
            }
        }
    }
    server.createContext("/api/credentials") { ex ->
        ex.handle { body ->
            val path = writeCredentials(body.str("supabaseUrl"), body.str("anonKey"))
            buildJsonObject { put("ok", true); put("path", path.toString()) }
        }
    }
    server.createContext("/api/studies") { ex ->
        ex.handle { body -> buildJsonObject { put("ok", true); put("studies", StudyUpdate.list(body.str("dbUrl"))) } }
    }
    // Dry run of an update: every check, plus what the update will change.
    server.createContext("/api/check-update") { ex ->
        ex.handle { body ->
            val plan = StudyUpdate.check(
                body.str("dbUrl"), body.str("studyId"),
                body["study"]?.jsonObject ?: error("missing study"),
                body["oldPiPrivateKeyPem"]?.jsonPrimitive?.content
            )
            buildJsonObject {
                put("ok", true); put("handover", plan.handover)
                put("changes", buildJsonArray { plan.changes.forEach { add(it) } })
            }
        }
    }
    server.createContext("/api/seed") { ex ->
        ex.handle { body ->
            val dbUrl = body.str("dbUrl")
            val piPem = Keys.piPublicPem(keysDir)
                ?: error("PI key not found — generate the keys in step 2 first")
            Keys.ensureStudySigningKey(keysDir)
            var form = body["study"]?.jsonObject ?: error("missing study")

            // Update of an existing study: same studyId, checked first (signing
            // key, version bump, PI key or proof of the old one for a handover).
            val updateId = body["updateStudyId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            val plan = updateId?.let {
                StudyUpdate.check(dbUrl, it, form, body["oldPiPrivateKeyPem"]?.jsonPrimitive?.content)
            }
            if (updateId != null) form = JsonObject(form + ("studyId" to kotlinx.serialization.json.JsonPrimitive(updateId)))

            // Signed with the PI-held study-signing key; the app refuses
            // definitions whose signature does not verify against its pinned key.
            val study = StudySigning.sign(form, piPem, keysDir)
            Files.writeString(supabaseDir.resolve("study.json"), pretty(study))
            val result = Db.seed(dbUrl, study.toString(), piPem)
            val studyId = Json.parseToJsonElement(result).jsonObject["studyId"]!!.jsonPrimitive.content
            // PI staff account (gates export_study_records): an existing Auth user.
            val piEmail = body["piEmail"]?.jsonPrimitive?.content?.trim().orEmpty()
            val staff = if (piEmail.isEmpty()) null else Db.linkStaff(dbUrl, studyId, piEmail)
            // Handover: the previous investigator's staff link goes (on request).
            val removedStaff = if (plan?.handover == true && piEmail.isNotEmpty() &&
                body["removeOtherStaff"]?.jsonPrimitive?.content == "true"
            ) Db.removeOtherStaff(dbUrl, studyId, piEmail) else null
            buildJsonObject {
                put("ok", true); put("result", Json.parseToJsonElement(result))
                staff?.let { put("staff", it) }
                plan?.let { put("handover", it.handover) }
                removedStaff?.let { put("removedStaff", it) }
            }
        }
    }
    // Study lifecycle: close/reopen enrolment, end the study (irreversible).
    server.createContext("/api/study/enrolment") { ex ->
        ex.handle { body ->
            val open = body["open"]?.jsonPrimitive?.content == "true"
            val result = Db.setStudyEnrolment(body.str("dbUrl"), body.str("studyId"), open)
            buildJsonObject { put("ok", true); put("result", Json.parseToJsonElement(result)) }
        }
    }
    server.createContext("/api/study/end") { ex ->
        ex.handle { body ->
            val result = Db.endStudy(body.str("dbUrl"), body.str("studyId"))
            buildJsonObject { put("ok", true); put("result", Json.parseToJsonElement(result)) }
        }
    }
    // Backend questionnaires: list (with signature status), publish the next version, retire/restore.
    server.createContext("/api/questionnaires") { ex ->
        ex.handle { body ->
            val rows = Json.parseToJsonElement(Db.listQuestionnaires(body.str("dbUrl"), body.str("studyId"))).jsonArray
            buildJsonObject {
                put("ok", true)
                put("questionnaires", buildJsonArray {
                    rows.forEach { el ->
                        val row = el.jsonObject
                        val definition = row["definition"]?.jsonPrimitive?.content.orEmpty()
                        val signature = row["signature"]?.jsonPrimitive?.content.orEmpty()
                        add(JsonObject(row + mapOf(
                            "definition" to Json.parseToJsonElement(definition),
                            "signatureValid" to kotlinx.serialization.json.JsonPrimitive(
                                StudyUpdate.verifiesWithLocalKey(definition, signature))
                        )))
                    }
                })
            }
        }
    }
    server.createContext("/api/questionnaire/publish") { ex ->
        ex.handle { body ->
            val result = QuestionnaireSigning.publish(
                body.str("dbUrl"), body.str("studyId"),
                body["questionnaire"]?.jsonObject ?: error("missing questionnaire")
            )
            buildJsonObject { put("ok", true); put("result", result) }
        }
    }
    server.createContext("/api/questionnaire/active") { ex ->
        ex.handle { body ->
            val active = body["active"]?.jsonPrimitive?.content == "true"
            Db.setQuestionnaireActive(body.str("dbUrl"), body.str("studyId"), body.str("questionnaireId"), active)
            buildJsonObject { put("ok", true); put("active", active) }
        }
    }
    // Custom-schema import: search/fetch Open mHealth, or convert an uploaded JSON Schema.
    server.createContext("/api/omh/search") { ex ->
        ex.handle { body -> SchemaImport.search(body["q"]?.jsonPrimitive?.content ?: "") }
    }
    server.createContext("/api/omh/schema") { ex ->
        ex.handle { body -> SchemaImport.fromOmh(body.str("name"), body.str("version")) }
    }
    server.createContext("/api/schema/parse") { ex ->
        ex.handle { body -> SchemaImport.fromJson(body.str("json"), body["id"]?.jsonPrimitive?.content) }
    }
    server.createContext("/api/brief") { ex ->
        ex.handle { body ->
            val app = body["app"]?.jsonObject ?: error("missing app")
            val brief = Brief.build(
                study = body["study"]?.jsonObject ?: error("missing study"),
                app   = app,
                receiptPublicKeyB64 = Keys.receiptPublicB64(keysDir),
                studySigningPublicKeyB64 = Keys.studySigningPublicB64(keysDir),
                libraryVersion = libraryVersion()
            )
            // The app's credentials are written here, not by the agent.
            val appName = app["appName"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: "My Study App"
            val folder = Brief.appFolder(app["appFolder"]?.jsonPrimitive?.content.orEmpty(), Brief.moduleName(appName))
            val (localProps, missing) = writeAppLocalProperties(folder)
            // The app's specification for its HandoffConformanceTest (no secrets).
            // Hashed as the app's Gradle build reads them (java.util.Properties: unescaped).
            val creds = java.util.Properties().apply {
                if (Files.exists(localProps)) Files.newBufferedReader(localProps).use { load(it) }
            }.let { p -> p.stringPropertyNames().associateWith { p.getProperty(it) } }
            val handoff = Handoff.build(
                study = body["study"]!!.jsonObject, app = app,
                receiptPublicKeyB64 = Keys.receiptPublicB64(keysDir),
                studySigningPublicKeyB64 = Keys.studySigningPublicB64(keysDir),
                libraryVersion = libraryVersion(),
                supabaseUrl = creds["mbpulse.supabaseUrl"]?.takeIf { it.isNotBlank() },
                supabaseKey = creds["mbpulse.supabaseKey"]?.takeIf { it.isNotBlank() }
            )
            val handoffPath = localProps.resolveSibling("handoff.json")
            Files.writeString(handoffPath, pretty(handoff) + "\n")
            val path = repoRoot.resolve("AGENT_BRIEF.md")
            Files.writeString(path, brief)
            buildJsonObject {
                put("ok", true); put("path", path.toString()); put("prompt", AGENT_PROMPT)
                put("localProperties", localProps.toString())
                put("handoff", handoffPath.toString())
                put("missing", buildJsonArray { missing.forEach { add(it) } })
            }
        }
    }
    server.createContext("/api/verify-db") { ex ->
        ex.handle { body ->
            val missing = Db.verify(body.str("dbUrl"))
            buildJsonObject {
                put("ok", true)
                put("provisioned", missing.isEmpty())
                put("verdict",
                    if (missing.isEmpty())
                        "Backend fully provisioned — all tables and functions are in place. No setup needed."
                    else
                        "Missing: ${missing.joinToString(", ")} — run the database setup below (safe on existing data).")
            }
        }
    }
    server.createContext("/api/function-code") { ex ->
        ex.handle {
            val f = supabaseDir.resolve("functions/confirm-withdrawal/index.ts")
            require(Files.exists(f)) { "Function source not found at $f" }
            buildJsonObject { put("ok", true); put("code", Files.readString(f)) }
        }
    }
    server.createContext("/api/test-function") { ex ->
        ex.handle { body ->
            // Probe the deployed function with the anon key: it is a valid
            // project JWT, so the gateway (JWT verification ON) lets it through,
            // and our code must refuse it as "sign in required" — only a
            // participant's own session may withdraw.
            val base = body.str("supabaseUrl").trimEnd('/')
            val anonKey = body["anonKey"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: readLocalProperties()["mbpulse.supabaseKey"]
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build()
            val request = HttpRequest.newBuilder(URI("$base/functions/v1/confirm-withdrawal"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .apply {
                    anonKey?.let { header("Authorization", "Bearer $it"); header("apikey", it) }
                }
                .POST(HttpRequest.BodyPublishers.ofString(
                    """{"pseudoId":"00000000-0000-0000-0000-000000000000","signatureB64":"test"}"""))
                .build()

            val (configured, verdict) = try {
                val res = client.send(request, HttpResponse.BodyHandlers.ofString())
                val text = res.body().take(300)
                when {
                    res.statusCode() == 404 ->
                        false to "Not deployed yet — or the function isn't named exactly 'confirm-withdrawal' (steps 1–2)."
                    res.statusCode() == 401 && text.contains("sign in required") ->
                        true to "Deployed and reachable — it refuses callers without a participant session, exactly as designed."
                    res.statusCode() == 401 && text.contains("no challenge") ->
                        false to "An OLD version of the function is deployed — redeploy the current " +
                            "functions/confirm-withdrawal/index.ts (it now requires the participant's session)."
                    anonKey == null ->
                        false to "The gateway wants an API key and none is known yet — fill in the anon key in step 1 and retry."
                    res.statusCode() == 401 || res.statusCode() == 403 ->
                        false to "The gateway rejected the request before the function ran (${text.ifBlank { "no detail" }}) — " +
                            "check the anon key in step 1."
                    else ->
                        false to "Unexpected response ${res.statusCode()}: $text"
                }
            } catch (e: java.io.IOException) {
                false to "Could not reach $base — check the Project URL and your connection."
            }
            buildJsonObject { put("ok", true); put("configured", configured); put("verdict", verdict) }
        }
    }
    server.createContext("/api/launch-agent") { ex ->
        ex.handle { body ->
            val agent = AGENT_CLIS.firstOrNull { it.id == body.str("agent") } ?: error("unknown agent")
            if (!isOnPath(agent.exe)) error(
                "${agent.name} not found on PATH. Open a terminal in the repo root and run:  ${agent.command(AGENT_PROMPT)}"
            )
            ProcessBuilder(listOf("cmd", "/c", "start", agent.name, "cmd", "/k") + agent.args(AGENT_PROMPT))
                .directory(repoRoot.toFile()).start()
            buildJsonObject { put("ok", true); put("launched", true) }
        }
    }
    server.createContext("/") { ex -> ex.serveStatic() }

    server.start()
    val url = "http://localhost:$PORT"
    println("\n  Study setup wizard running at  $url   (Ctrl+C to stop)\n")
    runCatching { ProcessBuilder("cmd", "/c", "start", "", url).start() }
}

// ---------------------------------------------------------------------------

fun stateJson(): JsonObject {
    val props = readLocalProperties()
    return buildJsonObject {
        put("ok", true)
        put("repoRoot", repoRoot.toString())
        put("supabaseUrl", props["mbpulse.supabaseUrl"] ?: "")
        put("hasCredentials",
            !props["mbpulse.supabaseUrl"].isNullOrBlank() && !props["mbpulse.supabaseKey"].isNullOrBlank())
        put("keysExist", Files.exists(keysDir.resolve("pi_private.pem")))
        put("agentPrompt", AGENT_PROMPT)
        put("agents", buildJsonArray {
            AGENT_CLIS.forEach { a ->
                add(buildJsonObject {
                    put("id", a.id); put("name", a.name)
                    put("command", a.command(AGENT_PROMPT)); put("available", isOnPath(a.exe))
                })
            }
        })
        put("sqlFilesPresent", SQL_ORDER.all { Files.exists(supabaseDir.resolve(it)) })
        put("schemas", buildJsonArray {
            SCHEMA_REGISTRY.forEach { s ->
                add(buildJsonObject {
                    put("id", s.id); put("version", s.version)
                    put("name", s.name); put("description", s.description)
                    put("loinc", s.loinc); put("ucum", s.ucum); put("category", s.category)
                    put("exampleBody", Json.parseToJsonElement(s.exampleBody))
                    put("role", s.role)
                    put("enums", buildJsonObject {
                        s.enums.forEach { (field, values) -> put(field, buildJsonArray { values.forEach { add(it) } }) }
                    })
                })
            }
        })
    }
}

fun findRepoRoot(): Path {
    var dir: Path? = Paths.get("").toAbsolutePath()
    while (dir != null) {
        if (Files.exists(dir.resolve("settings.gradle.kts"))) return dir
        dir = dir.parent
    }
    error("Could not locate the repo root (settings.gradle.kts) from ${Paths.get("").toAbsolutePath()}")
}

fun readLocalProperties(): Map<String, String> {
    val f = repoRoot.resolve("local.properties")
    if (!Files.exists(f)) return emptyMap()
    return Files.readAllLines(f)
        .filter { it.contains('=') && !it.trimStart().startsWith("#") }
        .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
}

/**
 * Sets [values] in the `local.properties` file [f] (created if missing),
 * replacing those keys and preserving every other line. Values are written
 * as given — `sdk.dir` keeps the escaping it was read with.
 */
fun upsertProperties(f: Path, values: Map<String, String>) {
    val kept = if (Files.exists(f)) {
        Files.readAllLines(f).filterNot { line -> values.keys.any { line.trimStart().startsWith("$it=") || line.trimStart().startsWith("$it ") } }
    } else emptyList()
    Files.createDirectories(f.toAbsolutePath().parent)
    Files.write(f, kept + values.map { (k, v) -> "$k=$v" })
}

/**
 * The Android SDK folder as a `local.properties` value (escaped), for a
 * checkout never opened in Android Studio (which is what normally writes
 * `sdk.dir`): `ANDROID_HOME`, `ANDROID_SDK_ROOT`, then the installer's default
 * location — the first that exists. Null if none does.
 */
fun detectSdkDir(): String? {
    val home = System.getProperty("user.home")
    val os = System.getProperty("os.name").lowercase()
    val defaults = when {
        "win" in os -> listOfNotNull(System.getenv("LOCALAPPDATA")?.let { "$it\\Android\\Sdk" })
        "mac" in os -> listOf("$home/Library/Android/sdk")
        else -> listOf("$home/Android/Sdk")
    }
    val found = (listOfNotNull(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT")) + defaults)
        .map { Paths.get(it) }
        .firstOrNull { Files.isDirectory(it) } ?: return null
    return found.toAbsolutePath().toString().replace("\\", "\\\\").replace(":", "\\:")
}

/**
 * Adds `sdk.dir` to the repo's local.properties when it has none (detected by
 * [detectSdkDir]) — publishing the library from this checkout needs it too.
 */
fun ensureRepoSdkDir() {
    if (readLocalProperties()["sdk.dir"].isNullOrBlank()) {
        detectSdkDir()?.let { upsertProperties(repoRoot.resolve("local.properties"), mapOf("sdk.dir" to it)) }
    }
}

/** Upserts the two mbpulse credentials in the repo's local.properties, preserving everything else. */
fun writeCredentials(supabaseUrl: String, anonKey: String): Path {
    require(supabaseUrl.startsWith("http")) { "Supabase URL must start with http(s)://" }
    require(anonKey.isNotBlank()) { "anon key is blank" }
    val f = repoRoot.resolve("local.properties")
    upsertProperties(f, linkedMapOf("mbpulse.supabaseUrl" to supabaseUrl, "mbpulse.supabaseKey" to anonKey))
    ensureRepoSdkDir()
    return f
}

/**
 * The study app's own `local.properties` (its folder from the brief): the SDK
 * path and backend credentials from this repository's file. Written by the
 * wizard so the agent never handles the key; re-generating the brief
 * refreshes them. Returns the file and the keys it could not fill.
 */
fun writeAppLocalProperties(appFolder: String): Pair<Path, List<String>> {
    val dir = repoRoot.resolve(appFolder).normalize()
    require(dir != repoRoot.normalize() && !dir.startsWith(repoRoot.resolve("template-app")) &&
        !dir.startsWith(repoRoot.resolve("mbpulse")) && !dir.startsWith(repoRoot.resolve("sample-app"))) {
        "App folder $dir is part of this repository's own modules — choose a separate folder"
    }
    ensureRepoSdkDir()
    val repo = readLocalProperties()
    val keys = listOf("sdk.dir", "mbpulse.supabaseUrl", "mbpulse.supabaseKey")
    val values = keys.mapNotNull { k -> repo[k]?.takeIf { it.isNotBlank() }?.let { k to it } }.toMap(LinkedHashMap())
    val f = dir.resolve("local.properties")
    upsertProperties(f, values)
    return f to keys.filter { it !in values }
}

fun isOnPath(exe: String): Boolean = runCatching {
    ProcessBuilder("where", exe).start().waitFor() == 0
}.getOrDefault(false)

// ---------------------------------------------------------------------------
// HTTP plumbing
// ---------------------------------------------------------------------------

/** Wraps a handler: parses the JSON body (POST), catches errors into JSON 500s. */
fun HttpExchange.handle(block: (JsonObject) -> JsonObject) {
    val response = try {
        val body = if (requestMethod == "POST")
            Json.parseToJsonElement(requestBody.readBytes().decodeToString().ifBlank { "{}" }).jsonObject
        else buildJsonObject {}
        block(body) to 200
    } catch (e: Exception) {
        buildJsonObject { put("ok", false); put("error", e.message ?: e.toString()) } to 500
    }
    val bytes = response.first.toString().toByteArray()
    responseHeaders["Content-Type"] = "application/json; charset=utf-8"
    sendResponseHeaders(response.second, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}

fun HttpExchange.serveStatic() {
    val rel = requestURI.path.trimStart('/').ifBlank { "index.html" }
    val file = publicDir.resolve(rel).normalize()
    if (!file.startsWith(publicDir) || !Files.exists(file)) {
        sendResponseHeaders(404, -1); responseBody.close(); return
    }
    val type = when {
        rel.endsWith(".html") -> "text/html"
        rel.endsWith(".js")   -> "text/javascript"
        rel.endsWith(".css")  -> "text/css"
        rel.endsWith(".svg")  -> "image/svg+xml"
        rel.endsWith(".png")  -> "image/png"
        rel.endsWith(".jpg") || rel.endsWith(".jpeg") -> "image/jpeg"
        else                  -> "application/octet-stream"
    }
    val bytes = Files.readAllBytes(file)
    responseHeaders["Content-Type"] = "$type; charset=utf-8"
    sendResponseHeaders(200, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}

fun JsonObject.str(key: String): String =
    this[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: error("missing '$key'")

fun pretty(o: JsonObject): String = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), o)
