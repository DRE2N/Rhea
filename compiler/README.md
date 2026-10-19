# Rhea compiler

Compiles a folder with `rhea.json` and HTML/CSS into resource-pack glyphs, dialog templates, and preview PNGs.

```sh
npm install
npx playwright install chromium
npx rhea --source path/to/ui --output path/to/build/rhea
```

The command verifies its output before it exits. Put `pack/assets/` in a resource pack, and bundle the whole `templates/` folder in a Paper plugin. Live slots bring their local `@font-face` files and CSS sizes with them. Mark click targets with `data-rhea-action="id"` and live text with `data-rhea-slot="id"`. Chromium only runs while building.
