package net.okakatsuo.mekanisminventorycharger;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

@Mod(MekanismInventoryCharger.MOD_ID)
public final class MekanismInventoryCharger {
    public static final String MOD_ID = "mekanism_inventory_charger";
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, MOD_ID);
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<ChargerSettings>> SETTINGS = ATTACHMENTS.register("settings", () ->
            AttachmentType.builder(ChargerSettings::new).serialize(new IAttachmentSerializer<CompoundTag, ChargerSettings>() {
                @Override public ChargerSettings read(IAttachmentHolder holder, CompoundTag tag, HolderLookup.Provider provider) { return ChargerSettings.load(tag); }
                @Override public CompoundTag write(ChargerSettings settings, HolderLookup.Provider provider) { return settings.save(); }
            }).copyOnDeath().build());

    public MekanismInventoryCharger(IEventBus bus) {
        ATTACHMENTS.register(bus);
        bus.addListener(ChargerNetwork::register);
        if (FMLEnvironment.dist.isClient()) ChargerClient.init(bus);
        NeoForge.EVENT_BUS.addListener(MekanismInventoryCharger::tick);
        NeoForge.EVENT_BUS.addListener(MekanismInventoryCharger::commands);
        NeoForge.EVENT_BUS.addListener(MekanismInventoryCharger::login);
        NeoForge.EVENT_BUS.addListener(MekanismInventoryCharger::respawn);
        NeoForge.EVENT_BUS.addListener(MekanismInventoryCharger::dimension);
        NeoForge.EVENT_BUS.addListener(MekanismInventoryCharger::logout);
    }

    public static ChargerSettings get(ServerPlayer player) { return player.getData(SETTINGS.get()); }

    public static void set(ServerPlayer player, ChargerSettings settings) {
        player.setData(SETTINGS.get(), settings);
        ChargeEngine.clear(player);
        ChargerNetwork.sync(player);
    }

    private static void tick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player) ChargeEngine.tick(player);
    }

    private static void login(PlayerEvent.PlayerLoggedInEvent event) { syncPlayer(event.getEntity()); }
    private static void respawn(PlayerEvent.PlayerRespawnEvent event) { syncPlayer(event.getEntity()); }
    private static void dimension(PlayerEvent.PlayerChangedDimensionEvent event) { syncPlayer(event.getEntity()); }
    private static void logout(PlayerEvent.PlayerLoggedOutEvent event) { if (event.getEntity() instanceof ServerPlayer server) ChargeEngine.clear(server); }
    private static void syncPlayer(Player player) { if (player instanceof ServerPlayer server) ChargerNetwork.sync(server); }

    private static void commands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("mic")
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource(), ctx.getSource().getPlayerOrException()))
                        .then(Commands.argument("player", EntityArgument.player()).requires(s -> s.hasPermission(2))
                                .executes(ctx -> status(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))))
                .then(Commands.literal("enable").executes(ctx -> toggle(ctx.getSource().getPlayerOrException(), true))
                        .then(Commands.argument("player", EntityArgument.player()).requires(s -> s.hasPermission(2))
                                .executes(ctx -> toggle(EntityArgument.getPlayer(ctx, "player"), true))))
                .then(Commands.literal("disable").executes(ctx -> toggle(ctx.getSource().getPlayerOrException(), false))
                        .then(Commands.argument("player", EntityArgument.player()).requires(s -> s.hasPermission(2))
                                .executes(ctx -> toggle(EntityArgument.getPlayer(ctx, "player"), false))))
                .then(Commands.literal("config").executes(ctx -> { ChargerNetwork.open(ctx.getSource().getPlayerOrException()); return 1; })));
    }

    private static int status(CommandSourceStack source, ServerPlayer player) {
        source.sendSuccess(() -> Component.literal(player.getGameProfile().getName() + ": ").append(Component.translatable("message.mekanism_inventory_charger.status", Component.translatable("message.mekanism_inventory_charger." + (get(player).enabled ? "on" : "off")))), false);
        return 1;
    }

    static int toggle(ServerPlayer player, boolean enabled) {
        ChargerSettings s = get(player).copy();
        s.enabled = enabled;
        s.revision++;
        set(player, s);
        player.displayClientMessage(Component.translatable("message.mekanism_inventory_charger.status", Component.translatable("message.mekanism_inventory_charger." + (enabled ? "on" : "off"))), true);
        return 1;
    }
}
