package net.okakatsuo.mekanisminventorycharger;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

public final class ChargerClient {
    private static final KeyMapping OPEN = new KeyMapping("key.mekanism_inventory_charger.open", KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_I, "key.categories.mekanism_inventory_charger");
    private static final KeyMapping TOGGLE = new KeyMapping("key.mekanism_inventory_charger.toggle", KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_O, "key.categories.mekanism_inventory_charger");
    private static ChargerSettings settings = new ChargerSettings();
    private static long rate;
    private static int charged, percent, extra;
    private static String source = "";

    private ChargerClient() {}

    public static void init(IEventBus bus) {
        bus.addListener(ChargerClient::registerKeys);
        NeoForge.EVENT_BUS.addListener(ChargerClient::tick);
        NeoForge.EVENT_BUS.addListener(ChargerClient::render);
    }

    private static void registerKeys(RegisterKeyMappingsEvent event) { event.register(OPEN); event.register(TOGGLE); }

    private static void tick(ClientTickEvent.Post event) {
        if (Minecraft.getInstance().player == null) return;
        while (OPEN.consumeClick()) PacketDistributor.sendToServer(new ChargerNetwork.Packet("request", new net.minecraft.nbt.CompoundTag()));
        while (TOGGLE.consumeClick()) PacketDistributor.sendToServer(new ChargerNetwork.Packet("toggle", new net.minecraft.nbt.CompoundTag()));
    }

    public static void receive(ChargerNetwork.ClientPacket packet) {
        switch (packet.action()) {
            case "open" -> Minecraft.getInstance().setScreen(new ChargerScreen(ChargerSettings.load(packet.data())));
            case "sync" -> { settings = ChargerSettings.load(packet.data()); rate = 0; charged = 0; source = ""; }
            case "applied" -> { if (Minecraft.getInstance().screen instanceof ChargerScreen) Minecraft.getInstance().setScreen(null); }
            case "error" -> { if (Minecraft.getInstance().screen instanceof ChargerScreen screen) screen.setError(packet.data().getString("error")); }
            case "status" -> {
                rate = packet.data().getLong("rate"); charged = packet.data().getInt("charged");
                source = packet.data().getString("source"); percent = packet.data().getInt("percent"); extra = packet.data().getInt("extra");
            }
            default -> { }
        }
    }

    private static void render(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.isSpectator() || mc.screen != null || mc.getDebugOverlay().showDebugScreen() || settings.hud == ChargerSettings.Hud.OFF) return;
        String state = Component.translatable("hud.mekanism_inventory_charger." + (settings.enabled ? (rate > 0 ? "charging" : "idle") : "off")).getString();
        ResourceLocation texture = ResourceLocation.fromNamespaceAndPath(MekanismInventoryCharger.MOD_ID, "textures/gui/charger_settings.png");
        boolean image = mc.getResourceManager().getResource(texture).isPresent();
        if (image) event.getGuiGraphics().blit(texture, 8, 8, 304, 0, 16, 16, 512, 256);
        event.getGuiGraphics().drawString(mc.font, Component.literal((image ? "" : "⚡ ") + state + "  " + rate + " J/t"), image ? 28 : 8, 8, 0x80FFF0);
        if (settings.hud == ChargerSettings.Hud.DETAILED) {
            String name = source;
            ResourceLocation id = ResourceLocation.tryParse(source);
            if (id != null && BuiltInRegistries.ITEM.containsKey(id)) name = new ItemStack(BuiltInRegistries.ITEM.get(id)).getHoverName().getString();
            event.getGuiGraphics().drawString(mc.font, Component.literal(source.isBlank() ? Component.translatable("hud.mekanism_inventory_charger.no_source").getString() : name + " " + percent + "%" + (extra > 0 ? " +" + extra : "")), 8, 20, 0xDDDDDD);
            event.getGuiGraphics().drawString(mc.font, Component.translatable("hud.mekanism_inventory_charger.targets", charged), 8, 32, 0xDDDDDD);
        }
    }
}
