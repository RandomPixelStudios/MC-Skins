![title](https://cdn.modrinth.com/data/cached_images/9bd4a2a4e11c13c86fb1b21d40bf711f341f983c.png)

### About The Mod:
This mod lets you edit your Minecraft skins **in-game!** It comes with a 3D canvas that makes editing quick and simple.

### Requirements:
- Minecraft: Java Edition **26.3** (the mod declares 26.1 – 26.3; it is built and tested against 26.3)
- [Fabric Loader](https://fabricmc.net/use/installer/) 0.19.5 or newer
- [Fabric API](https://modrinth.com/mod/fabric-api)
- Java 25 (bundled with the official launcher for these versions)
- Client only – no server install needed

### Features:
- **Dressing Room** (button on the title screen): your local skin library with a 3D preview, rename, export, delete, uploading a skin to your account, and previewing/wearing the capes you own.
  - **Search, sort and filter**: search by name or tag, sort by name, last edited or creation date, filter by favourites or a tag. Tags work like folders.
  - **Favourites** (★) and **tags** per skin.
  - **Version history**: snapshots are taken automatically when a skin is saved (at most one every 5 minutes, the newest 20 are kept) and manually on request (20 kept); restore any of them – the version it replaces is kept as a snapshot.
  - **Compare** two skins side by side in 3D with synced rotation, zoom, pose and animation.
  - **Batch export**: Ctrl+click several skins, then "Export n".
  - **Import…**: any player's current skin by **player name** (public Mojang profile, arm width taken from the profile) or a **PNG from a web address**. Old 64×32 skins are converted to 64×64 automatically (left arm and leg mirrored like vanilla), for every import path.
- **Quick skin switcher** (in game, key **K** by default, changeable under Options → Controls → MC-Skins): pick a library skin and upload it to your account. Uploads are spaced 30 s apart because Mojang limits skin changes; errors (offline, rate limit, …) are shown in the screen.
- **Skin editor**: paint directly on the 3D model or on the flat 64×64 texture, base and overlay (hat, jacket, sleeves) shells, slim and classic arms.
- **Tools**: brush, eraser, color picker, fill, line, rectangle, filled rectangle, lighten, darken, noise, **rectangle selection**, **magic wand**, **gradient** (primary → previous color, optional dithering), **shading brush** (lighten/darken with a warm/cool hue shift and adjustable strength) and **replace color** (on the layer or the whole skin, with tolerance), with brush size, mirror mode and grid.
- **Selections** (flat view): rectangle or magic wand (same color, connected), Shift adds; copy, cut, paste, move by dragging, flip horizontally/vertically, rotate 90°, delete. Selections work on the active layer, and painting tools stay inside an active selection.
- **Body parts** (☰ menu): copy one part onto another (optionally mirrored, slim/classic arm widths respected), copy base → overlay or overlay → base for one part or all.
- **Clipboard**: Ctrl+V pastes an image from the system clipboard as a new layer when nothing was copied inside the editor (Ctrl+Shift+V always does). Minecraft runs AWT headless, so the mod asks the system: PowerShell on Windows, `osascript` on macOS, `wl-paste` or `xclip` on Linux (one of them must be installed). Clipboard text with a path to a PNG works too.
- **Layers**: up to 32 layers with visibility, opacity, **blend modes** (normal, multiply, screen, overlay, add), **alpha lock**, **layer groups** (collapse, group visibility and opacity), reorder, duplicate, merge down, rename and clear.
- **Undo history**: undo/redo plus a history list (☰ menu) with named steps; click a step to jump to it.
- **Colors**: HSV square or **color wheel** (◎), alpha, hex input, recent colors, **custom palettes** (▦): extract a palette from the skin, add colors, save/load/delete named palettes.
- **Reference image** (☰ menu): load a PNG (path or drag & drop) as a semi-transparent guide over the flat view, with opacity and a show/hide toggle; it is never part of the skin.
- **Skin check** (☰ menu): finds transparent and semi-transparent base pixels (the game draws the base opaque, holes turn black) and paint outside the model's texture areas, highlights them in red and can fix them (an opaque layer under everything / clearing the stray paint).
- **Render PNG** (☰ menu): renders the current 3D pose and camera at 512, 1024 or 2048 px with a transparent background.
- **Import**: drop a PNG onto the Dressing Room to import it as a new skin, or onto the editor to add it as a layer.
- **Templates**: start from Steve, Alex (in the arm width you chose) or a blank skin.
- **Test world**: try the skin in a temporary local world with animations (idle, walk, run, swim, sneak, elytra, swing, crawl), **armor per slot** (leather, chainmail, iron, gold, diamond, netherite, copper), **elytra**, **items in both hands**, **lighting** (day, night, nether, cave) and **GIF recording** (4 s, saved to `config/mcskins/recordings/`). Nether and cave are simulated by a closed chamber around the player (netherrack with glowstone and magma, or stone lit by one torch) instead of a real dimension change. The world is deleted afterwards and your account skin is not changed.
- **Export** to PNG for use in the official launcher or anywhere else.

### Keyboard Shortcuts (Editor):
Letter shortcuts follow your keyboard layout (on QWERTZ, Ctrl+Z is the key labelled Z).

| Key | Action |
| --- | --- |
| B / E / I / F / L / U | Brush / Eraser / Picker / Fill / Line / Rectangle |
| D / S / N | Lighten / Darken / Noise |
| Q / W | Rectangle selection / Magic wand |
| J / H / K | Gradient / Shading brush / Replace color |
| Ctrl+C / Ctrl+X / Ctrl+V | Copy / cut / paste (without an internal copy: paste the clipboard image as a layer) |
| Ctrl+Shift+V | Paste the clipboard image as a new layer |
| Ctrl+A | Select everything |
| Delete / Backspace | Clear the selected pixels |
| Enter | Apply a pasted or moved selection |
| Esc | Cancel the paste/move or deselect (otherwise leaves the editor) |
| Shift + click (selection tools) | Add to the selection |
| `[` / `]` | Smaller / larger brush |
| M | Mirror mode |
| G | Grid |
| O | Paint base or overlay shell |
| Tab | Switch between 3D model and flat texture |
| R | Reset view |
| X | Swap current and previous color |
| Alt + click | Pick a color |
| Ctrl+Z | Undo |
| Ctrl+Y or Ctrl+Shift+Z | Redo |
| Ctrl+S | Save |
| Right drag / middle drag / wheel | Rotate / pan / zoom |

In the test world: 1–8 choose the animation, R resets the camera, Esc pauses (use "Back to Editor" there). The buttons on the right choose armor, held items, lighting and start a GIF recording.

In game: **K** (configurable) opens the quick skin switcher.

### Where Your Files Are:
Everything is stored in the `config/mcskins/` folder inside your Minecraft folder (the Dressing Room settings have an "Open folder" button):

| Path | Contents |
| --- | --- |
| `config/mcskins/projects/` | Your skin projects (`<id>.mcskin`) |
| `config/mcskins/exports/` | Exported PNGs (`<skin name>_<short id>.png`) |
| `config/mcskins/broken/` | Project files that could not be read are moved here instead of being deleted |
| `config/mcskins/capes/` | Cache of your cape textures |
| `config/mcskins/history/<id>/` | Version history snapshots of each skin |
| `config/mcskins/renders/` | Rendered PNGs of the 3D pose (`<skin name>_<date-time>.png`) |
| `config/mcskins/recordings/` | GIF recordings from the test world |
| `config/mcskins/palettes/` | Saved custom palettes |
| `config/mcskins/library.properties` | Favourites, tags and creation dates of your skins |
| `config/mcskins/settings.properties` | Editor and Dressing Room settings |

Projects use format version 3 (blend modes, alpha lock and layer groups); older projects load fine and are upgraded when saved. Projects saved by a newer MC-Skins version are left untouched (and skipped) when you go back to an older version.

### Installation:

1. Install Minecraft: Java Edition.
2. Install the [Fabric Loader](https://fabricmc.net/use/installer/) (if you use a launcher like Prism or the Modrinth App, follow their own guide for Fabric).
3. Download the dependency [Fabric API](https://modrinth.com/mod/fabric-api) and [the mod](https://modrinth.com/mod/mc-skins/versions).
4. Put both jars into the `mods` folder inside your Minecraft folder.
5. Launch the game!


Thanks!
