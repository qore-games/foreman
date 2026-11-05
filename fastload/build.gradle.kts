plugins {
    `java-library`
    `maven-publish`
}

// The fast-loading algorithms (mipmaps, font providers, zip index) and their claims: plain Java on JDK types, no
// dependencies, for Foreman's mod and for any other mod or Java agent that bundles it.
group = providers.gradleProperty("maven_group").get()
version = providers.gradleProperty("mod_version").get()
base.archivesName = "fastload"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    maxHeapSize = "1g"
}

tasks.named<Jar>("jar") {
    from(rootProject.file("LICENSE")) {
        rename { "${it}_fastload" }
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "fastload"
            from(components["java"])
        }
    }
    repositories {
        mavenLocal()
    }
}
