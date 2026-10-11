package faygolover.rppda.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import faygolover.rpcore.api.RpCoreAPI;
import faygolover.rppda.RpPda;
import faygolover.rppda.config.PdaConfig;
import faygolover.rppda.data.Account;
import faygolover.rppda.data.Chat;
import faygolover.rppda.data.PdaStore;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Чат игры. Обычный чат — речь: слышно рядом ({@code chat_radius}), «~текст» — шёпот, «!текст» — крик; админы
 * слышат всех. В общий чат КПК пишут только из КПК ({@code chat_to_pda_global} возвращает старое поведение).
 * {@code /tell} ({@code /msg}, {@code /w}): по ID учётки — в КПК (если диалог уже есть), по нику — только админу
 * и от админа. {@code /me} — действие рядом. Перехват на входе (а не по типу сообщения): No Chat Reports делает
 * сообщения игроков «системными». Системные сообщения и {@code tellraw} не трогаются.
 */
public final class ChatIntercept {
    private ChatIntercept() {}

    enum Speech { NORMAL, WHISPER, SHOUT }

    public static void registerEvents(IEventBus bus) {
        bus.addListener(EventPriority.LOW, ChatIntercept::onChat);
        bus.addListener(ChatIntercept::onCommands);
        bus.addListener(PdaCommands::register);
    }

    private static void onChat(ServerChatEvent e) {
        if (PdaServer.store == null || e.isCanceled()) return;
        e.setCanceled(true);
        ServerPlayer sp = e.getPlayer();
        String text = e.getRawText().strip();
        if (text.isEmpty()) return;
        if (RpCoreAPI.unconscious(sp)) {
            sp.sendSystemMessage(Component.translatable("rppda.err.unconscious").withStyle(ChatFormatting.RED));
            return;
        }
        if (PdaConfig.chatToPda) {
            sayToPda(sp, text);
            return;
        }
        Speech kind = Speech.NORMAL;
        if (text.startsWith("!")) kind = Speech.SHOUT;
        else if (text.startsWith("~")) kind = Speech.WHISPER;
        if (kind != Speech.NORMAL) text = text.substring(1).strip();
        if (!text.isEmpty()) speak(sp, text, kind);
    }

    /** Старое поведение: обычный чат — в общий чат КПК (без КПК — речь рядом). */
    private static void sayToPda(ServerPlayer sp, String text) {
        Account acc = active(sp);
        if (acc == null) {
            speak(sp, text, Speech.NORMAL);
            return;
        }
        Chat c = PdaServer.store.global();
        String err = Messaging.check(acc, c, text, sp);
        if (err != null) {
            sp.sendSystemMessage(Component.translatable(err).withStyle(ChatFormatting.RED));
            return;
        }
        Messaging.post(c, acc, text, 0, 0, null, null);
    }

    // ---------------------------------------------------------------- речь

    /** Речь рядом: каждый слышит по-своему (шёпот издалека — обрывками). Админ слышит всё и видит, как далеко. */
    static void speak(ServerPlayer sp, String text, Speech kind) {
        Component name = sp.getDisplayName();
        for (ServerPlayer p : sp.server.getPlayerList().getPlayers()) {
            boolean same = p.level() == sp.level();
            double d = same ? Math.sqrt(p.distanceToSqr(sp)) : Double.MAX_VALUE;
            Component line = p == sp ? full(kind, name, text) : heard(kind, name, text, d, p.getRandom());
            if (p != sp && PdaServer.isAdmin(p) && (line == null || d > radius(kind))) {
                line = Component.empty().append(far(d, same, sp)).append(full(kind, name, text));
            }
            if (line != null) p.sendSystemMessage(line);
        }
    }

    static int radius(Speech kind) {
        return switch (kind) {
            case WHISPER -> PdaConfig.whisperRadius;
            case SHOUT -> PdaConfig.shoutRadius;
            default -> PdaConfig.chatRadius;
        };
    }

    static MutableComponent full(Speech kind, Component name, String text) {
        return switch (kind) {
            case WHISPER -> Component.translatable("rppda.speech.whisper", name, text).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
            case SHOUT -> Component.translatable("rppda.speech.shout", name, text).withStyle(ChatFormatting.YELLOW);
            default -> Component.translatable("rppda.speech.say", name, text);
        };
    }

    /** Что слышно на расстоянии {@code d} (null — ничего). */
    @Nullable
    static MutableComponent heard(Speech kind, Component name, String text, double d, RandomSource rnd) {
        double r = radius(kind);
        if (d <= r) return full(kind, name, text);
        if (kind == Speech.WHISPER) {
            if (d <= r * 7 / 6) return full(kind, name, garble(text, 0.35, rnd));
            if (d <= r * 4 / 3) return full(kind, name, garble(text, 0.65, rnd));
            if (d <= r * 5 / 3) return Component.translatable("rppda.speech.whisper_far", name).withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC);
            return null;
        }
        if (kind == Speech.SHOUT && d <= r * 1.5) return Component.translatable("rppda.speech.shout_far").withStyle(ChatFormatting.GOLD, ChatFormatting.ITALIC);
        return null;
    }

    /**
     * Плохо слышно: часть слов пропадает, подряд пропавшие — одно «...». Длина фразы не сохраняется — так и
     * задумано (по длине не угадать, сколько не расслышал).
     */
    static String garble(String text, double drop, RandomSource rnd) {
        String[] words = text.split("\\s+");
        boolean[] lost = new boolean[words.length];
        int lostN = 0;
        for (int i = 0; i < words.length; i++) {
            lost[i] = rnd.nextDouble() < drop;
            if (lost[i]) lostN++;
        }
        if (lostN == 0) lost[rnd.nextInt(words.length)] = true;
        StringBuilder sb = new StringBuilder();
        boolean gap = false;
        for (int i = 0; i < words.length; i++) {
            if (lost[i]) {
                if (!gap) sb.append(sb.length() == 0 ? "..." : " ...");
                gap = true;
            } else {
                sb.append(sb.length() == 0 ? "" : " ").append(words[i]);
                gap = false;
            }
        }
        return sb.toString();
    }

    private static Component far(double d, boolean same, ServerPlayer from) {
        String s = same ? "[" + (int) d + " м] " : "[" + from.level().dimension().location().getPath() + "] ";
        return Component.literal(s).withStyle(ChatFormatting.DARK_GRAY);
    }

    @Nullable
    private static Account active(ServerPlayer sp) {
        Integer id = PdaServer.activeAccount(sp);
        return id == null || PdaServer.store == null ? null : PdaServer.store.account(id);
    }

    // ---------------------------------------------------------------- команды

    /**
     * {@code msg} ({@code tell}, {@code w}) и {@code me} — свои вместо ванильных: адресат {@code /tell} — ник или
     * ID учётки, поэтому ванильные узлы убираются (у Brigadier нет удаления — через отражение; не вышло — свои
     * ветки сливаются с ванильными, работают только ники).
     */
    private static void onCommands(RegisterCommandsEvent e) {
        CommandDispatcher<CommandSourceStack> d = e.getDispatcher();
        boolean removed = remove(d.getRoot(), "msg", "tell", "w", "me");
        if (!removed) RpPda.LOGGER.warn("RP PDA: не убрать ванильные /msg и /me — /tell по ID учётки работать не будет");
        LiteralCommandNode<CommandSourceStack> msg = d.register(Commands.literal("msg")
                .then(Commands.argument("target", StringArgumentType.word()).suggests(ChatIntercept::suggestTargets)
                        .then(Commands.argument("message", StringArgumentType.greedyString()).executes(ChatIntercept::tell))));
        d.register(Commands.literal("tell").redirect(msg));
        d.register(Commands.literal("w").redirect(msg));
        d.register(Commands.literal("me").then(Commands.argument("action", StringArgumentType.greedyString())
                .executes(ChatIntercept::me)));
    }

    @SuppressWarnings("unchecked")
    private static boolean remove(CommandNode<CommandSourceStack> root, String... names) {
        try {
            Field children = CommandNode.class.getDeclaredField("children");
            Field literals = CommandNode.class.getDeclaredField("literals");
            children.setAccessible(true);
            literals.setAccessible(true);
            Map<String, ?> c = (Map<String, ?>) children.get(root);
            Map<String, ?> l = (Map<String, ?>) literals.get(root);
            for (String n : names) {
                c.remove(n);
                l.remove(n);
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return false;
        }
    }

    /** Подсказки адресата: админам — ники; игрокам — админы в сети и ID тех, с кем уже есть диалог. */
    private static CompletableFuture<Suggestions> suggestTargets(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder b) {
        ServerPlayer sp = ctx.getSource().getPlayer();
        List<String> out = new ArrayList<>();
        PdaStore store = PdaServer.store;
        boolean names = sp == null || PdaServer.isAdmin(sp) || PdaConfig.tellByName;
        for (ServerPlayer p : ctx.getSource().getServer().getPlayerList().getPlayers()) {
            if (p != sp && (names || PdaServer.isAdmin(p))) out.add(p.getGameProfile().getName());
        }
        if (sp != null && store != null) {
            Account a = active(sp);
            if (a != null) for (int id : store.chatsOf(a.id)) {
                Chat c = store.chat(id);
                if (c != null && c.type == Chat.Type.DM && c.lastSeq > 0 && c.peer(a.id) != 0) out.add(String.format("%08d", c.peer(a.id)));
            }
        }
        return SharedSuggestionProvider.suggest(out, b);
    }

    private static int me(CommandContext<CommandSourceStack> ctx) {
        String text = StringArgumentType.getString(ctx, "action").strip();
        ServerPlayer sp = ctx.getSource().getPlayer();
        if (sp == null || PdaServer.store == null) {
            ctx.getSource().getServer().getPlayerList().broadcastSystemMessage(
                    Component.literal("* ").append(ctx.getSource().getDisplayName()).append(" " + text), false);
            return 1;
        }
        if (RpCoreAPI.unconscious(sp)) {
            sp.sendSystemMessage(Component.translatable("rppda.err.unconscious").withStyle(ChatFormatting.RED));
            return 0;
        }
        // Действие видно глазами — в обычный чат тем, кто рядом (и без КПК тоже), а не в КПК.
        Component line = Component.literal("* ").append(sp.getDisplayName()).append(" " + text)
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
        double r = PdaConfig.meRadius;
        for (ServerPlayer p : sp.serverLevel().players()) if (p.distanceToSqr(sp) <= r * r) p.sendSystemMessage(line);
        return 1;
    }

    private static int tell(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        String target = StringArgumentType.getString(ctx, "target");
        String text = StringArgumentType.getString(ctx, "message").strip();
        if (text.isEmpty() || PdaServer.store == null) return 0;
        ServerPlayer sp = src.getPlayer();
        if (target.matches("\\d{8}")) return tellId(src, sp, Integer.parseInt(target), text);
        return tellName(src, sp, target, text);
    }

    /** {@code /tell <ID>}: в КПК (в чат игры не пишется), только если диалог уже начат. */
    private static int tellId(CommandSourceStack src, @Nullable ServerPlayer sp, int id, String text) {
        PdaStore store = PdaServer.store;
        Account to = store.account(id);
        if (to == null || to.kind == Account.Kind.SYSTEM) return fail(src, "rppda.err.no_account");
        if (sp == null) {
            // Консоль и командные блоки пишут от Системы.
            Messaging.post(store.dm(0, to.id), store.system(), text, 0, 0, src.getTextName(), null);
            return 1;
        }
        if (RpCoreAPI.unconscious(sp)) return fail(src, "rppda.err.unconscious");
        Account from = active(sp);
        if (from == null) {
            if (!PdaServer.isAdmin(sp)) return fail(src, "rppda.err.need_pda");
            Messaging.post(store.dm(0, to.id), store.system(), text, 0, 0, sp.getGameProfile().getName(), null);
            sp.displayClientMessage(Component.translatable("rppda.msg.sent", to.name).withStyle(ChatFormatting.GRAY), true);
            return 1;
        }
        if (from.id == to.id) return 0;
        Chat c = store.findDm(from.id, to.id);
        if (c == null || c.lastSeq == 0) {
            if (PdaConfig.tellRequiresDialog) return fail(src, "rppda.err.no_dialog");
            c = store.dm(from.id, to.id);
        }
        String err = Messaging.check(from, c, text, sp);
        if (err != null) return fail(src, err.equals("rppda.err.blocked_by") ? "rppda.err.not_delivered" : err);
        Messaging.post(c, from, text, 0, 0, null, null);
        sp.displayClientMessage(Component.translatable("rppda.msg.sent", Views.nameFor(store, from, to.id)).withStyle(ChatFormatting.GRAY), true);
        return 1;
    }

    /** {@code /tell <ник>}: админ — любому (сообщение ГМа, не в КПК); игрок — только админу; иначе — по конфигу. */
    private static int tellName(CommandSourceStack src, @Nullable ServerPlayer sp, String name, String text) {
        ServerPlayer t = src.getServer().getPlayerList().getPlayerByName(name);
        if (t == null) return fail(src, "rppda.err.offline_player");
        if (sp == null) {
            t.sendSystemMessage(Component.translatable("rppda.tell.gm_in", src.getDisplayName(), text).withStyle(ChatFormatting.GOLD));
            return 1;
        }
        if (t == sp) return 0;
        boolean senderAdmin = PdaServer.isAdmin(sp), targetAdmin = PdaServer.isAdmin(t);
        if (senderAdmin) {
            t.sendSystemMessage(Component.translatable("rppda.tell.gm_in", sp.getDisplayName(), text).withStyle(ChatFormatting.GOLD));
            sp.sendSystemMessage(Component.translatable("rppda.tell.gm_out", t.getDisplayName(), text).withStyle(ChatFormatting.GOLD));
            return 1;
        }
        if (targetAdmin) return toAdmin(sp, t, text);
        if (RpCoreAPI.unconscious(sp)) return fail(src, "rppda.err.unconscious");
        if (!PdaConfig.tellByName) return fail(src, "rppda.err.tell_by_id");
        Account from = active(sp);
        Account to = PdaServer.accountFor(t.getUUID());
        if (from == null) return fail(src, "rppda.err.need_pda");
        if (to == null) return fail(src, "rppda.err.target_no_pda_name");
        if (to.id == from.id) return 0;
        Chat c = PdaServer.store.dm(from.id, to.id);
        String err = Messaging.check(from, c, text, sp);
        if (err != null) return fail(src, err);
        Messaging.post(c, from, text, 0, 0, null, null);
        sp.displayClientMessage(Component.translatable("rppda.msg.sent", Views.nameFor(PdaServer.store, from, to.id)).withStyle(ChatFormatting.GRAY), true);
        return 1;
    }

    /**
     * Игрок пишет админу — всегда, даже без КПК, без связи и без сознания. Есть учётка — в диалог с учёткой админа
     * (или с Системой); нет — сообщение ждёт первого КПК игрока. Админ видит строку в чате.
     */
    private static int toAdmin(ServerPlayer sp, ServerPlayer admin, String text) {
        PdaStore store = PdaServer.store;
        Account mine = active(sp);
        if (mine == null) mine = PdaServer.accountFor(sp.getUUID());
        if (mine != null) {
            Account adminAcc = PdaServer.accountFor(admin.getUUID());
            Account to = adminAcc == null || adminAcc.id == mine.id ? store.system() : adminAcc;
            Chat c = store.dm(mine.id, to.id);
            String err = Messaging.check(mine, c, text, null);
            if (err != null) {
                sp.sendSystemMessage(Component.translatable(err).withStyle(ChatFormatting.RED));
                return 0;
            }
            Messaging.post(c, mine, text, 0, 0, null, admin);
        } else {
            store.addPending(sp.getUUID(), new PdaStore.Pending(admin.getUUID(), System.currentTimeMillis(), text), PdaConfig.pendingLimit);
            admin.sendSystemMessage(Component.translatable("rppda.chat.no_pda").withStyle(ChatFormatting.DARK_GRAY).append(" ")
                    .append(Component.translatable("commands.message.display.incoming", sp.getDisplayName(), text)
                            .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)));
        }
        sp.sendSystemMessage(Component.translatable("commands.message.display.outgoing", admin.getDisplayName(), text)
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        return 1;
    }

    private static int fail(CommandSourceStack src, String key) {
        src.sendFailure(Component.translatable(key));
        return 0;
    }
}
