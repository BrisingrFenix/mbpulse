import java.util.Properties

// The whole app is this one project (settings.gradle.kts has no includes).
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Backend credentials live in the gitignored local.properties, never in source:
// this app's own (a study app's is written by the setup wizard). Inside the
// mbPulse repository, the repository's local.properties (where the
// wizard keeps the credentials) fills what this app's file does not set — so
// template-app/ runs in place without its own copy.
val localProps = Properties().apply {
    val repoProps = rootProject.file("../local.properties")
    if (rootProject.file("../mbpulse/build.gradle").exists() && repoProps.exists()) {
        repoProps.inputStream().use { load(it) }
    }
    val own = rootProject.file("local.properties")
    if (own.exists()) {
        // This app's values win — but a blank one does not hide the repository's.
        Properties().apply { own.inputStream().use { load(it) } }
            .forEach { (k, v) -> if (v.toString().isNotBlank()) setProperty(k.toString(), v.toString()) }
    }
}

android {
    // TODO(agent): set your study's package, e.g. "org.myuni.sleepstudy" —
    // change `namespace`, `applicationId`, AND the package of the sources in
    // src/main/java/ together.
    namespace = "com.example.mystudy"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.mystudy"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        buildConfigField(
            "String", "SUPABASE_URL",
            "\"${localProps.getProperty("mbpulse.supabaseUrl", "")}\""
        )
        buildConfigField(
            "String", "SUPABASE_KEY",
            "\"${localProps.getProperty("mbpulse.supabaseKey", "")}\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            // HandoffConformanceTest runs app code on the JVM; an android.util.Log
            // call there returns instead of throwing "not mocked".
            isReturnDefaultValues = true
            all { test ->
                // handoff.json from the setup wizard (default: this folder's own).
                // -Pmbpulse.handoff=<path> points at another one.
                test.systemProperty("mbpulse.handoff",
                    providers.gradleProperty("mbpulse.handoff").orNull
                        ?: rootProject.file("handoff.json").absolutePath)
                test.systemProperty("mbpulse.projectDir", rootProject.projectDir.absolutePath)
                // Re-run when the handoff changes, not only when code does.
                rootProject.file("handoff.json").takeIf { it.exists() }?.let { test.inputs.file(it) }
            }
        }
    }
}

dependencies {
    // The library does the heavy lifting; a host app needs surprisingly little.
    // Pinned in gradle/libs.versions.toml ("mbpulse").
    implementation(libs.mbpulse)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)   // icons in the example screens

    // Hilt — the library's ViewModels/Workers are injected through the host app.
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.androidx.hilt.navigation.compose)   // hiltViewModel() in overrides
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // HandoffConformanceTest: `.\gradlew.bat testDebugUnitTest`.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.serialization.json)
}
