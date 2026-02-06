import java.net.URI

plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
}

group = "com.s1mar.kompletions"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val ktorVersion = "2.3.7"
val kotlinxSerializationVersion = "1.6.2"

// Configuration for OpenAPI spec
val openApiSpecDir = layout.buildDirectory.dir("openapi-spec").get().asFile
val openApiSpecFile = File(openApiSpecDir, "openai-spec.yaml")
val defaultSpecUrl = "https://raw.githubusercontent.com/openai/openai-openapi/manual_spec/openapi.yaml"

val specUrl: String = (project.properties["specUrl"] as? String) ?: defaultSpecUrl
val customSpecFile: String? = project.properties["customSpecFile"] as? String

dependencies {
    // Kotlin
    implementation(kotlin("stdlib"))

    // HTTP Client (Ktor)
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    // JSON Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")

    // Testing
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.9")
}

// Task to download OpenAPI spec
tasks.register("downloadOpenApiSpec") {
    group = "openai"
    description = "Download OpenAPI specification from URL (use -PspecUrl=<url> to override default)"

    doLast {
        openApiSpecDir.mkdirs()

        val url = if (customSpecFile != null) {
            println("Using custom spec file: $customSpecFile")
            File(customSpecFile).copyTo(openApiSpecFile, overwrite = true)
            return@doLast
        } else {
            specUrl
        }

        println("Downloading OpenAPI spec from: $url")
        println("Saving to: ${openApiSpecFile.absolutePath}")

        try {
            URI(url).toURL().openStream().use { input ->
                openApiSpecFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            println("OpenAPI spec downloaded successfully")
        } catch (e: Exception) {
            throw GradleException("Failed to download OpenAPI spec from $url: ${e.message}", e)
        }
    }
}

// Task to check if spec exists, download if not
tasks.register("ensureOpenApiSpec") {
    group = "openai"
    description = "Ensure OpenAPI spec is available (downloads if missing)"

    doLast {
        if (!openApiSpecFile.exists()) {
            println("OpenAPI spec not found, downloading...")
            tasks.named("downloadOpenApiSpec").get().actions.forEach { it.execute(tasks.named("downloadOpenApiSpec").get()) }
        } else {
            println("OpenAPI spec already exists at: ${openApiSpecFile.absolutePath}")
        }
    }
}

// Task to force update the spec
tasks.register("updateOpenApiSpec") {
    group = "openai"
    description = "Force download the latest OpenAPI specification"
    dependsOn("downloadOpenApiSpec")
}

tasks.test {
    useJUnitPlatform()
}