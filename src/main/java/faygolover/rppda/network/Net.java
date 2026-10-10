package faygolover.rppda.network;

import faygolover.rppda.RpPda;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Сеть КПК — два общих пакета: действие игрока серверу ({@link Action}: имя + данные) и ответ/событие клиенту
 * ({@link View}). Сервер проверяет каждое действие сам; клиент только показывает.
 */
public final class Net {
    private Net() {}

    private static final String PROTOCOL = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(new ResourceLocation(RpPda.MODID, "main"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++, Action.class, Action::encode, Action::decode, Action::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, View.class, View::encode, View::decode, View::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    public static void send(ServerPlayer sp, String op, CompoundTag data) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp), new View(op, data));
    }

    public static void toServer(String op, CompoundTag data) {
        CHANNEL.sendToServer(new Action(op, data));
    }

    public record Action(String op, CompoundTag data) {
        void encode(FriendlyByteBuf buf) {
            buf.writeUtf(op, 64);
            buf.writeNbt(data);
        }

        static Action decode(FriendlyByteBuf buf) {
            String op = buf.readUtf(64);
            CompoundTag t = buf.readNbt();
            return new Action(op, t == null ? new CompoundTag() : t);
        }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            ServerPlayer sp = c.getSender();
            c.enqueueWork(() -> {
                if (sp != null) faygolover.rppda.server.Actions.handle(sp, op, data);
            });
            c.setPacketHandled(true);
        }
    }

    public record View(String op, CompoundTag data) {
        void encode(FriendlyByteBuf buf) {
            buf.writeUtf(op, 64);
            buf.writeNbt(data);
        }

        static View decode(FriendlyByteBuf buf) {
            String op = buf.readUtf(64);
            CompoundTag t = buf.readNbt();
            return new View(op, t == null ? new CompoundTag() : t);
        }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> faygolover.rppda.client.ClientPda.receive(op, data)));
            c.setPacketHandled(true);
        }
    }
}
