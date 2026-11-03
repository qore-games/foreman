pluginManagement {
    repositories {
        maven {
            name = "Fabric"
            url = uri("https://maven.fabricmc.net/")
        }
        mavenCentral()
        gradlePluginPortal()
    }

    plugins {
        id("net.fabricmc.fabric-loom") version providers.gradleProperty("loom_version")
    }
}

rootProject.name = "foreman"

// api: the location/overlay API other plugins use; paper: its Paper side, standalone or shaded into a plugin;
// server: the Foreman plugin (world MCP server, bridge); server-api: what other plugins use to publish MCP tools on it;
// client: the Fabric mod, a project per Minecraft version (client/versions/<minecraft>).
include("api", "paper", "server", "server-api")
file("client/versions").listFiles { it.resolve("gradle.properties").isFile }!!.forEach {
    include(":client:${it.name}")
    project(":client:${it.name}").projectDir = it
}
