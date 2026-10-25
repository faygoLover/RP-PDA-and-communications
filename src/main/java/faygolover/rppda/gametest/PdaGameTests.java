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

    /** Начать диалог из окна КПК (как кнопка «Написать по ID» и отправка). */
    static void startDm(ServerPlayer p, Account from, Account to, String text) {
        PdaServer.openFromHand(p, InteractionHand.MAIN_HAND);
        CompoundTag dm = new CompoundTag();
        dm.putInt("id", to.id);
        Actions.handle(p, "dm", dm);
        Chat c = store().findDm(from.id, to.id);
        CompoundTag send = new CompoundTag();
        send.putInt("chat", c.id);
        send.putString("text", text);
        Actions.handle(p, "chat.send", send);
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
        // По нику между игроками — нельзя (по умолчанию).
        cmd(a, "msg " + b.getGameProfile().getName() + " привет по нику");
        h.assertTrue(store().findDm(aa.id, bb.id) == null, "/tell по нику игроку не работает");
        // По ID без диалога — нельзя; диалог начинают в КПК.
        cmd(a, "msg " + String.format("%08d", bb.id) + " привет по ID");
        h.assertTrue(store().findDm(aa.id, bb.id) == null || store().findDm(aa.id, bb.id).lastSeq == 0, "без диалога /tell по ID не работает");
        startDm(a, aa, bb, "первое из КПК");
        cmd(a, "msg " + String.format("%08d", bb.id) + " привет, это тест");
        Chat dm = store().findDm(aa.id, bb.id);
        List<Message> ms = messages(dm);
        h.assertTrue(!ms.isEmpty() && ms.get(ms.size() - 1).text.equals("привет, это тест") && ms.get(ms.size() - 1).sender == aa.id,
                "/tell по ID — в диалог от отправителя");
        // /me — действие рядом, в обычный чат, а не в КПК.
        int before = store().global().lastSeq;
        cmd(b, "me машет рукой");
        h.assertTrue(store().global().lastSeq == before, "/me не пишется в КПК");
        remove(h, a);
        remove(h, b);
        h.succeed();
    }

    @GameTest(template = T)
    public static void noPdaTellToAdminIsPlain(GameTestHelper h) {
        ServerPlayer admin = player(h, true), p = player(h);
        {
            // Без КПК — обычной строкой админу: ни в КПК, ни в очередь до первого КПК (решение 26.10).
            cmd(p, "msg " + admin.getGameProfile().getName() + " помогите");
            h.assertTrue(!store().pending.containsKey(p.getUUID()), "без учётки сообщение админу не откладывается");
            // Первый КПК — диалога с Системой нет.
            Account mine = register(h, p);
            Chat dm = store().findDm(mine.id, 0);
            h.assertTrue(dm == null || messages(dm).isEmpty(), "в первый КПК ничего не переехало");
            // С КПК и связью — строкой КПК, в диалог с Системой (у админа учётки нет).
            cmd(p, "msg " + admin.getGameProfile().getName() + " теперь с КПК");
            dm = store().findDm(mine.id, 0);
            h.assertTrue(dm != null && messages(dm).get(messages(dm).size() - 1).text.equals("теперь с КПК"), "с КПК — в диалог");
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

    @GameTest(template = T)
    public static void noSignalBlocksExceptSystem(GameTestHelper h) {
        ServerPlayer a = player(h), b = player(h);
        Account aa = register(h, a), bb = register(h, b);
        startDm(a, aa, bb, "привет");
        Actions.setSignal(store(), aa, false, "test");
        Chat dm = store().findDm(aa.id, bb.id);
        int before = dm.lastSeq;
        // Без связи писать нельзя.
        cmd(a, "msg " + String.format("%08d", bb.id) + " есть кто?");
        h.assertTrue(dm.lastSeq == before, "без связи сообщение не ушло");
        // Ему пишут — сообщение ждёт на сервере, но окно его не показывает до возврата связи.
        cmd(b, "msg " + String.format("%08d", aa.id) + " ты где?");
        h.assertTrue(dm.lastSeq == before + 1, "входящее сохранено");
        Message incoming = messages(dm).get(messages(dm).size() - 1);
        h.assertTrue(incoming.time >= PdaServer.signalCut(a, aa), "входящее — позже пропажи связи (скрыто)");
        // Диалог с Системой работает и без связи.
        Chat sys = store().dm(aa.id, 0);
        h.assertTrue(faygolover.rppda.server.Messaging.check(aa, sys, "помогите", a) == null, "Системе писать можно");
        h.assertTrue(faygolover.rppda.server.Messaging.check(aa, dm, "привет", a) != null, "другим — нельзя");
        // Связь у игрока.
        Actions.setSignal(store(), aa, true, "test");
        h.assertTrue(PdaServer.signal(a, aa), "связь вернулась");
        Actions.setPlayerSignal(store(), a.getUUID(), "a", false, "test");
        h.assertTrue(!PdaServer.signal(a, aa), "без связи у игрока — нет связи и у его КПК");
        Actions.setPlayerSignal(store(), a.getUUID(), "a", true, "test");
        remove(h, a);
        remove(h, b);
        h.succeed();
    }

    @GameTest(template = T)
    public static void characterModeDropsAdminPerks(GameTestHelper h) {
        ServerPlayer gm = player(h, true);
        h.assertTrue(PdaServer.isAdmin(gm) && PdaServer.isGm(gm), "админ");
        Actions.setCharacter(store(), gm, true);
        h.assertTrue(!PdaServer.isAdmin(gm), "в режиме персонажа — как обычный игрок");
        h.assertTrue(PdaServer.isGm(gm), "права ГМа (системный КПК, команды) остаются");
        Actions.setCharacter(store(), gm, false);
        h.assertTrue(PdaServer.isAdmin(gm), "режим выключен — снова админ");
        remove(h, gm);
        h.succeed();
    }

    @GameTest(template = T)
    public static void lookGoesWithDevice(GameTestHelper h) {
        ServerPlayer p = player(h);
        ItemStack stack = new ItemStack(ModItems.PDA.get());
        faygolover.rppda.data.Look l = new faygolover.rppda.data.Look();
        l.body = "disco";
        l.screen = "synth";
        l.style = "retro";
        l.model = "МОЙ-1";
        PdaItem.setLook(stack, l);
        p.setItemInHand(InteractionHand.MAIN_HAND, stack);
        PdaServer.openFromHand(p, InteractionHand.MAIN_HAND);
        Actions.handle(p, "register", new CompoundTag());
        Account a = store().account(PdaItem.account(p.getMainHandItem()));
        h.assertTrue(a != null && a.look.body.equals("disco") && a.look.model.equals("МОЙ-1") && a.look.style.equals("retro"),
                "вид из предмета перешёл в учётку");
        faygolover.rppda.data.Look n = a.look.copy();
        n.body = "wave";
        Actions.setLook(store(), a, n, "test");
        PdaServer.scan(p);
        h.assertTrue(PdaItem.look(p.getMainHandItem()).body.equals("wave"), "предмет показывает новый корпус");
        h.assertTrue(PdaItem.skinIndex(p.getMainHandItem()) > 0, "иконка — не стандартная");
        remove(h, p);
        h.succeed();
    }

    @GameTest(template = T)
    public static void commandsForCommandBlocks(GameTestHelper h) {
        ServerPlayer p = player(h);
        Account a = register(h, p);
        var server = h.getLevel().getServer();
        var src = server.createCommandSourceStack().withSuppressedOutput();
        server.getCommands().performPrefixedCommand(src, "rppda look " + a.id + " body wave");
        server.getCommands().performPrefixedCommand(src, "rppda look " + a.id + " screen synth");
        server.getCommands().performPrefixedCommand(src, "rppda look " + a.id + " model ПОДАРОК-1");
        h.assertTrue(a.look.body.equals("wave") && a.look.screen.equals("synth") && a.look.model.equals("ПОДАРОК-1"), "/rppda look");
        server.getCommands().performPrefixedCommand(src, "rppda signal pda " + a.id + " off");
        h.assertTrue(a.noSignal, "/rppda signal pda off");
        server.getCommands().performPrefixedCommand(src, "rppda signal pda " + a.id + " on");
        server.getCommands().performPrefixedCommand(src, "rppda signal player " + p.getGameProfile().getName() + " off");
        h.assertTrue(!a.noSignal && !PdaServer.signal(p, a), "/rppda signal player off");
        server.getCommands().performPrefixedCommand(src, "rppda signal player " + p.getGameProfile().getName() + " on");
        h.assertTrue(PdaServer.signal(p, a), "связь вернулась");
        int before = 0;
        for (ItemStack s : p.getInventory().items) if (s.is(ModItems.PDA.get())) before++;
        server.getCommands().performPrefixedCommand(src, "rppda give " + p.getGameProfile().getName() + " disco lcd");
        int after = 0;
        ItemStack given = ItemStack.EMPTY;
        for (ItemStack s : p.getInventory().items) if (s.is(ModItems.PDA.get())) {
            after++;
            if (PdaItem.account(s) == 0) given = s;
        }
        h.assertTrue(after == before + 1 && PdaItem.look(given).body.equals("disco") && PdaItem.look(given).screen.equals("lcd"), "/rppda give");
        remove(h, p);
        h.succeed();
    }

    @GameTest(template = T)
    public static void whisperFadesWithDistance(GameTestHelper h) {
        var rnd = net.minecraft.util.RandomSource.create(42);
        String text = "встречаемся у старой станции в полночь, никому не говори";
        String g1 = faygolover.rppda.server.ChatInterceptAccess.garble(text, 0.35, rnd);
        String g2 = faygolover.rppda.server.ChatInterceptAccess.garble(text, 0.65, rnd);
        h.assertTrue(g1.contains("...") && !g1.equals(text), "шёпот издалека — обрывками");
        h.assertTrue(g2.contains("...") && g2.split("\\s+").length <= text.split("\\s+").length, "ещё дальше — меньше слов");
        int r = faygolover.rppda.config.PdaConfig.whisperRadius;
        var name = net.minecraft.network.chat.Component.literal("X");
        h.assertTrue(faygolover.rppda.server.ChatInterceptAccess.heardWhisper(name, text, r, rnd).contains(text), "в радиусе — ясно");
        h.assertTrue(faygolover.rppda.server.ChatInterceptAccess.heardWhisper(name, text, r * 1.6, rnd).contains("*"), "почти на краю — «*шёпот*»");
        h.assertTrue(faygolover.rppda.server.ChatInterceptAccess.heardWhisper(name, text, r * 2, rnd).isEmpty(), "дальше — ничего");
        h.succeed();
    }

    @GameTest(template = T)
    public static void notesShareAndAccept(GameTestHelper h) {
        // Старый способ (сразу, без передачи рядом) — при выключенных реалистичных ретро-КПК.
        faygolover.rppda.config.PdaConfig.realisticRetro = false;
        ServerPlayer a = player(h), b = player(h);
        Account aa = register(h, a), bb = register(h, b);
        startDm(a, aa, bb, "привет");
        PdaServer.openFromHand(a, InteractionHand.MAIN_HAND);
        CompoundTag save = new CompoundTag();
        save.putInt("id", 0);
        save.putString("title", "Склад");
        save.putString("text", "Код двери: 4512");
        act(a, "notes.save", save);
        h.assertTrue(aa.notes.size() == 1 && aa.notes.get(0).title.equals("Склад"), "заметка сохранена");
        Chat dm = store().findDm(aa.id, bb.id);
        CompoundTag share = new CompoundTag();
        share.putInt("id", aa.notes.get(0).id);
        share.putInt("chat", dm.id);
        act(a, "notes.share", share);
        Message m = messages(dm).get(messages(dm).size() - 1);
        h.assertTrue(m.has(Message.NOTE), "заметкой поделились в диалоге");
        PdaServer.openFromHand(b, InteractionHand.MAIN_HAND);
        CompoundTag acc = new CompoundTag();
        acc.putInt("chat", dm.id);
        acc.putInt("seq", m.seq);
        act(b, "notes.accept", acc);
        h.assertTrue(bb.notes.size() == 1 && bb.notes.get(0).text.equals("Код двери: 4512") && bb.notes.get(0).from != null,
                "получатель добавил себе — с пометкой от кого");
        remove(h, a);
        remove(h, b);
        h.succeed();
    }

    @GameTest(template = T)
    public static void npcAtRegistrationAndDelete(GameTestHelper h) {
        ServerPlayer gm = player(h, true), p = player(h);
        gm.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.PDA.get()));
        PdaServer.openFromHand(gm, InteractionHand.MAIN_HAND);
        CompoundTag npc = new CompoundTag();
        npc.putString("name", "Торговец");
        act(gm, "register.npc", npc);
        Account n = store().account(PdaItem.account(gm.getMainHandItem()));
        h.assertTrue(n != null && n.kind == Account.Kind.NPC && n.owner == null && n.name.equals("Торговец"), "NPC-учётка при регистрации");
        Account pa = register(h, p);
        startDm(p, pa, n, "есть товар?");
        h.assertTrue(store().findDm(pa.id, n.id) != null, "диалог с NPC есть");
        gm.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(ModItems.SYSTEM_PDA.get()));
        PdaServer.openFromHand(gm, InteractionHand.OFF_HAND);
        CompoundTag del = new CompoundTag();
        del.putInt("id", n.id);
        act(gm, "sys.delete", del);
        h.assertTrue(store().account(n.id) == null, "учётка удалена");
        h.assertTrue(store().findDm(pa.id, n.id) == null, "её личные диалоги удалены");
        h.assertTrue(faygolover.rppda.server.Devices.status(gm.getMainHandItem(), store()) == faygolover.rppda.server.Devices.Status.DELETED,
                "КПК с ней мёртв");
        remove(h, gm);
        remove(h, p);
        h.succeed();
    }

    @GameTest(template = T)
    public static void chatOutputNeedsServerPermission(GameTestHelper h) {
        ServerPlayer p = player(h);
        Account a = register(h, p);
        PdaServer.openFromHand(p, InteractionHand.MAIN_HAND);
        CompoundTag on = new CompoundTag();
        on.putInt("chat", Chat.GLOBAL_ID);
        on.putBoolean("on", true);
        act(p, "chat.out", on);
        h.assertTrue(!a.chatOut.contains(Chat.GLOBAL_ID), "по умолчанию вывод в чат игры игроку недоступен");
        CompoundTag mode = new CompoundTag();
        mode.putInt("chat", Chat.GLOBAL_ID);
        mode.putInt("mode", 2);
        act(p, "chat.notify", mode);
        h.assertTrue(a.notify(Chat.GLOBAL_ID) == Account.Notify.NONE, "без уведомлений");
        remove(h, p);
        h.succeed();
    }
}
