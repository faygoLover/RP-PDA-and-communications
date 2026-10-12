package faygolover.rppda.server;

import faygolover.rppda.config.PdaConfig;
import faygolover.rppda.data.Account;
import faygolover.rppda.data.Chat;
import faygolover.rppda.data.ChatLog;
import faygolover.rppda.data.Message;
import faygolover.rppda.data.Note;
import faygolover.rppda.data.PdaStore;
import faygolover.rppda.item.ModItems;
import faygolover.rppda.item.PdaItem;
import faygolover.rppda.network.Net;
import faygolover.rppda.util.Passwords;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/** Действия из окна КПК. Сервер проверяет всё сам: окно открыто, КПК ещё при игроке, права на чат. */
public final class Actions {
    private Actions() {}

    private static final int PAGE = 100;
    private static final int TITLE_MAX = 32;

    public static void handle(ServerPlayer sp, String op, CompoundTag d) {
        PdaStore store = PdaServer.store;
        if (store == null) return;
        PdaServer.Session cur = PdaServer.sessions.get(sp.getUUID());
        if (!op.equals("close") && !op.equals("open") && (cur == null || !cur.system) && faygolover.rpcore.api.RpCoreAPI.unconscious(sp)) {
            PdaServer.close(sp);
            return;
        }
        switch (op) {
            case "open" -> PdaServer.openHotkey(sp);
            case "close" -> {
                PdaServer.sessions.remove(sp.getUUID());
                Hack.end(sp);
                Cipher.end(sp.getUUID());
            }
            case "hack.pick", "hack.enter" -> Hack.handle(sp, op, d);
            case "register" -> register(sp, store);
            case "register.npc" -> registerNpc(sp, store, d.getString("name").strip());
            case "unlock" -> unlock(sp, store, d.getString("pw"));
            case "look.hand" -> lookHand(sp, store, d);
            default -> {
                PdaServer.Session s = PdaServer.sessions.get(sp.getUUID());
                if (s == null || s.mode != PdaServer.Session.Mode.OPEN || !valid(sp, s, store)) {
                    PdaServer.close(sp);
                    return;
                }
                Account me = store.account(s.acting);
                if (me == null) return;
                if (op.startsWith("sys.")) {
                    if (s.system) system(sp, s, store, op, d);
                    return;
                }
                session(sp, s, store, me, op, d);
            }
        }
    }

    private static boolean valid(ServerPlayer sp, PdaServer.Session s, PdaStore store) {
        if (s.system) return PdaServer.isAdmin(sp) && Devices.hasSystem(sp);
        Account a = store.account(s.device);
        return a != null && !a.locked && Devices.holds(sp, a);
    }

    static void toast(ServerPlayer sp, String key, boolean error, String... args) {
        CompoundTag t = new CompoundTag();
        t.putString("key", key);
        t.putBoolean("err", error);
        ListTag l = new ListTag();
        for (String a : args) l.add(StringTag.valueOf(a));
        t.put("args", l);
        Net.send(sp, "toast", t);
    }

    // ---------------------------------------------------------------- регистрация и пароль

    private static void register(ServerPlayer sp, PdaStore store) {
        PdaServer.Session s = PdaServer.sessions.get(sp.getUUID());
        if (s == null || s.mode != PdaServer.Session.Mode.REGISTER) return;
        ItemStack stack = sp.getItemInHand(s.hand);
        if (!Devices.isPda(stack) || PdaItem.account(stack) != 0) {
            PdaServer.close(sp);
            return;
        }
        String name = PdaServer.displayName(sp);
        Account a = store.createAccount(Account.Kind.PLAYER, name, sp.getUUID());
        // Вид, что ГМ задал новому КПК, переходит в учётку.
        a.look = PdaItem.look(stack);
        PdaItem.bind(stack, a.id, a.issue, a.name);
        PdaItem.setLook(stack, a.look);
        PdaServer.flushPending(sp, a);
        store.setLastAccount(sp.getUUID(), a.id);
        PdaServer.open(sp, a);
        PdaServer.scan(sp);
        toast(sp, "rppda.toast.registered", false, PdaItem.formatId(a.id));
    }

    /** ГМ регистрирует новый КПК сразу на NPC-учётку (владельца нет). */
    private static void registerNpc(ServerPlayer sp, PdaStore store, String name) {
        PdaServer.Session s = PdaServer.sessions.get(sp.getUUID());
        if (!PdaServer.isGm(sp) || s == null || s.mode != PdaServer.Session.Mode.REGISTER) return;
        if (name.isEmpty() || name.length() > TITLE_MAX) {
            toast(sp, "rppda.err.title", true);
            return;
        }
        ItemStack stack = sp.getItemInHand(s.hand);
        if (!Devices.isPda(stack) || PdaItem.account(stack) != 0) {
            PdaServer.close(sp);
            return;
        }
        Account a = store.createAccount(Account.Kind.NPC, name, null);
        a.look = PdaItem.look(stack);
        PdaItem.bind(stack, a.id, a.issue, a.name);
        PdaItem.setLook(stack, a.look);
        store.gmLog(sp.getGameProfile().getName(), "npc", label(a));
        PdaServer.open(sp, a);
        PdaServer.scan(sp);
        toast(sp, "rppda.toast.registered", false, PdaItem.formatId(a.id));
    }

    private static void unlock(ServerPlayer sp, PdaStore store, String pw) {
        PdaServer.Session s = PdaServer.sessions.get(sp.getUUID());
        if (s == null || s.mode != PdaServer.Session.Mode.LOCKED) return;
        Account a = store.account(s.device);
        if (a == null || !Devices.holds(sp, a)) {
            PdaServer.close(sp);
            return;
        }
        if (a.burned) return;
        if (!a.locked || !a.hasPassword()) {
            a.locked = false;
            PdaServer.open(sp, a);
            return;
        }
        if (!checkPassword(sp, a, pw)) return;
        a.locked = false;
        a.pwLen = pw.length();
        if (sp.getUUID().equals(a.owner)) a.hackNotice = 0;
        store.markAccounts();
        PdaServer.holders.put(a.id, sp.getUUID());
        PdaServer.open(sp, a);
        PdaServer.scan(sp);
    }

    /** Проверить пароль с учётом пауз после неверных попыток. */
    private static boolean checkPassword(ServerPlayer sp, Account a, String pw) {
        long wait = PdaServer.lockoutLeft(sp);
        if (wait > 0) {
            toast(sp, "rppda.err.wait", true, Long.toString((wait + 999) / 1000));
            return false;
        }
        if (Passwords.check(pw, a.pwSalt, a.pwHash)) {
            PdaServer.failed.remove(sp.getUUID());
            return true;
        }
        int[] f = PdaServer.failed.computeIfAbsent(sp.getUUID(), k -> new int[1]);
        f[0]++;
        if (f[0] >= PdaConfig.passwordAttempts) {
            f[0] = 0;
            PdaServer.lockoutUntil.put(sp.getUUID(), System.currentTimeMillis() + PdaConfig.lockoutSeconds * 1000L);
            toast(sp, "rppda.err.wait", true, Integer.toString(PdaConfig.lockoutSeconds));
        } else toast(sp, "rppda.err.password", true);
        return false;
    }

    // ---------------------------------------------------------------- окно

    private static void session(ServerPlayer sp, PdaServer.Session s, PdaStore store, Account me, String op, CompoundTag d) {
        switch (op) {
            case "state" -> Views.sendState(sp, s);
            case "lock" -> {
                if (s.system || !me.hasPassword()) return;
                PdaServer.lock(me.id);
            }
            case "chat.open" -> {
                Chat c = store.chat(d.getInt("chat"));
                if (c == null || !canRead(s, me, c)) return;
                s.openChat = c.id;
                history(sp, s, store, c, 0);
                if (c.has(me.id) && !s.gmSwitched() && !(s.system && c.type != Chat.Type.GLOBAL && !c.has(0))) {
                    long cut = Views.cut(sp, s);
                    Message seen = cut == Long.MAX_VALUE || PdaServer.emergency(c) ? null : Views.lastVisible(store, c, cut);
                    int upTo = cut == Long.MAX_VALUE || PdaServer.emergency(c) ? c.lastSeq : seen == null ? 0 : seen.seq;
                    me.lastRead.put(c.id, Math.max(me.lastRead.getOrDefault(c.id, 0), upTo));
                    store.markAccounts();
                    if (!s.system) Messaging.sendUnread(sp);
                }
            }
            case "chat.leave" -> s.openChat = -1;
            case "chat.history" -> {
                Chat c = store.chat(d.getInt("chat"));
                if (c != null && canRead(s, me, c)) history(sp, s, store, c, d.getInt("before"));
            }
            case "chat.send" -> {
                Chat c = store.chat(d.getInt("chat"));
                if (c == null) return;
                String text = d.getString("text").strip();
                String err = Messaging.check(me, c, text, s.system ? null : sp);
                if (err != null) {
                    toast(sp, err, true);
                    return;
                }
                int reply = d.getInt("reply");
                if (reply > 0 && store.log(c).find(reply) == null) reply = 0;
                String gm = s.system ? sp.getGameProfile().getName() : null;
                Messaging.post(c, me, text, reply, 0, gm, null);
            }
            case "chat.delete" -> delete(sp, s, store, me, d);
            case "chat.notify" -> {
                Chat c = store.chat(d.getInt("chat"));
                if (c == null || !c.has(me.id)) return;
                Account.Notify[] modes = Account.Notify.values();
                int m = d.getInt("mode");
                if (m < 0 || m >= modes.length) return;
                if (modes[m] == Account.Notify.SOUND) me.notify.remove(c.id);
                else me.notify.put(c.id, modes[m]);
                store.markAccounts();
                sendRow(sp, s, store, me, c);
                if (!s.system) Messaging.sendUnread(sp);
            }
            case "chat.out" -> {
                Chat c = store.chat(d.getInt("chat"));
                if (c == null || !c.has(me.id)) return;
                boolean on = d.getBoolean("on");
                if (on && !PdaConfig.allowChatOutput && !PdaServer.isAdmin(sp)) {
                    toast(sp, "rppda.err.chat_out_off", true);
                    return;
                }
                if (on) me.chatOut.add(c.id);
                else me.chatOut.remove(c.id);
                store.markAccounts();
                sendRow(sp, s, store, me, c);
            }
            case "notes.list" -> sendNotes(sp, me);
            case "notes.encrypt" -> Cipher.encrypt(sp, store, me, d.getInt("id"));
            case "cipher.start" -> Cipher.start(sp, store, me, d.getInt("id"));
            case "cipher.pick", "cipher.timeout", "cipher.quit" -> Cipher.handle(sp, store, me, op, d);
            case "notes.save" -> {
                String title = d.getString("title").strip(), text = d.getString("text");
                if (title.isEmpty()) title = Note.firstLine(text);
                if (title.length() > Note.TITLE_MAX) title = title.substring(0, Note.TITLE_MAX);
                if (text.length() > Note.TEXT_MAX) text = text.substring(0, Note.TEXT_MAX);
                int id = d.getInt("id");
                Note n = null;
                for (Note o : me.notes) if (o.id == id) n = o;
                // Полученные заметки не меняются (только удалить); зашифрованную чужую — тоже нельзя.
                if (n != null && (n.from != null || !Cipher.readable(sp, me, n))) return;
                if (n == null) {
                    if (me.notes.size() >= Note.LIMIT) {
                        toast(sp, "rppda.err.notes_full", true);
                        return;
                    }
                    n = new Note(me.nextNote++, title, text);
                    n.created = System.currentTimeMillis();
                    me.notes.add(n);
                }
                n.title = title;
                n.text = text;
                n.updated = System.currentTimeMillis();
                store.markAccounts();
                sendNotes(sp, me);
                CompoundTag t = new CompoundTag();
                t.putInt("id", n.id);
                Net.send(sp, "notes.saved", t);
            }
            case "notes.delete" -> {
                int id = d.getInt("id");
                me.notes.removeIf(n -> n.id == id);
                store.markAccounts();
                sendNotes(sp, me);
            }
            case "notes.share" -> {
                Note n = null;
                for (Note o : me.notes) if (o.id == d.getInt("id")) n = o;
                Chat c = store.chat(d.getInt("chat"));
                if (n == null || c == null) return;
                String err = Messaging.check(me, c, "note", s.system ? null : sp);
                if (err != null) {
                    toast(sp, err, true);
                    return;
                }
                Messaging.post(c, me, n.title + "\n" + n.text, 0, Message.NOTE | (n.cipher && !n.fullyOpened() ? Message.CIPHER : 0),
                        s.system ? sp.getGameProfile().getName() : null, null);
                toast(sp, "rppda.toast.note_shared", false);
            }
            case "notes.accept" -> {
                Chat c = store.chat(d.getInt("chat"));
                if (c == null || !canRead(s, me, c)) return;
                Message m = store.log(c).find(d.getInt("seq"));
                if (m == null || !m.has(Message.NOTE) || m.deleted()) return;
                if (me.notes.size() >= Note.LIMIT) {
                    toast(sp, "rppda.err.notes_full", true);
                    return;
                }
                int nl = m.text.indexOf('\n');
                Note n = new Note(me.nextNote++, nl < 0 ? m.text : m.text.substring(0, nl), nl < 0 ? "" : m.text.substring(nl + 1));
                n.created = n.updated = System.currentTimeMillis();
                n.from = Views.nameFor(store, me, m.sender);
                n.cipher = m.has(Message.CIPHER);
                me.notes.add(n);
                store.markAccounts();
                toast(sp, "rppda.toast.note_added", false);
                sendNotes(sp, me);
            }
            case "dm" -> {
                if (noSignal(sp, s)) return;
                int id = d.getInt("id");
                Account peer = store.account(id);
                if (peer == null || peer.kind == Account.Kind.SYSTEM && !s.system) {
                    toast(sp, "rppda.err.no_account", true);
                    return;
                }
                if (peer.id == me.id) return;
                Chat c = store.dm(me.id, peer.id);
                CompoundTag t = new CompoundTag();
                t.put("row", Views.row(store, me, c, s));
                Net.send(sp, "goto", t);
            }
            case "group.create" -> {
                if (noSignal(sp, s)) return;
                String title = d.getString("title").strip();
                if (title.isEmpty() || title.length() > TITLE_MAX) {
                    toast(sp, "rppda.err.title", true);
                    return;
                }
                Chat c = store.createGroup(me.id, title, d.getBoolean("channel"));
                Messaging.notice(c, "rppda.notice.created", me.name);
                CompoundTag t = new CompoundTag();
                t.put("row", Views.row(store, me, c, s));
                Net.send(sp, "goto", t);
            }
            case "group.invite", "group.kick", "group.admin", "group.rename", "group.leave", "group.members",
                 "group.accept", "group.decline" -> {
                if (!op.equals("group.members") && noSignal(sp, s)) return;
                group(sp, s, store, me, op, d);
            }
            case "contact.add", "contact.label" -> {
                int id = d.getInt("id");
                Account o = store.account(id);
                if (o == null || o.kind == Account.Kind.SYSTEM) {
                    toast(sp, "rppda.err.no_account", true);
                    return;
                }
                if (o.id == me.id) return;
                String label = d.getString("label").strip();
                if (label.length() > TITLE_MAX) label = label.substring(0, TITLE_MAX);
                me.contacts.put(id, label);
                store.markAccounts();
                Views.sendState(sp, s);
            }
            case "contact.remove" -> {
                me.contacts.remove(d.getInt("id"));
                store.markAccounts();
                Views.sendState(sp, s);
            }
            case "contact.block" -> {
                int id = d.getInt("id");
                if (store.account(id) == null || id == me.id) return;
                if (d.getBoolean("on")) me.blocked.add(id);
                else me.blocked.remove(id);
                store.markAccounts();
                Views.sendState(sp, s);
            }
            case "profile.password" -> {
                if (s.system) return;
                String old = d.getString("old"), pw = d.getString("pw");
                if (me.hasPassword() && !checkPassword(sp, me, old)) return;
                if (pw.length() > Passwords.MAX_LENGTH) {
                    toast(sp, "rppda.err.password_long", true);
                    return;
                }
                if (pw.isEmpty()) {
                    me.pwHash = me.pwSalt = null;
                    PdaServer.holders.remove(me.id);
                    toast(sp, "rppda.toast.password_removed", false);
                } else {
                    me.pwSalt = Passwords.newSalt();
                    me.pwHash = Passwords.hash(pw, me.pwSalt);
                    me.pwLen = pw.length();
                    PdaServer.holders.put(me.id, sp.getUUID());
                    toast(sp, "rppda.toast.password_set", false);
                }
                store.markAccounts();
                Views.sendState(sp, s);
            }
            case "profile.silent" -> {
                me.silent = d.getBoolean("on");
                store.markAccounts();
                Views.sendState(sp, s);
            }
            case "profile.avatar" -> {
                // Системный КПК в своей учётке (Система) аватарку не меняет; ГМ в чужой учётке — может.
                if (me.kind == Account.Kind.SYSTEM) return;
                int mode = d.getInt("mode");
                if (mode < Account.AV_FACE || mode > Account.AV_PICTURE) return;
                // Портрет — фото скина владельца (у NPC владельца нет).
                if (mode == Account.AV_PORTRAIT && me.owner == null) return;
                // Своя картинка: новая — в этом же действии; без новой — только если уже загружена.
                if (d.contains("png") && !AvatarPics.upload(sp, store, me, d.getByteArray("png"))) return;
                if (mode == Account.AV_PICTURE && me.avatarPic == 0) return;
                int color = d.getInt("color");
                me.avatar = mode;
                me.avatarColor = color >= 0 && color < Account.AV_COLORS ? color : -1;
                me.avatarMark = avatarMark(d.getString("mark"));
                store.markAccounts();
                Views.sendState(sp, s);
            }
            case "avatar.get" -> {
                ListTag l = new ListTag();
                int[] ids = d.getIntArray("ids");
                for (int i = 0; i < ids.length && i < 64; i++) {
                    // Нет учётки или удалена — вид по умолчанию (клиент перестанет спрашивать).
                    Account a = store.accountAny(ids[i]);
                    CompoundTag e = a == null || a.deleted ? new CompoundTag() : Views.avatar(a, new CompoundTag());
                    e.putInt("id", ids[i]);
                    l.add(e);
                }
                CompoundTag t = new CompoundTag();
                t.put("list", l);
                Net.send(sp, "avatars", t);
            }
            case "nearby", "nearby.offer", "nearby.accept", "nearby.decline" -> Nearby.handle(sp, s, store, me, op, d);
            case "avatar.pic" -> AvatarPics.query(sp, store, d.getInt("id"));
            default -> {}
        }
    }

    /** Знаки аватарки: 1–2 видимых символа, без кодов форматирования. */
    static String avatarMark(String raw) {
        StringBuilder b = new StringBuilder();
        raw.strip().codePoints().filter(cp -> cp != '§' && !Character.isISOControl(cp) && !Character.isWhitespace(cp)
                && Character.getType(cp) != Character.FORMAT).limit(2).forEach(b::appendCodePoint);
        return b.toString();
    }

    private static boolean canRead(PdaServer.Session s, Account me, Chat c) {
        return c.has(me.id) || s.system;
    }

    private static void sendRow(ServerPlayer sp, PdaServer.Session s, PdaStore store, Account me, Chat c) {
        CompoundTag t = new CompoundTag();
        t.put("row", Views.row(store, me, c, s));
        Net.send(sp, "row", t);
    }

    /** Страница из {@link #PAGE} сообщений до номера {@code before} (0 — последние). Архив читается в отдельном потоке. */
    private static void history(ServerPlayer sp, PdaServer.Session s, PdaStore store, Chat c, int before) {
        int to = before <= 0 ? c.lastSeq : Math.min(c.lastSeq, before - 1);
        int from = Math.max(1, to - PAGE + 1);
        int hotFrom = PdaStore.hotFrom(c);
        if (to < 1 || from >= hotFrom) {
            sendHistory(sp, s, store, c, before, from, to < 1 ? List.of() : store.log(c).range(from, to));
            return;
        }
        int firstChunk = (from - 1) / PdaStore.CHUNK;
        int lastChunk = Math.min((to - 1) / PdaStore.CHUNK, c.archived - 1);
        int chatId = c.id;
        var server = PdaServer.server;
        PdaServer.io.execute(() -> {
            List<Message> old = new ArrayList<>();
            for (int k = firstChunk; k <= lastChunk; k++) old.addAll(store.readChunk(chatId, k));
            if (server == null) return;
            server.execute(() -> {
                if (PdaServer.store != store || PdaServer.sessions.get(sp.getUUID()) != s) return;
                List<Message> all = new ArrayList<>();
                for (Message m : old) if (m.seq >= from && m.seq <= to) all.add(m);
                all.addAll(store.log(c).range(Math.max(from, hotFrom), to));
                all.sort(Comparator.comparingInt(m -> m.seq));
                sendHistory(sp, s, store, c, before, from, all);
            });
        });
    }

    private static void sendHistory(ServerPlayer sp, PdaServer.Session s, PdaStore store, Chat c, int before, int from,
                                    List<Message> msgs) {
        CompoundTag t = new CompoundTag();
        t.putInt("chat", c.id);
        t.putInt("before", before);
        t.putBoolean("more", from > 1);
        long cut = Views.cut(sp, s);
        Account viewer = store.account(s.acting);
        if (c.type == Chat.Type.GLOBAL && viewer != null && viewer.id != 0) {
            // Новый КПК не знает, что писали в общем чате до его появления.
            List<Message> seen = new ArrayList<>();
            for (Message m : msgs) if (m.time >= viewer.created) seen.add(m);
            msgs = seen;
        }
        if (cut != Long.MAX_VALUE && !PdaServer.emergency(c)) {
            // Без связи — только то, что пришло до её пропажи.
            List<Message> seen = new ArrayList<>();
            for (Message m : msgs) if (m.time <= cut) seen.add(m);
            msgs = seen;
        }
        t.put("msgs", Views.page(store, s, c, msgs));
        t.put("row", Views.row(store, store.account(s.acting), c, s, cut));
        Net.send(sp, "history", t);
    }

    static void sendNotes(ServerPlayer sp, Account me) {
        ListTag l = new ListTag();
        for (int i = me.notes.size() - 1; i >= 0; i--) l.add(Cipher.view(sp, me, me.notes.get(i)));
        CompoundTag t = new CompoundTag();
        t.put("list", l);
        Net.send(sp, "notes", t);
    }

    /** Окно КПК без связи: сказать и вернуть true. */
    private static boolean noSignal(ServerPlayer sp, PdaServer.Session s) {
        if (s.system) return false;
        Account dev = PdaServer.store.account(s.device);
        if (dev == null || PdaServer.signal(sp, dev)) return false;
        toast(sp, "rppda.err.no_signal", true);
        return true;
    }

    // ---------------------------------------------------------------- внешний вид (ГМ)

    /** ГМ задаёт вид новому (не зарегистрированному) КПК у себя в руке. */
    private static void lookHand(ServerPlayer sp, PdaStore store, CompoundTag d) {
        PdaServer.Session s = PdaServer.sessions.get(sp.getUUID());
        if (!PdaServer.isGm(sp) || s == null || s.mode != PdaServer.Session.Mode.REGISTER) return;
        ItemStack stack = sp.getItemInHand(s.hand);
        if (!Devices.isPda(stack) || PdaItem.account(stack) != 0) return;
        PdaItem.setLook(stack, faygolover.rppda.data.Look.load(d.getCompound("look")));
        PdaServer.sendRegister(sp, stack);
    }

    /** Вид учётки (из системного КПК или командой): у всех, кто держит её КПК, окно обновится. */
    public static void setLook(PdaStore store, Account a, faygolover.rppda.data.Look look, String gm) {
        if (a.look.same(look)) return;
        a.look = look;
        store.markAccounts();
        store.gmLog(gm, "look", label(a));
        if (PdaServer.server == null) return;
        for (ServerPlayer p : PdaServer.server.getPlayerList().getPlayers()) {
            PdaServer.Session o = PdaServer.sessions.get(p.getUUID());
            if (o != null && !o.system && o.device == a.id && o.mode == PdaServer.Session.Mode.OPEN) Views.sendState(p, o);
            PdaServer.scan(p);
        }
    }

    /** Связь на КПК (учётке). */
    public static void setSignal(PdaStore store, Account a, boolean on, String gm) {
        if (a.noSignal == !on) return;
        a.noSignal = !on;
        a.noSignalSince = on ? 0 : System.currentTimeMillis();
        store.markAccounts();
        store.gmLog(gm, on ? "signal_on" : "signal_off", label(a));
        PdaServer.signalChanged(a.id, null);
    }

    /** Связь у игрока (любой КПК у него в руках). */
    public static void setPlayerSignal(PdaStore store, java.util.UUID player, String name, boolean on, String gm) {
        boolean has = !store.noSignalPlayers.containsKey(player);
        if (has == on) return;
        if (on) store.noSignalPlayers.remove(player);
        else store.noSignalPlayers.put(player, System.currentTimeMillis());
        store.markMisc();
        store.gmLog(gm, on ? "signal_on" : "signal_off", name);
        PdaServer.signalChanged(null, player);
    }

    /** Режим персонажа: админ — как обычный игрок (системный КПК и команды остаются). */
    public static void setCharacter(PdaStore store, ServerPlayer sp, boolean on) {
        if (on) store.characterMode.add(sp.getUUID());
        else store.characterMode.remove(sp.getUUID());
        store.markMisc();
        store.gmLog(sp.getGameProfile().getName(), on ? "character_on" : "character_off", "");
        sp.displayClientMessage(net.minecraft.network.chat.Component.translatable(on ? "rppda.msg.character_on" : "rppda.msg.character_off")
                .withStyle(net.minecraft.ChatFormatting.GOLD), true);
        PdaServer.scan(sp);
        Messaging.sendUnread(sp);
    }

    private static void delete(ServerPlayer sp, PdaServer.Session s, PdaStore store, Account me, CompoundTag d) {
        Chat c = store.chat(d.getInt("chat"));
        if (c == null || !canRead(s, me, c)) return;
        ChatLog log = store.log(c);
        Message m = log.find(d.getInt("seq"));
        if (m == null) {
            toast(sp, "rppda.err.too_old", true);
            return;
        }
        if (m.deleted() || m.has(Message.NOTICE)) return;
        boolean gm = s.system;
        if (m.sender != me.id && !gm) return;
        m.flags |= Message.DELETED;
        log.dirty = true;
        if (m.seq == c.lastSeq) {
            c.lastPreview = "";
            store.markChats();
        }
        if (gm && m.sender != me.id) {
            Account from = store.account(m.sender);
            store.gmLog(sp.getGameProfile().getName(), "delete", (from == null ? "?" : from.name) + ": " + m.text);
        }
        CompoundTag t = new CompoundTag();
        t.putInt("chat", c.id);
        t.putInt("seq", m.seq);
        for (ServerPlayer p : sp.server.getPlayerList().getPlayers()) {
            PdaServer.Session o = PdaServer.sessions.get(p.getUUID());
            if (o != null && o.openChat == c.id) Net.send(p, "deleted", t);
        }
    }

    // ---------------------------------------------------------------- группы

    private static void group(ServerPlayer sp, PdaServer.Session s, PdaStore store, Account me, String op, CompoundTag d) {
        Chat c = store.chat(d.getInt("chat"));
        if (c == null || !c.isGroup()) return;
        switch (op) {
            case "group.accept" -> {
                if (!me.invites.contains(c.id)) return;
                store.addMember(c, me.id);
                store.markAccounts();
                Messaging.notice(c, "rppda.notice.joined", me.name);
                Views.sendState(sp, s);
                if (!s.system) Messaging.sendUnread(sp);
                return;
            }
            case "group.decline" -> {
                me.invites.remove(c.id);
                c.invited.remove(me.id);
                store.markAccounts();
                store.markChats();
                Views.sendState(sp, s);
                if (!s.system) Messaging.sendUnread(sp);
                return;
            }
            case "group.members" -> {
                if (!canRead(s, me, c)) return;
                sendMembers(sp, store, me, c);
                return;
            }
            default -> {}
        }
        if (!c.has(me.id)) return;
        boolean admin = c.admin == me.id;
        switch (op) {
            case "group.invite" -> {
                if (!admin) return;
                Account o = store.account(d.getInt("id"));
                if (o == null || o.kind == Account.Kind.SYSTEM) {
                    toast(sp, "rppda.err.no_account", true);
                    return;
                }
                if (c.has(o.id) || o.invites.contains(c.id)) {
                    toast(sp, "rppda.err.already", true);
                    return;
                }
                if (o.blocked.contains(me.id)) {
                    toast(sp, "rppda.err.blocked_by", true);
                    return;
                }
                o.invites.add(c.id);
                c.invited.add(o.id);
                store.markAccounts();
                store.markChats();
                toast(sp, "rppda.toast.invited", false, Views.nameFor(store, me, o.id));
                notifyInvite(o);
                sendMembers(sp, store, me, c);
            }
            case "group.kick" -> {
                int id = d.getInt("id");
                if (!admin || id == me.id) return;
                if (c.invited.remove(id)) {
                    Account o = store.account(id);
                    if (o != null) o.invites.remove(c.id);
                    store.markAccounts();
                    store.markChats();
                } else if (c.has(id)) {
                    store.removeMember(c, id);
                    Account o = store.account(id);
                    Messaging.notice(c, "rppda.notice.kicked", me.name, o == null ? "?" : o.name);
                }
                sendMembers(sp, store, me, c);
            }
            case "group.admin" -> {
                int id = d.getInt("id");
                if (!admin || !c.members.contains(id) || id == me.id) return;
                c.admin = id;
                store.markChats();
                Account o = store.account(id);
                Messaging.notice(c, "rppda.notice.admin", o == null ? "?" : o.name);
                sendMembers(sp, store, me, c);
            }
            case "group.rename" -> {
                String title = d.getString("title").strip();
                if (!admin) return;
                if (title.isEmpty() || title.length() > TITLE_MAX) {
                    toast(sp, "rppda.err.title", true);
                    return;
                }
                c.title = title;
                store.markChats();
                Messaging.notice(c, "rppda.notice.renamed", me.name, title);
                sendMembers(sp, store, me, c);
            }
            case "group.leave" -> {
                store.removeMember(c, me.id);
                Messaging.notice(c, "rppda.notice.left", me.name);
                if (admin && c.admin != 0) {
                    Account o = store.account(c.admin);
                    Messaging.notice(c, "rppda.notice.admin", o == null ? "?" : o.name);
                }
                if (s.openChat == c.id) s.openChat = -1;
                Views.sendState(sp, s);
            }
            default -> {}
        }
    }

    private static void notifyInvite(Account o) {
        if (PdaServer.server == null) return;
        for (ServerPlayer p : PdaServer.server.getPlayerList().getPlayers()) {
            Integer act = PdaServer.active.get(p.getUUID());
            if (act != null && act == o.id) Messaging.sendUnread(p);
        }
    }

    private static void sendMembers(ServerPlayer sp, PdaStore store, Account me, Chat c) {
        CompoundTag t = new CompoundTag();
        t.putInt("chat", c.id);
        t.putString("title", c.title);
        t.putInt("admin", c.admin);
        t.putBoolean("channel", c.type == Chat.Type.CHANNEL);
        ListTag l = new ListTag();
        for (int id : c.members) {
            Account a = store.account(id);
            if (a == null) continue;
            CompoundTag e = Views.account(store, a, null);
            e.putString("name", Views.nameFor(store, me, id));
            l.add(e);
        }
        t.put("members", l);
        ListTag inv = new ListTag();
        for (int id : c.invited) {
            Account a = store.account(id);
            if (a == null) continue;
            CompoundTag e = Views.account(store, a, null);
            e.putString("name", Views.nameFor(store, me, id));
            inv.add(e);
        }
        t.put("invited", inv);
        Net.send(sp, "members", t);
    }

    // ---------------------------------------------------------------- системный КПК

    private static void system(ServerPlayer sp, PdaServer.Session s, PdaStore store, String op, CompoundTag d) {
        String gm = sp.getGameProfile().getName();
        switch (op) {
            case "sys.accounts" -> {
                String q = d.getString("q").strip().toLowerCase(Locale.ROOT);
                List<Account> list = new ArrayList<>();
                for (Account a : store.accounts.values()) {
                    // Удалённые учётки остаются в хранилище (подпись «Удалённая учётка» в группах), но не в списке.
                    if (a.deleted) continue;
                    if (q.isEmpty() || a.name.toLowerCase(Locale.ROOT).contains(q)
                            || String.format("%08d", a.id).contains(q.replace(" ", ""))) list.add(a);
                }
                list.sort(Comparator.comparing((Account a) -> a.kind != Account.Kind.SYSTEM)
                        .thenComparing(a -> a.name.toLowerCase(Locale.ROOT)));
                ListTag l = new ListTag();
                for (Account a : list) {
                    if (l.size() >= 300) break;
                    l.add(sysAccount(store, a));
                }
                CompoundTag t = new CompoundTag();
                t.put("list", l);
                Net.send(sp, "sys.accounts", t);
            }
            case "sys.chats" -> {
                List<Chat> list = new ArrayList<>();
                for (Chat c : store.allChats()) if (c.type != Chat.Type.DM || c.lastSeq > 0) list.add(c);
                list.sort(Comparator.comparingLong((Chat c) -> c.lastTime).reversed());
                Account viewer = store.account(s.acting);
                ListTag l = new ListTag();
                for (Chat c : list) {
                    if (l.size() >= 500) break;
                    l.add(Views.row(store, viewer, c, s));
                }
                CompoundTag t = new CompoundTag();
                t.put("list", l);
                Net.send(sp, "sys.chats", t);
            }
            case "sys.log" -> {
                ListTag l = new ListTag();
                Iterator<PdaStore.GmEntry> it = store.gmLog.descendingIterator();
                while (it.hasNext() && l.size() < 300) {
                    PdaStore.GmEntry e = it.next();
                    CompoundTag ct = new CompoundTag();
                    ct.putLong("time", e.time());
                    ct.putString("gm", e.gm());
                    ct.putString("action", e.action());
                    ct.putString("target", e.target());
                    l.add(ct);
                }
                CompoundTag t = new CompoundTag();
                t.put("list", l);
                Net.send(sp, "sys.log", t);
            }
            case "sys.switch" -> {
                Account a = store.account(d.getInt("id"));
                if (a == null) return;
                s.acting = a.id;
                s.openChat = -1;
                if (a.id != 0) store.gmLog(gm, "switch", label(a));
                Views.sendState(sp, s);
            }
            case "sys.give" -> {
                Account a = target(store, d);
                if (a == null) return;
                a.issue++;
                a.burned = false;
                if (a.hasPassword()) a.locked = true;
                PdaServer.holders.remove(a.id);
                store.markAccounts();
                ItemStack stack = new ItemStack(ModItems.PDA.get());
                PdaItem.bind(stack, a.id, a.issue, a.name);
                PdaItem.setLook(stack, a.look);
                if (!sp.getInventory().add(stack)) sp.drop(stack, false);
                store.gmLog(gm, "give", label(a));
                toast(sp, "rppda.toast.given", false, a.name);
                PdaServer.lock(a.id);
                refreshAccount(sp, store, a);
            }
            case "sys.unlock" -> {
                Account a = target(store, d);
                if (a == null) return;
                a.locked = false;
                store.markAccounts();
                store.gmLog(gm, "unlock", label(a));
                refreshAccount(sp, store, a);
            }
            case "sys.avatar_reset" -> {
                Account a = target(store, d);
                if (a == null || a.avatarPic == 0) return;
                AvatarPics.remove(store, a);
                store.markAccounts();
                store.gmLog(gm, "avatar_reset", label(a));
                refreshAccount(sp, store, a);
            }
            case "sys.resetpw" -> {
                Account a = target(store, d);
                if (a == null) return;
                a.pwHash = a.pwSalt = null;
                a.locked = false;
                PdaServer.holders.remove(a.id);
                store.markAccounts();
                store.gmLog(gm, "resetpw", label(a));
                refreshAccount(sp, store, a);
            }
            case "sys.tp" -> {
                Account a = target(store, d);
                if (a == null || a.owner == null) return;
                ServerPlayer p = sp.server.getPlayerList().getPlayer(a.owner);
                if (p == null) {
                    toast(sp, "rppda.err.offline", true);
                    return;
                }
                sp.teleportTo(p.serverLevel(), p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot());
                store.gmLog(gm, "tp", label(a));
            }
            case "sys.npc" -> {
                String name = d.getString("name").strip();
                if (name.isEmpty() || name.length() > TITLE_MAX) {
                    toast(sp, "rppda.err.title", true);
                    return;
                }
                Account a = store.createAccount(Account.Kind.NPC, name, null);
                ItemStack stack = new ItemStack(ModItems.PDA.get());
                PdaItem.bind(stack, a.id, a.issue, a.name);
                PdaItem.setLook(stack, a.look);
                if (!sp.getInventory().add(stack)) sp.drop(stack, false);
                store.gmLog(gm, "npc", label(a));
                toast(sp, "rppda.toast.npc", false, a.name, PdaItem.formatId(a.id));
                refreshAccount(sp, store, a);
            }
            case "sys.look" -> {
                Account a = target(store, d);
                if (a == null) return;
                setLook(store, a, faygolover.rppda.data.Look.load(d.getCompound("look")), gm);
                refreshAccount(sp, store, a);
            }
            case "sys.signal" -> {
                Account a = target(store, d);
                if (a == null) return;
                setSignal(store, a, d.getBoolean("on"), gm);
                refreshAccount(sp, store, a);
            }
            case "sys.signalOwner" -> {
                Account a = target(store, d);
                if (a == null || a.owner == null) return;
                setPlayerSignal(store, a.owner, a.name, d.getBoolean("on"), gm);
                refreshAccount(sp, store, a);
            }
            case "sys.delete" -> {
                Account a = target(store, d);
                if (a == null) return;
                String who = label(a);
                // Окна с этой учёткой закрываются, КПК с ней больше не работают.
                for (ServerPlayer p : sp.server.getPlayerList().getPlayers()) {
                    PdaServer.Session o = PdaServer.sessions.get(p.getUUID());
                    if (o != null && !o.system && o.device == a.id) PdaServer.close(p);
                    if (o != null && o.system && o.acting == a.id) o.acting = 0;
                }
                PdaServer.holders.remove(a.id);
                store.deleteAccount(a);
                store.gmLog(gm, "delete_account", who);
                toast(sp, "rppda.toast.deleted", false, who);
                for (ServerPlayer p : sp.server.getPlayerList().getPlayers()) PdaServer.scan(p);
                Views.sendState(sp, s);
            }
            case "sys.character" -> {
                setCharacter(store, sp, d.getBoolean("on"));
                Views.sendState(sp, s);
            }
            case "sys.rename" -> {
                Account a = target(store, d);
                String name = d.getString("name").strip();
                if (a == null || a.kind != Account.Kind.NPC) return;
                if (name.isEmpty() || name.length() > TITLE_MAX) {
                    toast(sp, "rppda.err.title", true);
                    return;
                }
                store.gmLog(gm, "rename", label(a) + " → " + name);
                a.name = name;
                store.markAccounts();
                refreshAccount(sp, store, a);
            }
            default -> {}
        }
    }

    @Nullable
    private static Account target(PdaStore store, CompoundTag d) {
        Account a = store.account(d.getInt("id"));
        return a == null || a.kind == Account.Kind.SYSTEM ? null : a;
    }

    static String label(Account a) {
        return a.name + " (" + PdaItem.formatId(a.id) + ")";
    }

    private static CompoundTag sysAccount(PdaStore store, Account a) {
        CompoundTag t = Views.account(store, a, null);
        t.putBoolean("locked", a.locked);
        t.putBoolean("pw", a.hasPassword());
        t.putInt("issue", a.issue);
        t.put("look", a.look.save());
        t.putBoolean("signal", !a.noSignal);
        if (a.owner != null) t.putBoolean("ownerSignal", !store.noSignalPlayers.containsKey(a.owner));
        t.putLong("created", a.created);
        boolean online = false;
        if (a.owner != null && PdaServer.server != null) online = PdaServer.server.getPlayerList().getPlayer(a.owner) != null;
        t.putBoolean("online", online);
        if (a.owner != null && PdaServer.server != null) {
            var profile = PdaServer.server.getProfileCache() == null ? null
                    : PdaServer.server.getProfileCache().get(a.owner).orElse(null);
            if (profile != null) t.putString("ownerName", profile.getName());
        }
        return t;
    }

    private static void refreshAccount(ServerPlayer sp, PdaStore store, Account a) {
        CompoundTag t = new CompoundTag();
        t.put("acc", sysAccount(store, a));
        Net.send(sp, "sys.account", t);
    }
}
