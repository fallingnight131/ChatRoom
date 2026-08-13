plugins {
    application
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation(project(":im-gateway"))
    implementation(project(":persistence-postgres"))
    implementation(project(":protocol-v2"))
    implementation(libs.jackson.core)
    implementation(libs.postgresql)
}

application {
    mainClass = "com.fallingnight.chat.performance.GatewayMessagingBaseline"
}
