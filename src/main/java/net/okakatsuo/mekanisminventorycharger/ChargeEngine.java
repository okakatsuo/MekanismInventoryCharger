package net.okakatsuo.mekanisminventorycharger;

import mekanism.api.Action;
import mekanism.api.energy.IStrictEnergyHandler;
import mekanism.common.integration.energy.EnergyCompatUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ChargeEngine {
    private static final Logger LOG = LoggerFactory.getLogger(ChargeEngine.class);
    private static final TagKey<net.minecraft.world.item.Item> SOURCES = TagKey.create(Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath(MekanismInventoryCharger.MOD_ID, "charging_sources"));
    private static final Map<UUID, Map<String, ItemStack>> ACTIVE = new HashMap<>();
    private static final Map<UUID, Long> LAST_RATE = new HashMap<>();
    private static final Map<UUID, Long> LAST_STATUS = new HashMap<>();

    private record Target(String key, ChargerSettings.Category category, int slot, ItemStack stack, IStrictEnergyHandler energy, long cap, int rule) {}
    record Move(long extracted, long inserted) {}
    private static final class Source {
        final int slot;
        final ItemStack stack;
        final IStrictEnergyHandler energy;
        final long cap;
        final long stored;
        long budget;
        boolean used;
        Source(int slot, ItemStack stack, IStrictEnergyHandler energy, long cap, long stored, long budget) {
            this.slot = slot; this.stack = stack; this.energy = energy; this.cap = cap; this.stored = stored; this.budget = budget;
        }
    }

    private ChargeEngine() {}

    public static void tick(ServerPlayer player) {
        tick(player, true);
    }

    static void tick(ServerPlayer player, boolean sendStatus) {
        ChargerSettings settings = MekanismInventoryCharger.get(player);
        long now = player.serverLevel().getGameTime();
        if (!settings.enabled || settings.validate() != null || player.isSpectator() || !player.isAlive()) return;
        if (Math.floorMod(now + player.getUUID().hashCode(), settings.interval) != 0) return;
        List<Source> sources = sources(player, settings);
        List<Target> targets = targets(player, settings);
        long remaining = saturatedMultiply(settings.rate, settings.interval);
        long spent = 0;
        Set<String> chargedTargets = new HashSet<>();
        Source primary = null;
        if (settings.distribution == ChargerSettings.Distribution.PRIORITY) {
            targets.sort(Comparator.comparingInt(Target::rule).thenComparingInt(t -> settings.categoryOrder.indexOf(t.category())).thenComparingInt(Target::slot));
            for (Target target : targets) {
                long used = fill(target, sources, stopAmount(target.cap, settings.stop), remaining);
                if (used > 0) { spent += used; remaining -= used; chargedTargets.add(target.key()); if (primary == null) primary = firstUsedSource(sources); }
                if (remaining <= 0) break;
            }
        } else {
            targets.sort(Comparator.comparingInt(Target::slot));
            List<Target> pending = new ArrayList<>(targets);
            while (remaining > 0 && !pending.isEmpty()) {
                long before = remaining;
                int count = pending.size();
                Iterator<Target> it = pending.iterator();
                while (it.hasNext()) {
                    Target target = it.next();
                    long share = Math.max(1, remaining / count + (remaining % count == 0 ? 0 : 1));
                    long used = fill(target, sources, stopAmount(target.cap, settings.stop), Math.min(share, remaining));
                    if (used > 0) { spent += used; remaining -= used; chargedTargets.add(target.key()); if (primary == null) primary = firstUsedSource(sources); }
                    if (used < share || totalEnergy(target.energy) >= stopAmount(target.cap, settings.stop)) it.remove();
                    count--;
                    if (remaining == 0) break;
                }
                if (remaining == before) break;
            }
        }
        LAST_RATE.put(player.getUUID(), spent / Math.max(1, settings.interval));
        if (sendStatus && now - LAST_STATUS.getOrDefault(player.getUUID(), Long.MIN_VALUE / 2) >= 20) {
            ChargerNetwork.status(player, LAST_RATE.get(player.getUUID()), chargedTargets.size(), primary == null ? "" : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(primary.stack.getItem()).toString(),
                    primary == null || primary.cap <= 0 ? 0 : (int) (100.0 * totalEnergy(primary.energy) / primary.cap), Math.max(0, sources.size() - 1));
            LAST_STATUS.put(player.getUUID(), now);
        }
    }

    public static void clear(ServerPlayer player) {
        ACTIVE.remove(player.getUUID()); LAST_RATE.remove(player.getUUID()); LAST_STATUS.remove(player.getUUID());
    }

    private static Source firstUsedSource(List<Source> sources) {
        for (Source s : sources) if (s.used) return s;
        return sources.isEmpty() ? null : sources.getFirst();
    }

    private static List<Source> sources(ServerPlayer player, ChargerSettings settings) {
        List<Source> out = new ArrayList<>();
        for (int slot = 0; slot <= 36; slot++) {
            ItemStack stack = slot == 36 ? player.getOffhandItem() : player.getInventory().getItem(slot);
            if (stack.isEmpty() || !stack.is(SOURCES)) continue;
            ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (!id.getNamespace().equals("mekanism")) continue;
            IStrictEnergyHandler energy = EnergyCompatUtils.getStrictEnergyHandler(stack);
            if (energy == null) continue;
            long cap = totalCapacity(energy);
            long stored = totalEnergy(energy);
            long reserve = settings.reserveType == ChargerSettings.Reserve.PERCENT ? percent(cap, settings.reserve) : settings.reserve;
            long available = Math.max(0, stored - reserve);
            long budget = energy.extractEnergy(available, Action.SIMULATE);
            if (budget > 0) out.add(new Source(slot, stack, energy, cap, stored, budget));
        }
        Comparator<Source> bySlot = Comparator.comparingInt(s -> s.slot);
        switch (settings.sourceOrder) {
            case LOWEST_CHARGE_FIRST -> out.sort(Comparator.comparingDouble((Source s) -> s.cap == 0 ? 1 : (double) s.stored / s.cap).thenComparing(bySlot));
            case HIGHEST_CHARGE_FIRST -> out.sort(Comparator.comparingDouble((Source s) -> s.cap == 0 ? 0 : -(double) s.stored / s.cap).thenComparing(bySlot));
            case LOWEST_CAPACITY_FIRST -> out.sort(Comparator.comparingLong((Source s) -> s.cap).thenComparing(bySlot));
            case HIGHEST_CAPACITY_FIRST -> out.sort(Comparator.comparingLong((Source s) -> -s.cap).thenComparing(bySlot));
            default -> out.sort(bySlot);
        }
        return out;
    }

    private static List<Target> targets(ServerPlayer player, ChargerSettings settings) {
        List<Target> out = new ArrayList<>();
        int selected = player.getInventory().selected;
        for (int slot = 0; slot < 36; slot++) {
            ChargerSettings.Category category = slot == selected ? ChargerSettings.Category.MAIN_HAND : slot < 9 ? ChargerSettings.Category.HOTBAR : ChargerSettings.Category.INVENTORY;
            consider(out, player, settings, category, slot, player.getInventory().getItem(slot));
        }
        for (int i = 0; i < 4; i++) consider(out, player, settings, ChargerSettings.Category.ARMOR, 37 + i, player.getInventory().armor.get(3 - i));
        consider(out, player, settings, ChargerSettings.Category.OFF_HAND, 36, player.getOffhandItem());
        return out;
    }

    private static void consider(List<Target> out, ServerPlayer player, ChargerSettings settings, ChargerSettings.Category category, int slot, ItemStack stack) {
        String key = category.name() + ":" + slot;
        Map<String, ItemStack> active = ACTIVE.computeIfAbsent(player.getUUID(), ignored -> new HashMap<>());
        if (!settings.categories.contains(category) || stack.isEmpty() || stack.is(SOURCES)) { active.remove(key); return; }
        boolean match = ChargerSettings.matches(settings.filters, stack);
        if ((settings.filter == ChargerSettings.Filter.WHITELIST && !match) || (settings.filter == ChargerSettings.Filter.BLACKLIST && match)) { active.remove(key); return; }
        IStrictEnergyHandler energy = EnergyCompatUtils.getStrictEnergyHandler(stack);
        if (energy == null) { active.remove(key); return; }
        long cap = totalCapacity(energy), stored = totalEnergy(energy);
        if (cap <= 0 || stored >= stopAmount(cap, settings.stop)) { active.remove(key); return; }
        boolean continuing = active.get(key) == stack;
        if (!continuing && stored >= percent(cap, settings.start)) return;
        if (energy.insertEnergy(1, Action.SIMULATE) == 1) { active.remove(key); return; }
        active.put(key, stack);
        int rule = settings.priorities.size();
        for (int i = 0; i < settings.priorities.size(); i++) {
            if (ChargerSettings.matches(List.of(settings.priorities.get(i)), stack)) { rule = i; break; }
        }
        out.add(new Target(key, category, slot, stack, energy, cap, rule));
    }

    private static long fill(Target target, List<Source> sources, long stop, long limit) {
        long used = 0;
        for (Source source : sources) {
            long need = Math.min(limit - used, stop - totalEnergy(target.energy));
            if (need <= 0) break;
            long offer = Math.min(need, source.budget);
            if (offer <= 0) continue;
            Move move = transfer(source.energy, target.energy, offer);
            if (move.extracted > 0) source.used = true;
            source.budget = Math.max(0, source.budget - move.extracted);
            used += move.inserted;
        }
        return used;
    }

    static Move transfer(IStrictEnergyHandler source, IStrictEnergyHandler target, long offer) {
        if (offer <= 0) return new Move(0, 0);
        long accepted = offer - target.insertEnergy(offer, Action.SIMULATE);
        long take = source.extractEnergy(accepted, Action.SIMULATE);
        if (take <= 0) return new Move(0, 0);
        long extracted = source.extractEnergy(take, Action.EXECUTE);
        if (extracted <= 0) return new Move(0, 0);
        long inserted = extracted - target.insertEnergy(extracted, Action.EXECUTE);
        if (inserted < extracted) {
            long unreturned = source.insertEnergy(extracted - inserted, Action.EXECUTE);
            if (unreturned > 0) LOG.warn("Energy capability rejected {} J refund after target accepted less than simulated", unreturned);
        }
        return new Move(extracted, inserted);
    }

    private static long totalEnergy(IStrictEnergyHandler h) {
        long value = 0;
        for (int i = 0; i < h.getEnergyContainerCount(); i++) value = saturatedAdd(value, h.getEnergy(i));
        return value;
    }
    private static long totalCapacity(IStrictEnergyHandler h) {
        long value = 0;
        for (int i = 0; i < h.getEnergyContainerCount(); i++) value = saturatedAdd(value, h.getMaxEnergy(i));
        return value;
    }
    private static long saturatedAdd(long a, long b) { return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b; }
    static long saturatedMultiply(long a, long b) { return a > Long.MAX_VALUE / b ? Long.MAX_VALUE : a * b; }
    static long percent(long value, long percent) { return BigInteger.valueOf(value).multiply(BigInteger.valueOf(percent)).divide(BigInteger.valueOf(100)).longValue(); }
    static long stopAmount(long cap, int stop) { return BigInteger.valueOf(cap).multiply(BigInteger.valueOf(stop)).add(BigInteger.valueOf(99)).divide(BigInteger.valueOf(100)).longValue(); }
}
