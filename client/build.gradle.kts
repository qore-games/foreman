import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.0" apply false
}

// The Fabric mod, a jar per Minecraft version. Each versions/<minecraft> project compiles the shared sources (src/) and
// its own layer (package games.qore.foreman.client.mc: what differs between versions) against its Minecraft version,
// with the versions in its gradle.properties.
val shared = layout.projectDirectory.dir("src")
// IntelliJ gives a source folder to one module only: while it syncs, the shared sources go to the newest version (an
// older version's own files then miss the shared types in the IDE; build and run them with Gradle).
val ideVersion = subprojects.maxBy { p -> p.name.split('.').joinToString(".") { it.padStart(4, '0') } }
val ideSync = System.getProperty("idea.sync.active") == "true"

subprojects {
    apply(plugin = "net.fabricmc.fabric-loom")
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.serialization")
    apply(plugin = "maven-publish")

    fun prop(name: String) = property(name) as String
    val minecraft = prop("minecraft_version")
    version = "${prop("mod_version")}+$minecraft"
    group = prop("maven_group")
    extensions.configure<BasePluginExtension> { archivesName = prop("mod_name") }

    if (!ideSync || this == ideVersion) {
        extensions.configure<SourceSetContainer> {
            named("main") {
                java.srcDir(shared.dir("main/java"))
                resources.srcDir(shared.dir("main/resources"))
            }
        }
        extensions.configure<KotlinJvmProjectExtension> {
            sourceSets.named("main") { kotlin.srcDir(shared.dir("main/kotlin")) }
            sourceSets.named("test") { kotlin.srcDir(shared.dir("test/kotlin")) }
        }
    }

    repositories {
        maven {
            name = "Fabric"
            url = uri("https://maven.fabricmc.net/")
        }
        maven {
            name = "Terraformers"
            url = uri("https://maven.terraformersmc.com/releases/")
        }
        mavenCentral()
    }

    dependencies {
        "minecraft"("com.mojang:minecraft:$minecraft")
        "implementation"("net.fabricmc:fabric-loader:${prop("loader_version")}")
        "implementation"("net.fabricmc.fabric-api:fabric-api:${prop("fabric_api_version")}")
        "implementation"("net.fabricmc:fabric-language-kotlin:${prop("fabric_kotlin_version")}")
        "implementation"("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
        "compileOnly"("com.terraformersmc:modmenu:${prop("modmenu_version")}")
        "implementation"(project(":api"))
        "implementation"(project(":fastload"))
        "testImplementation"(kotlin("test"))
    }

    tasks.named<Test>("test") {
        useJUnitPlatform()
    }

    tasks.named<ProcessResources>("processResources") {
        // fabric.mod.json requires the versions built against, and the Minecraft versions this jar runs on
        val expanded = mapOf(
            "version" to version,
            "minecraft" to prop("minecraft_range"),
            "loader" to prop("loader_version"),
            "fabric_api" to prop("fabric_api_version").substringBefore('+'),
            "fabric_kotlin" to prop("fabric_kotlin_version").substringBefore('+'),
        )
        inputs.properties(expanded)
        filesMatching("fabric.mod.json") {
            expand(expanded)
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release = 25
    }

    extensions.configure<KotlinJvmProjectExtension> {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_25
        }
    }

    extensions.configure<JavaPluginExtension> {
        withSourcesJar()
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    tasks.named<Jar>("jar") {
        val projectName = rootProject.name
        from(rootProject.file("LICENSE")) {
            rename { "${it}_$projectName" }
        }
        from(project(":api").the<SourceSetContainer>()["main"].output)
        // fastload's package is its own: bundled as is, beside other copies of it (a launcher's agent, another mod).
        from(project(":fastload").the<SourceSetContainer>()["main"].output)
    }

    tasks.named<JavaExec>("runClient") {
        jvmArgs("-XX:+AllowEnhancedClassRedefinition")
    }

    extensions.configure<PublishingExtension> {
        publications {
            register<MavenPublication>("mavenJava") {
                artifactId = "foreman-client"
                from(components["java"])
            }
        }
    }
}
