package faygolover.rppda.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import faygolover.rppda.config.PdaConfig;
import faygolover.rppda.data.Account;
import faygolover.rppda.data.Chat;
import faygolover.rppda.data.Message;
import faygolover.rppda.data.PdaStore;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.MessageArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/**
 * Чат игры → КПК. Обычный чат идёт в общий чат КПК, {@code /tell} ({@code /msg}, {@code /w}) — в личные,
 * {@code /me} — действием в общий. Перехват на входе (а не по типу сообщения): No Chat Reports делает сообщения
 * игроков «системными», и отличить их потом нельзя. Системные сообщения и {@code tellraw} не трогаются.
 */
public final class ChatIntercept {
    private ChatIntercept() {}

    public static void registerEvents(IEventBus bus) {
        bus.addListener(EventPriority.LOW, ChatIntercept::onChat);
        bus.addListener(ChatIntercept::onCommands);
        bus.addListener(PdaCommands::register);
    }

    private static void onChat(ServerChatEvent e) {
        if (PdaServer.store == null || e.isCanceled()) return;
        e.setCanceled(true);
        say(e.getPlayer(), e.getRawText(), false);
    }

    /** Строка игрока в общий чат: с КПК — в общий чат КПК; без — видят админы (или все, если скрытие выключено). */
    static void say(ServerPlayer sp, String text, boolean action) {
        PdaStore store = PdaServer.store;
        Account acc = active(sp);
        if (acc != null) {
            Chat c = store.global();
            String err = Messaging.check(acc, c, text, sp);
            if (err != null) {
                sp.sendSystemMessage(Component.translatable(err).withStyle(ChatFormatting.RED));
                return;
            }
            Messaging.post(c, acc, text, 0, action ? Message.ACTION : 0, null, null);
            return;
        }
        MutableComponent line = action
                ? Component.literal("* ").append(sp.getDisplayName()).append(" " + text).withStyle(ChatFormatting.ITALIC)
                : Component.translatable("chat.type.text", sp.getDisplayName(), text);
        if (PdaServer.isAdmin(sp) || !PdaConfig.hideWithoutPda) {
            sp.server.getPlayerList().broadcastSystemMessage(line, false);
            return;
        }
        MutableComponent forAdmins = Component.translatable("rppda.chat.no_pda").withStyle(ChatFormatting.DARK_GRAY)
                .append(" ").append(line.copy().withStyle(ChatFormatting.GRAY));
        for (ServerPlayer p : sp.server.getPlayerList().getPlayers()) {
            if (p != sp && PdaServer.isAdmin(p)) p.sendSystemMessage(forAdmins);
        }
        sp.sendSystemMessage(Component.translatable("rppda.chat.only_admins").withStyle(ChatFormatting.DARK_GRAY)
                .append(" ").append(line.copy().withStyle(ChatFormatting.GRAY)));
    }

    @Nullable
    private static Account active(ServerPlayer sp) {
        Integer id = PdaServer.activeAccount(sp);
        return id == null || PdaServer.store == null ? null : PdaServer.store.account(id);
    }

    /**
     * Ветки {@code msg} и {@code me} регистрируются поверх ванильных: Brigadier сливает узлы с тем же именем и
     * заменяет команду на листе. {@code tell} и {@code w} — перенаправления на {@code msg}, их тоже касается.
     */
    private static void onCommands(RegisterCommandsEvent e) {
        CommandDispatcher<CommandSourceStack> d = e.getDispatcher();
        d.register(Commands.literal("msg").then(Commands.argument("targets", EntityArgument.players())
                .then(Commands.argument("message", MessageArgument.message()).executes(ChatIntercept::tell))));
        d.register(Commands.literal("me").then(Commands.argument("action", MessageArgument.message())
                .executes(ChatIntercept::me)));
    }

    private static int me(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String text = MessageArgument.getMessage(ctx, "action").getString();
        ServerPlayer sp = ctx.getSource().getPlayer();
        if (sp == null || PdaServer.store == null) {
            ctx.getSource().getServer().getPlayerList().broadcastSystemMessage(
                    Component.literal("* ").append(ctx.getSource().getDisplayName()).append(" " + text), false);
            return 1;
        }
        // Действие видно глазами — в обычный чат тем, кто рядом (и без КПК тоже), а не в КПК.
        Component line = Component.literal("* ").append(sp.getDisplayName()).append(" " + text)
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
        double r = PdaConfig.meRadius;
        for (ServerPlayer p : sp.serverLevel().players()) if (p.distanceToSqr(sp) <= r * r) p.sendSystemMessage(line);
        return 1;
    }

    private static int tell(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
        String text = MessageArgument.getMessage(ctx, "message").getString();
        ServerPlayer sp = src.getPlayer();
        PdaStore store = PdaServer.store;
        if (sp == null || store == null) {
            for (ServerPlayer t : targets) whisper(src.getDisplayName(), t, text);
            return targets.size();
        }
        Account acc = active(sp);
        boolean senderAdmin = PdaServer.isAdmin(sp);
        int sent = 0;
        for (ServerPlayer t : targets) {
            if (t == sp) continue;
            boolean targetAdmin = PdaServer.isAdmin(t);
            Account to = PdaServer.accountFor(t.getUUID());
            if (acc == null) {
                // Без КПК: админ пишет как обычно; игрок — только админу.
                if (senderAdmin || (!targetAdmin && !PdaConfig.hideWithoutPda)) {
                    whisper(sp.getDisplayName(), t, text);
                    echo(sp, t, text);
                    sent++;
                    continue;
                }
                if (!targetAdmin) {
                    sp.sendSystemMessage(Component.translatable("rppda.err.tell_admins_only").withStyle(ChatFormatting.RED));
                    continue;
                }
                Account mine = PdaServer.accountFor(sp.getUUID());
                if (mine != null) {
                    if (dm(sp, mine, to == null ? store.system() : to, text, t)) sent++;
                    continue;
                }
                store.addPending(sp.getUUID(), new PdaStore.Pending(t.getUUID(), System.currentTimeMillis(), text),
                        PdaConfig.pendingLimit);
                t.sendSystemMessage(Component.translatable("rppda.chat.no_pda").withStyle(ChatFormatting.DARK_GRAY).append(" ")
                        .append(Component.translatable("commands.message.display.incoming", sp.getDisplayName(), text)
                                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)));
                echo(sp, t, text);
                sent++;
                continue;
            }
            if (to == null) {
                if (targetAdmin) {
                    if (dm(sp, acc, store.system(), text, t)) sent++;
                } else sp.sendSystemMessage(Component.translatable("rppda.err.target_no_pda", t.getDisplayName())
                        .withStyle(ChatFormatting.RED));
                continue;
            }
            if (to.id == acc.id) continue;
            if (dm(sp, acc, to, text, targetAdmin ? t : null)) sent++;
        }
        return sent;
    }

    private static boolean dm(ServerPlayer sp, Account from, Account to, String text, @Nullable ServerPlayer forceLine) {
        Chat c = PdaServer.store.dm(from.id, to.id);
        Integer held = PdaServer.activeAccount(sp);
        String err = Messaging.check(from, c, text, held != null && held == from.id ? sp : null);
        if (err != null) {
            sp.sendSystemMessage(Component.translatable(err).withStyle(ChatFormatting.RED));
            return false;
        }
        Messaging.post(c, from, text, 0, 0, null, forceLine);
        // Отправитель без КПК при себе (заблокирован или не у него) всё равно видит, что ушло.
        Integer act = PdaServer.activeAccount(sp);
        if (act == null || act != from.id) echo(sp, forceLine, text);
        return true;
    }

    private static void whisper(Component from, ServerPlayer to, String text) {
        to.sendSystemMessage(Component.translatable("commands.message.display.incoming", from, text)
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
    }

    private static void echo(ServerPlayer sp, @Nullable ServerPlayer to, String text) {
        Component target = to == null ? Component.translatable("rppda.chat.admin") : to.getDisplayName();
        sp.sendSystemMessage(Component.translatable("commands.message.display.outgoing", target, text)
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
    }
}
