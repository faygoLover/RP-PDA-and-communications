package faygolover.rppda.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Все данные КПК на сервере, папка {@code <мир>/rppda/}:
 * <ul>
 *     <li>{@code accounts.dat} — учётки; {@code chats.dat} — список чатов; {@code misc.dat} — отложенные сообщения
 *     админам, журнал ГМов, последняя учётка игрока;</li>
 *     <li>{@code chats/<id>.dat} — «свежие» сообщения чата (грузятся, когда чат нужен, выгружаются без дела);</li>
 *     <li>{@code archive/<id>/<n>.dat} — архив: куски по {@link #CHUNK} сообщений, кусок n — номера
 *     {@code n*CHUNK+1 .. (n+1)*CHUNK}.</li>
 * </ul>
 * Не потокобезопасен: всё — в потоке сервера, кроме {@link #readChunk} (только чтение файла).
 */
public final class PdaStore {
    public static final int CHUNK = 1000;
    /** Сколько последних сообщений чата всегда держать в «свежих». */
    public static final int KEEP_HOT = 1000;
    public static final int GM_LOG_LIMIT = 1000;

    private final Path root;
    private final Logger log;
    private final Random random = new Random();

    public final Map<Integer, Account> accounts = new HashMap<>();
    public final Map<Integer, Chat> chats = new HashMap<>();
    /** Учётка → её чаты (кроме общего). */
    private final Map<Integer, Set<Integer>> chatsOf = new HashMap<>();
    /** Пара учёток → личный диалог. */
    private final Map<Long, Integer> dmIndex = new HashMap<>();
    private final Map<Integer, ChatLog> logs = new HashMap<>();
    /** Сообщения админам от игроков без учётки — уйдут в первую учётку игрока. */
    public final Map<UUID, Deque<Pending>> pending = new HashMap<>();
    public final Deque<GmEntry> gmLog = new ArrayDeque<>();
    /** Игрок → учётка, с которой он был последний раз (куда слать {@code /tell}, пока его нет). */
    public final Map<UUID, Integer> lastAccount = new HashMap<>();
    /** Игроки без связи (ГМ выключил): с какого момента. */
    public final Map<UUID, Long> noSignalPlayers = new HashMap<>();
    /** Админы в «режиме персонажа»: мод считает их обычными игроками. */
    public final Set<UUID> characterMode = new HashSet<>();
    /** RP-время на реальный момент (находка 56): опорные точки часов мира. */
    public final faygolover.rppda.util.RpClock clock = new faygolover.rppda.util.RpClock();
    private int nextChatId = 2;

    private boolean accountsDirty, chatsDirty, miscDirty;

    public record Pending(UUID admin, long time, String text) {}

    public record GmEntry(long time, String gm, String action, String target) {}

    public PdaStore(Path root, Logger log) {
        this.root = root;
        this.log = log;
    }

    // ---------------------------------------------------------------- загрузка и сохранение

    public void load() {
        CompoundTag a = read(root.resolve("accounts.dat"));
        if (a != null) for (Tag t : a.getList("accounts", Tag.TAG_COMPOUND)) {
            Account acc = Account.load((CompoundTag) t);
            accounts.put(acc.id, acc);
        }
        CompoundTag c = read(root.resolve("chats.dat"));
        if (c != null) {
            nextChatId = Math.max(2, c.getInt("next"));
            for (Tag t : c.getList("chats", Tag.TAG_COMPOUND)) {
                Chat chat = Chat.load((CompoundTag) t);
                chats.put(chat.id, chat);
                nextChatId = Math.max(nextChatId, chat.id + 1);
            }
        }
        CompoundTag m = read(root.resolve("misc.dat"));
        if (m != null) {
            CompoundTag p = m.getCompound("pending");
            for (String k : p.getAllKeys()) {
                Deque<Pending> q = new ArrayDeque<>();
                for (Tag t : p.getList(k, Tag.TAG_COMPOUND)) {
                    CompoundTag e = (CompoundTag) t;
                    q.add(new Pending(e.getUUID("admin"), e.getLong("time"), e.getString("text")));
                }
                pending.put(UUID.fromString(k), q);
            }
            for (Tag t : m.getList("gmlog", Tag.TAG_COMPOUND)) {
                CompoundTag e = (CompoundTag) t;
                gmLog.add(new GmEntry(e.getLong("time"), e.getString("gm"), e.getString("action"), e.getString("target")));
            }
            CompoundTag l = m.getCompound("last");
            for (String k : l.getAllKeys()) lastAccount.put(UUID.fromString(k), l.getInt(k));
            CompoundTag ns = m.getCompound("noSignal");
            for (String k : ns.getAllKeys()) noSignalPlayers.put(UUID.fromString(k), ns.getLong(k));
            for (Tag t : m.getList("character", Tag.TAG_STRING)) characterMode.add(UUID.fromString(t.getAsString()));
            clock.load(m.getLongArray("rpclock"));
        }
        system();
        global();
        reindex();
    }

    /** Записать изменённое. {@code all} — ещё и выгрузить «свежие» чаты, к которым давно не обращались. */
    public void save() {
        if (accountsDirty) {
            ListTag l = new ListTag();
            for (Account acc : accounts.values()) l.add(acc.save());
            CompoundTag t = new CompoundTag();
            t.put("accounts", l);
            if (write(root.resolve("accounts.dat"), t)) accountsDirty = false;
        }
        if (chatsDirty) {
            ListTag l = new ListTag();
            for (Chat chat : chats.values()) l.add(chat.save());
            CompoundTag t = new CompoundTag();
            t.putInt("next", nextChatId);
            t.put("chats", l);
            if (write(root.resolve("chats.dat"), t)) chatsDirty = false;
        }
        if (miscDirty) {
            CompoundTag t = new CompoundTag();
            CompoundTag p = new CompoundTag();
            pending.forEach((k, q) -> {
                ListTag l = new ListTag();
                for (Pending e : q) {
                    CompoundTag ct = new CompoundTag();
                    ct.putUUID("admin", e.admin());
                    ct.putLong("time", e.time());
                    ct.putString("text", e.text());
                    l.add(ct);
                }
                p.put(k.toString(), l);
            });
            t.put("pending", p);
            ListTag g = new ListTag();
            for (GmEntry e : gmLog) {
                CompoundTag ct = new CompoundTag();
                ct.putLong("time", e.time());
                ct.putString("gm", e.gm());
                ct.putString("action", e.action());
                ct.putString("target", e.target());
                g.add(ct);
            }
            t.put("gmlog", g);
            CompoundTag l = new CompoundTag();
            lastAccount.forEach((k, v) -> l.putInt(k.toString(), v));
            t.put("last", l);
            CompoundTag ns = new CompoundTag();
            noSignalPlayers.forEach((k, v) -> ns.putLong(k.toString(), v));
            t.put("noSignal", ns);
            ListTag ch = new ListTag();
            for (UUID u : characterMode) ch.add(net.minecraft.nbt.StringTag.valueOf(u.toString()));
            t.put("character", ch);
            t.putLongArray("rpclock", clock.points());
            if (write(root.resolve("misc.dat"), t)) miscDirty = false;
        }
        for (ChatLog cl : logs.values()) if (cl.dirty) saveLog(cl);
    }

    public void markAccounts() {
        accountsDirty = true;
    }

    public void markChats() {
        chatsDirty = true;
    }

    public void markMisc() {
        miscDirty = true;
    }

    private void saveLog(ChatLog cl) {
        ListTag l = new ListTag();
        for (Message m : cl.messages) l.add(m.save());
        CompoundTag t = new CompoundTag();
        t.put("messages", l);
        if (write(root.resolve("chats").resolve(cl.chatId + ".dat"), t)) cl.dirty = false;
    }

    @Nullable
    private CompoundTag read(Path p) {
        File f = p.toFile();
        if (!f.isFile()) return null;
        try {
            return NbtIo.readCompressed(f);
        } catch (IOException e) {
            log.error("RP PDA: не прочитать {}: {}", p, e.toString());
            return null;
        }
    }

    private boolean write(Path p, CompoundTag t) {
        try {
            Files.createDirectories(p.getParent());
            Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
            NbtIo.writeCompressed(t, tmp.toFile());
            try {
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            log.error("RP PDA: не записать {}: {}", p, e.toString());
            return false;
        }
    }

    // ---------------------------------------------------------------- учётки

    public Account system() {
        return accounts.computeIfAbsent(0, id -> {
            accountsDirty = true;
            Account a = new Account(0, Account.Kind.SYSTEM, "Система");
            a.created = System.currentTimeMillis();
            return a;
        });
    }

    /** Учётка (удалённые — как нет). */
    @Nullable
    public Account account(int id) {
        Account a = accounts.get(id);
        return a == null || a.deleted ? null : a;
    }

    /** Учётка, даже удалённая (для подписей старых сообщений). */
    @Nullable
    public Account accountAny(int id) {
        return accounts.get(id);
    }

    /**
     * Удалить учётку (ГМ): КПК с ней больше не работают, данных нет. Личные диалоги удаляются, из групп она
     * выходит (её старые сообщения там остаются — «Удалённая учётка»), у других пропадает из контактов.
     */
    public void deleteAccount(Account a) {
        if (a.kind == Account.Kind.SYSTEM || a.deleted) return;
        a.deleted = true;
        a.contacts.clear();
        a.blocked.clear();
        a.notes.clear();
        a.invites.clear();
        a.pwHash = a.pwSalt = null;
        for (Integer id : new ArrayList<>(chatsOf(a.id))) {
            Chat c = chats.get(id);
            if (c == null) continue;
            if (c.type == Chat.Type.DM) deleteChat(c);
            else removeMember(c, a.id);
        }
        for (Chat c : chats.values()) c.invited.remove(a.id);
        for (Account o : accounts.values()) {
            if (o.contacts.remove(a.id) != null | o.blocked.remove(a.id)) accountsDirty = true;
        }
        lastAccount.values().removeIf(v -> v == a.id);
        accountsDirty = true;
        chatsDirty = true;
        miscDirty = true;
    }

    private void deleteChat(Chat c) {
        chats.remove(c.id);
        logs.remove(c.id);
        for (int m : c.members) {
            Set<Integer> s = chatsOf.get(m);
            if (s != null) s.remove(c.id);
        }
        if (c.members.size() == 2) {
            Iterator<Integer> it = c.members.iterator();
            dmIndex.remove(pair(it.next(), it.next()));
        }
        try {
            Files.deleteIfExists(root.resolve("chats").resolve(c.id + ".dat"));
            Path arch = root.resolve("archive").resolve(Integer.toString(c.id));
            if (Files.isDirectory(arch)) try (var files = Files.list(arch)) {
                for (Path f : files.toList()) Files.deleteIfExists(f);
            }
            Files.deleteIfExists(arch);
        } catch (IOException e) {
            log.warn("RP PDA: не удалить файлы чата {}: {}", c.id, e.toString());
        }
    }

    public Account createAccount(Account.Kind kind, String name, @Nullable UUID owner) {
        int id;
        do id = 10_000_000 + random.nextInt(90_000_000);
        while (accounts.containsKey(id));
        Account a = new Account(id, kind, name);
        a.owner = owner;
        a.created = System.currentTimeMillis();
        // Старое в общем чате — не непрочитанное.
        a.lastRead.put(Chat.GLOBAL_ID, global().lastSeq);
        accounts.put(id, a);
        accountsDirty = true;
        return a;
    }

    public List<Account> accountsOf(UUID owner) {
        List<Account> out = new ArrayList<>();
        for (Account a : accounts.values()) if (owner.equals(a.owner)) out.add(a);
        out.sort((x, y) -> Long.compare(x.created, y.created));
        return out;
    }

    // ---------------------------------------------------------------- чаты

    public Chat global() {
        return chats.computeIfAbsent(Chat.GLOBAL_ID, id -> {
            chatsDirty = true;
            Chat c = new Chat(id, Chat.Type.GLOBAL);
            c.created = System.currentTimeMillis();
            return c;
        });
    }

    @Nullable
    public Chat chat(int id) {
        return chats.get(id);
    }

    /** Чаты учётки, кроме общего. */
    public Set<Integer> chatsOf(int acc) {
        return chatsOf.getOrDefault(acc, Set.of());
    }

    private static long pair(int a, int b) {
        int lo = Math.min(a, b), hi = Math.max(a, b);
        return ((long) lo << 32) | (hi & 0xffffffffL);
    }

    @Nullable
    public Chat findDm(int a, int b) {
        Integer id = dmIndex.get(pair(a, b));
        return id == null ? null : chats.get(id);
    }

    public Chat dm(int a, int b) {
        Chat c = findDm(a, b);
        if (c != null) return c;
        c = new Chat(nextChatId++, Chat.Type.DM);
        c.created = System.currentTimeMillis();
        c.members.add(a);
        c.members.add(b);
        chats.put(c.id, c);
        dmIndex.put(pair(a, b), c.id);
        index(c);
        chatsDirty = true;
        return c;
    }

    public Chat createGroup(int creator, String title, boolean channel) {
        Chat c = new Chat(nextChatId++, channel ? Chat.Type.CHANNEL : Chat.Type.GROUP);
        c.created = System.currentTimeMillis();
        c.title = title;
        c.admin = creator;
        c.members.add(creator);
        chats.put(c.id, c);
        index(c);
        chatsDirty = true;
        return c;
    }

    public void addMember(Chat c, int acc) {
        c.members.add(acc);
        c.invited.remove(acc);
        chatsOf.computeIfAbsent(acc, k -> new HashSet<>()).add(c.id);
        Account a = accounts.get(acc);
        if (a != null) {
            a.invites.remove(c.id);
            a.lastRead.put(c.id, c.lastSeq);
            accountsDirty = true;
        }
        chatsDirty = true;
    }

    public void removeMember(Chat c, int acc) {
        c.members.remove(acc);
        Set<Integer> s = chatsOf.get(acc);
        if (s != null) s.remove(c.id);
        if (c.admin == acc) c.admin = c.members.isEmpty() ? 0 : c.members.iterator().next();
        chatsDirty = true;
    }

    private void reindex() {
        chatsOf.clear();
        dmIndex.clear();
        for (Chat c : chats.values()) {
            index(c);
            if (c.type == Chat.Type.DM && c.members.size() == 2) {
                Iterator<Integer> it = c.members.iterator();
                dmIndex.put(pair(it.next(), it.next()), c.id);
            }
        }
    }

    private void index(Chat c) {
        if (c.type == Chat.Type.GLOBAL) return;
        for (int m : c.members) chatsOf.computeIfAbsent(m, k -> new HashSet<>()).add(c.id);
    }

    public Collection<Chat> allChats() {
        return chats.values();
    }

    // ---------------------------------------------------------------- сообщения

    /** «Свежие» сообщения чата (загрузить с диска, если ещё не в памяти). */
    public ChatLog log(Chat c) {
        ChatLog cl = logs.get(c.id);
        if (cl == null) {
            cl = new ChatLog(c.id);
            CompoundTag t = read(root.resolve("chats").resolve(c.id + ".dat"));
            if (t != null) for (Tag e : t.getList("messages", Tag.TAG_COMPOUND)) cl.messages.add(Message.load((CompoundTag) e));
            cl.messages.sort((x, y) -> Integer.compare(x.seq, y.seq));
            logs.put(c.id, cl);
        }
        cl.lastAccess = System.currentTimeMillis();
        return cl;
    }

    public Message append(Chat c, int sender, String text, int replyTo, int flags, @Nullable String gm, long time) {
        ChatLog cl = log(c);
        // Размер чата — до нового сообщения (у старого чата он сначала оценивается).
        int before = chatBytes(c);
        Message m = new Message(++c.lastSeq, sender, time, text, replyTo, flags, gm);
        cl.messages.add(m);
        cl.dirty = true;
        c.lastTime = time;
        c.lastSender = sender;
        c.lastPreview = (flags & Message.NOTICE) != 0 ? ""
                : (flags & Message.NOTE) != 0 ? "#note:" + preview(text.split("\n", 2)[0])
                : (flags & Message.FILE) != 0 ? "#file:" + preview(fileTitle(text)) : preview(text);
        c.bytes = before + messageBytes(m);
        chatsDirty = true;
        return m;
    }

    /** Название файла из текста приглашения ({@link Message#FILE}: номер, размер, название). */
    public static String fileTitle(String text) {
        String[] p = text.split("\n", 3);
        return p.length < 3 ? text : p[2];
    }

    /** Сколько «весит» сообщение для памяти ретро-КПК: 1 знак = 1 байт (служебные строки — 0). */
    public static int messageBytes(Message m) {
        if (m.has(Message.NOTICE)) return 0;
        String t = m.has(Message.FILE) ? fileTitle(m.text) : m.text;
        return t.codePointCount(0, t.length());
    }

    /**
     * Размер всей переписки. У старых чатов (до памяти ретро-КПК) не считан: оценка по «свежим» сообщениям — их
     * сумма плюс средний размер на каждое ушедшее в архив.
     */
    public int chatBytes(Chat c) {
        if (c.bytes >= 0) return c.bytes;
        ChatLog cl = log(c);
        long sum = 0;
        int n = cl.messages.size();
        for (Message m : cl.messages) sum += messageBytes(m);
        long archivedCount = Math.max(0, c.lastSeq - cl.messages.size());
        long est = sum + (n == 0 ? 0 : archivedCount * sum / n);
        c.bytes = (int) Math.min(Integer.MAX_VALUE, est);
        chatsDirty = true;
        return c.bytes;
    }

    private static String preview(String text) {
        String s = text.replace('\n', ' ');
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }

    /** Номера, начиная с которых сообщения лежат в «свежих». */
    public static int hotFrom(Chat c) {
        return c.archived * CHUNK + 1;
    }

    /** Прочитать архивный кусок с диска. Можно звать не из потока сервера. */
    public List<Message> readChunk(int chatId, int chunk) {
        List<Message> out = new ArrayList<>();
        CompoundTag t = read(root.resolve("archive").resolve(Integer.toString(chatId)).resolve(chunk + ".dat"));
        if (t != null) for (Tag e : t.getList("messages", Tag.TAG_COMPOUND)) out.add(Message.load((CompoundTag) e));
        out.sort((x, y) -> Integer.compare(x.seq, y.seq));
        return out;
    }

    /**
     * Переложить в архив всё, что старше {@link #KEEP_HOT} последних сообщений, целыми кусками. Зовётся редко
     * (раз в несколько минут и при сохранении мира). Возвращает, сколько кусков ушло.
     */
    public int archiveOld() {
        int moved = 0;
        for (Chat c : chats.values()) {
            while (c.lastSeq - (c.archived + 1) * CHUNK >= KEEP_HOT) {
                ChatLog cl = log(c);
                int from = c.archived * CHUNK + 1, to = (c.archived + 1) * CHUNK;
                List<Message> part = cl.range(from, to);
                ListTag l = new ListTag();
                for (Message m : part) l.add(m.save());
                CompoundTag t = new CompoundTag();
                t.put("messages", l);
                if (!write(root.resolve("archive").resolve(Integer.toString(c.id)).resolve(c.archived + ".dat"), t)) break;
                cl.messages.removeIf(m -> m.seq <= to);
                cl.dirty = true;
                c.archived++;
                chatsDirty = true;
                moved++;
            }
        }
        return moved;
    }

    /** Выгрузить из памяти записанные «свежие» чаты, к которым не обращались {@code idleMs}. */
    public void unloadIdle(long idleMs) {
        long now = System.currentTimeMillis();
        logs.values().removeIf(cl -> !cl.dirty && now - cl.lastAccess > idleMs);
    }

    // ---------------------------------------------------------------- прочее

    public void addPending(UUID player, Pending p, int limit) {
        Deque<Pending> q = pending.computeIfAbsent(player, k -> new ArrayDeque<>());
        q.addLast(p);
        while (q.size() > limit) q.removeFirst();
        miscDirty = true;
    }

    public void gmLog(String gm, String action, String target) {
        gmLog.addLast(new GmEntry(System.currentTimeMillis(), gm, action, target));
        while (gmLog.size() > GM_LOG_LIMIT) gmLog.removeFirst();
        miscDirty = true;
    }

    public void setLastAccount(UUID player, int acc) {
        Integer old = lastAccount.put(player, acc);
        if (old == null || old != acc) miscDirty = true;
    }
}
