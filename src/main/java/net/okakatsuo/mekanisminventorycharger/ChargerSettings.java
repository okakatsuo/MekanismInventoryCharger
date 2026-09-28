package net.okakatsuo.mekanisminventorycharger;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ChargerSettings {
    public enum Category { MAIN_HAND, OFF_HAND, ARMOR, HOTBAR, INVENTORY }
    public enum Distribution { PRIORITY, EVEN }
    public enum SourceOrder { INVENTORY_ORDER, LOWEST_CHARGE_FIRST, HIGHEST_CHARGE_FIRST, LOWEST_CAPACITY_FIRST, HIGHEST_CAPACITY_FIRST }
    public enum Filter { ALL, WHITELIST, BLACKLIST }
    public enum Hud { OFF, COMPACT, DETAILED }
    public enum Reserve { PERCENT, ABSOLUTE_JOULES }

    public boolean enabled = false;
    public EnumSet<Category> categories = EnumSet.allOf(Category.class);
    public List<Category> categoryOrder = new ArrayList<>(List.of(Category.values()));
    public int start = 90;
    public int stop = 100;
    public long rate = 1_024_000L;
    public int interval = 5;
    public Distribution distribution = Distribution.PRIORITY;
    public SourceOrder sourceOrder = SourceOrder.INVENTORY_ORDER;
    public Reserve reserveType = Reserve.PERCENT;
    public long reserve = 10;
    public Filter filter = Filter.ALL;
    public List<String> filters = new ArrayList<>();
    public List<String> priorities = new ArrayList<>();
    public Hud hud = Hud.COMPACT;
    public int revision = 0;

    public ChargerSettings copy() { return load(save()); }

    public static boolean validSelector(String selector) {
        if (selector == null || selector.length() > 128 || selector.isBlank()) return false;
        String id = selector.startsWith("#") || selector.startsWith("@") ? selector.substring(1) : selector;
        if (selector.startsWith("@")) return id.matches("[a-z0-9_.-]+");
        return ResourceLocation.tryParse(id) != null && id.contains(":");
    }

    public static boolean matches(List<String> selectors, net.minecraft.world.item.ItemStack stack) {
        ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        for (String selector : selectors) {
            if (selector.startsWith("@") && id.getNamespace().equals(selector.substring(1))) return true;
            if (selector.startsWith("#") && stack.is(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM, ResourceLocation.parse(selector.substring(1))))) return true;
            if (!selector.startsWith("#") && !selector.startsWith("@") && id.toString().equals(selector)) return true;
        }
        return false;
    }

    public String validate() {
        if (start < 0 || start > 99 || stop < 1 || stop > 100 || start >= stop) return "Invalid charge thresholds";
        if (rate < 1 || interval < 1 || interval > 200) return "Invalid rate or interval";
        if (reserve < 0 || (reserveType == Reserve.PERCENT && reserve > 100)) return "Invalid reserve";
        if (categories == null || categoryOrder == null || categoryOrder.size() != Category.values().length || new LinkedHashSet<>(categoryOrder).size() != Category.values().length) return "Invalid category order";
        if (filters.size() > 256 || priorities.size() > 256) return "Too many selectors";
        if (!filters.stream().allMatch(ChargerSettings::validSelector) || !priorities.stream().allMatch(ChargerSettings::validSelector)) return "Invalid selector";
        return null;
    }

    public static String validateNetwork(CompoundTag tag) {
        if (tag.getInt("schemaVersion") != 1) return "Invalid settings version";
        if ((tag.getInt("categories") & ~31) != 0 || tag.getInt("revision") < 0) return "Invalid settings";
        if (!known(Distribution.class, tag.getString("distribution")) || !known(SourceOrder.class, tag.getString("sourceOrder"))
                || !known(Reserve.class, tag.getString("reserveType")) || !known(Filter.class, tag.getString("filter"))
                || !known(Hud.class, tag.getString("hud"))) return "Invalid settings";
        return load(tag).validate();
    }

    private static <T extends Enum<T>> boolean known(Class<T> type, String name) {
        try { Enum.valueOf(type, name); return true; } catch (IllegalArgumentException ex) { return false; }
    }

    public CompoundTag save() {
        CompoundTag nbt = new CompoundTag();
        nbt.putInt("schemaVersion", 1);
        nbt.putBoolean("enabled", enabled);
        nbt.putInt("categories", categories.stream().mapToInt(c -> 1 << c.ordinal()).reduce(0, (a, b) -> a | b));
        nbt.putString("categoryOrder", String.join(",", categoryOrder.stream().map(Enum::name).toList()));
        nbt.putInt("start", start);
        nbt.putInt("stop", stop);
        nbt.putLong("rate", rate);
        nbt.putInt("interval", interval);
        nbt.putString("distribution", distribution.name());
        nbt.putString("sourceOrder", sourceOrder.name());
        nbt.putString("reserveType", reserveType.name());
        nbt.putLong("reserve", reserve);
        nbt.putString("filter", filter.name());
        nbt.put("filters", strings(filters));
        nbt.put("priorities", strings(priorities));
        nbt.putString("hud", hud.name());
        nbt.putInt("revision", revision);
        return nbt;
    }

    private static ListTag strings(List<String> values) {
        ListTag tag = new ListTag();
        values.forEach(s -> tag.add(StringTag.valueOf(s)));
        return tag;
    }

    private static List<String> readStrings(CompoundTag tag, String key) {
        List<String> out = new ArrayList<>();
        ListTag list = tag.getList(key, Tag.TAG_STRING);
        for (int i = 0; i < Math.min(list.size(), 257); i++) out.add(list.getString(i));
        return out;
    }

    private static <T extends Enum<T>> T readEnum(Class<T> type, String name, T fallback) {
        try { return Enum.valueOf(type, name); } catch (IllegalArgumentException e) { return fallback; }
    }

    public static ChargerSettings load(CompoundTag tag) {
        ChargerSettings s = new ChargerSettings();
        if (tag.contains("enabled")) s.enabled = tag.getBoolean("enabled");
        if (tag.contains("categories")) {
            s.categories = EnumSet.noneOf(Category.class);
            int bits = tag.getInt("categories");
            for (Category c : Category.values()) if ((bits & (1 << c.ordinal())) != 0) s.categories.add(c);
        }
        if (tag.contains("categoryOrder")) {
            List<Category> order = new ArrayList<>();
            for (String name : tag.getString("categoryOrder").split(",")) {
                try { order.add(Category.valueOf(name)); } catch (IllegalArgumentException ignored) { }
            }
            s.categoryOrder = order;
        }
        if (tag.contains("start")) s.start = tag.getInt("start");
        if (tag.contains("stop")) s.stop = tag.getInt("stop");
        if (tag.contains("rate")) s.rate = tag.getLong("rate");
        if (tag.contains("interval")) s.interval = tag.getInt("interval");
        s.distribution = readEnum(Distribution.class, tag.getString("distribution"), s.distribution);
        s.sourceOrder = readEnum(SourceOrder.class, tag.getString("sourceOrder"), s.sourceOrder);
        s.reserveType = readEnum(Reserve.class, tag.getString("reserveType"), s.reserveType);
        if (tag.contains("reserve")) s.reserve = tag.getLong("reserve");
        s.filter = readEnum(Filter.class, tag.getString("filter"), s.filter);
        s.filters = readStrings(tag, "filters");
        s.priorities = readStrings(tag, "priorities");
        s.hud = readEnum(Hud.class, tag.getString("hud"), s.hud);
        s.revision = tag.getInt("revision");
        return s;
    }
}
