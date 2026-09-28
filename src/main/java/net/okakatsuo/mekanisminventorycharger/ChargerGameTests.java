package net.okakatsuo.mekanisminventorycharger;

import com.mojang.authlib.GameProfile;
import mekanism.api.Action;
import mekanism.api.energy.IStrictEnergyHandler;
import mekanism.common.integration.energy.EnergyCompatUtils;
import mekanism.common.item.block.ItemBlockEnergyCube;
import mekanism.common.util.StorageUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ClientInformation;

import java.util.UUID;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MekanismInventoryCharger.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChargerGameTests {
    @GameTest(template = "empty")
    public static void tabletCanExtractRealEnergy(GameTestHelper helper) {
        Item tablet = BuiltInRegistries.ITEM.get(ResourceLocation.parse("mekanism:energy_tablet"));
        ItemStack stack = new ItemStack(tablet);
        helper.assertTrue(stack.is(TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(MekanismInventoryCharger.MOD_ID, "charging_sources"))), "Energy Tablet must be in source tag");
        IStrictEnergyHandler energy = EnergyCompatUtils.getStrictEnergyHandler(stack);
        helper.assertTrue(energy != null, "Energy Tablet must expose strict energy");
        if (energy != null) {
            long accepted = 10_000 - energy.insertEnergy(10_000, Action.EXECUTE);
            long extracted = energy.extractEnergy(accepted, Action.SIMULATE);
            helper.assertTrue(accepted > 0 && extracted > 0, "Energy Tablet must accept and extract energy");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void cubeCanExtractRealEnergy(GameTestHelper helper) {
        Item cube = BuiltInRegistries.ITEM.get(ResourceLocation.parse("mekanism:basic_energy_cube"));
        ItemStack stack = new ItemStack(cube);
        helper.assertTrue(stack.is(TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(MekanismInventoryCharger.MOD_ID, "charging_sources"))), "Energy Cube must be in source tag");
        IStrictEnergyHandler energy = EnergyCompatUtils.getStrictEnergyHandler(stack);
        helper.assertTrue(energy != null, "Energy Cube must expose strict energy");
        if (energy != null) {
            long accepted = 10_000 - energy.insertEnergy(10_000, Action.EXECUTE);
            long extracted = energy.extractEnergy(accepted, Action.SIMULATE);
            helper.assertTrue(accepted > 0 && extracted > 0, "Energy Cube must accept and extract energy");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void creativeCubeOutputCanExtract(GameTestHelper helper) {
        ItemStack stack = StorageUtils.getFilledEnergyVariant(ItemBlockEnergyCube.withCreativeSideConfig(ItemBlockEnergyCube.ALL_OUTPUT));
        IStrictEnergyHandler energy = EnergyCompatUtils.getStrictEnergyHandler(stack);
        helper.assertTrue(energy != null, "Creative Cube must expose strict energy");
        if (energy != null) helper.assertTrue(energy.extractEnergy(10_000, Action.SIMULATE) > 0, "Output Creative Cube must supply energy");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void creativeCubeInputCanExtractAsItem(GameTestHelper helper) {
        ItemStack stack = StorageUtils.getFilledEnergyVariant(ItemBlockEnergyCube.withCreativeSideConfig(ItemBlockEnergyCube.ALL_INPUT));
        IStrictEnergyHandler energy = EnergyCompatUtils.getStrictEnergyHandler(stack);
        helper.assertTrue(energy != null, "Creative Cube must expose strict energy");
        if (energy != null) helper.assertTrue(energy.extractEnergy(10_000, Action.SIMULATE) > 0, "Creative Cube item must be available regardless of side config");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void playerTransferRespectsReserve(GameTestHelper helper) {
        ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), new GameProfile(UUID.randomUUID(), "charger-test"), ClientInformation.createDefault());
        ItemStack tablet = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("mekanism:energy_tablet")));
        ItemStack tool = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("mekanism:atomic_disassembler")));
        IStrictEnergyHandler source = EnergyCompatUtils.getStrictEnergyHandler(tablet);
        IStrictEnergyHandler target = EnergyCompatUtils.getStrictEnergyHandler(tool);
        helper.assertTrue(source != null && target != null, "Tablet and Atomic Disassembler need energy capabilities");
        if (source == null || target == null) return;
        helper.assertTrue(target.getMaxEnergy(0) > 0, "Target must have nonzero capacity");
        source.insertEnergy(10_000, Action.EXECUTE);
        source.insertEnergy(10_000, Action.EXECUTE);
        player.getInventory().setItem(0, tablet);
        player.getInventory().setItem(1, tool);
        ChargerSettings settings = new ChargerSettings();
        settings.enabled = true;
        settings.interval = 1;
        settings.start = 90;
        settings.rate = 5_000;
        settings.reserveType = ChargerSettings.Reserve.ABSOLUTE_JOULES;
        settings.reserve = 9_000;
        player.setData(MekanismInventoryCharger.SETTINGS.get(), settings);
        long beforeSource = source.getEnergy(0), beforeTarget = target.getEnergy(0);
        ChargeEngine.tick(player, false);
        long sourceLoss = beforeSource - source.getEnergy(0);
        long targetGain = target.getEnergy(0) - beforeTarget;
        helper.assertTrue(targetGain > 0, "Atomic Disassembler must gain energy");
        helper.assertTrue(sourceLoss == targetGain, "Source loss must equal target gain");
        helper.assertTrue(source.getEnergy(0) >= 9_000, "Source reserve must be preserved");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void distributionAndFilterModes(GameTestHelper helper) {
        ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), new GameProfile(UUID.randomUUID(), "charger-even-test"), ClientInformation.createDefault());
        ItemStack tablet = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("mekanism:energy_tablet")));
        ItemStack first = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("mekanism:atomic_disassembler")));
        ItemStack second = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("mekanism:atomic_disassembler")));
        IStrictEnergyHandler supply = EnergyCompatUtils.getStrictEnergyHandler(tablet);
        IStrictEnergyHandler a = EnergyCompatUtils.getStrictEnergyHandler(first);
        IStrictEnergyHandler b = EnergyCompatUtils.getStrictEnergyHandler(second);
        helper.assertTrue(supply != null && a != null && b != null, "Test items need energy capabilities");
        if (supply == null || a == null || b == null) return;
        supply.insertEnergy(10_000, Action.EXECUTE);
        supply.insertEnergy(10_000, Action.EXECUTE);
        player.getInventory().setItem(0, tablet);
        player.getInventory().setItem(1, first);
        player.getInventory().setItem(2, second);
        ChargerSettings settings = new ChargerSettings();
        settings.enabled = true; settings.interval = 1; settings.rate = 5_000;
        settings.distribution = ChargerSettings.Distribution.EVEN;
        settings.reserveType = ChargerSettings.Reserve.ABSOLUTE_JOULES; settings.reserve = 0;
        player.setData(MekanismInventoryCharger.SETTINGS.get(), settings);
        ChargeEngine.tick(player, false);
        helper.assertTrue(a.getEnergy(0) > 0 && b.getEnergy(0) > 0, "Even mode must charge both items");
        helper.assertTrue(Math.abs(a.getEnergy(0) - b.getEnergy(0)) <= 1, "Even mode must split energy equally");
        long firstBefore = a.getEnergy(0), secondBefore = b.getEnergy(0);
        settings.filter = ChargerSettings.Filter.BLACKLIST;
        settings.filters.add("@mekanism");
        ChargeEngine.clear(player);
        ChargeEngine.tick(player, false);
        helper.assertTrue(a.getEnergy(0) == firstBefore && b.getEnergy(0) == secondBefore, "Blacklist must exclude Mekanism targets");
        settings.filter = ChargerSettings.Filter.WHITELIST;
        a.setEnergy(0, 0); b.setEnergy(0, 0);
        ChargeEngine.clear(player);
        ChargeEngine.tick(player, false);
        helper.assertTrue(a.getEnergy(0) > 0 && b.getEnergy(0) > 0, "Whitelist must allow Mekanism targets");
        settings.filter = ChargerSettings.Filter.ALL;
        settings.distribution = ChargerSettings.Distribution.PRIORITY;
        settings.rate = 1_000;
        supply.insertEnergy(10_000, Action.EXECUTE);
        supply.insertEnergy(10_000, Action.EXECUTE);
        a.setEnergy(0, 0); b.setEnergy(0, 0);
        ChargeEngine.clear(player);
        ChargeEngine.tick(player, false);
        helper.assertTrue(a.getEnergy(0) > 0 && b.getEnergy(0) == 0, "Priority mode must charge first slot before second");
        helper.succeed();
    }
}
