# Block Me In (Fabric mod for Minecraft 1.21.11)

Press a key and you're instantly walled in: blocks on all 4 sides of you
(at both feet and head height) plus a block on top of your head. If you're
standing over air, it also seals the floor under you.

## How it works in-game

- **Default key: `G`.** To change it: *Options → Controls → Key Binds →*
  scroll to the **Block Me In** section → click the key → press any key
  (or mouse button) you want.
- It uses blocks from your **hotbar**. It uses the block you're holding first,
  then any other full block in your hotbar. It switches back to your original slot afterwards.
- It skips gravity blocks (sand, gravel, concrete powder, anvils) so nothing
  falls on your head, and skips chests, shulker boxes, furnaces etc. so you don't
  waste them.
- It snaps you to the middle of the block you're in first, so the walls
  never collide with your hitbox.
- A full box costs 9 blocks (10 if it also places a floor). In creative mode it costs nothing.
- Works in singleplayer and on multiplayer servers (client-side only; the server
  doesn't need the mod). Some servers with strict anti-cheat may block it.

## Installing

1. Install **Fabric Loader** for 1.21.11 (https://fabricmc.net/use/installer/).
2. Put **Fabric API** for 1.21.11 in your `mods` folder.
3. Put `blockmein-1.0.0.jar` in your `mods` folder.

## Building the jar yourself

You need **Java 21 (JDK)** installed.

**Windows:** open a terminal in this folder and run

    gradlew.bat build

**macOS / Linux:**

    ./gradlew build

The first build takes several minutes because it downloads Minecraft and Fabric.
The mod ends up at `build/libs/blockmein-1.0.0.jar`. Ignore the `-sources.jar`.

### Or let GitHub build it for you (no Java needed)

1. Create a new repository on github.com and upload all of these files
   (including the hidden `.github` folder).
2. Open the **Actions** tab. The "Build mod jar" workflow runs automatically.
3. When it finishes (green tick), open the run and download
   **BlockMeIn-mod-jar** at the bottom of the page. It's a zip with the mod jar inside.

## Changing the default key or behaviour

Everything is in `src/main/java/com/blockmein/BlockMeInClient.java`.
For example, change `GLFW.GLFW_KEY_G` to another key, or delete the floor
block section in `computeTargets` if you don't want a floor.
