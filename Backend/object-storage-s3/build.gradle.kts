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
    implementation(libs.aws.s3) {
        exclude(group = "software.amazon.awssdk", module = "apache5-client")
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    implementation(libs.aws.url.connection.client)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.register<JavaExec>("probeAttachmentStorage") {
    group = "verification"
    description = "Runs the explicitly confirmed, auto-cleaning real-provider capability probe"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.fallingnight.chat.storage.s3.S3AttachmentCapabilityProbeMain"
}
