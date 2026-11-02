# Skills for your plugin

Teach the AI your plugin: its commands, its config files, how to test a change. A [skill](../server/skills.md) is
markdown the AI reads when a request is about your plugin; it costs one line of the AI's instructions until then.

## Without code: files in your jar

Put markdown files under `foreman/skills/` in your plugin's resources:

```
src/main/resources/
  plugin.yml
  foreman/skills/
    myshop.md
    myshop-prices.md
```

```markdown title="foreman/skills/myshop.md"
---
name: myshop
description: Add, change or price items in MyShop (its config.yml and /shop commands). Read before touching the shop.
---

# MyShop

Items are in `plugins/MyShop/config.yml` under `items.<id>`: `material`, `amount` (default 1), `price`. After editing:
`/shop reload`, then `/shop list` to see what players see. `/shop buy <id>` as a player tests a purchase.
```

Foreman reads them while your plugin is enabled (no dependency on Foreman, nothing to register), and offers them with
your plugin as their source. `name` is lowercase letters, digits and `-`; `plugin` defaults to yours.

## From code: `registerSkill`

When the skill depends on the server (the arenas that exist, a config value), build it at runtime. You need
`foreman-server-api` ([Add Foreman to your plugin](install.md#the-server-api)).

=== "Java"

    ```java
    ForemanTools foreman = Bukkit.getServicesManager().load(ForemanTools.class);
    if (foreman != null)
        foreman.registerSkill(this, "myshop", "Add, change or price items in MyShop. Read before touching the shop.",
            "# MyShop\n\nItems: " + String.join(", ", shop.itemIds()) + "\n\nAfter editing config.yml: `/shop reload`.");
    ```

=== "Kotlin"

    ```kotlin
    Bukkit.getServicesManager().load(ForemanTools::class.java)?.registerSkill(
        this, "myshop", "Add, change or price items in MyShop. Read before touching the shop.",
        "# MyShop\n\nItems: ${shop.itemIds().joinToString()}\n\nAfter editing config.yml: `/shop reload`.",
    )
    ```

Registered skills are removed when your plugin disables; register again to update one.

## Writing a good skill

- **The description is the trigger.** The AI picks skills by it: say what the skill is for and when to read it
  ("Read before changing a region or when something is blocked by protection").
- **Exact commands**, with their arguments and which must run as a player. Say which work from the console: the AI
  runs commands as the console unless told otherwise.
- **How to check:** the command that shows the result, where errors are logged (`/myplugin reload` then the log).
- **The pitfalls** you'd tell a new admin.
- Short. A long skill costs tokens every time it's read: split it by task (`myshop`, `myshop-prices`).

When commands can't do what the AI needs (structured data, an action without a command), add a tool:
[ForemanTools](foreman-tools.md).
