import net.minecrell.pluginyml.bukkit.BukkitPluginDescription

plugins {
    kotlin("jvm")
    id("java")
    id("net.minecrell.plugin-yml.bukkit") version "0.6.0"
}

group = providers.gradleProperty("maven_group").get()
version = providers.gradleProperty("mod_version").get()

repositories {
    mavenCentral()
    mavenLocal()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.enginehub.org/repo/")
    maven("https://mvn.lumine.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:${providers.gradleProperty("paper_version").get()}")
    // The server's own: its log appenders (server_log) and players' connections (server_command as a player).
    compileOnly("org.apache.logging.log4j:log4j-core:2.26.0")
    compileOnly("io.netty:netty-transport:4.2.16.Final")
    implementation(project(":server-api"))
    implementation(project(":api"))

    implementation(platform("com.intellectualsites.bom:bom-newest:1.56"))
    compileOnly("com.fastasyncworldedit:FastAsyncWorldEdit-Core:2.16.1")
    compileOnly("com.fastasyncworldedit:FastAsyncWorldEdit-Bukkit:2.16.1") { isTransitive = false }
    // Integrations (integrations/): loaded only while their plugin runs.
    compileOnly("com.ticxo.modelengine:ModelEngine:R4.2.0") { isTransitive = false }
}

kotlin {
    jvmToolchain(25)
}

bukkit {
    main = "games.qore.foreman.server.Foreman"
    name = "Foreman"
    apiVersion = "26.3"
    description = "Tools for server owners, admins and plugin developers, for AI agents (MCP) and the Foreman client mod."
    website = "https://qore-games.github.io/foreman/"

    authors = listOf("evlad")
    // The world tools need it; the server tools and plugins' tools work without.
    softDepend = listOf("FastAsyncWorldEdit", "ModelEngine")
    libraries = listOf("org.jetbrains.kotlin:kotlin-stdlib:2.4.0")
    load = BukkitPluginDescription.PluginLoadOrder.POSTWORLD
    defaultPermission = BukkitPluginDescription.Permission.Default.OP

    commands {
        register("foreman") {
            aliases = listOf("fm")
            permission = "foreman.use"
        }
    }

    permissions {
        register("foreman.use") { description = "The /foreman command" }
        register("foreman.client") { description = "Being connected to the server's tools when joining with the client mod" }
        register("foreman.admin") { description = "The dev-server tools (commands as the console, logs, reloads) with a personal token" }
        register("foreman.test") { description = "The dupe-testing tools: item ledger, inventory watch and restore, and the mod's network profiles and packet drops" }
        register("foreman.watch") { description = "A chat line for each change an AI makes to the world" }
    }
}

base.archivesName = "foreman-server"

// The tool API's classes ship in the plugin (other plugins compile against foreman-server-api and use them from here).
tasks.jar {
    from(project(":server-api").sourceSets.main.get().output)
    // The names of Foreman's own tools, shared with the client (only this class of the api: the rest is the client's).
    from(project(":api").sourceSets.main.get().output) { include("games/qore/foreman/api/ForemanTool*") }
}
