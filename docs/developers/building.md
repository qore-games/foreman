# Building from source

Requirements: JDK 25.

```sh
./gradlew build
./gradlew publishToMavenLocal
```

| Module | Artifact | What |
|---|---|---|
| `client` | `client/build/libs/foreman-<version>.jar` | The Fabric mod |
| `server` | `server/build/libs/foreman-server-<version>.jar` | The Paper plugin |
| `server-api` | `games.qore:foreman-server-api` | What other plugins compile against (`ForemanTools`, `ForemanWorlds`, `FurnitureProvider`) |
| `paper` | `games.qore:foreman-paper` | The location API's Paper side, shaded into your plugin |
| `api` | `games.qore:foreman-api` | The location protocol, and Foreman's tool names shared by the client and the server |

`./gradlew :client:runClient` starts a development client.

## Documentation

The site is built with [Zensical](https://zensical.org) from `docs/` and `zensical.toml`:

```sh
pip install -r docs/requirements.txt
zensical serve   # http://localhost:8000, rebuilt as you edit
```

It's published to GitHub Pages by `.github/workflows/docs.yml` on every push to `main` that changes the docs, with
the Maven repository (`maven/`) next to it.
