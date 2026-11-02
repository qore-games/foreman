plugins {
    java
    `java-library`
    kotlin("jvm")
    `maven-publish`
}

group = providers.gradleProperty("maven_group").get()
version = providers.gradleProperty("mod_version").get()
base.archivesName = "foreman-server-api"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:${providers.gradleProperty("paper_version").get()}")
}

kotlin {
    jvmToolchain(25)
}

java {
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "foreman-server-api"
            from(components["java"])
        }
    }
}
