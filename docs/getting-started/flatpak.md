# Flatpak launchers

If your launcher is a Flatpak (Prism Launcher from Flathub, for example), it can't start programs on your computer
by default, so chats can't start Claude Code or opencode. Let it, once, then restart the launcher:

```sh
flatpak override --user --talk-name=org.freedesktop.Flatpak org.prismlauncher.PrismLauncher
```

The OpenRouter backend doesn't need this: it talks to OpenRouter from the game itself.

Folders outside the launcher's sandbox may not be visible from the game. Claude Code and opencode still use them,
because they run outside the sandbox. The hub warns about this when you add such a folder.
