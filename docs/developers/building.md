# Building from source

Requirements: JDK 25.

```sh
./gradlew build
./gradlew publishToMavenLocal
```

| Module | Artifact | What |
|---|---|---|
| `client` | `client/versions/<minecraft>/build/libs/foreman-<version>+<minecraft>.jar` | The Fabric mod, a jar per Minecraft version |
| `server` | `server/build/libs/foreman-server-<version>.jar` | The Paper plugin |
| `server-api` | `games.qore:foreman-server-api` | What other plugins compile against (`ForemanTools`, `ForemanWorlds`, `FurnitureProvider`) |
| `paper` | `games.qore:foreman-paper` | The location API's Paper side, shaded into your plugin |
| `api` | `games.qore:foreman-api` | The location protocol, and Foreman's tool names shared by the client and the server |
| `fastload` | `games.qore:fastload` | The fast reload algorithms and their claims, plain Java for other mods and launchers to bundle ([API](https://github.com/qore-games/foreman/blob/main/fastload/API.md)) |

`./gradlew :client:26.3:runClient` starts a development client (`:client:26.2:runClient` on 26.2). Each Minecraft
version is a project in `client/versions/` that builds the shared sources (`client/src`) against it, with what
differs between versions in the package `games.qore.foreman.client.mc`.

## Documentation

The site is built with [Zensical](https://zensical.org) from `docs/` and `zensical.toml`:

```sh
pip install -r docs/requirements.txt
zensical serve   # http://localhost:8000, rebuilt as you edit
```

It's published to GitHub Pages by `.github/workflows/docs.yml` on every push to `main` that changes the docs, with
the Maven repository (`maven/`) next to it.
