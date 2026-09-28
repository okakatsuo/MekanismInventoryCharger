package net.okakatsuo.mekanisminventorycharger;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class ChargerScreen extends Screen {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(MekanismInventoryCharger.MOD_ID, "textures/gui/charger_settings.png");
    private static final String[] TABS = {"General", "Filters", "Priority", "HUD"};
    private final ChargerSettings original;
    private ChargerSettings draft;
    private int tab, generalPage, priorityPage, scroll;
    private int left, top;
    private String error = "";
    private boolean discardPrompt;
    private EditBox start, stop, rate, interval, reserve, selector;
    private record Sprite(int x, int y, int u) {}
    private final List<Sprite> sprites = new ArrayList<>();

    public ChargerScreen(ChargerSettings settings) {
        super(Component.translatable("screen.mekanism_inventory_charger.title"));
        original = settings.copy(); draft = settings.copy();
    }

    @Override protected void init() {
        left = (width - 300) / 2; top = (height - 220) / 2;
        rebuild();
    }

    private Component tr(String key) { return Component.translatable("screen.mekanism_inventory_charger." + key); }
    private boolean textured() { return Minecraft.getInstance().getResourceManager().getResource(TEXTURE).isPresent(); }
    private String localized(String raw) {
        if (raw.startsWith("Auto Charging:")) return tr(draft.enabled ? "auto_on" : "auto_off").getString();
        if (raw.startsWith("✓") || raw.startsWith("×")) return raw.substring(0, 1) + tr("category_" + raw.substring(1).toLowerCase(java.util.Locale.ROOT)).getString();
        if (raw.startsWith("[") && raw.endsWith("]")) return "[" + localized(raw.substring(1, raw.length() - 1)) + "]";
        for (String tabName : TABS) if (raw.equals(tabName)) return tr("tab_" + tabName.toLowerCase(java.util.Locale.ROOT)).getString();
        for (Class<? extends Enum<?>> type : List.of(ChargerSettings.Distribution.class, ChargerSettings.SourceOrder.class,
                ChargerSettings.Reserve.class, ChargerSettings.Filter.class, ChargerSettings.Hud.class)) {
            for (Enum<?> value : type.getEnumConstants()) if (raw.equals(value.name()))
                return tr("option_" + value.name().toLowerCase(java.util.Locale.ROOT)).getString();
        }
        return tr("label_" + raw.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "")).getString();
    }
    private Button button(int x, int y, int w, String label, Consumer<Button> action) {
        String translated = label.matches("[0-9]+\\. .*|\\? [#@a-z0-9_.:-]+|[#@a-z0-9_.:-]+|[↑↓]") ? label : localized(label);
        int icon = switch (label.replace("[", "").replace("]", "")) {
            case "General" -> 320;
            case "Filters" -> 336;
            case "Priority" -> 352;
            case "HUD" -> 368;
            case "Add" -> 384;
            case "X" -> 400;
            case "↑" -> 416;
            case "↓" -> 432;
            case "Apply" -> 464;
            default -> -1;
        };
        boolean iconOnly = textured() && icon >= 0 && !label.equals("Apply");
        Button widget = addRenderableWidget(Button.builder(Component.literal(iconOnly ? "" : translated), action::accept).bounds(left + x, top + y, w, 18).build());
        if (iconOnly) widget.setTooltip(Tooltip.create(Component.literal(translated)));
        if (textured() && icon >= 0) sprites.add(new Sprite(x + (label.equals("Apply") ? 3 : (w - 16) / 2), y + 1, icon));
        return widget;
    }
    private void field(int x, int y, int w, String value, Consumer<EditBox> bind) {
        EditBox box = new EditBox(font, left + x, top + y, w, 18, Component.literal("value"));
        box.setMaxLength(40); box.setValue(value); addRenderableWidget(box); bind.accept(box);
    }

    private void rebuild() {
        clearWidgets(); sprites.clear(); start = stop = rate = interval = reserve = selector = null;
        button(9, 7, 110, draft.enabled ? "Auto Charging: ON" : "Auto Charging: OFF", b -> { if (!capture()) return; draft.enabled = !draft.enabled; rebuild(); });
        for (int i = 0; i < TABS.length; i++) {
            final int selected = i;
            button(9 + i * 71, 29, 68, (tab == i ? "[" : "") + TABS[i] + (tab == i ? "]" : ""), b -> {
                if (!capture()) return; tab = selected; scroll = 0; rebuild();
            });
        }
        if (tab == 0) general();
        else if (tab == 1) filters();
        else if (tab == 2) priority();
        else hud();
        button(8, 196, 86, "Reset Defaults", b -> { draft = new ChargerSettings(); draft.revision = original.revision; error = ""; rebuild(); });
        button(101, 196, 86, "Cancel", b -> Minecraft.getInstance().setScreen(null));
        Button apply = button(194, 196, 98, "Apply", b -> apply());
        apply.active = !discardPrompt;
        if (discardPrompt) {
            button(95, 105, 110, "Discard changes?", b -> { draft = original.copy(); Minecraft.getInstance().setScreen(null); });
        }
    }

    private void general() {
        button(245, 51, 46, generalPage == 0 ? "More >" : "< Back", b -> { if (!capture()) return; generalPage = 1 - generalPage; rebuild(); });
        if (generalPage == 0) {
            ChargerSettings.Category[] categories = ChargerSettings.Category.values();
            for (int i = 0; i < categories.length; i++) {
                ChargerSettings.Category c = categories[i];
                button(9 + i * 57, 73, 55, (draft.categories.contains(c) ? "✓" : "×") + shortCategory(c), b -> {
                    if (!capture()) return;
                    if (!draft.categories.add(c)) draft.categories.remove(c);
                    rebuild();
                });
            }
            field(96, 100, 50, Integer.toString(draft.start), box -> start = box);
            field(234, 100, 50, Integer.toString(draft.stop), box -> stop = box);
            field(96, 132, 90, Long.toString(draft.rate), box -> rate = box);
            field(234, 132, 50, Integer.toString(draft.interval), box -> interval = box);
        } else {
            button(120, 75, 164, draft.distribution.name(), b -> { if (!capture()) return; draft.distribution = next(ChargerSettings.Distribution.values(), draft.distribution); rebuild(); });
            button(120, 101, 164, draft.sourceOrder.name(), b -> { if (!capture()) return; draft.sourceOrder = next(ChargerSettings.SourceOrder.values(), draft.sourceOrder); rebuild(); });
            button(120, 127, 164, draft.reserveType.name(), b -> { if (!capture()) return; draft.reserveType = next(ChargerSettings.Reserve.values(), draft.reserveType); rebuild(); });
            field(120, 153, 164, Long.toString(draft.reserve), box -> reserve = box);
        }
    }

    private void filters() {
        button(92, 54, 190, draft.filter.name(), b -> { draft.filter = next(ChargerSettings.Filter.values(), draft.filter); rebuild(); });
        field(10, 79, 192, "", box -> selector = box);
        button(207, 79, 38, "Add", b -> addSelector(draft.filters));
        button(249, 79, 41, "Held", b -> addHeld(draft.filters));
        selectorList(draft.filters, 104);
    }

    private void priority() {
        button(226, 52, 64, priorityPage == 0 ? "Rules >" : "< Order", b -> { if (!capture()) return; priorityPage = 1 - priorityPage; scroll = 0; rebuild(); });
        if (priorityPage == 0) {
            for (int i = 0; i < draft.categoryOrder.size(); i++) {
                final int row = i;
                int y = 74 + i * 22;
                button(10, y, 210, (i + 1) + ". " + tr("full_" + draft.categoryOrder.get(i).name().toLowerCase(java.util.Locale.ROOT)).getString(), b -> {});
                button(224, y, 30, "↑", b -> { if (row > 0) { java.util.Collections.swap(draft.categoryOrder, row, row - 1); rebuild(); } });
                button(258, y, 30, "↓", b -> { if (row < draft.categoryOrder.size() - 1) { java.util.Collections.swap(draft.categoryOrder, row, row + 1); rebuild(); } });
            }
        } else {
            field(10, 74, 192, "", box -> selector = box);
            button(207, 74, 38, "Add", b -> addSelector(draft.priorities));
            button(249, 74, 41, "Held", b -> addHeld(draft.priorities));
            selectorList(draft.priorities, 99);
        }
    }

    private void hud() {
        button(94, 73, 190, draft.hud.name(), b -> { draft.hud = next(ChargerSettings.Hud.values(), draft.hud); rebuild(); });
    }

    private void selectorList(List<String> list, int y) {
        int max = 4;
        if (scroll > 0) button(279, y - 2, 13, "↑", b -> { scroll--; rebuild(); });
        if (scroll + max < list.size()) button(279, y + 66, 13, "↓", b -> { scroll++; rebuild(); });
        for (int i = scroll; i < Math.min(list.size(), scroll + max); i++) {
            final int index = i;
            int rowY = y + (i - scroll) * 20;
            button(10, rowY, 204, (resolved(list.get(i)) ? "" : "? ") + list.get(i), b -> {});
            button(217, rowY, 19, "X", b -> { list.remove(index); rebuild(); });
            if (tab == 2) {
                button(239, rowY, 18, "↑", b -> { if (index > 0) { java.util.Collections.swap(list, index, index - 1); rebuild(); } });
                button(260, rowY, 18, "↓", b -> { if (index < list.size() - 1) { java.util.Collections.swap(list, index, index + 1); rebuild(); } });
            }
        }
    }

    private void addSelector(List<String> list) {
        String value = selector.getValue().trim().toLowerCase(java.util.Locale.ROOT);
        if (!ChargerSettings.validSelector(value)) { error = "Invalid selector"; return; }
        if (list.size() >= 256) { error = "Limit: 256 rules"; return; }
        if (!list.contains(value)) list.add(value);
        error = ""; rebuild();
    }
    private static boolean resolved(String selector) {
        if (selector.startsWith("@")) return BuiltInRegistries.ITEM.keySet().stream().anyMatch(id -> id.getNamespace().equals(selector.substring(1)));
        ResourceLocation id = ResourceLocation.tryParse(selector.startsWith("#") ? selector.substring(1) : selector);
        if (id == null) return false;
        if (selector.startsWith("#")) return BuiltInRegistries.ITEM.getTag(TagKey.create(Registries.ITEM, id)).isPresent();
        return BuiltInRegistries.ITEM.containsKey(id);
    }
    private void addHeld(List<String> list) {
        if (Minecraft.getInstance().player == null || Minecraft.getInstance().player.getMainHandItem().isEmpty()) return;
        String id = BuiltInRegistries.ITEM.getKey(Minecraft.getInstance().player.getMainHandItem().getItem()).toString();
        if (!list.contains(id) && list.size() < 256) list.add(id);
        rebuild();
    }

    private static <T> T next(T[] values, T current) { return values[(java.util.Arrays.asList(values).indexOf(current) + 1) % values.length]; }
    private static String shortCategory(ChargerSettings.Category c) { return switch (c) {
        case MAIN_HAND -> "Main"; case OFF_HAND -> "Off"; case ARMOR -> "Armor"; case HOTBAR -> "Bar"; case INVENTORY -> "Inv";
    }; }

    private boolean capture() {
        try {
            if (start != null) draft.start = Integer.parseInt(start.getValue().trim());
            if (stop != null) draft.stop = Integer.parseInt(stop.getValue().trim());
            if (rate != null) draft.rate = parseRate(rate.getValue());
            if (interval != null) draft.interval = Integer.parseInt(interval.getValue().trim());
            if (reserve != null) draft.reserve = Long.parseLong(reserve.getValue().trim());
            error = ""; return true;
        } catch (NumberFormatException ex) { error = "Invalid number"; return false; }
    }
    private static long parseRate(String text) {
        String value = text.trim().toUpperCase(java.util.Locale.ROOT);
        long scale = 1;
        if (value.endsWith("K")) { scale = 1_000L; value = value.substring(0, value.length() - 1); }
        else if (value.endsWith("M")) { scale = 1_000_000L; value = value.substring(0, value.length() - 1); }
        else if (value.endsWith("G")) { scale = 1_000_000_000L; value = value.substring(0, value.length() - 1); }
        else if (value.endsWith("T")) { scale = 1_000_000_000_000L; value = value.substring(0, value.length() - 1); }
        return Math.multiplyExact(Long.parseLong(value.trim()), scale);
    }

    private void apply() {
        if (!capture()) return;
        String issue = draft.validate();
        if (issue != null) { error = issue; return; }
        PacketDistributor.sendToServer(new ChargerNetwork.Packet("apply", draft.save()));
    }

    public void setError(String message) { error = message; }

    @Override public void onClose() {
        boolean valid = capture();
        if (!discardPrompt && (!valid || !draft.save().equals(original.save()))) { discardPrompt = true; rebuild(); return; }
        Minecraft.getInstance().setScreen(null);
    }

    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Screen.render calls this before rendering widgets. Blur the world only,
        // then draw the panel and labels so they are never blurred a second time.
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        if (Minecraft.getInstance().getResourceManager().getResource(TEXTURE).isPresent()) {
            graphics.blit(TEXTURE, left, top, 0, 0, 300, 220, 512, 256);
        } else {
            graphics.fill(left, top, left + 300, top + 220, 0xEF131A20);
            graphics.fill(left, top, left + 300, top + 2, 0xFF55C7D3);
            graphics.fill(left + 3, top + 50, left + 297, top + 192, 0xFF202A31);
        }
        graphics.drawString(font, title, left + 126, top + 12, 0xFFFFFF);
        if (tab == 0 && generalPage == 0) {
            graphics.drawString(font, localized("Targets"), left + 10, top + 56, 0xFFFFFF);
            graphics.drawString(font, localized("Start %"), left + 10, top + 105, 0xFFFFFF);
            graphics.drawString(font, localized("Stop %"), left + 165, top + 105, 0xFFFFFF);
            graphics.drawString(font, localized("Rate J/t"), left + 10, top + 137, 0xFFFFFF);
            graphics.drawString(font, localized("Interval"), left + 191, top + 137, 0xFFFFFF);
        } else if (tab == 0) {
            graphics.drawString(font, localized("Distribution"), left + 10, top + 79, 0xFFFFFF);
            graphics.drawString(font, localized("Source order"), left + 10, top + 105, 0xFFFFFF);
            graphics.drawString(font, localized("Reserve type"), left + 10, top + 131, 0xFFFFFF);
            graphics.drawString(font, localized("Reserve"), left + 10, top + 157, 0xFFFFFF);
            graphics.drawString(font, localized("Creative Cube: always allowed"), left + 10, top + 179, 0xAAAAAA);
        } else if (tab == 1) {
            graphics.drawString(font, localized("Filter mode"), left + 10, top + 58, 0xFFFFFF);
            graphics.drawString(font, localized("Item ID / @mod / #tag"), left + 10, top + 183, 0xAAAAAA);
        }
        else if (tab == 2 && draft.distribution == ChargerSettings.Distribution.EVEN)
            graphics.drawString(font, localized("Priority ignored in Even mode"), left + 10, top + 183, 0xFFC070);
        else if (tab == 3) {
            graphics.drawString(font, localized("HUD mode"), left + 10, top + 77, 0xFFFFFF);
            graphics.drawString(font, "⚡ " + Component.translatable("hud.mekanism_inventory_charger.charging").getString() + "  1024 J/t", left + 20, top + 112, 0x80FFF0);
            if (draft.hud == ChargerSettings.Hud.DETAILED) {
                String tablet = new net.minecraft.world.item.ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("mekanism:energy_tablet"))).getHoverName().getString();
                graphics.drawString(font, tablet + " 84%  " + Component.translatable("hud.mekanism_inventory_charger.targets", 2).getString(), left + 20, top + 128, 0xDDDDDD);
            }
        }
        if (!error.isBlank()) graphics.drawString(font, localized(error), left + 10, top + 184, 0xFF7777);
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        if (textured()) {
            graphics.blit(TEXTURE, left + 278, top + 6, 304, 0, 16, 16, 512, 256);
            if (tab == 0 && generalPage == 1) graphics.blit(TEXTURE, left + 9, top + 175, 480, 0, 16, 16, 512, 256);
            if (!error.isBlank()) graphics.blit(TEXTURE, left + 275, top + 177, 448, 0, 16, 16, 512, 256);
            for (Sprite sprite : sprites) graphics.blit(TEXTURE, left + sprite.x, top + sprite.y, sprite.u, 0, 16, 16, 512, 256);
            graphics.fill(left + 9 + tab * 71, top + 47, left + 77 + tab * 71, top + 49, 0xFF55C7D3);
        }
    }
}
