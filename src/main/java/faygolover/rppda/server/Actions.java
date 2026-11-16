package faygolover.rppda.server;

import static faygolover.rppda.server.AccountActions.*;
import static faygolover.rppda.server.LookActions.*;
import static faygolover.rppda.server.GroupActions.*;
import static faygolover.rppda.server.SystemActions.*;
import faygolover.rppda.config.PdaConfig;
import faygolover.rppda.data.Account;
import faygolover.rppda.data.Chat;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Действия из окна КПК. Сервер проверяет всё сам: окно открыто, КПК ещё при игроке, права на чат. */
public final class Actions {
    private Actions() {}

    static final int PAGE = 100;
    static final int TITLE_MAX = 32;

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

    static boolean valid(ServerPlayer sp, PdaServer.Session s, PdaStore store) {
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

    // ---------------------------------------------------------------- окно

    static void session(ServerPlayer sp, PdaServer.Session s, PdaStore store, Account me, String op, CompoundTag d) {
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
                s.historyBytes = 0;
                s.historyFull = false;
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
            case "chat.leave" -> {
                // Подгруженная история освобождает память, когда выходишь из чата.
                s.openChat = -1;
                s.historyBytes = 0;
                s.historyFull = false;
            }
            case "chat.history" -> {
                Chat c = store.chat(d.getInt("chat"));
                if (c != null && canRead(s, me, c)) history(sp, s, store, c, d.getInt("before"));
            }
            case "chat.send" -> {
                Chat c = store.chat(d.getInt("chat"));
                if (c == null) return;
                String text = d.getString("text").strip();
                // Речь игрока — через фильтры семейства (RP Psyche: заикание); Система (ГМ) — как есть.
                if (!s.system && !text.isEmpty())
                    text = faygolover.rpcore.api.RpCoreAPI.filterSpeech(sp, text, faygolover.rpcore.api.RpCoreAPI.SpeechChannel.MESSAGE);
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
                if (n != null && (n.from != null || n.partial || !Cipher.readable(sp, me, n))) return;
                // Память ретро-КПК: новое и добавленные знаки — только если влезают (укорачивать можно всегда).
                if (!s.system && !Memory.allow(sp, me, s, n == null ? 0 : Memory.noteBytes(n), Memory.bytes(title) + Memory.bytes(text))) return;
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
                // Реалистичные ретро-КПК: заметкой делятся только приглашением с медленной передачей рядом
                // (системный КПК ГМа — по-старому, сразу).
                if (PdaConfig.realisticRetro && !s.system) {
                    Transfers.offer(sp, s, store, me, d.getInt("id"), d.getInt("chat"));
                    return;
                }
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
                if (!s.system && !Memory.allow(sp, me, s, 0, Memory.bytes(m.text) - (nl < 0 ? 0 : 1))) return;
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
                if (!s.system && !me.contacts.containsKey(id) && !Memory.allow(sp, me, s, 0, Memory.CONTACT)) return;
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
                // Портрет — фото скина владельца (у NPC владельца нет). «Лицо» больше не выбирается: у игрока это портрет.
                if (mode == Account.AV_FACE) mode = me.owner != null ? Account.AV_PORTRAIT : Account.AV_LETTER;
                if (mode == Account.AV_PORTRAIT && me.owner == null) return;
                // Свой портрет — переснять скин (вдруг сменился); фото хранит RP Core, видно и когда игрок не в сети.
                if (mode == Account.AV_PORTRAIT && me.owner.equals(sp.getUUID())) faygolover.rpcore.api.Photos.request(sp);
                int color = d.getInt("color");
                me.avatarColor = color >= 0 && color < Account.AV_COLORS ? color : -1;
                me.avatarMark = avatarMark(d.getString("mark"));
                // Новая картинка: вид «Картинка» — когда Core её примет (AvatarPics.onChanged).
                if (mode == Account.AV_PICTURE && d.getBoolean("upload")) AvatarPics.begin(sp, me);
                else if (mode != Account.AV_PICTURE || me.avatarPic > 0) me.avatar = mode;
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
            case "xfer.join" -> Transfers.join(sp, s, store, me, d.getInt("chat"), d.getInt("seq"));
            case "xfer.cancel" -> Transfers.cancel(sp, me, d.getInt("chat"), d.getInt("seq"));
            case "mem.slot" -> memSlot(sp, s, me, d.getInt("slot"), d.getInt("inv"));
            case "mem.unslot" -> memSlot(sp, s, me, d.getInt("slot"), -1);
            default -> {}
        }
    }

    /**
     * Ячейка расширения в профиле: поставить плату памяти из ячейки инвентаря {@code inv} (0–35, 40 — вторая рука) или
     * снять ({@code inv} = −1). Старая плата возвращается в инвентарь.
     */
    static void memSlot(ServerPlayer sp, PdaServer.Session s, Account me, int slot, int inv) {
        if (s.system || slot < 0 || slot >= PdaItem.SLOTS) return;
        ItemStack dev = Devices.stackOf(sp, me);
        if (dev == null) return;
        int[] slots = PdaItem.slots(dev);
        int put = 0;
        if (inv >= 0) {
            var inventory = sp.getInventory();
            ItemStack board = inv < inventory.items.size() ? inventory.items.get(inv) : inv == 40 ? inventory.offhand.get(0) : ItemStack.EMPTY;
            if (!(board.getItem() instanceof faygolover.rppda.item.MemoryBoardItem mb)) return;
            put = mb.kb;
            board.shrink(1);
        } else if (slots[slot] == 0) return;
        int old = slots[slot];
        PdaItem.setSlot(dev, slot, put);
        if (old > 0) {
            ItemStack back = new ItemStack(ModItems.memory(old));
            if (!back.isEmpty() && !sp.getInventory().add(back)) sp.drop(back, false);
        }
        Views.sendState(sp, s);
    }

    /** Знаки аватарки: 1–2 видимых символа, без кодов форматирования. */
    static String avatarMark(String raw) {
        StringBuilder b = new StringBuilder();
        raw.strip().codePoints().filter(cp -> cp != '§' && !Character.isISOControl(cp) && !Character.isWhitespace(cp)
                && Character.getType(cp) != Character.FORMAT).limit(2).forEach(b::appendCodePoint);
        return b.toString();
    }

    static boolean canRead(PdaServer.Session s, Account me, Chat c) {
        return c.has(me.id) || s.system;
    }

    static void sendRow(ServerPlayer sp, PdaServer.Session s, PdaStore store, Account me, Chat c) {
        CompoundTag t = new CompoundTag();
        t.put("row", Views.row(store, me, c, s));
        Net.send(sp, "row", t);
    }

    /** Страница из {@link #PAGE} сообщений до номера {@code before} (0 — последние). Архив читается в отдельном потоке. */
    static void history(ServerPlayer sp, PdaServer.Session s, PdaStore store, Chat c, int before) {
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

    static void sendHistory(ServerPlayer sp, PdaServer.Session s, PdaStore store, Chat c, int before, int from,
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
        // Память ретро-КПК: история сверх первой страницы занимает место (общий чат — нет). Не влезает — берём
        // сколько влезет (свежие из этой страницы), дальше назад не листается.
        if (before > 0 && c.type != Chat.Type.GLOBAL && !s.system) {
            Account dev = store.account(s.device);
            int free = dev == null ? Integer.MAX_VALUE : Memory.free(sp, dev, s);
            if (free != Integer.MAX_VALUE) {
                int keepFrom = msgs.size(), sum = 0;
                boolean full = s.historyFull;
                for (int i = msgs.size() - 1; i >= 0 && !full; i--) {
                    Message m = msgs.get(i);
                    int b = m.deleted() ? 0 : PdaStore.messageBytes(m);
                    if (sum + b > free) full = true;
                    else {
                        sum += b;
                        keepFrom = i;
                    }
                }
                if (full) {
                    msgs = msgs.subList(keepFrom, msgs.size());
                    t.putBoolean("more", false);
                }
                s.historyBytes += sum;
                s.historyFull = full;
            }
        }
        t.putInt("hist", s.historyBytes);
        t.putBoolean("full", s.historyFull);
        t.put("msgs", Views.page(store, s, c, msgs));
        t.put("row", Views.row(store, store.account(s.acting), c, s, cut));
        Net.send(sp, "history", t);
        Account dev = s.system ? null : store.account(s.device);
        if (dev != null) Memory.push(sp, dev);
    }

    static void sendNotes(ServerPlayer sp, Account me) {
        ListTag l = new ListTag();
        for (int i = me.notes.size() - 1; i >= 0; i--) l.add(Cipher.view(sp, me, me.notes.get(i)));
        CompoundTag t = new CompoundTag();
        t.put("list", l);
        Net.send(sp, "notes", t);
        Memory.push(sp, me);
    }

    /** Окно КПК без связи: сказать и вернуть true. */
    static boolean noSignal(ServerPlayer sp, PdaServer.Session s) {
        if (s.system) return false;
        Account dev = PdaServer.store.account(s.device);
        if (dev == null || PdaServer.signal(sp, dev)) return false;
        toast(sp, "rppda.err.no_signal", true);
        return true;
    }

}
