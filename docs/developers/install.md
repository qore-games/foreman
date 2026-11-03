# Add Foreman to your plugin

Foreman publishes three artifacts. Most plugins need one of the first two:

| Artifact | Gives you | How you depend on it | Needs the Foreman plugin |
|---|---|---|---|
| `games.qore:foreman-server-api` | [`ForemanTools`](foreman-tools.md), [`ForemanWorlds`](foreman-worlds.md), [`FurnitureProvider`](furniture.md) | provided (`compileOnly`): the Foreman plugin has the classes | yes |
| `games.qore:foreman-paper` | The [location API](locations-api.md): your locations in the mod, staff's edits back as events | shaded into your plugin | no |
| `games.qore:foreman-api` | The location protocol (packets, codecs, builders) | comes with `foreman-paper` | no |

They're in Foreman's Maven repository, `https://qore-games.github.io/foreman/maven/`. The examples use version
`1.1.1`: the latest is on the [releases page](https://github.com/qore-games/foreman/releases).

## The server API

### 1. The dependency

=== "Gradle (Kotlin)"

    ```kotlin title="build.gradle.kts"
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://qore-games.github.io/foreman/maven/")
    }

    dependencies {
        compileOnly("io.papermc.paper:paper-api:26.3.build.+")
        compileOnly("games.qore:foreman-server-api:1.1.1")
    }
    ```

=== "Gradle (Groovy)"

    ```groovy title="build.gradle"
    repositories {
        mavenCentral()
        maven { url = 'https://repo.papermc.io/repository/maven-public/' }
        maven { url = 'https://qore-games.github.io/foreman/maven/' }
    }

    dependencies {
        compileOnly 'io.papermc.paper:paper-api:26.3.build.+'
        compileOnly 'games.qore:foreman-server-api:1.1.1'
    }
    ```

=== "Maven"

    ```xml title="pom.xml"
    <repositories>
        <repository>
            <id>papermc</id>
            <url>https://repo.papermc.io/repository/maven-public/</url>
        </repository>
        <repository>
            <id>foreman</id>
            <url>https://qore-games.github.io/foreman/maven/</url>
        </repository>
    </repositories>

    <dependencies>
        <dependency>
            <groupId>games.qore</groupId>
            <artifactId>foreman-server-api</artifactId>
            <version>1.1.1</version>
            <scope>provided</scope>
        </dependency>
    </dependencies>
    ```

Never shade `foreman-server-api`: the Foreman plugin provides its classes, and a copy in your jar would be a different
class from the one Foreman registered.

### 2. A soft dependency on Foreman

Your plugin keeps working without Foreman; with it, Foreman enables first.

=== "plugin.yml"

    ```yaml
    softdepend: [Foreman]
    ```

=== "paper-plugin.yml"

    ```yaml
    dependencies:
      server:
        Foreman:
          load: BEFORE
          required: false
          join-classpath: true # lets your plugin see Foreman's classes
    ```

### 3. Register your tools

Get the service in `onEnable`. It's `null` when Foreman isn't installed: keep everything that touches Foreman's classes
behind that check (or in a class you only load then).

=== "Java"

    ```java
    import games.qore.foreman.server.api.ExternalTool;
    import games.qore.foreman.server.api.ForemanTools;
    import games.qore.foreman.server.api.ToolReply;

    public final class MyPlugin extends JavaPlugin {
        @Override
        public void onEnable() {
            if (getServer().getPluginManager().isPluginEnabled("Foreman")) ForemanHook.register(this);
        }
    }

    final class ForemanHook {
        static void register(MyPlugin plugin) {
            ForemanTools tools = plugin.getServer().getServicesManager().load(ForemanTools.class);
            if (tools == null) return;

            // One of your commands as a tool: the AI gets everything it printed.
            tools.registerCommand(plugin, "arena_reset", "Resets an arena to its saved state", "arena reset {arena}",
                Map.of("arena", "Arena id"), false);

            // A tool of your own.
            tools.register(plugin, new ExternalTool(
                "arena_list",
                "Lists the arenas with their state and player count. Use it before resetting one.",
                "{\"type\": \"object\", \"properties\": {}}",
                false,
                call -> ToolReply.ok(tools.onMainThread(() -> plugin.describeArenas()))));
        }
    }
    ```

=== "Kotlin"

    ```kotlin
    import games.qore.foreman.server.api.ExternalTool
    import games.qore.foreman.server.api.ForemanTools
    import games.qore.foreman.server.api.ToolReply

    class MyPlugin : JavaPlugin() {
        override fun onEnable() {
            if (server.pluginManager.isPluginEnabled("Foreman")) ForemanHook.register(this)
        }
    }

    object ForemanHook {
        fun register(plugin: MyPlugin) {
            val tools = plugin.server.servicesManager.load(ForemanTools::class.java) ?: return

            // One of your commands as a tool: the AI gets everything it printed.
            tools.registerCommand(plugin, "arena_reset", "Resets an arena to its saved state", "arena reset {arena}",
                mapOf("arena" to "Arena id"), false)

            // A tool of your own.
            tools.register(plugin, ExternalTool(
                "arena_list",
                "Lists the arenas with their state and player count. Use it before resetting one.",
                """{"type": "object", "properties": {}}""",
                false,
            ) { ToolReply.ok(tools.onMainThread { plugin.describeArenas() }) })
        }
    }
    ```

Then `/foreman tools` lists them, and every chat on the server can use them. The rest of the API:
[ForemanTools](foreman-tools.md), [ForemanWorlds](foreman-worlds.md), [FurnitureProvider](furniture.md).

## The location API

`foreman-paper` runs inside your plugin, so it works on servers without Foreman: shade it into your jar.

### 1. Shade it

=== "Gradle (Kotlin)"

    ```kotlin title="build.gradle.kts"
    plugins {
        java
        id("com.gradleup.shadow") version "9.6.1"
    }

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://qore-games.github.io/foreman/maven/")
    }

    dependencies {
        compileOnly("io.papermc.paper:paper-api:26.3.build.+")
        implementation("games.qore:foreman-paper:1.1.1")
    }

    tasks.shadowJar {
        // Kotlin's standard library comes from the server (plugin.yml `libraries`), not your jar.
        dependencies { exclude(dependency("org.jetbrains.kotlin:.*")) }
        relocate("games.qore.foreman", "com.example.myplugin.foreman")
    }
    ```

=== "Gradle (Groovy)"

    ```groovy title="build.gradle"
    plugins {
        id 'java'
        id 'com.gradleup.shadow' version '9.6.1'
    }

    repositories {
        mavenCentral()
        maven { url = 'https://repo.papermc.io/repository/maven-public/' }
        maven { url = 'https://qore-games.github.io/foreman/maven/' }
    }

    dependencies {
        compileOnly 'io.papermc.paper:paper-api:26.3.build.+'
        implementation 'games.qore:foreman-paper:1.1.1'
    }

    shadowJar {
        // Kotlin's standard library comes from the server (plugin.yml `libraries`), not your jar.
        dependencies { exclude(dependency('org.jetbrains.kotlin:.*')) }
        relocate 'games.qore.foreman', 'com.example.myplugin.foreman'
    }
    ```

=== "Maven"

    ```xml title="pom.xml"
    <dependencies>
        <dependency>
            <groupId>games.qore</groupId>
            <artifactId>foreman-paper</artifactId>
            <version>1.1.1</version>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-shade-plugin</artifactId>
                <version>3.6.2</version>
                <executions>
                    <execution>
                        <phase>package</phase>
                        <goals><goal>shade</goal></goals>
                        <configuration>
                            <artifactSet>
                                <!-- Kotlin's standard library comes from the server (plugin.yml `libraries`). -->
                                <excludes><exclude>org.jetbrains.kotlin:*</exclude></excludes>
                            </artifactSet>
                            <relocations>
                                <relocation>
                                    <pattern>games.qore.foreman</pattern>
                                    <shadedPattern>com.example.myplugin.foreman</shadedPattern>
                                </relocation>
                            </relocations>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
    ```

`foreman-paper` is written in Kotlin. A Java plugin asks the server for Kotlin's standard library in its
`plugin.yml` (Paper downloads it once); a Kotlin plugin already has it.

```yaml title="plugin.yml"
libraries:
  - org.jetbrains.kotlin:kotlin-stdlib:2.4.0
```

Relocating keeps your copy apart from other plugins' (each plugin registers its own). The relocation is optional.

### 2. Send your locations, receive the edits

=== "Java"

    ```java
    import games.qore.foreman.api.ForemanAPI;
    import games.qore.foreman.api.data.LocationAction;
    import games.qore.foreman.api.data.RenderShape;
    import games.qore.foreman.paper.ForemanPaper;
    import games.qore.foreman.paper.event.LocationMoveRequestEvent;

    public final class MyPlugin extends JavaPlugin implements Listener {
        private final ForemanPaper foreman = new ForemanPaper();

        @Override
        public void onEnable() {
            foreman.register(this);
            getServer().getPluginManager().registerEvents(this, this);
        }

        /** Sends the spawns to a staff member (on join, and after each change). */
        void showSpawns(Player staff) {
            var builder = ForemanAPI.builder("myplugin", "spawns")
                .setAction(LocationAction.REPLACE)
                .setSourceColor(0x55FF55)
                .setDefaultDisplay(ForemanAPI.displayConfig().shape(RenderShape.FLAG).glow(true).build());
            for (Spawn spawn : spawns) builder.addPreciseLocation(spawn.id(), spawn.world(), spawn.x(), spawn.y(), spawn.z());
            foreman.sendLocationData(staff, builder.build());
        }

        @EventHandler
        public void onMove(LocationMoveRequestEvent event) {
            if (!event.getSource().equals("myplugin")) return;
            if (!event.getPlayer().hasPermission("myplugin.edit")) { event.setCancelled(true); return; }
            moveSpawn(event.getLocationId(), event.getWorld(), event.getX(), event.getY(), event.getZ());
            showSpawns(event.getPlayer());
        }
    }
    ```

=== "Kotlin"

    ```kotlin
    import games.qore.foreman.api.ForemanAPI
    import games.qore.foreman.api.data.RenderShape
    import games.qore.foreman.paper.ForemanPaper
    import games.qore.foreman.paper.event.LocationMoveRequestEvent

    class MyPlugin : JavaPlugin(), Listener {
        private val foreman = ForemanPaper()

        override fun onEnable() {
            foreman.register(this)
            server.pluginManager.registerEvents(this, this)
        }

        /** Sends the spawns to a staff member (on join, and after each change). */
        fun showSpawns(staff: Player) {
            val packet = ForemanAPI.createReplacePacket("myplugin", "spawns") {
                setSourceColor(0x55FF55)
                setDefaultDisplay { shape(RenderShape.FLAG); glow(true) }
                for (spawn in spawns) addPreciseLocation(spawn.id, spawn.world, spawn.x, spawn.y, spawn.z)
            }
            foreman.sendLocationData(staff, packet)
        }

        @EventHandler
        fun onMove(event: LocationMoveRequestEvent) {
            if (event.source != "myplugin") return
            if (!event.player.hasPermission("myplugin.edit")) return event.setCancelled(true)
            moveSpawn(event.locationId, event.world, event.x, event.y, event.z)
            showSpawns(event.player)
        }
    }
    ```

Every plugin that shades the location API receives every edit: check `source` before acting on one. The details:
[Location API](locations-api.md).

## Before a release: your local build

To try a change to Foreman's APIs in your plugin, publish them to your local Maven repository and add `mavenLocal()`
first in your repositories:

```sh
git clone https://github.com/qore-games/foreman && cd foreman
./gradlew publishToMavenLocal
```
