# Contributing

Issues and pull requests are welcome. For a larger change, open an issue first to agree on the approach.

## Building

JDK 25. `./gradlew build` builds everything; `./gradlew :client:26.3:runClient` starts a development client (each
Minecraft version has its own: `:client:26.2:runClient`). The modules
and their artifacts are described in [Building from source](https://qore-games.github.io/foreman/developers/building/).

The mod has a jar per Minecraft version: `client/versions/<minecraft>` builds the shared sources (`client/src`)
against that version, with what differs between versions in the package `games.qore.foreman.client.mc` (an `Mc` object
per version with the same members, and that version's mixins). The shared code calls `Mc` where the versions differ
and never tests the version: key and mouse button numbers changed in 26.3 and are compiled in. To add a Minecraft
version, copy the newest `client/versions/<minecraft>`, set its `gradle.properties`, make it compile, and play it: a
mixin whose target is gone fails only in game.

The versions in `gradle.properties` and in each `client/versions/<minecraft>/gradle.properties` are the oldest Foreman
supports (`fabric.mod.json` asks for them): raise one only when the code needs it, and check a lower one in a real game
first (a Fabric API built for a 26.3 pre-release builds fine and crashes on 26.3). The plugin is one jar for every Paper
version, compiled against the oldest. Every pull request is built by the Build workflow, docs included (`zensical build --strict`),
and its jars are attached to the run.

## Code

- Kotlin, in the style of the code around it: short functions, a doc comment that says what a thing is or does, few
  comments inside, for the why.
- Nothing may cost anything while unused: no listener, task or recording doing work while its feature is off.
- Foreman is generic: no server's names or content in its code, defaults or examples.

## Documentation

The site is in `docs/`, built with [Zensical](https://zensical.org) (`pip install -r docs/requirements.txt`, then
`zensical serve`). Change the docs in the same pull request as the behaviour they describe.

## Releases

1. Set `mod_version` in `gradle.properties` and move the changes under its heading in `CHANGELOG.md`.
2. Push the tag `v<version>`. The release workflow builds the jars, attaches them to a GitHub release with that
   section of the changelog, and publishes the API artifacts to the Maven repository on the docs site.
