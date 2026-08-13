plugins {
    application
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation(project(":application"))
    implementation(project(":persistence-postgres"))
    implementation(libs.jackson.core)
    implementation(libs.postgresql)
}

application {
    mainClass = "com.fallingnight.chat.performance.PostgresMessagingBaseline"
}
