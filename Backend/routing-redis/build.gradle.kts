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
    implementation(libs.lettuce.core)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    listOf(
        "CHATROOM_TEST_REDIS_URI",
        "CHATROOM_TEST_REDIS_INVALID_URI",
        "CHATROOM_TEST_REDIS_UNTRUSTED_URI",
    ).forEach { name ->
        environment(name, providers.environmentVariable(name).orNull ?: "")
    }
    providers.environmentVariable("CHATROOM_TEST_REDIS_TRUST_STORE").orNull
        ?.takeIf(String::isNotBlank)
        ?.let { systemProperty("javax.net.ssl.trustStore", it) }
    providers.environmentVariable("CHATROOM_TEST_REDIS_TRUST_STORE_PASSWORD").orNull
        ?.takeIf(String::isNotBlank)
        ?.let { systemProperty("javax.net.ssl.trustStorePassword", it) }
}
