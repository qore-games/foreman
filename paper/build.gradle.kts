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
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://oss.sonatype.org/content/groups/public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:${providers.gradleProperty("paper_version").get()}")
    api(project(":api"))
}

kotlin {
    jvmToolchain(25)
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
    withSourcesJar()
}

tasks.withType<Jar> {
    exclude("plugin.yml")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "foreman-paper"
            from(components["java"])
        }
    }
    repositories {
        mavenLocal()
    }
}

base.archivesName = "foreman-paper"
