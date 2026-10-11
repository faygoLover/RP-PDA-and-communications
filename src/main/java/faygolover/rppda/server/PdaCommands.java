package faygolover.rppda.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import faygolover.rppda.config.PdaConfig;
import faygolover.rppda.data.Account;
import faygolover.rppda.data.Look;
import faygolover.rppda.data.PdaStore;
import faygolover.rppda.item.ModItems;
import faygolover.rppda.item.PdaItem;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.RegisterCommandsEvent;

import java.util.Collection;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * {@code /rppda} — для ГМа и командных блоков (основное — в системном КПК):
 * <ul>
 *     <li>{@code signal player <игроки> on|off}, {@code signal pda <ID> on|off} — связь;</li>
 *     <li>{@code look <ID> style|body|screen|accent|model …} — внешний вид КПК учётки;</li>
 *     <li>{@code give <игроки> [корпус] [экран]} — новый КПК нужного вида;</li>
 *     <li>{@code gm on|off} — режим персонажа выкл/вкл (только для себя).</li>
 * </ul>
 */
public final class PdaCommands {
    private PdaCommands() {}

    static void register(RegisterCommandsEvent e) {
        CommandDispatcher<CommandSourceStack> d = e.getDispatcher();
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("rppda")
                .requires(s -> s.hasPermission(PdaConfig.adminLevel));
        root.then(Commands.literal("signal")
                .then(Commands.literal("player").then(Commands.argument("targets", EntityArgument.players())
                        .then(Commands.literal("on").executes(c -> signalPlayers(c, true)))
                        .then(Commands.literal("off").executes(c -> signalPlayers(c, false)))))
                .then(Commands.literal("pda").then(id()
                        .then(Commands.literal("on").executes(c -> signalPda(c, true)))
                        .then(Commands.literal("off").executes(c -> signalPda(c, false))))));
        root.then(Commands.literal("look").then(id()
                .then(choice("style", List.of("default", "retro", "modern"), (l, v) -> l.style = v.equals("default") ? "" : v))
                .then(choice("body", Look.BODIES, (l, v) -> l.body = v))
                .then(choice("screen", Look.SCREENS, (l, v) -> l.screen = v))
                .then(choice("accent", Look.ACCENTS, (l, v) -> l.accent = v))
                .then(Commands.literal("model")
                        .then(Commands.literal("reset").executes(c -> look(c, l -> l.model = "")))
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(c -> look(c, l -> {
                                    String n = StringArgumentType.getString(c, "name").strip();
                                    l.model = n.length() > Look.MODEL_MAX ? n.substring(0, Look.MODEL_MAX) : n;
                                }))))));
        root.then(Commands.literal("give").then(Commands.argument("targets", EntityArgument.players())
                .executes(c -> give(c, "beige", "green"))
                .then(Commands.argument("body", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(Look.BODIES, b))
                        .executes(c -> give(c, StringArgumentType.getString(c, "body"), "green"))
                        .then(Commands.argument("screen", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(Look.SCREENS, b))
                                .executes(c -> give(c, StringArgumentType.getString(c, "body"), StringArgumentType.getString(c, "screen")))))));
        root.then(Commands.literal("gm").then(Commands.argument("on", BoolArgumentType.bool()).executes(c -> {
            ServerPlayer sp = c.getSource().getPlayerOrException();
            if (PdaServer.store == null) return 0;
            Actions.setCharacter(PdaServer.store, sp, !BoolArgumentType.getBool(c, "on"));
            return 1;
        })));
        d.register(root);
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, Integer> id() {
        return Commands.argument("id", IntegerArgumentType.integer(0, 99_999_999));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> choice(String name, List<String> values, BiConsumer<Look, String> set) {
        return Commands.literal(name).then(Commands.argument("value", StringArgumentType.word())
                .suggests((c, b) -> SharedSuggestionProvider.suggest(values, b))
                .executes(c -> {
                    String v = StringArgumentType.getString(c, "value");
                    if (!values.contains(v)) {
                        c.getSource().sendFailure(Component.translatable("rppda.cmd.bad_value", String.join(", ", values)));
                        return 0;
                    }
                    return look(c, l -> set.accept(l, v));
                }));
    }

    private static String gm(CommandContext<CommandSourceStack> c) {
        return c.getSource().getTextName();
    }

    private static Account account(CommandContext<CommandSourceStack> c) {
        PdaStore store = PdaServer.store;
        Account a = store == null ? null : store.account(IntegerArgumentType.getInteger(c, "id"));
        if (a == null || a.kind == Account.Kind.SYSTEM) {
            c.getSource().sendFailure(Component.translatable("rppda.err.no_account"));
            return null;
        }
        return a;
    }

    private static int look(CommandContext<CommandSourceStack> c, java.util.function.Consumer<Look> change) {
        Account a = account(c);
        if (a == null) return 0;
        Look l = a.look.copy();
        change.accept(l);
        Actions.setLook(PdaServer.store, a, Look.load(l.save()), gm(c));
        c.getSource().sendSuccess(() -> Component.translatable("rppda.cmd.look", Actions.label(a)), true);
        return 1;
    }

    private static int signalPda(CommandContext<CommandSourceStack> c, boolean on) {
        Account a = account(c);
        if (a == null) return 0;
        Actions.setSignal(PdaServer.store, a, on, gm(c));
        c.getSource().sendSuccess(() -> Component.translatable(on ? "rppda.cmd.signal_on" : "rppda.cmd.signal_off", Actions.label(a)), true);
        return 1;
    }

    private static int signalPlayers(CommandContext<CommandSourceStack> c, boolean on) throws CommandSyntaxException {
        if (PdaServer.store == null) return 0;
        Collection<ServerPlayer> ps = EntityArgument.getPlayers(c, "targets");
        for (ServerPlayer p : ps) Actions.setPlayerSignal(PdaServer.store, p.getUUID(), p.getGameProfile().getName(), on, gm(c));
        c.getSource().sendSuccess(() -> Component.translatable(on ? "rppda.cmd.signal_on" : "rppda.cmd.signal_off",
                ps.size() == 1 ? ps.iterator().next().getDisplayName().getString() : Integer.toString(ps.size())), true);
        return ps.size();
    }

    private static int give(CommandContext<CommandSourceStack> c, String body, String screen) throws CommandSyntaxException {
        if (!Look.BODIES.contains(body) || !Look.SCREENS.contains(screen)) {
            c.getSource().sendFailure(Component.translatable("rppda.cmd.bad_value", String.join(", ", Look.BODIES)));
            return 0;
        }
        Collection<ServerPlayer> ps = EntityArgument.getPlayers(c, "targets");
        Look l = new Look();
        l.body = body;
        l.screen = screen;
        for (ServerPlayer p : ps) {
            ItemStack stack = new ItemStack(ModItems.PDA.get());
            PdaItem.setLook(stack, l);
            if (!p.getInventory().add(stack)) p.drop(stack, false);
        }
        c.getSource().sendSuccess(() -> Component.translatable("rppda.cmd.give", ps.size()), true);
        return ps.size();
    }
}
