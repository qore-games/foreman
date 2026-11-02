# For developers

| You want to | Use | Needs the Foreman plugin |
|---|---|---|
| Teach the AI your plugin (its commands, configs) | [Skills](skills.md): markdown in your jar, no code | yes (to be read) |
| Give the AI your plugin's tools and commands | [`ForemanTools`](foreman-tools.md) (`foreman-server-api`) | yes |
| Show your plugin's locations in the mod and receive moves | [Location API](locations-api.md) (`foreman-paper`) | no |
| Read the areas and points Foreman keeps in a world | [`ForemanWorlds`](foreman-worlds.md) (`foreman-server-api`) | yes |
| Offer your furniture to the world tools | [`FurnitureProvider`](furniture.md) (`foreman-server-api`) | yes |

Start with [Add Foreman to your plugin](install.md): the repository, the dependency for Gradle (Kotlin or Groovy)
or Maven, and a first tool or location in Java or Kotlin.
