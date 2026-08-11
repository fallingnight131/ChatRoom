plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation(project(":application"))
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.postgresql)
}

tasks.test {
    environment("CHATROOM_TEST_POSTGRES_URL",
            providers.environmentVariable("CHATROOM_TEST_POSTGRES_URL").orNull ?: "")
    environment("CHATROOM_TEST_POSTGRES_USER",
            providers.environmentVariable("CHATROOM_TEST_POSTGRES_USER").orNull ?: "")
    environment("CHATROOM_TEST_POSTGRES_PASSWORD",
            providers.environmentVariable("CHATROOM_TEST_POSTGRES_PASSWORD").orNull ?: "")
}
