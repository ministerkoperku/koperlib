# KUI

KUI is the menu and HUD system. A page is described in JSON, KoperLib syncs it to the client, and clicks come back to the pack script or a Java hook.

Overview level material is in [KOPERLIB.md](KOPERLIB.md). This file is the detail.

## Contents

1. [Two modes](#two-modes)
2. [Defining a page](#defining-a-page)
3. [Layout files](#layout-files)
4. [Element types](#element-types)
5. [Containers and real item slots](#containers-and-real-item-slots)
6. [Recipes and craft buttons](#recipes-and-craft-buttons)
7. [Driving a page from Lua](#driving-a-page-from-lua)
8. [HUD mode](#hud-mode)
9. [Limits and how to get around them](#limits-and-how-to-get-around-them)

## Two modes

**`json` mode** builds the interface from a `.layout.json` file. Elements are positioned in pixels, KoperLib draws them. Suited to menus edited as data, without an image editor.

**`texture` mode** shows a hand-drawn PNG, plus a `.regions.json` that says which rectangles are clickable. Suited to menus that need full control of the look.

Both modes support real item slots and both can be shown as a HUD overlay.

## Defining a page

A page is a JSON file in `guis/`:

```json
{
  "id": "mypack:forge_menu",
  "title": "Forge",
  "mode": "json",
  "w": 176,
  "h": 166,
  "layout": "forge_menu.layout.json",
  "script": "mypack:scripts/forge.lua"
}
```

Fields:

* `id` full page id, `namespace:name`
* `title` shown in the menu header
* `mode` `json` or `texture`, defaults to `json`
* `w` and `h` size in pixels, defaults 176 by 166 which is the vanilla chest size
* `layout` path to the layout file, relative to the pack, for `json` mode
* `texture` path to the PNG, for `texture` mode
* `script` Lua script id for the page logic
* `hud_x` and `hud_y` anchor when the page is drawn as a HUD, defaults 4 and 4
* `recipes` list of shapeless recipes this page can craft
* `craft_buttons` button ids that attempt a craft when clicked

Open it from a block by setting `"gui": "mypack:forge_menu"` on the block JSON, or from a script with `koper.gui.open(player, "mypack:forge_menu")`.

## Layout files

A layout is a list of elements. Coordinates are pixels from the top left of the page.

The root key is **`widgets`**. `KuiLayout.parse` reads that and nothing else, so a layout with any
other root key parses to zero elements and the result is an empty window with no error in the log.

```json
{
  "widgets": [
    { "type": "panel",  "x": 0,  "y": 0,  "w": 176, "h": 166 },
    { "type": "label",  "x": 8,  "y": 6,  "text": "Forge", "color": "0xFF404040" },
    { "type": "grid",   "x": 8,  "y": 20, "cols": 3, "rows": 3, "gap": 2,
      "container": true, "role": "input" },
    { "type": "slot",   "x": 120, "y": 40, "container": true, "role": "output" },
    { "type": "button", "id": "smelt", "x": 70, "y": 100, "w": 40, "h": 16,
      "text": "Smelt", "craft": true },
    { "type": "progress", "id": "heat", "x": 8, "y": 140, "w": 160, "h": 6, "value": 0.0 }
  ]
}
```

Fields shared by every element:

* `type` see below
* `id` the element's handle, needed if a script will address the element
* `x`, `y`, `w`, `h` position and size in pixels
* `text` label or button caption
* `tooltip` hover text
* `color` an ARGB integer

## Element types

**`label`** text. Uses `text` and `color`. With a `w` set it also takes `align`: `left`, `center` or `right`. Labels do not wrap, so long text has to be split into several labels. Text that does not fit is shortened at the last whole word with `...`, and hovering it shows the whole text. List rows, buttons and toggles shorten the same way.

**`panel`** a filled background rectangle. Draw it first, since elements render in file order. Set `color` and it uses that instead of the theme grey, which is how pages, cards and sections read as separate things.

**`rule`** a plain line. Horizontal when it is wider than tall, vertical otherwise. Takes `color`. Use it to separate sections instead of faking one with a one pixel panel.

**`image`** a texture. `frames` is a list of texture ids and `fps` the frame rate, so an image can animate.

**`button`** clickable. Set `craft: true` to make it attempt a recipe, otherwise it fires an event to the pack script. `color` tints the face and the hover shade is derived from it, so a green Save and a red Delete take one field each.

**`toggle`** on and off. `checked` sets the starting state.

**`slider`** `value` from 0 to 1.

**`radio`** and **`selector`** pick one of `options`, with `selected` naming the current one. A selector splits its width between the options, so when a segment would fall under about 34 pixels it switches by itself to a cycler: one option shown with `<` and `>` on the ends and a counter.

**`input`** a text field, with `placeholder` for the empty hint. The current contents are in `inputText`. It reports its text when the player presses Enter or Esc or leaves the field. Set `live: true` (or `KuiJson.liveInput` in Java) to report on every keystroke instead, for search boxes that filter while typing.

**`progress`** a bar. `value` from 0 to 1. Set `anim: "auto"` with an `fps` to have it fill by itself over that many seconds.

**`slot`** one item slot.

**`grid`** a block of slots. `cols`, `rows` and `gap` shape it.

**`entity`** a live entity preview. `previewZoom` scales it after the automatic fit.

**`ghost`** a slot that shows an item without holding one, for recipe hints.

## Containers and real item slots

This is the part that matters most, and it is easy to get wrong.

A `slot` or `grid` element with **`"container": true`** is a real synced inventory slot. The player can put items in, take them out, shift click, and the contents are saved. Without that flag the element is only a drawing.

`role` decides how a slot behaves:

* `""` or absent, a normal slot
* `"input"`, a normal slot that recipes read from
* `"output"`, take only. The player can remove items but not insert. Use it for results.

**Where the items actually live.** For a menu opened from a block, the items are in that block's block entity. Two of the same block have two separate inventories, hoppers can reach them and comparators can read them. For a menu with a shared container, or one opened from a script with no block behind it, the items live in a world file keyed by the page id.

That means a block gui is automatically automation friendly and a script gui is not. For hopper access, put the menu on a block.

## Recipes and craft buttons

A page can craft without any script at all.

```json
{
  "id": "mypack:forge_menu",
  "recipes": [
    { "input": ["mypack:ore", "mypack:ore", "minecraft:coal"], "output": "mypack:ingot", "count": 2 }
  ],
  "craft_buttons": ["smelt"]
}
```

Recipes are shapeless. When a button listed in `craft_buttons` is clicked, KoperLib matches the input slots against every recipe on the page, and on a hit consumes the inputs and puts the result in the output slot.

For anything conditional, skip `craft` and handle the button click in Lua instead.

## Driving a page from Lua

Bind a script with the page's `script` field, or handle clicks in a Java hook.

```lua
koper.gui.open(player, "mypack:forge_menu")
koper.gui.close(player)

koper.gui.set("heat", 0.75)              -- set a widget value by element id
koper.gui.set_slot(4, "mypack:ingot", 2) -- put an item in a slot index
koper.gui.clear_slot(4)
koper.gui.clear_all()
koper.gui.consume(0, 1)                  -- take from a slot

koper.gui.hud(player, "mypack:overlay", true)
```

Slot indices count from zero across the container slots of the page, in the order they appear in the layout.

## HUD mode

Any layout can be drawn as an overlay instead of a menu. `koper.gui.hud(player, pageId, true)` shows it, `false` hides it. Position comes from `hud_x` and `hud_y` on the page.

A HUD has no interaction. It is a readout, so use `label`, `image` and `progress`, and update values with `koper.gui.set`.

## Limits and how to get around them

**`koper.ui.screen` is a stub.** The builder API with `add_label`, `add_button` and `add_slider` accepts calls and does nothing. Every one of them. **Workaround:** there is no substitute, use a KUI page. The whole system is the replacement for that builder.

**Layouts are static.** Elements cannot be added or removed at runtime, only change their values with `koper.gui.set`. **Workaround:** declare every element that might be needed and drive them. A progress bar at 0 and a label with empty text are invisible enough.

**Several listeners on one gui event all fire now**, in registration order. This used to keep only the last one registered, which is why two scripts in the same pack reacting to `gui:click` used to look broken.

**Slot indices are positional.** Reordering elements in the layout file renumbers the slots, and items already stored keep their old index. Reordering a container layout makes an existing test chest look shuffled. **Workaround:** append new slots at the end rather than inserting in the middle.

**Shared containers are worldwide.** A page opened without a block behind it shares one inventory across every player who opens it. That suits a guild bank and little else. **Workaround:** open menus from blocks when they should be separate.

**Recipes are shapeless only.** No shaped grid matching. **Workaround:** handle the button in Lua and inspect the slots there, or use a real recipe in `recipes/` and a vanilla station.

**Text input is minimal.** `input` returns a string, with no validation and no numeric mode. Validate it in the handler.

## Themes and runtime updates

KUI supplies named palettes through `KuiThemes`. Addon palettes can be loaded from `gui/themes/*.json` or `koperlib/gui/themes/*.json`. The current themes apply to JSON and texture-mode widgets.

Java addons can update widget text, values and options through `KuiPoke`. `KuiPoke.text(player, widget, shown, full)` draws `shown` and puts `full` in the hover tooltip, for text the server has to shorten itself; an empty `full` clears the tooltip. `KuiDraw` exposes shared drawing helpers for custom screens, such as real-time minigames that do not fit a declarative page. These belong to the Fullpack module; see [Java addons](JAVA_ADDONS.md).

*Claude AI used for documentation.*
