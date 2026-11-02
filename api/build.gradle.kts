plugins {
    java
    `java-library`
    kotlin("jvm")
    `maven-publish`
}

group = providers.gradleProperty("maven_group").get()
version = providers.gradleProperty("mod_version").get()

repositories {
    mavenLocal()
    mavenCentral()
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25
    }
}

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "foreman-api"
            from(components["java"])
        }
    }
    repositories {
        mavenLocal()
    }
}

base.archivesName = "foreman-api"
