package faygolover.rppda.gametest;

import faygolover.rppda.RpPda;
import faygolover.rppda.config.PdaConfig;
import faygolover.rppda.data.Account;
import faygolover.rppda.data.Chat;
import faygolover.rppda.data.Message;
import faygolover.rppda.data.Note;
import faygolover.rppda.item.ModItems;
import faygolover.rppda.item.PdaItem;
import faygolover.rppda.server.Memory;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import static faygolover.rppda.gametest.PdaGameTests.T;
import static faygolover.rppda.gametest.PdaGameTests.act;
import static faygolover.rppda.gametest.PdaGameTests.messages;
import static faygolover.rppda.gametest.PdaGameTests.player;
import static faygolover.rppda.gametest.PdaGameTests.register;
import static faygolover.rppda.gametest.PdaGameTests.remove;
import static faygolover.rppda.gametest.PdaGameTests.store;

/**
 * Память ретро-КПК и передача заметок ({@code docs/20_retro_memory.md}). Своя пачка: на время пачки включены
 * реалистичные ретро-КПК (передача 20 Б/с), после — настройки снова из конфига.
 */
@GameTestHolder(RpPda.MODID)
@PrefixGameTestTemplate(false)
public final class MemoryGameTests {
    private MemoryGameTests() {}

    static final String B = "retro_memory";

    @BeforeBatch(batch = B)
    public static void before(ServerLevel level) {
        PdaConfig.realisticRetro = true;
        PdaConfig.retroMemory = 2048;
        PdaConfig.retroSpeed = 20;
        PdaConfig.modernSpeed = 200;
        PdaConfig.transferRadius = 16;
        PdaConfig.transferPeers = 2;
        PdaConfig.inviteMinutes = 5;
        PdaConfig.style = "retro";
    }

    @AfterBatch(batch = B)
    public static void after(ServerLevel level) {
        PdaConfig.bake();
    }

    static void save(ServerPlayer p, int id, String title, String text) {
        CompoundTag t = new CompoundTag();
        t.putInt("id", id);
        t.putString("title", title);
        t.putString("text", text);
        act(p, "notes.save", t);
    }

    static Note note(Account a, String title) {
        for (Note n : a.notes) if (n.title.equals(title)) return n;
        return null;
    }

    static Note last(Account a) {
        return a.notes.get(a.notes.size() - 1);
    }

    /** Выложить заметку приглашением в общий чат; номер сообщения-приглашения (0 — не выложено). */
    static int share(ServerPlayer p, Account a, Note n) {
        Chat g = store().global();
        CompoundTag t = new CompoundTag();
        t.putInt("id", n.id);
        t.putInt("chat", g.id);
        act(p, "notes.share", t);
        int seq = 0;
        for (Message m : messages(g)) if (m.sender == a.id && m.has(Message.FILE)) seq = m.seq;
        return seq;
    }

    static int invites(Account a) {
        int n = 0;
        for (Message m : messages(store().global())) if (m.sender == a.id && m.has(Message.FILE)) n++;
        return n;
    }

    static void join(ServerPlayer p, int seq) {
        CompoundTag t = new CompoundTag();
        t.putInt("chat", store().global().id);
        t.putInt("seq", seq);
        act(p, "xfer.join", t);
    }

    @GameTest(template = T, batch = B)
    public static void memoryLimitBlocksGrowth(GameTestHelper h) {
        ServerPlayer p = player(h);
        Account a = register(h, p);
        save(p, 0, "A", "x".repeat(1800));
        h.assertTrue(a.notes.size() == 1, "заметка влезла (128 + 1801 из 2048)");
        save(p, 0, "B", "y".repeat(200));
        h.assertTrue(a.notes.size() == 1, "вторая не влезла — не сохранена");
        Note n = note(a, "A");
        save(p, n.id, "A", "x".repeat(1000));
        h.assertTrue(n.text.length() == 1000, "укоротить можно");
        // Занято больше лимита: добавить знаки нельзя, укоротить — можно.
        Note big = new Note(a.nextNote++, "Z", "z".repeat(2000));
        a.notes.add(big);
        h.assertTrue(Memory.used(a, null) > 2048, "переполнено");
        save(p, n.id, "A", "x".repeat(1001));
        h.assertTrue(n.text.length() == 1000, "переполнено — добавить знак нельзя");
        save(p, n.id, "A", "x".repeat(900));
        h.assertTrue(n.text.length() == 900, "переполнено — укоротить можно");
        remove(h, p);
        h.succeed();
    }

    @GameTest(template = T, batch = B)
    public static void boardsExtendMemory(GameTestHelper h) {
        ServerPlayer p = player(h);
        Account a = register(h, p);
        ItemStack dev = p.getMainHandItem();
        p.getInventory().setItem(5, new ItemStack(ModItems.MEMORY_2.get()));
        CompoundTag t = new CompoundTag();
        t.putInt("slot", 0);
        t.putInt("inv", 5);
        act(p, "mem.slot", t);
        h.assertTrue(PdaItem.slots(dev)[0] == 2, "плата в ячейке 1");
        h.assertTrue(p.getInventory().getItem(5).isEmpty(), "плата ушла из инвентаря");
        h.assertTrue(Memory.capacity(dev) == 4096, "объём 2 + 2 КБ");
        save(p, 0, "Big", "b".repeat(3000));
        h.assertTrue(a.notes.size() == 1, "с платой влезает 3000 знаков");
        CompoundTag u = new CompoundTag();
        u.putInt("slot", 0);
        act(p, "mem.unslot", u);
        h.assertTrue(PdaItem.slots(dev)[0] == 0, "ячейка пуста");
        h.assertTrue(p.getInventory().countItem(ModItems.MEMORY_2.get()) == 1, "плата вернулась в инвентарь");
        save(p, 0, "S", "s");
        h.assertTrue(a.notes.size() == 1, "без платы переполнено — новое не сохраняется");
        remove(h, p);
        h.succeed();
    }

    @GameTest(template = T, batch = B, timeoutTicks = 300)
    public static void transferCompletes(GameTestHelper h) {
        ServerPlayer s = player(h), p = player(h);
        Account sa = register(h, s), pa = register(h, p);
        String text = "Тайник у старой водокачки, третий кирпич слева. Пароль: ёлка.";
        save(s, 0, "Схема тайника", text);
        int seq = share(s, sa, last(sa));
        h.assertTrue(seq > 0, "приглашение выложено");
        join(p, seq);
        Note got = note(pa, "Схема тайника");
        h.assertTrue(got != null && got.partial, "название пришло сразу, файл качается");
        h.assertTrue(got.text.length() < text.length(), "текст ещё не весь");
        h.runAfterDelay(140, () -> {
            h.assertTrue(!got.partial && got.text.equals(text), "файл докачан целиком: " + got.text.length() + " из " + text.length());
            h.assertTrue(got.from != null, "от кого — записано");
            remove(h, s);
            remove(h, p);
            h.succeed();
        });
    }

    @GameTest(template = T, batch = B, timeoutTicks = 300)
    public static void transferBreaksOutOfRange(GameTestHelper h) {
        ServerPlayer s = player(h), p = player(h);
        Account sa = register(h, s), pa = register(h, p);
        String text = "r".repeat(400);
        save(s, 0, "Далеко", text);
        join(p, share(s, sa, last(sa)));
        Note got = note(pa, "Далеко");
        h.assertTrue(got != null, "качается");
        h.runAfterDelay(30, () -> p.teleportTo(p.getX() + 40, p.getY(), p.getZ()));
        int[] mid = new int[1];
        h.runAfterDelay(90, () -> mid[0] = got.text.length());
        h.runAfterDelay(150, () -> {
            h.assertTrue(got.partial, "ушёл из радиуса — файл остался битым");
            h.assertTrue(got.text.length() == mid[0] && got.text.length() < 400, "после обрыва больше не качается: " + got.text.length());
            remove(h, s);
            remove(h, p);
            h.succeed();
        });
    }

    @GameTest(template = T, batch = B, timeoutTicks = 300)
    public static void transferBreaksWhenFull(GameTestHelper h) {
        ServerPlayer s = player(h), p = player(h);
        Account sa = register(h, s), pa = register(h, p);
        save(p, 0, "F", "f".repeat(1850));
        save(s, 0, "T", "t".repeat(300));
        join(p, share(s, sa, last(sa)));
        Note got = note(pa, "T");
        h.assertTrue(got != null, "название влезло");
        h.runAfterDelay(160, () -> {
            h.assertTrue(got.partial, "место кончилось — файл битый");
            h.assertTrue(Memory.used(pa, null) <= 2048, "лимит не превышен: " + Memory.used(pa, null));
            h.assertTrue(got.text.length() < 300, "пришло меньше файла: " + got.text.length());
            remove(h, s);
            remove(h, p);
            h.succeed();
        });
    }

    @GameTest(template = T, batch = B, timeoutTicks = 300)
    public static void cancelAndOneFileRule(GameTestHelper h) {
        ServerPlayer s = player(h), p = player(h);
        Account sa = register(h, s), pa = register(h, p);
        save(s, 0, "Один", "o".repeat(400));
        save(s, 0, "Два", "d".repeat(50));
        save(p, 0, "Своя", "m".repeat(20));
        int seq = share(s, sa, note(sa, "Один"));
        join(p, seq);
        // Пока отправляешь — второе приглашение нельзя; пока качаешь — отправлять нельзя.
        share(s, sa, note(sa, "Два"));
        h.assertTrue(invites(sa) == 1, "второе приглашение не выложено");
        share(p, pa, note(pa, "Своя"));
        h.assertTrue(invites(pa) == 0, "качающий не может отправлять");
        Note got = note(pa, "Один");
        h.runAfterDelay(30, () -> {
            CompoundTag t = new CompoundTag();
            t.putInt("chat", store().global().id);
            t.putInt("seq", seq);
            act(s, "xfer.cancel", t);
        });
        int[] mid = new int[1];
        h.runAfterDelay(60, () -> mid[0] = got.text.length());
        h.runAfterDelay(120, () -> {
            h.assertTrue(got.partial && got.text.length() == mid[0], "отменили — передача оборвалась");
            // Приглашение закрыто — новое скачивание не начнётся.
            int before = pa.notes.size();
            join(p, seq);
            h.assertTrue(pa.notes.size() == before, "по закрытому приглашению не качается");
            remove(h, s);
            remove(h, p);
            h.succeed();
        });
    }
}
