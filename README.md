# Mekanism Inventory Charger

Minecraft 1.21.1 / NeoForge addon that uses a carried Mekanism Energy Tablet or Energy Cube to charge energy items in the player's hotbar, inventory, armor slots, and offhand. Mekanism is required on both client and server.

## Use

- Press **I** to open settings and **O** to toggle charging. Both keys are remappable in Controls.
- `/mic status`, `/mic enable`, `/mic disable`, and `/mic config` affect your own settings. Operators can use `status|enable|disable <player>`.
- Settings are per player, saved on the server, and retained after death.
- Charging is off by default. Enable it in the screen or with `/mic enable`.

## Charging behavior

The addon uses the actual stored energy in allowed Mekanism items. The default source tag is `#mekanism_inventory_charger:charging_sources`: Energy Tablet and Basic, Advanced, Elite, Ultimate, and Creative Energy Cubes. A filled Creative Cube supplies energy as an item regardless of its block-side configuration. Datapacks may add more Mekanism items to this tag. Items outside the `mekanism` namespace cannot be sources, even if added to the tag.

Targets may be Mekanism items or items from other mods that expose compatible energy capabilities. The addon does not charge source-tagged items from each other. Curios and accessory slots are intentionally unsupported, as are batteries from other mods as sources.

`Start %` decides when an item begins charging. It then continues until `Stop %`. `Rate J/t` is a per-tick budget, applied across the selected scan interval; item capability limits can reduce the actual amount. `Reserve` leaves either a percentage or a fixed number of Joules in each source. In Priority mode, the first matching item rule and then the chosen category order determine target order. Even mode splits the available energy among targets and ignores target priority. Source order remains active in both modes.

Filters and item priority rules accept item IDs (`mekanism:meka_tool`), mod IDs (`@mekanism`), and item tags (`#c:tools`). Unknown but syntactically valid IDs can be stored so settings survive modpack changes.

## Build and verify

Use Java 21. Run `./gradlew test`, `./gradlew runGameTestServer`, and `./gradlew build`. The distributable JAR is written to `build/libs/`.

## 日本語

このアドオンは、持ち歩いているMekanismのEnergy TabletまたはEnergy Cubeから、ホットバー、インベントリ、防具、副手の充電可能アイテムへエネルギーを転送します。初期状態では自動充電はOFFです。**I**キーで設定画面、**O**キーでON/OFF切り替えができます。設定はプレイヤー別にサーバーへ保存されます。他Modの電池を充電元にする機能とCuriosスロットは対象外です。
