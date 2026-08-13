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
    implementation(project(":identity-crypto"))
    implementation(project(":persistence-postgres"))
    implementation(project(":protocol-v2"))
    implementation(libs.hikari)
    implementation(libs.netty.codec.http)
    implementation(libs.jackson.core)
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.slf4j.jdk14)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.postgresql)
    testImplementation(project(":routing-redis"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass = "com.fallingnight.chat.gateway.GatewayMain"
}

tasks.test {
    environment("CHATROOM_TEST_POSTGRES_URL",
            providers.environmentVariable("CHATROOM_TEST_POSTGRES_URL").orNull ?: "")
    environment("CHATROOM_TEST_POSTGRES_USER",
            providers.environmentVariable("CHATROOM_TEST_POSTGRES_USER").orNull ?: "")
    environment("CHATROOM_TEST_POSTGRES_PASSWORD",
            providers.environmentVariable("CHATROOM_TEST_POSTGRES_PASSWORD").orNull ?: "")
    environment("CHATROOM_TEST_REDIS_URI",
            providers.environmentVariable("CHATROOM_TEST_REDIS_URI").orNull ?: "")
}
