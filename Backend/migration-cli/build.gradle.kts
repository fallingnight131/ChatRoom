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
    implementation(project(":profile-image-codec"))
    implementation(libs.postgresql)
    implementation(libs.sqlite.jdbc)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass = "com.fallingnight.chat.migration.IdentityMigrationMain"
}

tasks.test {
    environment("CHATROOM_TEST_POSTGRES_URL",
            providers.environmentVariable("CHATROOM_TEST_POSTGRES_URL").orNull ?: "")
    environment("CHATROOM_TEST_POSTGRES_USER",
            providers.environmentVariable("CHATROOM_TEST_POSTGRES_USER").orNull ?: "")
    environment("CHATROOM_TEST_POSTGRES_PASSWORD",
            providers.environmentVariable("CHATROOM_TEST_POSTGRES_PASSWORD").orNull ?: "")
}
