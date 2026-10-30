// Study-setup wizard — a local web app run with the tooling this repo already
// requires (JDK + Gradle), so researchers install nothing extra:
//
//   .\gradlew.bat :setup-wizard:run     ->  http://localhost:8765
//
plugins {
    alias(libs.plugins.jetbrains.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("wizard.MainKt")
}

dependencies {
    // SQL provisioning/seeding straight to the study's Postgres (Supabase).
    implementation("org.postgresql:postgresql:42.7.4")
    // JSON tree API only (no @Serializable classes, so no compiler plugin).
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
