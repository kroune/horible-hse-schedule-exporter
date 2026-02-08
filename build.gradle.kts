plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
}

group = "io.github.kroune"
version = "0.0.1"

application {
    mainClass = "io.github.kroune.ApplicationKt"
}

repositories {
    mavenCentral()
}

dependencies {
    // Ktor client
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.auth)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // Kotlinx
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.serialization.json)

    // Google Calendar API
    implementation(libs.google.auth.oauth2)
    implementation(libs.google.api.calendar)

    // Configuration
    implementation(libs.typesafe.config)

    // Logging
    implementation(libs.logback.classic)

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}
