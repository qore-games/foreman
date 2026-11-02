import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("net.fabricmc.fabric-loom")
    kotlin("jvm")
    `maven-publish`
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.0"
}

val archiveName = providers.gradleProperty("mod_name").get()
version = providers.gradleProperty("mod_version").get()
group = providers.gradleProperty("maven_group").get()
base.archivesName = archiveName

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
    minecraft("com.mojang:minecraft:${providers.gradleProperty("minecraft_version").get()}")
    implementation("net.fabricmc:fabric-loader:${providers.gradleProperty("loader_version").get()}")
    implementation("net.fabricmc.fabric-api:fabric-api:${providers.gradleProperty("fabric_api_version").get()}")
    implementation("net.fabricmc:fabric-language-kotlin:${providers.gradleProperty("fabric_kotlin_version").get()}")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    compileOnly("com.terraformersmc:modmenu:${providers.gradleProperty("modmenu_version").get()}")
    implementation(project(":api"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    val version = version
    inputs.property("version", version)

    filesMatching("fabric.mod.json") {
        expand("version" to version)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
    }
}

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.jar {
    val projectName = rootProject.name
    from(rootProject.file("LICENSE")) {
        rename { "${it}_$projectName" }
    }
    from(project(":api").sourceSets.main.get().output)
}

tasks.named<JavaExec>("runClient") {
    jvmArgs("-XX:+AllowEnhancedClassRedefinition")
}

publishing {
    publications {
        register<MavenPublication>("mavenJava") {
            artifactId = "foreman-client"
            from(components["java"])
        }
    }
}
