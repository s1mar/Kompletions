import java.net.URI

plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    id("org.openapi.generator") version "7.19.0"
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
            tasks.named("fixOpenApiSpec").get().actions.forEach { it.execute(tasks.named("fixOpenApiSpec").get()) }
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

// Task to fix YAML anchors in the spec (OpenAPI generator doesn't handle them well)
tasks.register("fixOpenApiSpec") {
    group = "openai"
    description = "Fix YAML anchors and aliases in the OpenAPI spec"

    doLast {
        if (!openApiSpecFile.exists()) {
            println("Spec file not found, skipping fix")
            return@doLast
        }

        var content = openApiSpecFile.readText()

        content = content.replace("required: &a1", "required:")
        content = content.replace("required: *a1", """required:
        - type""")

        openApiSpecFile.writeText(content)
        println("Fixed YAML anchors in OpenAPI spec")
    }
}

// Task to fix generated Kotlin code
tasks.register("fixGeneratedCode") {
    group = "openai"
    description = "Fix issues in generated Kotlin code"

    doLast {
        val generatedDir = layout.buildDirectory.dir("generated/openapi/src/main/kotlin").get().asFile
        if (!generatedDir.exists()) {
            println("Generated code not found, skipping fix")
            return@doLast
        }

        var fixedDataClassCount = 0
        var fixedAnyOfCount = 0
        var fixedOverrideCount = 0
        generatedDir.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            var content = file.readText()
            val originalContent = content

            // Find data classes with no parameters and fix them
            val dataClassRegex = Regex("""data class\s+(\w+)\s*\(\s*\)""")
            content = dataClassRegex.replace(content) { matchResult ->
                val className = matchResult.groupValues[1]
                fixedDataClassCount++
                "class $className ()"
            }

            // Fix AnyOfLessThanGreaterThan references
            if (content.contains("AnyOfLessThanGreaterThan")) {
                fixedAnyOfCount += content.split("AnyOfLessThanGreaterThan").size - 1
                content = content.replace("import com.s1mar.openai.models.AnyOfLessThanGreaterThan\n", "")
                val lines = content.lines()
                content = lines.joinToString("\n") { line ->
                    if (!line.trim().startsWith("package") && !line.trim().startsWith("import")) {
                        line.replace("AnyOfLessThanGreaterThan", "Any")
                    } else {
                        line
                    }
                }
            }

            // Fix invalid override keywords
            if (content.contains("override val") || content.contains("override var")) {
                val beforeOverrideFix = content
                content = content.replace("    override val detail:", "    val detail:")
                content = content.replace("    override val imageUrl:", "    val imageUrl:")
                content = content.replace("    override val fileId:", "    val fileId:")
                content = content.replace("    override val filename:", "    val filename:")
                content = content.replace("    override val fileUrl:", "    val fileUrl:")
                content = content.replace("    override val fileData:", "    val fileData:")
                if (content != beforeOverrideFix) {
                    fixedOverrideCount++
                }
            }

            // Fix malformed List types
            content = content.replace(": kotlin.collections.List?", ": kotlin.collections.List<Any>?")

            // Replace BigDecimal with Double (avoids serializer issues)
            content = content.replace("import java.math.BigDecimal\n", "")
            val bigDecimalRegex = Regex("""(?:java\.math\.)?BigDecimal\("([^"]+)"\)""")
            content = bigDecimalRegex.replace(content) { m ->
                val num = m.groupValues[1]
                if (num.contains(".")) num else "$num.0"
            }
            content = content.replace("java.math.BigDecimal", "Double")
            content = content.replace(": BigDecimal", ": Double")

            // Replace kotlin.Any types in val declarations with JsonElement/JsonObject
            // for kotlinx.serialization compatibility. Do NOT replace in method signatures
            // (e.g., enum companion encode/decode methods).
            // Use simple string replacements on val lines for reliability:
            val lines = content.lines().toMutableList()
            var needsJsonImport = false
            for (i in lines.indices) {
                val line = lines[i]
                if (!line.contains("val ") || !line.contains("kotlin.Any")) continue
                var fixed = line
                // List<Map<String, Any>>
                fixed = fixed.replace(
                    "kotlin.collections.List<kotlin.collections.Map<kotlin.String, kotlin.Any>>",
                    "kotlin.collections.List<kotlinx.serialization.json.JsonObject>"
                )
                // Map<String, Any>
                fixed = fixed.replace(
                    "kotlin.collections.Map<kotlin.String, kotlin.Any>",
                    "kotlinx.serialization.json.JsonObject"
                )
                // List<Any>
                fixed = fixed.replace(
                    "kotlin.collections.List<kotlin.Any>",
                    "kotlin.collections.List<kotlinx.serialization.json.JsonElement>"
                )
                // Bare Any? and Any
                fixed = fixed.replace("kotlin.Any?", "kotlinx.serialization.json.JsonElement?")
                fixed = fixed.replace("kotlin.Any", "kotlinx.serialization.json.JsonElement")
                if (fixed != line) {
                    needsJsonImport = true
                    lines[i] = fixed
                }
            }
            if (needsJsonImport) {
                content = lines.joinToString("\n")
                content = content.replace("@Contextual ", "")
            }

            if (content != originalContent) {
                file.writeText(content)
            }
        }

        println("Fixed $fixedDataClassCount empty data classes, $fixedAnyOfCount AnyOf references, and $fixedOverrideCount invalid overrides")
    }
}

// Configure OpenAPI Generator
openApiGenerate {
    generatorName.set("kotlin")
    inputSpec.set(openApiSpecFile.absolutePath.replace("\\", "/"))
    outputDir.set(layout.buildDirectory.dir("generated/openapi").get().asFile.absolutePath)
    packageName.set("com.s1mar.openai")
    apiPackage.set("com.s1mar.openai.api")
    modelPackage.set("com.s1mar.openai.models")

    validateSpec.set(false)
    skipValidateSpec.set(true)

    configOptions.set(mapOf(
        "dateLibrary" to "java8",
        "serializationLibrary" to "kotlinx_serialization"
    ))

    globalProperties.set(mapOf(
        "models" to "",
        "modelDocs" to "false",
        "skipValidateSpec" to "true"
    ))
}

// Make sure spec exists before generating
tasks.named("openApiGenerate") {
    dependsOn("ensureOpenApiSpec")
    finalizedBy("fixGeneratedCode")
}

// Add generated sources to source sets
kotlin.sourceSets["main"].kotlin.srcDir("${layout.buildDirectory.get()}/generated/openapi/src/main/kotlin")

// Make compileKotlin depend on openApiGenerate
tasks.named("compileKotlin") {
    dependsOn("openApiGenerate")
}

tasks.test {
    useJUnitPlatform()
}
