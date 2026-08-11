import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

plugins {
    `java-library`
    alias(libs.plugins.protobuf)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    api(libs.protobuf.java)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.35.1"
    }
}

val protocExecutable = providers.provider {
    configurations.getByName("protobufToolsLocator_protoc").singleFile
}

abstract class GenerateClientBindings : DefaultTask() {
    @get:InputFile
    abstract val protoc: RegularFileProperty

    @get:InputFile
    abstract val typescriptPlugin: RegularFileProperty

    @get:InputFile
    abstract val schema: RegularFileProperty

    @get:InputDirectory
    abstract val protoRoot: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun generate() {
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        output.resolve("cpp").mkdirs()
        output.resolve("typescript").mkdirs()
        execOperations.exec {
            executable(protoc.get().asFile.absolutePath)
            args(
                "--proto_path=${protoRoot.get().asFile}",
                "--cpp_out=${output.resolve("cpp")}",
                "--plugin=protoc-gen-es=${typescriptPlugin.get().asFile}",
                "--es_out=${output.resolve("typescript")}",
                "--es_opt=target=ts",
                "--descriptor_set_out=${output.resolve("chat-v2.desc")}",
                "--include_imports",
                schema.get().asFile.absolutePath,
            )
        }
    }
}

tasks.register<GenerateClientBindings>("generateClientBindings") {
    group = "build"
    description = "Generate non-Java V2 client bindings from the authoritative schema"
    protoc.set(layout.file(protocExecutable))
    typescriptPlugin.set(layout.projectDirectory.file(
        "typescript/node_modules/.bin/protoc-gen-es"))
    schema.set(layout.projectDirectory.file("src/main/proto/chat/v2/envelope.proto"))
    protoRoot.set(layout.projectDirectory.dir("src/main/proto"))
    outputDirectory.set(layout.projectDirectory.dir("typescript/generated"))
}
