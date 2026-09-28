package net.okakatsuo.mekanisminventorycharger;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class ChargerNetwork {
    public record Packet(String action, CompoundTag data) implements CustomPacketPayload {
        public Packet { if (data == null) data = new CompoundTag(); }
        public static final Type<Packet> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MekanismInventoryCharger.MOD_ID, "to_server"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Packet> CODEC = StreamCodec.of(
                (buf, packet) -> { buf.writeUtf(packet.action, 32); buf.writeNbt(packet.data); },
                buf -> new Packet(buf.readUtf(32), buf.readNbt()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ClientPacket(String action, CompoundTag data) implements CustomPacketPayload {
        public ClientPacket { if (data == null) data = new CompoundTag(); }
        public static final Type<ClientPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MekanismInventoryCharger.MOD_ID, "to_client"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ClientPacket> CODEC = StreamCodec.of(
                (buf, packet) -> { buf.writeUtf(packet.action, 32); buf.writeNbt(packet.data); },
                buf -> new ClientPacket(buf.readUtf(32), buf.readNbt()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private ChargerNetwork() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToServer(Packet.TYPE, Packet.CODEC, ChargerNetwork::fromClient);
        registrar.playToClient(ClientPacket.TYPE, ClientPacket.CODEC, ChargerNetwork::fromServer);
    }

    private static void fromServer(ClientPacket packet, IPayloadContext context) {
        if (FMLEnvironment.dist.isClient()) ChargerClient.receive(packet);
    }

    private static void fromClient(Packet packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        switch (packet.action) {
            case "request" -> open(player);
            case "toggle" -> MekanismInventoryCharger.toggle(player, !MekanismInventoryCharger.get(player).enabled);
            case "apply" -> {
                ChargerSettings current = MekanismInventoryCharger.get(player);
                String error = ChargerSettings.validateNetwork(packet.data);
                ChargerSettings incoming = error == null ? ChargerSettings.load(packet.data) : null;
                if (incoming != null && incoming.revision != current.revision) error = "Settings changed on server. Reopen this screen.";
                if (error == null) {
                    incoming.revision++;
                    MekanismInventoryCharger.set(player, incoming);
                    PacketDistributor.sendToPlayer(player, new ClientPacket("applied", new CompoundTag()));
                } else {
                    CompoundTag reply = new CompoundTag(); reply.putString("error", error);
                    PacketDistributor.sendToPlayer(player, new ClientPacket("error", reply));
                    sync(player);
                }
            }
            default -> { }
        }
    }

    public static void open(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new ClientPacket("open", MekanismInventoryCharger.get(player).save()));
    }

    public static void sync(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new ClientPacket("sync", MekanismInventoryCharger.get(player).save()));
        if (!MekanismInventoryCharger.get(player).enabled) status(player, 0, 0, "", 0, 0);
    }

    public static void status(ServerPlayer player, long rate, int charged, String source, int percent, int extra) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("rate", rate); tag.putInt("charged", charged); tag.putString("source", source);
        tag.putInt("percent", percent); tag.putInt("extra", extra);
        PacketDistributor.sendToPlayer(player, new ClientPacket("status", tag));
    }
}
