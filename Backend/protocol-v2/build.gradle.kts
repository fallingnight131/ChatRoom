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

    @get:InputDirectory
    abstract val protoRoot: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val webOutputDirectory: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun generate() {
        val output = outputDirectory.get().asFile
        val schemas = protoRoot.get().asFileTree.matching {
            include("**/*.proto")
        }.files.sortedBy { it.absolutePath }
        require(schemas.isNotEmpty()) { "no V2 protobuf schemas found" }
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
                "--es_opt=target=ts,import_extension=js",
                "--descriptor_set_out=${output.resolve("chat-v2.desc")}",
                "--include_imports",
            )
            args(schemas.map { it.absolutePath })
        }
        val generatedWeb = output.resolve("typescript/chat/v2")
        require(generatedWeb.isDirectory) { "generated Web V2 bindings are missing" }
        val webOutput = webOutputDirectory.get().asFile
        webOutput.deleteRecursively()
        require(generatedWeb.copyRecursively(webOutput, overwrite = true)) {
            "could not publish generated Web V2 bindings"
        }
        webOutput.walkTopDown()
            .filter { it.isFile && it.extension == "ts" }
            .forEach { file -> file.writeText(file.readText().trimEnd() + "\n") }
    }
}

tasks.register<GenerateClientBindings>("generateClientBindings") {
    group = "build"
    description = "Generate non-Java V2 client bindings from the authoritative schema"
    protoc.set(layout.file(protocExecutable))
    typescriptPlugin.set(layout.projectDirectory.file(
        "typescript/node_modules/.bin/protoc-gen-es"))
    protoRoot.set(layout.projectDirectory.dir("src/main/proto"))
    outputDirectory.set(layout.projectDirectory.dir("typescript/generated"))
    webOutputDirectory.set(layout.projectDirectory.dir(
        "../../WebClient/src/protocol/v2/generated"))
}
