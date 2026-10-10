package faygolover.rppda.client;

import com.mojang.blaze3d.platform.InputConstants;
import faygolover.rppda.client.Ui.Icon;
import faygolover.rppda.client.Ui.IconButton;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Окно КПК. Слева — полоса разделов (чаты, контакты, профиль; у системного — учётки, все чаты, журнал), справа —
 * раздел. Кнопки — значки, название всплывает через секунду. Всё, что показывается, присылает сервер.
 */
public class PdaScreen extends Screen {
    enum Page { WAIT, REGISTER, LOCKED, CHATS, CHAT, CONTACTS, PROFILE, MEMBERS, SYS_ACCOUNTS, SYS_ACCOUNT, SYS_CHATS, SYS_LOG }

    enum Prompt { NONE, NEW_DM, NEW_GROUP, NEW_CHANNEL, ADD_CONTACT, EDIT_LABEL, SET_PW, REMOVE_PW, RENAME_GROUP, INVITE, NPC, NPC_RENAME }

    private Page page = Page.WAIT;
    private Page chatReturn = Page.CHATS;

    // Геометрия: корпус, экран, содержимое.
    private int x0, y0, w, h, sx, sy, sw, sh, cx, cy, cw, ch;

    // Состояние от сервера.
    private boolean system, admin, hasPw, silent;
    private int meId;
    private String meName = "";
    private String meKind = "PLAYER";
    @Nullable
    private UUID meOwner;
    private int maxLen = 256;
    private final List<CompoundTag> chats = new ArrayList<>();
    private final List<CompoundTag> contacts = new ArrayList<>();
    private final List<CompoundTag> invites = new ArrayList<>();

    // Открытый чат.
    @Nullable
    private CompoundTag chatRow;
    private final List<CompoundTag> msgs = new ArrayList<>();
    private boolean more, loading;
    private double scroll;
    private int replySeq;
    private String replyName = "", replyText = "";
    private List<Block> blocks = List.of();
    private int blocksWidth = -1;
    private int blocksVersion = -1, msgsVersion;

    // Группа, системные списки.
    @Nullable
    private CompoundTag members;
    private final List<CompoundTag> sysAccounts = new ArrayList<>();
    private final List<CompoundTag> sysChats = new ArrayList<>();
    private final List<CompoundTag> sysLog = new ArrayList<>();
    @Nullable
    private CompoundTag sysAcc;
    private String sysQuery = "";

    // Экран блокировки.
    private String lockedName = "";
    private int lockedId;
    private long waitUntil;

    // Ввод.
    private Prompt prompt = Prompt.NONE;
    private int promptTarget;
    @Nullable
    private EditBox promptA, promptB, input, password, search;
    private String inputDraft = "";

    private double listScroll;
    @Nullable
    private Component toast;
    private int toastColor;
    private long toastUntil;

    /** Кликабельные места, нарисованные вручную (строки списков, значки у сообщений). Собираются каждый кадр. */
    private record Hot(int x, int y, int w, int h, String key, @Nullable Component tip, Runnable run) {
        boolean in(double mx, double my) {
            return mx >= x && my >= y && mx < x + w && my < y + h;
        }
    }

    private final List<Hot> hots = new ArrayList<>();
    private String hoverKey = "";
    private long hoverSince;

    public PdaScreen() {
        super(Component.translatable("rppda.screen.title"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        w = Math.min(360, width - 8);
        h = Math.min(236, height - 8);
        x0 = (width - w) / 2;
        y0 = (height - h) / 2;
        sx = x0 + 8;
        sy = y0 + 8;
        sw = w - 16;
        sh = h - 16;
        cx = sx + 24;
        cy = sy + 14;
        cw = sw - 26;
        ch = sh - 16;
        blocksWidth = -1;
        rebuild();
    }

    // ================================================================ приём

    void receive(String op, CompoundTag d) {
        switch (op) {
            case "register" -> setPage(Page.REGISTER);
            case "locked" -> {
                lockedName = d.getString("name");
                lockedId = d.getInt("id");
                waitUntil = System.currentTimeMillis() + d.getLong("wait");
                setPage(Page.LOCKED);
            }
            case "state" -> {
                readState(d);
                if (page == Page.WAIT || page == Page.REGISTER || page == Page.LOCKED) setPage(Page.CHATS);
                else rebuild();
            }
            case "history" -> {
                if (chatRow == null || chatRow.getInt("id") != d.getInt("chat")) return;
                List<CompoundTag> got = list(d, "msgs");
                if (d.getInt("before") <= 0) {
                    msgs.clear();
                    msgs.addAll(got);
                    scroll = 0;
                } else {
                    msgs.addAll(0, got);
                }
                more = d.getBoolean("more");
                loading = false;
                msgsVersion++;
                updateRow(d.getCompound("row"));
                chatRow = d.getCompound("row");
                if (this.page == Page.CHAT) rebuild();
            }
            case "msg" -> {
                if (chatRow == null || chatRow.getInt("id") != d.getInt("chat")) return;
                CompoundTag m = d.getCompound("m");
                for (CompoundTag o : msgs) if (o.getInt("s") == m.getInt("s")) return;
                int before = totalHeight();
                msgs.add(m);
                msgsVersion++;
                if (scroll > 0) {
                    layout();
                    scroll += totalHeight() - before;
                }
            }
            case "deleted" -> {
                if (chatRow == null || chatRow.getInt("id") != d.getInt("chat")) return;
                int seq = d.getInt("seq");
                if (system) {
                    for (CompoundTag m : msgs) if (m.getInt("s") == seq) m.putInt("fl", m.getInt("fl") | 1);
                } else msgs.removeIf(m -> m.getInt("s") == seq);
                msgsVersion++;
            }
            case "row" -> {
                CompoundTag row = d.getCompound("row");
                updateRow(row);
                if (chatRow != null && chatRow.getInt("id") == row.getInt("id")) {
                    chatRow = row;
                    if (page == Page.CHAT) rebuild();
                }
            }
            case "goto" -> openChat(d.getCompound("row"), page == Page.SYS_ACCOUNT ? Page.SYS_ACCOUNT : Page.CHATS);
            case "members" -> {
                members = d;
                if (page == Page.MEMBERS) rebuild();
            }
            case "toast" -> {
                List<Object> args = new ArrayList<>();
                for (Tag t : d.getList("args", Tag.TAG_STRING)) args.add(t.getAsString());
                showToast(Component.translatable(d.getString("key"), args.toArray()), d.getBoolean("err"));
            }
            case "sys.accounts" -> {
                sysAccounts.clear();
                sysAccounts.addAll(list(d, "list"));
            }
            case "sys.chats" -> {
                sysChats.clear();
                sysChats.addAll(list(d, "list"));
            }
            case "sys.log" -> {
                sysLog.clear();
                sysLog.addAll(list(d, "list"));
            }
            case "sys.account" -> {
                CompoundTag a = d.getCompound("acc");
                sysAcc = a;
                boolean found = false;
                for (int i = 0; i < sysAccounts.size(); i++) {
                    if (sysAccounts.get(i).getInt("id") == a.getInt("id")) {
                        sysAccounts.set(i, a);
                        found = true;
                    }
                }
                if (!found) sysAccounts.add(0, a);
                if (page == Page.SYS_ACCOUNT) rebuild();
            }
            default -> {}
        }
    }

    private void readState(CompoundTag d) {
        CompoundTag me = d.getCompound("me");
        meId = me.getInt("id");
        meName = me.getString("name");
        meKind = me.getString("kind");
        meOwner = me.hasUUID("owner") ? me.getUUID("owner") : null;
        system = d.getBoolean("system");
        admin = d.getBoolean("admin");
        hasPw = d.getBoolean("pw");
        silent = d.getBoolean("silent");
        if (d.contains("maxLen")) maxLen = d.getInt("maxLen");
        chats.clear();
        chats.addAll(list(d, "chats"));
        contacts.clear();
        contacts.addAll(list(d, "contacts"));
        invites.clear();
        invites.addAll(list(d, "invites"));
        if (chatRow != null) {
            boolean still = false;
            for (CompoundTag r : chats) if (r.getInt("id") == chatRow.getInt("id")) still = true;
            if (!still && page == Page.CHAT && !system) {
                chatRow = null;
                page = Page.CHATS;
            }
        }
    }

    private static List<CompoundTag> list(CompoundTag d, String key) {
        List<CompoundTag> out = new ArrayList<>();
        for (Tag t : d.getList(key, Tag.TAG_COMPOUND)) out.add((CompoundTag) t);
        return out;
    }

    private void updateRow(CompoundTag row) {
        if (row.isEmpty()) return;
        int id = row.getInt("id");
        boolean mine = row.getBoolean("member");
        for (List<CompoundTag> l : List.of(chats, sysChats)) {
            boolean found = false;
            for (int i = 0; i < l.size(); i++) {
                if (l.get(i).getInt("id") == id) {
                    l.set(i, row);
                    found = true;
                }
            }
            if (!found && l == chats && mine && (!row.getString("type").equals("DM") || !row.getString("last").isEmpty()
                    || row.getLong("time") > 0)) l.add(row);
            l.sort(Comparator.comparingLong((CompoundTag r) -> r.getLong("time")).reversed());
        }
    }

    private void showToast(Component c, boolean error) {
        toast = c;
        toastColor = error ? Ui.RED : Ui.GREEN;
        toastUntil = System.currentTimeMillis() + 3500;
    }

    // ================================================================ разделы и кнопки

    void setPage(Page p) {
        if (page == Page.CHAT && p != Page.CHAT && p != Page.MEMBERS) ClientPda.send("chat.leave", new CompoundTag());
        page = p;
        prompt = Prompt.NONE;
        listScroll = 0;
        switch (p) {
            case SYS_ACCOUNTS -> send("sys.accounts", t -> t.putString("q", sysQuery));
            case SYS_CHATS -> send("sys.chats", t -> {});
            case SYS_LOG -> send("sys.log", t -> {});
            default -> {}
        }
        rebuild();
    }

    private void rebuild() {
        if (input != null) inputDraft = input.getValue();
        clearWidgets();
        promptA = promptB = input = password = search = null;
        if (page == Page.WAIT) return;
        if (page == Page.REGISTER) {
            addRenderableWidget(new IconButton(sx + sw / 2 - 70, sy + sh / 2 + 10, 140, 20, Icon.CHECK,
                    Component.translatable("rppda.btn.register"), Component.translatable("rppda.btn.register"),
                    b -> ClientPda.send("register", new CompoundTag())));
            return;
        }
        if (page == Page.LOCKED) {
            password = new EditBox(font, sx + sw / 2 - 60, sy + sh / 2 + 8, 100, 16, Component.translatable("rppda.hint.password"));
            password.setMaxLength(32);
            mask(password);
            password.setHint(Component.translatable("rppda.hint.password").withStyle(ChatFormatting.DARK_GRAY));
            addRenderableWidget(password);
            setInitialFocus(password);
            addRenderableWidget(new IconButton(sx + sw / 2 + 44, sy + sh / 2 + 7, Icon.UNLOCK,
                    Component.translatable("rppda.btn.unlock"), b -> unlock()));
            return;
        }
        rail();
        switch (page) {
            case CHATS -> {
                toolbarButton(0, Icon.CHANNEL, "rppda.btn.new_channel", () -> openPrompt(Prompt.NEW_CHANNEL, 0));
                toolbarButton(1, Icon.GROUP, "rppda.btn.new_group", () -> openPrompt(Prompt.NEW_GROUP, 0));
                toolbarButton(2, Icon.MESSAGE, "rppda.btn.new_dm", () -> openPrompt(Prompt.NEW_DM, 0));
            }
            case CONTACTS -> toolbarButton(0, Icon.PERSON_ADD, "rppda.btn.add_contact", () -> openPrompt(Prompt.ADD_CONTACT, 0));
            case CHAT -> chatWidgets();
            case MEMBERS -> membersWidgets();
            case PROFILE -> profileWidgets();
            case SYS_ACCOUNTS -> {
                search = new EditBox(font, cx + 2, cy + 2, cw - 46, 14, Component.translatable("rppda.hint.search"));
                search.setHint(Component.translatable("rppda.hint.search").withStyle(ChatFormatting.DARK_GRAY));
                search.setValue(sysQuery);
                search.setResponder(s -> {
                    sysQuery = s;
                    send("sys.accounts", t -> t.putString("q", s));
                });
                addRenderableWidget(search);
                toolbarButton(0, Icon.PLUS, "rppda.btn.npc", () -> openPrompt(Prompt.NPC, 0));
                toolbarButton(1, Icon.REFRESH, "rppda.btn.refresh", () -> send("sys.accounts", t -> t.putString("q", sysQuery)));
            }
            case SYS_ACCOUNT -> sysAccountWidgets();
            case SYS_CHATS -> toolbarButton(0, Icon.REFRESH, "rppda.btn.refresh", () -> send("sys.chats", t -> {}));
            case SYS_LOG -> toolbarButton(0, Icon.REFRESH, "rppda.btn.refresh", () -> send("sys.log", t -> {}));
            default -> {}
        }
        if (prompt != Prompt.NONE) promptWidgets();
    }

    private void rail() {
        int y = cy;
        y = railButton(y, Icon.CHATS, "rppda.page.chats", Page.CHATS);
        y = railButton(y, Icon.CONTACTS, "rppda.page.contacts", Page.CONTACTS);
        if (!system) y = railButton(y, Icon.PROFILE, "rppda.page.profile", Page.PROFILE);
        if (system) {
            y += 4;
            y = railButton(y, Icon.ACCOUNTS, "rppda.page.accounts", Page.SYS_ACCOUNTS);
            y = railButton(y, Icon.GLOBE, "rppda.page.all_chats", Page.SYS_CHATS);
            railButton(y, Icon.LOG, "rppda.page.log", Page.SYS_LOG);
        }
        int by = sy + sh - 20;
        addRenderableWidget(new IconButton(sx + 3, by, Icon.CLOSE, Component.translatable("rppda.btn.close"), b -> onClose()));
        if (!system && hasPw) {
            by -= 20;
            addRenderableWidget(new IconButton(sx + 3, by, Icon.LOCK, Component.translatable("rppda.btn.lock"),
                    b -> ClientPda.send("lock", new CompoundTag())));
        }
        if (system && meId != 0) {
            by -= 20;
            IconButton back = new IconButton(sx + 3, by, Icon.SYSTEM, Component.translatable("rppda.btn.back_system"),
                    b -> send("sys.switch", t -> t.putInt("id", 0)));
            back.color = Ui.RED;
            addRenderableWidget(back);
        }
    }

    private int railButton(int y, Icon icon, String key, Page target) {
        IconButton b = new IconButton(sx + 3, y, icon, Component.translatable(key), btn -> setPage(target));
        boolean on = page == target || target == Page.CHATS && (page == Page.CHAT || page == Page.MEMBERS) && chatReturn == Page.CHATS
                || target == Page.SYS_ACCOUNTS && page == Page.SYS_ACCOUNT
                || target == Page.SYS_CHATS && (page == Page.CHAT || page == Page.MEMBERS) && chatReturn == Page.SYS_CHATS;
        if (on) b.color = Ui.ACCENT;
        addRenderableWidget(b);
        return y + 20;
    }

    /** Значок в правом верхнем углу раздела; {@code n} — номер справа налево. */
    private IconButton toolbarButton(int n, Icon icon, String key, Runnable run) {
        IconButton b = new IconButton(cx + cw - 19 - n * 19, cy, icon, Component.translatable(key), btn -> run.run());
        addRenderableWidget(b);
        return b;
    }

    private void chatWidgets() {
        if (chatRow == null) return;
        addRenderableWidget(new IconButton(cx, cy, Icon.BACK, Component.translatable("rppda.btn.back"), b -> {
            ClientPda.send("state", new CompoundTag());
            setPage(chatReturn);
        }));
        int n = 0;
        if (chatRow.getBoolean("member")) {
            String mode = chatRow.getString("mode");
            Icon mi = mode.equals("PDA") ? Icon.PDA_ONLY : mode.equals("MUTED") ? Icon.MUTE : Icon.BELL;
            toolbarButton(n++, mi, "rppda.mode." + mode.toLowerCase(), () -> {
                int next = mode.equals("CHAT") ? 1 : mode.equals("PDA") ? 2 : 0;
                send("chat.notify", t -> {
                    t.putInt("chat", chatRow.getInt("id"));
                    t.putInt("mode", next);
                });
            });
        }
        String type = chatRow.getString("type");
        if (type.equals("GROUP") || type.equals("CHANNEL")) {
            toolbarButton(n++, Icon.MEMBERS, "rppda.btn.members", () -> {
                members = null;
                send("group.members", t -> t.putInt("chat", chatRow.getInt("id")));
                setPage(Page.MEMBERS);
            });
        }
        if (type.equals("DM") && chatRow.contains("peer")) {
            int peer = chatRow.getInt("peer");
            boolean known = false;
            for (CompoundTag c : contacts) if (c.getInt("id") == peer) known = true;
            if (!known && peer != 0) toolbarButton(n++, Icon.PERSON_ADD, "rppda.btn.add_contact",
                    () -> openPrompt(Prompt.ADD_CONTACT, peer));
        }
        if (chatRow.getBoolean("write")) {
            int iy = cy + ch - 16;
            input = new EditBox(font, cx + 2, iy, cw - 24, 15, Component.translatable("rppda.hint.message"));
            input.setMaxLength(maxLen);
            input.setHint(Component.translatable("rppda.hint.message").withStyle(ChatFormatting.DARK_GRAY));
            input.setValue(inputDraft);
            addRenderableWidget(input);
            setInitialFocus(input);
            addRenderableWidget(new IconButton(cx + cw - 19, iy - 2, Icon.SEND, Component.translatable("rppda.btn.send"), b -> sendMessage()));
            if (replySeq > 0) addRenderableWidget(new IconButton(cx + cw - 15, iy - 15, 12, 12, Icon.CLOSE,
                    Component.translatable("rppda.btn.cancel_reply"), null, b -> {
                replySeq = 0;
                rebuild();
            }));
        }
    }

    private void membersWidgets() {
        addRenderableWidget(new IconButton(cx, cy, Icon.BACK, Component.translatable("rppda.btn.back"), b -> setPage(Page.CHAT)));
        if (chatRow == null || !chatRow.getBoolean("member")) return;
        int n = 0;
        toolbarButton(n++, Icon.LEAVE, "rppda.btn.leave", () -> {
            send("group.leave", t -> t.putInt("chat", chatRow.getInt("id")));
            chatRow = null;
            setPage(Page.CHATS);
        });
        if (members != null && members.getInt("admin") == meId) {
            toolbarButton(n++, Icon.PERSON_ADD, "rppda.btn.invite", () -> openPrompt(Prompt.INVITE, 0));
            toolbarButton(n, Icon.EDIT, "rppda.btn.rename", () -> openPrompt(Prompt.RENAME_GROUP, 0));
        }
    }

    private void profileWidgets() {
        int bx = cx + 6, by = cy + 64;
        addRenderableWidget(new IconButton(cx + 44 + font.width(Ui.formatId(meId)) + 30, cy + 26, 14, 14, Icon.COPY,
                Component.translatable("rppda.btn.copy_id"), null, b -> {
            Minecraft.getInstance().keyboardHandler.setClipboard(String.format("%08d", meId));
            showToast(Component.translatable("rppda.toast.copied"), false);
        }));
        addRenderableWidget(new IconButton(bx, by, Icon.KEY, Component.translatable(hasPw ? "rppda.btn.change_pw" : "rppda.btn.set_pw"),
                b -> openPrompt(Prompt.SET_PW, 0)));
        if (hasPw) {
            addRenderableWidget(new IconButton(bx + 20, by, Icon.TRASH, Component.translatable("rppda.btn.remove_pw"),
                    b -> openPrompt(Prompt.REMOVE_PW, 0)));
            addRenderableWidget(new IconButton(bx + 40, by, Icon.LOCK, Component.translatable("rppda.btn.lock"),
                    b -> ClientPda.send("lock", new CompoundTag())));
        }
        IconButton snd = new IconButton(bx, by + 26, silent ? Icon.SILENT : Icon.SOUND,
                Component.translatable(silent ? "rppda.btn.sound_on" : "rppda.btn.sound_off"),
                b -> send("profile.silent", t -> t.putBoolean("on", !silent)));
        addRenderableWidget(snd);
    }

    private void sysAccountWidgets() {
        addRenderableWidget(new IconButton(cx, cy, Icon.BACK, Component.translatable("rppda.btn.back"), b -> setPage(Page.SYS_ACCOUNTS)));
        if (sysAcc == null) return;
        int id = sysAcc.getInt("id");
        int bx = cx + 4, by = cy + 76, n = 0;
        List<Object[]> acts = new ArrayList<>();
        acts.add(new Object[]{Icon.SWITCH, "rppda.btn.switch", (Runnable) () -> send("sys.switch", t -> t.putInt("id", id))});
        if (id != 0) {
            acts.add(new Object[]{Icon.MESSAGE, "rppda.btn.write_system", (Runnable) () -> send("dm", t -> t.putInt("id", id))});
            acts.add(new Object[]{Icon.GIVE, "rppda.btn.give", (Runnable) () -> send("sys.give", t -> t.putInt("id", id))});
            if (sysAcc.getBoolean("locked"))
                acts.add(new Object[]{Icon.UNLOCK, "rppda.btn.sys_unlock", (Runnable) () -> send("sys.unlock", t -> t.putInt("id", id))});
            if (sysAcc.getBoolean("pw"))
                acts.add(new Object[]{Icon.KEY, "rppda.btn.reset_pw", (Runnable) () -> send("sys.resetpw", t -> t.putInt("id", id))});
            if (sysAcc.getBoolean("online"))
                acts.add(new Object[]{Icon.TELEPORT, "rppda.btn.tp", (Runnable) () -> send("sys.tp", t -> t.putInt("id", id))});
            if (sysAcc.getString("kind").equals("NPC"))
                acts.add(new Object[]{Icon.EDIT, "rppda.btn.rename_npc", (Runnable) () -> openPrompt(Prompt.NPC_RENAME, id)});
        }
        for (Object[] a : acts) {
            Runnable r = (Runnable) a[2];
            addRenderableWidget(new IconButton(bx + n * 22, by, (Icon) a[0], Component.translatable((String) a[1]), b -> r.run()));
            n++;
        }
    }

    // ================================================================ строка ввода (новый чат, контакт, пароль…)

    private void openPrompt(Prompt p, int target) {
        prompt = p;
        promptTarget = target;
        rebuild();
    }

    private void promptWidgets() {
        int y = cy + 20;
        boolean two = prompt == Prompt.ADD_CONTACT && promptTarget == 0 || prompt == Prompt.SET_PW && hasPw;
        int boxW = two ? (cw - 52) / 2 : cw - 48;
        promptA = new EditBox(font, cx + 4, y + 2, boxW, 14, Component.empty());
        promptA.setHint(Component.translatable(hintA()).withStyle(ChatFormatting.DARK_GRAY));
        promptA.setMaxLength(prompt == Prompt.NEW_DM || prompt == Prompt.INVITE || prompt == Prompt.ADD_CONTACT && promptTarget == 0 ? 9 : 32);
        if (prompt == Prompt.SET_PW || prompt == Prompt.REMOVE_PW) mask(promptA);
        if (prompt == Prompt.EDIT_LABEL) for (CompoundTag c : contacts) if (c.getInt("id") == promptTarget) promptA.setValue(c.getString("label"));
        if (prompt == Prompt.RENAME_GROUP && chatRow != null) promptA.setValue(chatRow.getString("title"));
        addRenderableWidget(promptA);
        setInitialFocus(promptA);
        setFocused(promptA);
        if (two) {
            promptB = new EditBox(font, cx + 8 + boxW, y + 2, boxW, 14, Component.empty());
            promptB.setHint(Component.translatable(prompt == Prompt.SET_PW ? "rppda.hint.new_password" : "rppda.hint.label")
                    .withStyle(ChatFormatting.DARK_GRAY));
            promptB.setMaxLength(32);
            if (prompt == Prompt.SET_PW) mask(promptB);
            addRenderableWidget(promptB);
        }
        addRenderableWidget(new IconButton(cx + cw - 40, y, Icon.CHECK, Component.translatable("rppda.btn.ok"), b -> confirmPrompt()));
        addRenderableWidget(new IconButton(cx + cw - 20, y, Icon.CLOSE, Component.translatable("rppda.btn.cancel"), b -> {
            prompt = Prompt.NONE;
            rebuild();
        }));
    }

    private String hintA() {
        return switch (prompt) {
            case NEW_DM, INVITE -> "rppda.hint.id";
            case ADD_CONTACT -> promptTarget == 0 ? "rppda.hint.id" : "rppda.hint.label";
            case NEW_GROUP, NEW_CHANNEL, RENAME_GROUP -> "rppda.hint.title";
            case EDIT_LABEL -> "rppda.hint.label";
            case SET_PW -> hasPw ? "rppda.hint.old_password" : "rppda.hint.new_password";
            case REMOVE_PW -> "rppda.hint.old_password";
            case NPC, NPC_RENAME -> "rppda.hint.name";
            default -> "rppda.hint.title";
        };
    }

    private void confirmPrompt() {
        String a = promptA == null ? "" : promptA.getValue().strip();
        String b = promptB == null ? "" : promptB.getValue().strip();
        switch (prompt) {
            case NEW_DM -> {
                int id = parseId(a);
                if (id < 0) return;
                send("dm", t -> t.putInt("id", id));
            }
            case NEW_GROUP, NEW_CHANNEL -> send("group.create", t -> {
                t.putString("title", a);
                t.putBoolean("channel", prompt == Prompt.NEW_CHANNEL);
            });
            case ADD_CONTACT -> {
                int id = promptTarget != 0 ? promptTarget : parseId(a);
                if (id < 0) return;
                String label = promptTarget != 0 ? a : b;
                send("contact.add", t -> {
                    t.putInt("id", id);
                    t.putString("label", label);
                });
            }
            case EDIT_LABEL -> send("contact.label", t -> {
                t.putInt("id", promptTarget);
                t.putString("label", a);
            });
            case SET_PW -> {
                String old = hasPw ? a : "";
                String pw = hasPw ? b : a;
                if (pw.isEmpty()) return;
                send("profile.password", t -> {
                    t.putString("old", old);
                    t.putString("pw", pw);
                });
            }
            case REMOVE_PW -> send("profile.password", t -> {
                t.putString("old", a);
                t.putString("pw", "");
            });
            case RENAME_GROUP -> send("group.rename", t -> {
                t.putInt("chat", chatRow == null ? 0 : chatRow.getInt("id"));
                t.putString("title", a);
            });
            case INVITE -> {
                int id = parseId(a);
                if (id < 0) return;
                send("group.invite", t -> {
                    t.putInt("chat", chatRow == null ? 0 : chatRow.getInt("id"));
                    t.putInt("id", id);
                });
            }
            case NPC -> send("sys.npc", t -> t.putString("name", a));
            case NPC_RENAME -> send("sys.rename", t -> {
                t.putInt("id", promptTarget);
                t.putString("name", a);
            });
            default -> {}
        }
        prompt = Prompt.NONE;
        rebuild();
    }

    private int parseId(String s) {
        String digits = s.replace(" ", "");
        if (!digits.matches("\\d{8}")) {
            showToast(Component.translatable("rppda.err.bad_id"), true);
            return -1;
        }
        return Integer.parseInt(digits);
    }

    private static void mask(EditBox box) {
        box.setFormatter((s, pos) -> FormattedCharSequence.forward("*".repeat(s.length()), Style.EMPTY));
    }

    // ================================================================ действия

    private interface Filler {
        void fill(CompoundTag t);
    }

    private static void send(String op, Filler f) {
        CompoundTag t = new CompoundTag();
        f.fill(t);
        ClientPda.send(op, t);
    }

    private void unlock() {
        if (password == null) return;
        String pw = password.getValue();
        send("unlock", t -> t.putString("pw", pw));
        password.setValue("");
    }

    private void openChat(CompoundTag row, Page back) {
        chatRow = row;
        chatReturn = back == Page.SYS_ACCOUNT ? Page.CHATS : back;
        msgs.clear();
        msgsVersion++;
        more = false;
        loading = true;
        scroll = 0;
        replySeq = 0;
        inputDraft = "";
        prompt = Prompt.NONE;
        page = Page.CHAT;
        send("chat.open", t -> t.putInt("chat", row.getInt("id")));
        rebuild();
    }

    private void sendMessage() {
        if (input == null || chatRow == null) return;
        String text = input.getValue().strip();
        if (text.isEmpty()) return;
        int reply = replySeq;
        send("chat.send", t -> {
            t.putInt("chat", chatRow.getInt("id"));
            t.putString("text", text);
            t.putInt("reply", reply);
        });
        input.setValue("");
        inputDraft = "";
        scroll = 0;
        if (replySeq > 0) {
            replySeq = 0;
            rebuild();
        }
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        boolean enter = key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER;
        if (enter) {
            if (prompt != Prompt.NONE && (getFocused() == promptA || getFocused() == promptB)) {
                confirmPrompt();
                return true;
            }
            if (page == Page.LOCKED) {
                unlock();
                return true;
            }
            if (page == Page.CHAT && input != null && getFocused() == input) {
                sendMessage();
                return true;
            }
        }
        if (key == GLFW.GLFW_KEY_ESCAPE && prompt != Prompt.NONE) {
            prompt = Prompt.NONE;
            rebuild();
            return true;
        }
        if (super.keyPressed(key, scan, mods)) return true;
        // Клавиша КПК закрывает окно, если не печатаем.
        InputConstants.Key k = InputConstants.getKey(key, scan);
        if (ClientSetup.OPEN.isActiveAndMatches(k) && !(getFocused() instanceof EditBox)) {
            onClose();
            return true;
        }
        return false;
    }

    @Override
    public void onClose() {
        ClientPda.send("close", new CompoundTag());
        super.onClose();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (button != 0) return false;
        for (Hot hot : new ArrayList<>(hots)) {
            if (hot.in(mx, my)) {
                hot.run.run();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (page == Page.CHAT) {
            scroll = Math.max(0, scroll + delta * 18);
            return true;
        }
        listScroll = Math.max(0, listScroll - delta * 20);
        return true;
    }

    // ================================================================ отрисовка

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);
        hots.clear();
        boolean sys = system;
        // Корпус.
        g.fill(x0 - 1, y0 - 1, x0 + w + 1, y0 + h + 1, Ui.BODY_DARK);
        g.fill(x0, y0, x0 + w, y0 + h, sys ? Ui.SYSTEM_BODY : Ui.BODY);
        g.fill(x0, y0, x0 + w, y0 + 1, Ui.BODY_LIGHT);
        g.fill(x0, y0, x0 + 1, y0 + h, Ui.BODY_LIGHT);
        g.fill(sx - 1, sy - 1, sx + sw + 1, sy + sh + 1, 0xFF000000);
        g.fill(sx, sy, sx + sw, sy + sh, Ui.SCREEN);
        g.fill(x0 + w / 2 - 12, y0 + h - 5, x0 + w / 2 + 12, y0 + h - 3, sys ? 0xFF7A2A2A : 0xFF2C5C88);

        if (page == Page.WAIT) {
            drawCentered(g, Component.translatable("rppda.screen.loading"), sy + sh / 2 - 4, Ui.DIM);
        } else if (page == Page.REGISTER) {
            Ui.icon(g, Icon.PDA, sx + sw / 2 - 16, sy + sh / 2 - 46, 32, Ui.ACCENT);
            drawCentered(g, Component.translatable("rppda.screen.new_device"), sy + sh / 2 - 8, Ui.TEXT);
            drawCentered(g, Component.translatable("rppda.screen.new_device_hint"), sy + sh / 2 + 36, Ui.DIM);
        } else if (page == Page.LOCKED) {
            Ui.icon(g, Icon.LOCK, sx + sw / 2 - 16, sy + sh / 2 - 52, 32, Ui.ACCENT);
            drawCentered(g, Component.literal(lockedName), sy + sh / 2 - 14, Ui.TEXT);
            drawCentered(g, Component.literal("ID " + Ui.formatId(lockedId)), sy + sh / 2 - 4, Ui.DIM);
            long wait = waitUntil - System.currentTimeMillis();
            if (wait > 0) drawCentered(g, Component.translatable("rppda.err.wait", (wait + 999) / 1000), sy + sh / 2 + 30, Ui.RED);
        } else {
            statusBar(g);
            g.fill(sx + 22, sy + 12, sx + 23, sy + sh, 0xFF1E2833);
            switch (page) {
                case CHATS -> renderChats(g, mx, my);
                case CHAT -> renderChat(g, mx, my);
                case CONTACTS -> renderContacts(g, mx, my);
                case PROFILE -> renderProfile(g);
                case MEMBERS -> renderMembers(g, mx, my);
                case SYS_ACCOUNTS -> renderSysAccounts(g, mx, my);
                case SYS_ACCOUNT -> renderSysAccount(g);
                case SYS_CHATS -> renderSysChats(g, mx, my);
                case SYS_LOG -> renderSysLog(g);
                default -> {}
            }
            if (prompt != Prompt.NONE) g.fill(cx, cy + 19, cx + cw, cy + 39, Ui.PANEL);
        }
        super.render(g, mx, my, pt);
        if (toast != null && System.currentTimeMillis() < toastUntil) {
            int tw = font.width(toast) + 10;
            int tx = sx + sw / 2 - tw / 2, ty = sy + sh - 34;
            g.pose().pushPose();
            g.pose().translate(0, 0, 400);
            g.fill(tx, ty, tx + tw, ty + 14, 0xEE10161D);
            g.fill(tx, ty + 13, tx + tw, ty + 14, toastColor);
            g.drawString(font, toast, tx + 5, ty + 3, Ui.TEXT, false);
            g.pose().popPose();
        }
        hoverTooltip(g, mx, my);
    }

    private void hoverTooltip(GuiGraphics g, int mx, int my) {
        Hot over = null;
        for (Hot hot : hots) if (hot.tip != null && hot.in(mx, my)) over = hot;
        String key = over == null ? "" : over.key;
        if (!key.equals(hoverKey)) {
            hoverKey = key;
            hoverSince = System.currentTimeMillis();
        }
        if (over != null && System.currentTimeMillis() - hoverSince >= 1000) {
            g.pose().pushPose();
            g.pose().translate(0, 0, 500);
            g.renderTooltip(font, over.tip, mx, my);
            g.pose().popPose();
        }
    }

    private void drawCentered(GuiGraphics g, Component c, int y, int color) {
        g.drawString(font, c, sx + sw / 2 - font.width(c) / 2, y, color, false);
    }

    private void statusBar(GuiGraphics g) {
        g.fill(sx, sy, sx + sw, sy + 12, 0xFF0A1018);
        String clock = Ui.clock(System.currentTimeMillis());
        g.drawString(font, clock, sx + sw - font.width(clock) - 4, sy + 2, Ui.DIM, false);
        int x = sx + 4;
        if (system) {
            Component s = Component.translatable("rppda.screen.system");
            g.drawString(font, s, x, sy + 2, Ui.RED, false);
            x += font.width(s) + 6;
            if (meId != 0) {
                String who = "→ " + meName + "  " + Ui.formatId(meId);
                g.drawString(font, who, x, sy + 2, Ui.GOLD, false);
            }
        } else {
            g.drawString(font, meName, x, sy + 2, Ui.TEXT, false);
            g.drawString(font, "ID " + Ui.formatId(meId), x + font.width(meName) + 6, sy + 2, Ui.DIM, false);
        }
        if (silent && !system) Ui.icon(g, Icon.SILENT, sx + sw - font.width(clock) - 16, sy + 2, 9, Ui.DIM);
    }

    private void title(GuiGraphics g, Component c, int xOff) {
        g.drawString(font, c, cx + xOff, cy + 5, Ui.TEXT, false);
    }

    private int listTop() {
        return cy + (prompt != Prompt.NONE ? 42 : 22);
    }

    /** Вертикальный список строк одинаковой высоты: прокрутка, наведение, клик. */
    private <T> void list(GuiGraphics g, int mx, int my, List<T> items, int rowH, String keyPrefix, RowDraw<T> draw,
                          @Nullable RowClick<T> click) {
        int top = listTop(), bottom = cy + ch;
        int total = items.size() * rowH;
        listScroll = Math.min(listScroll, Math.max(0, total - (bottom - top)));
        g.enableScissor(cx, top, cx + cw, bottom);
        int y = top - (int) listScroll;
        for (int i = 0; i < items.size(); i++, y += rowH) {
            if (y + rowH < top || y > bottom) continue;
            T it = items.get(i);
            boolean hover = mx >= cx && mx < cx + cw && my >= Math.max(top, y) && my < Math.min(bottom, y + rowH);
            if (hover) g.fill(cx, y, cx + cw, y + rowH, Ui.ROW_HOVER);
            draw.draw(g, it, y, hover);
            if (click != null && hover) {
                int yy = y;
                hots.add(new Hot(cx, Math.max(top, y), cw, Math.min(bottom, y + rowH) - Math.max(top, y), keyPrefix + i, null,
                        () -> click.click(it, yy)));
            }
        }
        g.disableScissor();
        if (items.isEmpty()) g.drawString(font, Component.translatable("rppda.screen.empty"), cx + 6, top + 6, Ui.DIM, false);
    }

    private interface RowDraw<T> {
        void draw(GuiGraphics g, T item, int y, boolean hover);
    }

    private interface RowClick<T> {
        void click(T item, int y);
    }

    /** Значок в строке списка (вручную, со своей подсказкой). Вставляется перед кликом по строке. */
    private void rowIcon(GuiGraphics g, int x, int y, Icon icon, String tipKey, String key, int color, Runnable run) {
        Ui.icon(g, icon, x, y, 12, color);
        hots.add(0, new Hot(x - 1, y - 1, 14, 14, key, Component.translatable(tipKey), run));
    }

    // ---------------------------------------------------------------- чаты

    private void renderChats(GuiGraphics g, int mx, int my) {
        title(g, Component.translatable("rppda.page.chats"), 4);
        List<Object> rows = new ArrayList<>();
        rows.addAll(invites);
        rows.addAll(chats);
        list(g, mx, my, rows, 24, "chat", (gg, it, y, hover) -> {
            CompoundTag r = (CompoundTag) it;
            if (r.contains("from")) {
                drawInvite(gg, r, y, hover);
                return;
            }
            drawChatRow(gg, r, y);
        }, (it, y) -> {
            CompoundTag r = (CompoundTag) it;
            if (!r.contains("from")) openChat(r, Page.CHATS);
        });
    }

    private void drawInvite(GuiGraphics g, CompoundTag r, int y, boolean hover) {
        g.fill(cx, y, cx + 2, y + 24, Ui.GOLD);
        g.fill(cx + 4, y + 4, cx + 20, y + 20, 0xFF5A4A1E);
        Ui.icon(g, r.getBoolean("channel") ? Icon.CHANNEL : Icon.GROUP, cx + 6, y + 6, 12, Ui.GOLD);
        g.drawString(font, Ui.fit(font, r.getString("title"), cw - 80), cx + 24, y + 3, Ui.TEXT, false);
        g.drawString(font, Ui.fit(font, Component.translatable("rppda.screen.invite_from", r.getString("from")).getString(), cw - 80),
                cx + 24, y + 13, Ui.GOLD, false);
        int chat = r.getInt("chat");
        rowIcon(g, cx + cw - 34, y + 6, Icon.CHECK, "rppda.btn.accept", "acc" + chat, Ui.GREEN,
                () -> send("group.accept", t -> t.putInt("chat", chat)));
        rowIcon(g, cx + cw - 17, y + 6, Icon.CLOSE, "rppda.btn.decline", "dec" + chat, Ui.RED,
                () -> send("group.decline", t -> t.putInt("chat", chat)));
    }

    private void chatAvatar(GuiGraphics g, CompoundTag r, int x, int y, int size) {
        String type = r.getString("type");
        switch (type) {
            case "GLOBAL" -> {
                g.fill(x, y, x + size, y + size, 0xFF24557F);
                Ui.icon(g, Icon.GLOBE, x + 1, y + 1, size - 2, 0xFFFFFFFF);
            }
            case "GROUP", "CHANNEL" -> {
                g.fill(x, y, x + size, y + size, 0xFF3C4A5C);
                Ui.icon(g, type.equals("GROUP") ? Icon.GROUP : Icon.CHANNEL, x + 1, y + 1, size - 2, 0xFFFFFFFF);
            }
            default -> {
                if (r.contains("peer"))
                    Ui.avatar(g, x, y, size, r.hasUUID("owner") ? r.getUUID("owner") : null, r.getString("title"), r.getInt("peer"));
                else {
                    g.fill(x, y, x + size, y + size, 0xFF3C3C46);
                    Ui.icon(g, Icon.MESSAGE, x + 1, y + 1, size - 2, 0xFFFFFFFF);
                }
            }
        }
    }

    private Component chatTitle(CompoundTag r) {
        return r.getString("type").equals("GLOBAL") ? Component.translatable("rppda.chat.global") : Component.literal(r.getString("title"));
    }

    private void drawChatRow(GuiGraphics g, CompoundTag r, int y) {
        chatAvatar(g, r, cx + 4, y + 4, 16);
        int unread = r.getInt("unread");
        String time = Ui.time(r.getLong("time"));
        int tw = font.width(time);
        g.drawString(font, Ui.fit(font, chatTitle(r).getString(), cw - 34 - tw), cx + 24, y + 3, unread > 0 ? 0xFFFFFFFF : Ui.TEXT, false);
        g.drawString(font, time, cx + cw - tw - 4, y + 3, Ui.DIM, false);
        String last = r.getString("last");
        String from = r.getString("lastFrom");
        String preview = last.isEmpty() ? (r.getLong("time") > 0 ? "…" : "") : (from.isEmpty() ? last : from + ": " + last);
        int right = 6;
        String mode = r.getString("mode");
        if (mode.equals("MUTED")) {
            Ui.icon(g, Icon.MUTE, cx + cw - 14, y + 13, 9, Ui.DIM);
            right += 12;
        }
        if (unread > 0) {
            String n = unread > 99 ? "99+" : Integer.toString(unread);
            int bw = font.width(n) + 6;
            g.fill(cx + cw - right - bw + 2, y + 12, cx + cw - right + 2, y + 22, mode.equals("MUTED") ? 0xFF55606B : Ui.ACCENT);
            g.drawString(font, n, cx + cw - right - bw + 5, y + 13, 0xFFFFFFFF, false);
            right += bw + 2;
        }
        g.drawString(font, Ui.fit(font, preview, cw - 28 - right), cx + 24, y + 13, Ui.DIM, false);
    }

    // ---------------------------------------------------------------- открытый чат

    private record Block(CompoundTag m, List<FormattedCharSequence> lines, int height, boolean own, boolean notice,
                         @Nullable String date, int bubbleW, @Nullable List<FormattedCharSequence> quote) {}

    private int maxBubble() {
        return Math.max(80, cw * 3 / 4);
    }

    private void layout() {
        if (blocksVersion == msgsVersion && blocksWidth == cw) return;
        blocksVersion = msgsVersion;
        blocksWidth = cw;
        List<Block> out = new ArrayList<>();
        String lastDay = "";
        boolean group = chatRow != null && !chatRow.getString("type").equals("DM");
        for (CompoundTag m : msgs) {
            String day = Ui.day(m.getLong("t"));
            String date = day.equals(lastDay) ? null : day;
            lastDay = day;
            int fl = m.getInt("fl");
            if ((fl & 2) != 0) {
                Component text = noticeText(m.getString("x"));
                List<FormattedCharSequence> lines = font.split(text, cw - 20);
                out.add(new Block(m, lines, lines.size() * 9 + 4 + (date != null ? 14 : 0), false, true, date, 0, null));
                continue;
            }
            boolean own = m.getInt("f") == meId;
            boolean deleted = (fl & 1) != 0;
            Component body = deleted && m.getString("x").isEmpty() ? Component.translatable("rppda.chat.deleted").withStyle(ChatFormatting.ITALIC)
                    : (fl & 4) != 0 ? Component.literal("* " + m.getString("x")).withStyle(ChatFormatting.ITALIC)
                    : Component.literal(m.getString("x"));
            if (deleted && !m.getString("x").isEmpty()) body = body.copy().withStyle(ChatFormatting.STRIKETHROUGH);
            int inner = maxBubble() - 10;
            List<FormattedCharSequence> lines = font.split(body, inner);
            List<FormattedCharSequence> quote = null;
            if (m.getInt("r") > 0) {
                String q = m.contains("rn") ? m.getString("rn") + ": " + (m.getString("rx").isEmpty() ? "…" : m.getString("rx")) : "…";
                quote = List.of(Language.fit(font, q, inner - 6));
            }
            int textW = 0;
            for (FormattedCharSequence l : lines) textW = Math.max(textW, font.width(l));
            String header = header(m, own, group);
            int headW = font.width(header) + 8 + font.width(Ui.clock(m.getLong("t")));
            int bw = Math.min(maxBubble(), Math.max(Math.max(textW, headW), quote == null ? 0 : Math.min(inner, font.width(quote.get(0)) + 6)) + 10);
            int hgt = 4 + 9 + (quote != null ? 11 : 0) + lines.size() * 9 + 3;
            out.add(new Block(m, lines, hgt + 3 + (date != null ? 14 : 0), own, false, date, bw, quote));
        }
        blocks = out;
    }

    /** Заголовок пузыря: имя (у своих — пусто), у ГМа — пометка. */
    private String header(CompoundTag m, boolean own, boolean group) {
        String s = own ? "" : m.getString("n");
        if (m.contains("g")) s = (s.isEmpty() ? "" : s + " ") + "[ГМ " + m.getString("g") + "]";
        return s;
    }

    private Component noticeText(String raw) {
        String[] p = raw.split("\n");
        Object[] args = new Object[p.length - 1];
        System.arraycopy(p, 1, args, 0, args.length);
        return Component.translatable(p[0], args);
    }

    private int totalHeight() {
        layout();
        int t = 0;
        for (Block b : blocks) t += b.height;
        return t;
    }

    private void renderChat(GuiGraphics g, int mx, int my) {
        if (chatRow == null) return;
        // Шапка.
        chatAvatar(g, chatRow, cx + 22, cy + 1, 16);
        int toolW = 19 * 3;
        g.drawString(font, Ui.fit(font, chatTitle(chatRow).getString(), cw - 44 - toolW), cx + 42, cy + 1, Ui.TEXT, false);
        String type = chatRow.getString("type");
        Component sub = switch (type) {
            case "GROUP" -> Component.translatable("rppda.chat.members_n", chatRow.getInt("count"));
            case "CHANNEL" -> Component.translatable("rppda.chat.channel_n", chatRow.getInt("count"));
            case "GLOBAL" -> Component.translatable("rppda.chat.global_sub");
            default -> chatRow.contains("peer") ? Component.literal("ID " + Ui.formatId(chatRow.getInt("peer"))) : Component.empty();
        };
        g.drawString(font, sub, cx + 42, cy + 10, Ui.DIM, false);
        g.fill(cx, cy + 19, cx + cw, cy + 20, 0xFF1E2833);

        boolean canWrite = chatRow.getBoolean("write");
        int top = cy + 21;
        int bottom = cy + ch - (canWrite ? 19 : 12) - (replySeq > 0 ? 14 : 0);
        if (!canWrite) {
            Component ro = Component.translatable(chatRow.getBoolean("member") ? "rppda.chat.read_only_channel" : "rppda.chat.read_only");
            g.drawString(font, ro, cx + cw / 2 - font.width(ro) / 2, cy + ch - 10, Ui.DIM, false);
        }
        layout();
        int total = 0;
        for (Block b : blocks) total += b.height;
        int viewH = bottom - top;
        scroll = Math.min(scroll, Math.max(0, total - viewH));
        if (!loading && more && (total <= viewH || scroll >= total - viewH - 4)) {
            loading = true;
            int first = msgs.isEmpty() ? 0 : msgs.get(0).getInt("s");
            send("chat.history", t -> {
                t.putInt("chat", chatRow.getInt("id"));
                t.putInt("before", first);
            });
        }
        g.enableScissor(cx, top, cx + cw, bottom);
        int y = bottom - total + (int) scroll;
        if (loading) {
            Component l = Component.translatable("rppda.screen.loading");
            g.drawString(font, l, cx + cw / 2 - font.width(l) / 2, Math.max(top + 2, y - 12), Ui.DIM, false);
        }
        boolean group = !type.equals("DM");
        for (Block b : blocks) {
            int by = y;
            y += b.height;
            if (y < top || by > bottom) continue;
            if (b.date != null) {
                String d = b.date;
                int dw = font.width(d) + 8;
                g.fill(cx + cw / 2 - dw / 2, by + 1, cx + cw / 2 + dw / 2, by + 11, 0xFF1A2430);
                g.drawString(font, d, cx + cw / 2 - dw / 2 + 4, by + 2, Ui.DIM, false);
                by += 14;
            }
            if (b.notice) {
                int ly = by + 2;
                for (FormattedCharSequence l : b.lines) {
                    g.drawString(font, l, cx + cw / 2 - font.width(l) / 2, ly, Ui.DIM, false);
                    ly += 9;
                }
                continue;
            }
            CompoundTag m = b.m;
            int bw = b.bubbleW;
            int bh = b.height - 3 - (b.date != null ? 14 : 0);
            int bx = b.own ? cx + cw - bw - 4 : cx + 4;
            boolean deleted = (m.getInt("fl") & 1) != 0;
            g.fill(bx, by, bx + bw, by + bh, b.own ? Ui.OWN : Ui.OTHER);
            if (deleted) g.fill(bx, by, bx + 2, by + bh, Ui.RED);
            String head = header(m, b.own, group);
            if (!head.isEmpty()) g.drawString(font, Ui.fit(font, head, bw - 40), bx + 5, by + 3,
                    m.contains("g") ? Ui.GOLD : Ui.ACCENT, false);
            String clock = Ui.clock(m.getLong("t"));
            g.drawString(font, clock, bx + bw - font.width(clock) - 4, by + 3, 0xFF7F93A8, false);
            int ty = by + 13;
            if (b.quote != null) {
                g.fill(bx + 5, ty, bx + 6, ty + 9, Ui.ACCENT);
                g.drawString(font, b.quote.get(0), bx + 9, ty + 1, 0xFF9FB3C8, false);
                ty += 11;
            }
            for (FormattedCharSequence l : b.lines) {
                g.drawString(font, l, bx + 5, ty, deleted ? Ui.DIM : Ui.TEXT, false);
                ty += 9;
            }
            boolean hover = mx >= bx && mx < bx + bw && my >= by && my < by + bh && my >= top && my < bottom;
            if (hover && !deleted) {
                int ix = b.own ? bx - 15 : bx + bw + 3;
                int seq = m.getInt("s");
                int step = b.own ? -14 : 14;
                if (canWrite) {
                    String rn = b.own ? meName : m.getString("n");
                    String rx = m.getString("x");
                    rowIcon(g, ix, by + 1, Icon.REPLY, "rppda.btn.reply", "rep" + seq, Ui.TEXT, () -> {
                        replySeq = seq;
                        replyName = rn;
                        replyText = rx;
                        rebuild();
                    });
                    ix += step;
                }
                if (b.own || system) {
                    rowIcon(g, ix, by + 1, Icon.TRASH, "rppda.btn.delete", "del" + seq, Ui.RED, () -> send("chat.delete", t -> {
                        t.putInt("chat", chatRow.getInt("id"));
                        t.putInt("seq", seq);
                    }));
                }
            }
        }
        g.disableScissor();
        if (replySeq > 0 && canWrite) {
            int ry = cy + ch - 32;
            g.fill(cx, ry, cx + cw, ry + 13, Ui.PANEL);
            g.fill(cx, ry, cx + 2, ry + 13, Ui.ACCENT);
            Ui.icon(g, Icon.REPLY, cx + 4, ry + 1, 11, Ui.ACCENT);
            g.drawString(font, Ui.fit(font, replyName + ": " + replyText, cw - 36), cx + 18, ry + 3, Ui.DIM, false);
        }
        if (scroll > 0) {
            int ax = cx + cw - 16, ay = bottom - 16;
            g.fill(ax, ay, ax + 14, ay + 14, 0xCC24557F);
            Ui.icon(g, Icon.BACK, ax + 1, ay + 1, 12, 0xFFFFFFFF);
            hots.add(0, new Hot(ax, ay, 14, 14, "down", Component.translatable("rppda.btn.to_bottom"), () -> scroll = 0));
        }
    }

    // ---------------------------------------------------------------- контакты, участники, профиль

    private void renderContacts(GuiGraphics g, int mx, int my) {
        title(g, Component.translatable("rppda.page.contacts"), 4);
        list(g, mx, my, contacts, 22, "contact", (gg, c, y, hover) -> {
            int id = c.getInt("id");
            String label = c.getString("label");
            String name = c.getString("name");
            boolean blocked = c.getBoolean("blocked");
            Ui.avatar(gg, cx + 4, y + 3, 16, c.hasUUID("owner") ? c.getUUID("owner") : null, name, id);
            gg.drawString(font, Ui.fit(font, label.isEmpty() ? name : label, cw - 100), cx + 24, y + 2, blocked ? Ui.DIM : Ui.TEXT, false);
            String sub = (label.isEmpty() ? "" : name + " · ") + Ui.formatId(id) + (blocked ? " · " + Component.translatable("rppda.screen.blocked").getString() : "");
            gg.drawString(font, Ui.fit(font, sub, cw - 100), cx + 24, y + 12, blocked ? Ui.RED : Ui.DIM, false);
            if (!hover) return;
            int ix = cx + cw - 16;
            rowIcon(gg, ix, y + 5, Icon.TRASH, "rppda.btn.remove_contact", "crm" + id, Ui.RED, () -> {
                send("contact.remove", t -> t.putInt("id", id));
                if (blocked) send("contact.block", t -> {
                    t.putInt("id", id);
                    t.putBoolean("on", false);
                });
            });
            ix -= 15;
            rowIcon(gg, ix, y + 5, Icon.BLOCK, blocked ? "rppda.btn.unblock" : "rppda.btn.block", "cbl" + id, blocked ? Ui.GREEN : Ui.GOLD,
                    () -> send("contact.block", t -> {
                        t.putInt("id", id);
                        t.putBoolean("on", !blocked);
                    }));
            ix -= 15;
            rowIcon(gg, ix, y + 5, Icon.EDIT, "rppda.btn.edit_label", "ced" + id, Ui.TEXT, () -> openPrompt(Prompt.EDIT_LABEL, id));
            ix -= 15;
            rowIcon(gg, ix, y + 5, Icon.MESSAGE, "rppda.btn.write", "cms" + id, Ui.ACCENT, () -> send("dm", t -> t.putInt("id", id)));
        }, null);
    }

    private void renderMembers(GuiGraphics g, int mx, int my) {
        if (chatRow == null) return;
        g.drawString(font, Ui.fit(font, chatRow.getString("title"), cw - 90), cx + 22, cy + 5, Ui.TEXT, false);
        if (members == null) {
            g.drawString(font, Component.translatable("rppda.screen.loading"), cx + 6, listTop() + 6, Ui.DIM, false);
            return;
        }
        int adminId = members.getInt("admin");
        boolean iAmAdmin = adminId == meId;
        int chatId = members.getInt("chat");
        List<CompoundTag> rows = new ArrayList<>(list(members, "members"));
        List<CompoundTag> inv = list(members, "invited");
        for (CompoundTag i : inv) i.putBoolean("_inv", true);
        rows.addAll(inv);
        list(g, mx, my, rows, 20, "mem", (gg, c, y, hover) -> {
            int id = c.getInt("id");
            boolean invited = c.getBoolean("_inv");
            Ui.avatar(gg, cx + 4, y + 2, 16, c.hasUUID("owner") ? c.getUUID("owner") : null, c.getString("name"), id);
            int nx = cx + 24;
            if (id == adminId) {
                Ui.icon(gg, Icon.CROWN, nx, y + 5, 10, Ui.GOLD);
                nx += 12;
            }
            gg.drawString(font, Ui.fit(font, c.getString("name"), cw - 110), nx, y + 2, invited ? Ui.DIM : Ui.TEXT, false);
            gg.drawString(font, Ui.formatId(id) + (invited ? " · " + Component.translatable("rppda.screen.invited").getString() : ""),
                    nx, y + 11, Ui.DIM, false);
            if (!hover || !iAmAdmin || id == meId) return;
            int ix = cx + cw - 16;
            rowIcon(gg, ix, y + 4, invited ? Icon.CLOSE : Icon.KICK, invited ? "rppda.btn.cancel_invite" : "rppda.btn.kick",
                    "kick" + id, Ui.RED, () -> send("group.kick", t -> {
                        t.putInt("chat", chatId);
                        t.putInt("id", id);
                    }));
            if (!invited) rowIcon(gg, ix - 15, y + 4, Icon.CROWN, "rppda.btn.make_admin", "adm" + id, Ui.GOLD,
                    () -> send("group.admin", t -> {
                        t.putInt("chat", chatId);
                        t.putInt("id", id);
                    }));
        }, null);
    }

    private void renderProfile(GuiGraphics g) {
        title(g, Component.translatable("rppda.page.profile"), 4);
        Ui.avatar(g, cx + 6, cy + 18, 32, meOwner, meName, meId);
        g.drawString(font, meName, cx + 44, cy + 18, Ui.TEXT, false);
        g.drawString(font, "ID " + Ui.formatId(meId), cx + 44, cy + 30, Ui.ACCENT, false);
        Component kind = Component.translatable(meKind.equals("NPC") ? "rppda.kind.npc" : "rppda.kind.player");
        g.drawString(font, kind, cx + 44, cy + 41, Ui.DIM, false);
        int by = cy + 64;
        Component pw = Component.translatable(hasPw ? "rppda.profile.pw_on" : "rppda.profile.pw_off");
        g.drawString(font, pw, cx + 6 + (hasPw ? 62 : 22), by + 5, hasPw ? Ui.GREEN : Ui.DIM, false);
        Component snd = Component.translatable(silent ? "rppda.profile.silent" : "rppda.profile.sound");
        g.drawString(font, snd, cx + 28, by + 31, Ui.DIM, false);
        List<FormattedCharSequence> hint = font.split(Component.translatable("rppda.profile.hint"), cw - 12);
        int hy = by + 56;
        for (FormattedCharSequence l : hint) {
            g.drawString(font, l, cx + 6, hy, 0xFF5E6B78, false);
            hy += 9;
        }
    }

    // ---------------------------------------------------------------- системный КПК

    private void renderSysAccounts(GuiGraphics g, int mx, int my) {
        list(g, mx, my, sysAccounts, 22, "sacc", (gg, a, y, hover) -> {
            int id = a.getInt("id");
            String name = a.getString("name");
            Ui.avatar(gg, cx + 4, y + 3, 16, a.hasUUID("owner") ? a.getUUID("owner") : null, name, id);
            gg.drawString(font, Ui.fit(font, name, cw - 60), cx + 24, y + 2, Ui.TEXT, false);
            gg.drawString(font, Ui.fit(font, sysSub(a), cw - 40), cx + 24, y + 12, Ui.DIM, false);
            int ix = cx + cw - 14;
            if (a.getBoolean("online")) gg.fill(ix + 4, y + 4, ix + 8, y + 8, Ui.GREEN);
            if (a.getBoolean("locked")) Ui.icon(gg, Icon.LOCK, ix - 12, y + 3, 9, Ui.GOLD);
        }, (a, y) -> {
            sysAcc = a;
            setPage(Page.SYS_ACCOUNT);
        });
    }

    private String sysSub(CompoundTag a) {
        String kind = a.getString("kind");
        String k = Component.translatable(kind.equals("NPC") ? "rppda.kind.npc" : kind.equals("SYSTEM") ? "rppda.kind.system" : "rppda.kind.player").getString();
        String owner = a.getString("ownerName");
        int id = a.getInt("id");
        return (id == 0 ? "" : Ui.formatId(id) + " · ") + k + (owner.isEmpty() ? "" : " (" + owner + ")");
    }

    private void renderSysAccount(GuiGraphics g) {
        if (sysAcc == null) return;
        int id = sysAcc.getInt("id");
        String name = sysAcc.getString("name");
        Ui.avatar(g, cx + 6, cy + 22, 32, sysAcc.hasUUID("owner") ? sysAcc.getUUID("owner") : null, name, id);
        g.drawString(font, name, cx + 44, cy + 22, Ui.TEXT, false);
        g.drawString(font, sysSub(sysAcc), cx + 44, cy + 33, Ui.DIM, false);
        List<String> flags = new ArrayList<>();
        if (sysAcc.getBoolean("online")) flags.add(Component.translatable("rppda.sys.online").getString());
        if (sysAcc.getBoolean("pw")) flags.add(Component.translatable("rppda.sys.pw").getString());
        if (sysAcc.getBoolean("locked")) flags.add(Component.translatable("rppda.sys.locked").getString());
        if (id != 0) flags.add(Component.translatable("rppda.sys.issue", sysAcc.getInt("issue")).getString());
        g.drawString(font, Ui.fit(font, String.join(" · ", flags), cw - 50), cx + 44, cy + 44, Ui.GOLD, false);
        if (sysAcc.getLong("created") > 0)
            g.drawString(font, Component.translatable("rppda.sys.created", Ui.day(sysAcc.getLong("created"))), cx + 44, cy + 55, Ui.DIM, false);
    }

    private void renderSysChats(GuiGraphics g, int mx, int my) {
        title(g, Component.translatable("rppda.page.all_chats"), 4);
        list(g, mx, my, sysChats, 24, "schat", (gg, r, y, hover) -> drawChatRow(gg, r, y), (r, y) -> openChat(r, Page.SYS_CHATS));
    }

    private void renderSysLog(GuiGraphics g) {
        title(g, Component.translatable("rppda.page.log"), 4);
        list(g, 0, 0, sysLog, 11, "log", (gg, e, y, hover) -> {
            String when = new java.text.SimpleDateFormat("dd.MM HH:mm").format(new java.util.Date(e.getLong("time")));
            String line = when + "  " + e.getString("gm") + "  "
                    + Component.translatable("rppda.log." + e.getString("action")).getString() + "  " + e.getString("target");
            gg.drawString(font, Ui.fit(font, line, cw - 8), cx + 4, y + 1, Ui.DIM, false);
        }, null);
    }

    /** Обрезка одной строки по ширине (для цитат). */
    private static final class Language {
        static FormattedCharSequence fit(net.minecraft.client.gui.Font font, String s, int w) {
            return FormattedCharSequence.forward(Ui.fit(font, s, w), Style.EMPTY);
        }
    }
}
