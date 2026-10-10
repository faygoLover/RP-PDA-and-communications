package faygolover.rppda.gametest;

import com.mojang.authlib.GameProfile;
import faygolover.rppda.RpPda;
import faygolover.rppda.data.Account;
import faygolover.rppda.data.Chat;
import faygolover.rppda.data.ChatLog;
import faygolover.rppda.data.Message;
import faygolover.rppda.data.PdaStore;
import faygolover.rppda.item.ModItems;
import faygolover.rppda.item.PdaItem;
import faygolover.rppda.server.Actions;
import faygolover.rppda.server.PdaServer;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/** Проверка в настоящем сервере: регистрация, активный КПК, пароль и блокировка, /tell, чат без КПК, группы. */
@GameTestHolder(RpPda.MODID)
@PrefixGameTestTemplate(false)
public final class PdaGameTests {
    private PdaGameTests() {}

    static final String T = "platform";
    private static int counter;

    static ServerPlayer player(GameTestHelper h) {
        return player(h, false);
    }

    /** Тестовый игрок; {@code admin} — с правами (op в тестовом сервере даёт уровень 0, поэтому так). */
    static ServerPlayer player(GameTestHelper h, boolean admin) {
        MinecraftServer server = h.getLevel().getServer();
        GameProfile profile = new GameProfile(UUID.randomUUID(), "pda-test-" + (counter++));
        ServerPlayer p = admin ? new ServerPlayer(server, h.getLevel(), profile) {
            @Override
            public boolean hasPermissions(int level) {
                return true;
            }
        } : new ServerPlayer(server, h.getLevel(), profile);
        Connection conn = new Connection(PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(conn);
        server.getPlayerList().placeNewPlayer(conn, p);
        p.setGameMode(GameType.SURVIVAL);
        Vec3 pos = h.absoluteVec(new Vec3(2.5, 1, 2.5));
        p.moveTo(pos.x, pos.y, pos.z, 0, 0);
        return p;
    }

    static void remove(GameTestHelper h, ServerPlayer p) {
        if (!p.isRemoved()) h.getLevel().getServer().getPlayerList().remove(p);
    }

    /** Дать новый КПК в руку и зарегистрировать (как ПКМ + кнопка). */
    static Account register(GameTestHelper h, ServerPlayer p) {
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.PDA.get()));
        PdaServer.openFromHand(p, InteractionHand.MAIN_HAND);
        Actions.handle(p, "register", new CompoundTag());
        int id = PdaItem.account(p.getMainHandItem());
        h.assertTrue(id > 0, "КПК не зарегистрирован");
        Account a = store().account(id);
        h.assertTrue(a != null && p.getUUID().equals(a.owner), "учётка без владельца");
        return a;
    }

    /** Выполнить команду от игрока; ответы команды — в лог (видно, почему не сработала). */
    static void cmd(ServerPlayer p, String command) {
        var src = p.createCommandSourceStack().withSource(new net.minecraft.commands.CommandSource() {
            @Override
            public void sendSystemMessage(net.minecraft.network.chat.Component c) {
                RpPda.LOGGER.info("[test {}] {}", p.getGameProfile().getName(), c.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        });
        p.server.getCommands().performPrefixedCommand(src, command);
    }

    static PdaStore store() {
        return PdaServer.store();
    }

    static void act(ServerPlayer p, String op, CompoundTag t) {
        Actions.handle(p, op, t);
    }

    static List<Message> messages(Chat c) {
        ChatLog log = store().log(c);
        return log.messages;
    }

    @GameTest(template = T)
    public static void registerMakesActive(GameTestHelper h) {
        ServerPlayer p = player(h);
        Account a = register(h, p);
        h.assertTrue(a.name.equals(p.getGameProfile().getName()), "имя учётки = имя игрока");
        Integer act = PdaServer.activeAccount(p);
        h.assertTrue(act != null && act == a.id, "зарегистрированный КПК стал активным");
        remove(h, p);
        h.succeed();
    }

    @GameTest(template = T)
    public static void activeOrderSkipsLocked(GameTestHelper h) {
        ServerPlayer p = player(h);
        Account first = register(h, p);
        ItemStack firstStack = p.getMainHandItem().copy();
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        Account second = register(h, p);
        // Первый — в ячейку 5, второй — в руке.
        p.getInventory().setItem(5, firstStack);
        PdaServer.scan(p);
        h.assertTrue(PdaServer.activeAccount(p) == second.id, "активный — тот, что в руке");
        // Второй с паролем и заблокирован — пропускается.
        second.pwSalt = faygolover.rppda.util.Passwords.newSalt();
        second.pwHash = faygolover.rppda.util.Passwords.hash("1", second.pwSalt);
        second.locked = true;
        PdaServer.scan(p);
        h.assertTrue(PdaServer.activeAccount(p) == first.id, "заблокированный пропускается");
        remove(h, p);
        h.succeed();
    }

    @GameTest(template = T)
    public static void passwordLocksWhenItLeaves(GameTestHelper h) {
        ServerPlayer p = player(h);
        Account a = register(h, p);
        CompoundTag pw = new CompoundTag();
        pw.putString("old", "");
        pw.putString("pw", "qwerty");
        act(p, "profile.password", pw);
        h.assertTrue(a.hasPassword() && !a.locked, "пароль поставлен, КПК открыт");
        // Убрали из инвентаря (положили в сундук) — при проверке блокируется.
        ItemStack stack = p.getMainHandItem().copy();
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        PdaServer.scan(p);
        h.assertTrue(a.locked, "КПК ушёл из инвентаря — заблокирован");
        h.assertTrue(PdaServer.activeAccount(p) == null, "заблокированный не активен");
        // Вернули — нужен пароль.
        p.setItemInHand(InteractionHand.MAIN_HAND, stack);
        PdaServer.openFromHand(p, InteractionHand.MAIN_HAND);
        CompoundTag bad = new CompoundTag();
        bad.putString("pw", "nope");
        act(p, "unlock", bad);
        h.assertTrue(a.locked, "неверный пароль не открывает");
        CompoundTag good = new CompoundTag();
        good.putString("pw", "qwerty");
        act(p, "unlock", good);
        h.assertTrue(!a.locked, "верный пароль открывает");
        // Выход с сервера — тоже блок.
        remove(h, p);
        h.assertTrue(a.locked, "вышел с сервера — заблокирован");
        h.succeed();
    }

    @GameTest(template = T)
    public static void tellGoesToDm(GameTestHelper h) {
        ServerPlayer a = player(h), b = player(h);
        Account aa = register(h, a), bb = register(h, b);
        cmd(a, "msg " + b.getGameProfile().getName() + " привет, это тест");
        Chat dm = store().findDm(aa.id, bb.id);
        h.assertTrue(dm != null, "/tell создал личный диалог");
        List<Message> ms = messages(dm);
        h.assertTrue(!ms.isEmpty() && ms.get(ms.size() - 1).text.equals("привет, это тест") && ms.get(ms.size() - 1).sender == aa.id,
                "сообщение лежит в диалоге от отправителя");
        // Обычный чат — в общий.
        int before = store().global().lastSeq;
        cmd(b, "me машет рукой");
        h.assertTrue(store().global().lastSeq == before + 1, "/me ушёл в общий чат");
        Message last = messages(store().global()).get(messages(store().global()).size() - 1);
        h.assertTrue(last.has(Message.ACTION) && last.sender == bb.id, "/me — действие от учётки");
        remove(h, a);
        remove(h, b);
        h.succeed();
    }

    @GameTest(template = T)
    public static void noPdaTellToAdminIsKept(GameTestHelper h) {
        ServerPlayer admin = player(h, true), p = player(h);
        {
            cmd(p, "msg " + admin.getGameProfile().getName() + " помогите");
            var q = store().pending.get(p.getUUID());
            h.assertTrue(q != null && q.size() == 1, "без учётки сообщение админу отложено");
            // Не админу — нельзя.
            ServerPlayer other = player(h);
            cmd(p, "msg " + other.getGameProfile().getName() + " эй");
            h.assertTrue(store().pending.get(p.getUUID()).size() == 1, "не админу без КПК не уходит");
            remove(h, other);
            // Первый КПК — сообщение переезжает в диалог с Системой (у админа учётки нет).
            Account mine = register(h, p);
            Chat dm = store().findDm(mine.id, 0);
            h.assertTrue(dm != null && messages(dm).get(0).text.equals("помогите"), "отложенное попало в первый КПК");
            h.assertTrue(!store().pending.containsKey(p.getUUID()), "очередь очищена");
        }
        remove(h, admin);
        remove(h, p);
        h.succeed();
    }

    @GameTest(template = T)
    public static void groupInviteAcceptKick(GameTestHelper h) {
        ServerPlayer a = player(h), b = player(h);
        Account aa = register(h, a), bb = register(h, b);
        PdaServer.openFromHand(a, InteractionHand.MAIN_HAND);
        CompoundTag create = new CompoundTag();
        create.putString("title", "Отряд");
        act(a, "group.create", create);
        Chat g = null;
        for (int id : store().chatsOf(aa.id)) if (store().chat(id).isGroup()) g = store().chat(id);
        h.assertTrue(g != null && g.admin == aa.id, "группа создана, создатель — админ");
        CompoundTag inv = new CompoundTag();
        inv.putInt("chat", g.id);
        inv.putInt("id", bb.id);
        act(a, "group.invite", inv);
        h.assertTrue(bb.invites.contains(g.id) && !g.has(bb.id), "приглашение ждёт ответа");
        PdaServer.openFromHand(b, InteractionHand.MAIN_HAND);
        CompoundTag acc = new CompoundTag();
        acc.putInt("chat", g.id);
        act(b, "group.accept", acc);
        h.assertTrue(g.has(bb.id), "приглашённый вступил");
        // Не админ не может выгнать.
        CompoundTag kickA = new CompoundTag();
        kickA.putInt("chat", g.id);
        kickA.putInt("id", aa.id);
        act(b, "group.kick", kickA);
        h.assertTrue(g.has(aa.id), "не админ не выгоняет");
        CompoundTag kickB = new CompoundTag();
        kickB.putInt("chat", g.id);
        kickB.putInt("id", bb.id);
        act(a, "group.kick", kickB);
        h.assertTrue(!g.has(bb.id), "админ выгнал");
        remove(h, a);
        remove(h, b);
        h.succeed();
    }

    @GameTest(template = T)
    public static void restoreDisablesOldCopy(GameTestHelper h) {
        ServerPlayer gm = player(h, true), p = player(h);
        Account a = register(h, p);
        {
            gm.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.SYSTEM_PDA.get()));
            PdaServer.openFromHand(gm, InteractionHand.MAIN_HAND);
            CompoundTag t = new CompoundTag();
            t.putInt("id", a.id);
            act(gm, "sys.give", t);
            h.assertTrue(a.issue == 2, "номер выдачи вырос");
            PdaServer.scan(p);
            h.assertTrue(PdaServer.activeAccount(p) == null, "старый экземпляр отключён");
            boolean got = false;
            for (ItemStack s : gm.getInventory().items) if (PdaItem.account(s) == a.id && PdaItem.issue(s) == 2) got = true;
            h.assertTrue(got, "новый КПК у ГМа");
        }
        remove(h, gm);
        remove(h, p);
        h.succeed();
    }

    @GameTest(template = T)
    public static void curiosSlotCounts(GameTestHelper h) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("curios")) {
            h.succeed();
            return;
        }
        ServerPlayer p = player(h);
        Account a = register(h, p);
        ItemStack stack = p.getMainHandItem().copy();
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        h.assertTrue(CuriosTest.putInPdaSlot(p, stack), "в Curios нет слота «pda»");
        PdaServer.scan(p);
        h.assertTrue(PdaServer.activeAccount(p) != null && PdaServer.activeAccount(p) == a.id, "КПК в слоте Curios — активный");
        remove(h, p);
        h.succeed();
    }

    /** Классы Curios — только здесь (тест без Curios сюда не заходит). */
    private static final class CuriosTest {
        static boolean putInPdaSlot(ServerPlayer p, ItemStack stack) {
            var inv = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(p).resolve().orElse(null);
            if (inv == null) return false;
            var handler = inv.getStacksHandler("pda").orElse(null);
            if (handler == null || handler.getSlots() < 1) return false;
            handler.getStacks().setStackInSlot(0, stack);
            return true;
        }
    }
}
