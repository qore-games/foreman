plugins {
    kotlin("jvm") version "2.4.0" apply false
    id("net.fabricmc.fabric-loom") apply false
}

// The artifacts plugins compile against: their POMs, and the release repository (the release workflow publishes there,
// then the docs site serves it as https://qore-games.github.io/foreman/maven/).
configure(listOf(project(":api"), project(":paper"), project(":server-api"))) {
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            publications.withType<MavenPublication>().configureEach {
                val publication = this
                pom {
                    // Read late: each module sets its artifactId after this runs.
                    name = provider { publication.artifactId }
                    description = "Foreman: a multi-tool for Minecraft server owners, admins and plugin developers"
                    url = "https://qore-games.github.io/foreman/"
                    licenses {
                        license {
                            name = "MIT"
                            url = "https://github.com/qore-games/foreman/blob/main/LICENSE"
                        }
                    }
                    scm { url = "https://github.com/qore-games/foreman" }
                }
            }
            repositories {
                maven {
                    name = "release"
                    url = uri(rootProject.layout.buildDirectory.dir("release-repo"))
                }
            }
        }
    }
}
