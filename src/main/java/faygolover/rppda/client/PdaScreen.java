package faygolover.rppda.client;

import com.mojang.blaze3d.platform.InputConstants;
import faygolover.rppda.client.Ui.Icon;
import faygolover.rppda.client.Ui.IconButton;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
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
    enum Page { WAIT, REGISTER, LOCKED, CHATS, CHAT, CONTACTS, PROFILE, MEMBERS, SYS_ACCOUNTS, SYS_ACCOUNT, SYS_CHATS, SYS_LOG, LOOK, NOTES, NOTE, NOTE_SHARE, APP }

    enum Prompt { NONE, NEW_DM, NEW_GROUP, NEW_CHANNEL, ADD_CONTACT, EDIT_LABEL, SET_PW, REMOVE_PW, RENAME_GROUP, INVITE, NPC, NPC_RENAME }

    private Page page = Page.WAIT;

    /** Ретро-вид (80-е): решает сервер ({@code pda_style}) или игрок ({@code rppda-client.toml}). */
    private boolean retro;
    private boolean themedSystem;
    private String themeKey = "";
    /** Внешний вид этого КПК (задаёт ГМ); на странице «Внешний вид» — правка, видна сразу на корпусе. */
    private faygolover.rppda.data.Look look = new faygolover.rppda.data.Look();
    @Nullable
    private faygolover.rppda.data.Look editLook;
    /** Кому правим вид: −1 — новый КПК в руке, иначе ID учётки (системный КПК). */
    private int lookTarget = -1;
    private Page lookReturn = Page.REGISTER;
    @Nullable
    private EditBox modelBox;
    private boolean gm, character, signalOk = true, allowOut;
    /** Заметки учётки и открытая заметка (её черновик живёт в полях — окно пересобирается). */
    private final List<CompoundTag> notes = new ArrayList<>();
    private int noteId;
    private String noteTitle = "", noteText = "";
    private long noteDeleteArmed;
    @Nullable
    private EditBox noteTitleBox, npcBox;
    @Nullable
    private net.minecraft.client.gui.components.MultiLineEditBox noteBody;
    /** Удаление учётки в системном КПК: сколько раз подтверждено. */
    private int deleteArmId, deleteClicks;
    private long deleteArmTime;
    /** Раздел аддона, листание клавиш (ретро) и полосы разделов (современный), листание образцов корпусов. */
    @Nullable
    private faygolover.rppda.api.PdaApps.App app;
    private int keyPage, railOffset, swatchPage;
    /** Вид, заданный сервером: "" — выбирает игрок, иначе "modern"/"retro". */
    private String defaultStyle = "";
    /** Нажатая клавиша корпуса (−1 — нет) и чем: мышью держат, пока не отпустят. */
    private int heldKey = -1;
    private boolean heldByMouse;
    private static final ResourceLocation VIGNETTE = new ResourceLocation(faygolover.rppda.RpPda.MODID, "textures/gui/crt_vignette.png");
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
        retro = styleRetro();
        themedSystem = systemSkin();
        faygolover.rppda.data.Look shown = shownLook();
        Ui.theme(retro, themedSystem, shown.screen, shown.accent);
        themeKey = themeKey();
        if (retro) {
            // Корпус: полоса с названием сверху, экран, внизу клавиши.
            w = Math.min(360, width - 8);
            h = Math.min(254, height - 8);
            x0 = (width - w) / 2;
            y0 = (height - h) / 2;
            sx = x0 + 18;
            sy = y0 + 18;
            sw = w - 36;
            sh = h - 18 - 48;
            cx = sx + 4;
            cy = sy + 13;
            cw = sw - 8;
            ch = sh - 15;
            blocksWidth = -1;
            rebuild();
            return;
        }
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

    /** Какой вид показываем: правка на странице «Внешний вид» или вид этого КПК. */
    private faygolover.rppda.data.Look shownLook() {
        return page == Page.LOOK && editLook != null ? editLook : look;
    }

    /** Корпус системного КПК (красный, янтарный экран) — кроме страницы, где ГМ правит чужой вид. */
    private boolean systemSkin() {
        return system && page != Page.LOOK;
    }

    private String themeKey() {
        faygolover.rppda.data.Look l = shownLook();
        return styleRetro() + "/" + systemSkin() + "/" + l.screen + "/" + l.accent;
    }

    private boolean styleRetro() {
        // Вид задаётся при выдаче: свой у этого КПК или по умолчанию с сервера.
        String own = shownLook().style;
        if (!systemSkin() && !own.isEmpty()) return own.equals("retro");
        return !defaultStyle.equals("modern");
    }

    /** Вид или корпус (обычный/системный) поменялся — пересобрать окно. */
    private boolean refreshTheme() {
        if (themeKey().equals(themeKey)) return false;
        rebuildWidgets();
        return true;
    }

    @Override
    public void removed() {
        Ui.theme(false, false);
        super.removed();
    }

    // ================================================================ приём

    void receive(String op, CompoundTag d) {
        switch (op) {
            case "register" -> {
                defaultStyle = d.getString("style");
                look = faygolover.rppda.data.Look.load(d.getCompound("look"));
                gm = d.getBoolean("gm");
                editLook = null;
                system = false;
                refreshTheme();
                setPage(Page.REGISTER);
            }
            case "locked" -> {
                defaultStyle = d.getString("style");
                look = faygolover.rppda.data.Look.load(d.getCompound("look"));
                system = false;
                refreshTheme();
                lockedName = d.getString("name");
                lockedId = d.getInt("id");
                waitUntil = System.currentTimeMillis() + d.getLong("wait");
                setPage(Page.LOCKED);
            }
            case "state" -> {
                defaultStyle = d.getString("style");
                readState(d);
                refreshTheme();
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
            case "notes" -> {
                notes.clear();
                notes.addAll(list(d, "list"));
            }
            case "notes.saved" -> {
                noteId = d.getInt("id");
                if (page == Page.NOTE) rebuild();
            }
            case "sys.log" -> {
                sysLog.clear();
                sysLog.addAll(list(d, "list"));
            }
            case "sys.account" -> {
                CompoundTag a = d.getCompound("acc");
                if (page == Page.LOOK) return;
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
        look = d.contains("look") ? faygolover.rppda.data.Look.load(d.getCompound("look")) : new faygolover.rppda.data.Look();
        gm = d.getBoolean("admin");
        character = d.getBoolean("character");
        signalOk = !d.contains("signal") || d.getBoolean("signal");
        allowOut = d.getBoolean("allowOut");
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
        if (page == Page.LOOK && p != Page.LOOK) editLook = null;
        if (page == Page.APP && p != Page.APP && app != null) app.close();
        page = p;
        prompt = Prompt.NONE;
        listScroll = 0;
        switch (p) {
            case SYS_ACCOUNTS -> send("sys.accounts", t -> t.putString("q", sysQuery));
            case SYS_CHATS -> send("sys.chats", t -> {});
            case SYS_LOG -> send("sys.log", t -> {});
            case NOTES -> send("notes.list", t -> {});
            default -> {}
        }
        // Ушли с «Внешнего вида» (предпросмотр) — вернуть вид этого КПК.
        if (!refreshTheme()) rebuild();
    }

    private void rebuild() {
        if (input != null) inputDraft = input.getValue();
        clearWidgets();
        if (noteTitleBox != null) noteTitle = noteTitleBox.getValue();
        if (noteBody != null) noteText = noteBody.getValue();
        promptA = promptB = input = password = search = modelBox = noteTitleBox = npcBox = null;
        noteBody = null;
        if (page == Page.WAIT) return;
        if (page == Page.REGISTER) {
            addRenderableWidget(new IconButton(sx + sw / 2 - 70, sy + sh / 2 + 10, 140, 20, Icon.CHECK,
                    Component.translatable("rppda.btn.register"), Component.translatable("rppda.btn.register"),
                    b -> ClientPda.send("register", new CompoundTag())));
            if (gm) {
                addRenderableWidget(new IconButton(sx + sw - 22, sy + 14, Icon.BRUSH, Component.translatable("rppda.btn.look"),
                        b -> openLook(-1, Page.REGISTER)));
                // NPC-учётка прямо на этот КПК.
                int ny = sy + sh / 2 + 58;
                npcBox = new EditBox(font, sx + sw / 2 - 70, ny, 118, retro ? 10 : 14, Component.translatable("rppda.hint.npc_name"));
                npcBox.setMaxLength(32);
                npcBox.setHint(hint("rppda.hint.npc_name"));
                box(npcBox);
                addRenderableWidget(npcBox);
                addRenderableWidget(new IconButton(sx + sw / 2 + 52, ny - 3, Icon.PERSON_ADD, Component.translatable("rppda.btn.register_npc"), b -> {
                    String n = npcBox == null ? "" : npcBox.getValue().strip();
                    if (!n.isEmpty()) send("register.npc", t -> t.putString("name", n));
                }));
            }
            return;
        }
        if (page == Page.LOOK && lookTarget == -1) {
            lookWidgets();
            return;
        }
        if (page == Page.LOCKED) {
            password = new EditBox(font, sx + sw / 2 - 60, sy + sh / 2 + 8, 100, 16, Component.translatable("rppda.hint.password"));
            password.setMaxLength(32);
            mask(password);
            box(password);
            password.setHint(hint("rppda.hint.password"));
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
                search.setHint(hint("rppda.hint.search"));
                search.setValue(sysQuery);
                box(search);
                search.setResponder(s -> {
                    sysQuery = s;
                    send("sys.accounts", t -> t.putString("q", s));
                });
                addRenderableWidget(search);
                toolbarButton(0, Icon.PLUS, "rppda.btn.npc", () -> openPrompt(Prompt.NPC, 0));
                toolbarButton(1, Icon.REFRESH, "rppda.btn.refresh", () -> send("sys.accounts", t -> t.putString("q", sysQuery)));
                IconButton mask = toolbarButton(2, Icon.MASK, character ? "rppda.btn.character_off" : "rppda.btn.character_on",
                        () -> send("sys.character", t -> t.putBoolean("on", !character)));
                if (character) mask.color = Ui.GOLD;
            }
            case SYS_ACCOUNT -> sysAccountWidgets();
            case LOOK -> lookWidgets();
            case NOTES -> toolbarButton(0, Icon.PLUS, "rppda.btn.note_new", () -> openNote(null));
            case NOTE -> noteWidgets();
            case NOTE_SHARE -> addRenderableWidget(new IconButton(cx, cy, Icon.BACK, Component.translatable("rppda.btn.back"), b -> setPage(Page.NOTE)));
            case APP -> {
                if (app != null) app.open(host);
            }
            case SYS_CHATS -> toolbarButton(0, Icon.REFRESH, "rppda.btn.refresh", () -> send("sys.chats", t -> {}));
            case SYS_LOG -> toolbarButton(0, Icon.REFRESH, "rppda.btn.refresh", () -> send("sys.log", t -> {}));
            default -> {}
        }
        if (prompt != Prompt.NONE) promptWidgets();
    }

    private void rail() {
        if (retro) return;
        int controls = 1 + (!system && hasPw ? 1 : 0) + (system && meId != 0 ? 1 : 0);
        int fit = Math.max(1, (sy + sh - 20 * controls - 4 - cy) / 20);
        List<Section> secs = sections();
        railOffset = Math.max(0, Math.min(railOffset, secs.size() - fit));
        int y = cy;
        for (int i = railOffset; i < secs.size() && i < railOffset + fit; i++) {
            Section sec = secs.get(i);
            IconButton b = new IconButton(sx + 3, y, sec.icon == null ? Icon.PDA : sec.icon, sec.title, btn -> sec.run.run()) {
                @Override
                protected void renderWidget(GuiGraphics gg, int mx, int my, float pt) {
                    if (sec.app == null) {
                        super.renderWidget(gg, mx, my, pt);
                        return;
                    }
                    if (isHoveredOrFocused()) gg.fill(getX(), getY(), getX() + width, getY() + height, 0x33FFFFFF);
                    sec.app.icon(gg, getX() + 1, getY() + 1, isHoveredOrFocused() ? 0xFFFFFFFF : color);
                }
            };
            if (sec.on) b.color = Ui.ACCENT;
            addRenderableWidget(b);
            y += 20;
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

    /** Раздел КПК: встроенный (значок) или аддона ({@code app}). */
    private record Section(@Nullable Icon icon, @Nullable faygolover.rppda.api.PdaApps.App app, Component title, boolean on, Runnable run) {}

    /** Разделы этого КПК: встроенные, потом аддонов (их клавиши/кнопки подстраиваются). */
    private List<Section> sections() {
        List<Section> out = new ArrayList<>();
        boolean inChats = (page == Page.CHAT || page == Page.MEMBERS);
        out.add(new Section(Icon.CHATS, null, Component.translatable("rppda.page.chats"), page == Page.CHATS || inChats && chatReturn == Page.CHATS,
                () -> setPage(Page.CHATS)));
        out.add(new Section(Icon.CONTACTS, null, Component.translatable("rppda.page.contacts"), page == Page.CONTACTS, () -> setPage(Page.CONTACTS)));
        out.add(new Section(Icon.NOTE, null, Component.translatable("rppda.page.notes"),
                page == Page.NOTES || page == Page.NOTE || page == Page.NOTE_SHARE, () -> setPage(Page.NOTES)));
        if (system) {
            out.add(new Section(Icon.ACCOUNTS, null, Component.translatable("rppda.page.accounts"), page == Page.SYS_ACCOUNTS || page == Page.SYS_ACCOUNT,
                    () -> setPage(Page.SYS_ACCOUNTS)));
            out.add(new Section(Icon.GLOBE, null, Component.translatable("rppda.page.all_chats"),
                    page == Page.SYS_CHATS || inChats && chatReturn == Page.SYS_CHATS, () -> setPage(Page.SYS_CHATS)));
            out.add(new Section(Icon.LOG, null, Component.translatable("rppda.page.log"), page == Page.SYS_LOG, () -> setPage(Page.SYS_LOG)));
        } else {
            out.add(new Section(Icon.PROFILE, null, Component.translatable("rppda.page.profile"), page == Page.PROFILE, () -> setPage(Page.PROFILE)));
        }
        for (faygolover.rppda.api.PdaApps.App a : faygolover.rppda.api.PdaApps.all()) {
            if (system ? !a.system() : !a.player()) continue;
            out.add(new Section(null, a, a.title(), page == Page.APP && app == a, () -> openApp(a)));
        }
        return out;
    }

    private void openApp(faygolover.rppda.api.PdaApps.App a) {
        if (app != null && app != a) app.close();
        app = a;
        page = Page.APP;
        prompt = Prompt.NONE;
        rebuild();
    }

    /** Что окно даёт разделу аддона. */
    private final faygolover.rppda.api.PdaApps.Host host = new faygolover.rppda.api.PdaApps.Host() {
        public int x() { return cx; }
        public int y() { return cy; }
        public int width() { return cw; }
        public int height() { return ch; }
        public boolean retro() { return retro; }
        public int textColor() { return Ui.TEXT; }
        public int dimColor() { return Ui.DIM; }
        public int accentColor() { return Ui.ACCENT; }
        public int screenColor() { return Ui.SCREEN; }
        public net.minecraft.client.gui.Font font() { return font; }
        public void text(GuiGraphics g, String str, int x, int y, int color) { txt(g, str, x, y, color, false); }
        public <T extends net.minecraft.client.gui.components.events.GuiEventListener & net.minecraft.client.gui.components.Renderable
                & net.minecraft.client.gui.narration.NarratableEntry> T add(T widget) { return addRenderableWidget(widget); }
        public int account() { return meId; }
    };

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
            String nt = chatRow.getString("notify");
            Icon ni = nt.equals("SILENT") ? Icon.SILENT : nt.equals("NONE") ? Icon.MUTE : Icon.SOUND;
            toolbarButton(n++, ni, "rppda.notify." + (nt.isEmpty() ? "sound" : nt.toLowerCase(java.util.Locale.ROOT)), () -> {
                int next = nt.equals("SILENT") ? 2 : nt.equals("NONE") ? 0 : 1;
                send("chat.notify", t -> {
                    t.putInt("chat", chatRow.getInt("id"));
                    t.putInt("mode", next);
                });
            });
            boolean out = chatRow.getBoolean("out");
            IconButton ob = toolbarButton(n++, out ? Icon.CHATS : Icon.PDA_ONLY,
                    out ? "rppda.out.chat" : allowOut ? "rppda.out.pda" : "rppda.out.pda_locked", () -> send("chat.out", t -> {
                        t.putInt("chat", chatRow.getInt("id"));
                        t.putBoolean("on", !out);
                    }));
            ob.active = out || allowOut;
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
        if (canWrite()) {
            int iy = cy + ch - 16;
            input = retro ? new EditBox(font, cx + 12, iy + 4, cw - 34, 10, Component.translatable("rppda.hint.message"))
                    : new EditBox(font, cx + 2, iy, cw - 24, 15, Component.translatable("rppda.hint.message"));
            box(input);
            input.setMaxLength(maxLen);
            input.setHint(hint("rppda.hint.message"));
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
        int bx = cx + 6, by = cy + (retro ? 54 : 64), st = retro ? 22 : 26;
        addRenderableWidget(new IconButton(cx + 44 + tw(Ui.formatId(meId)) + 30, cy + 26, 14, 14, Icon.COPY,
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
        IconButton snd = new IconButton(bx, by + st, silent ? Icon.SILENT : Icon.SOUND,
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
            acts.add(new Object[]{Icon.BRUSH, "rppda.btn.look", (Runnable) () -> openLook(id, Page.SYS_ACCOUNT)});
            boolean player = sysAcc.getString("kind").equals("PLAYER");
            acts.add(new Object[]{Icon.TRASH, "rppda.btn.delete_account", (Runnable) () -> armDelete(id, player)});
            boolean sig = !sysAcc.contains("signal") || sysAcc.getBoolean("signal");
            acts.add(new Object[]{sig ? Icon.SIGNAL : Icon.NO_SIGNAL, sig ? "rppda.btn.signal_off" : "rppda.btn.signal_on",
                    (Runnable) () -> send("sys.signal", t -> {
                        t.putInt("id", id);
                        t.putBoolean("on", !sig);
                    })});
            if (sysAcc.contains("ownerSignal")) {
                boolean os = sysAcc.getBoolean("ownerSignal");
                acts.add(new Object[]{os ? Icon.SIGNAL : Icon.NO_SIGNAL, os ? "rppda.btn.owner_signal_off" : "rppda.btn.owner_signal_on",
                        (Runnable) () -> send("sys.signalOwner", t -> {
                            t.putInt("id", id);
                            t.putBoolean("on", !os);
                        })});
            }
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
        promptA.setHint(hint(hintA()));
        promptA.setMaxLength(prompt == Prompt.NEW_DM || prompt == Prompt.INVITE || prompt == Prompt.ADD_CONTACT && promptTarget == 0 ? 9 : 32);
        if (prompt == Prompt.SET_PW || prompt == Prompt.REMOVE_PW) mask(promptA);
        box(promptA);
        if (prompt == Prompt.EDIT_LABEL) for (CompoundTag c : contacts) if (c.getInt("id") == promptTarget) promptA.setValue(c.getString("label"));
        if (prompt == Prompt.RENAME_GROUP && chatRow != null) promptA.setValue(chatRow.getString("title"));
        addRenderableWidget(promptA);
        setInitialFocus(promptA);
        setFocused(promptA);
        if (two) {
            promptB = new EditBox(font, cx + 8 + boxW, y + 2, boxW, 14, Component.empty());
            promptB.setHint(hint(prompt == Prompt.SET_PW ? "rppda.hint.new_password" : "rppda.hint.label")
                    );
            promptB.setMaxLength(32);
            if (prompt == Prompt.SET_PW) mask(promptB);
            box(promptB);
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
        if (page == Page.APP && app != null && app.keyPressed(key, scan, mods)) return true;
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
    public boolean keyReleased(int key, int scan, int mods) {
        if (heldKey >= 0 && !heldByMouse && key == GLFW.GLFW_KEY_F3 + heldKey) heldKey = -1;
        return super.keyReleased(key, scan, mods);
    }

    @Override
    public void onClose() {
        ClientPda.send("close", new CompoundTag());
        super.onClose();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (retro && button == 0) {
            List<Key> ks = keys();
            for (int i = 0; i < ks.size(); i++) {
                if (mx >= keyX(i, ks.size()) && mx < keyX(i, ks.size()) + keyW(ks.size()) && my >= keyY() && my < keyY() + KEY_H + 2) {
                    pressKey(i, true);
                    return true;
                }
            }
        }
        if (super.mouseClicked(mx, my, button)) return true;
        if (page == Page.APP && app != null && app.mouseClicked(mx, my, button)) return true;
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
    public boolean mouseReleased(double mx, double my, int button) {
        if (heldByMouse && button == 0) {
            heldKey = -1;
            heldByMouse = false;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (!retro && mx < sx + 22 && mx >= sx) {
            railOffset = Math.max(0, railOffset - (int) Math.signum(delta));
            rebuild();
            return true;
        }
        if (page == Page.APP && app != null && app.mouseScrolled(mx, my, delta)) return true;
        if (page == Page.LOOK && my >= cy + 50 && my < cy + 66) {
            swatchPage = Math.max(0, swatchPage - (int) Math.signum(delta));
            return true;
        }
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
        boolean sys = systemSkin();
        if (retro) retroBody(g, mx, my);
        else {
        // Корпус.
        g.fill(x0 - 1, y0 - 1, x0 + w + 1, y0 + h + 1, Ui.BODY_DARK);
        g.fill(x0, y0, x0 + w, y0 + h, sys ? Ui.SYSTEM_BODY : Ui.BODY);
        g.fill(x0, y0, x0 + w, y0 + 1, Ui.BODY_LIGHT);
        g.fill(x0, y0, x0 + 1, y0 + h, Ui.BODY_LIGHT);
        g.fill(sx - 1, sy - 1, sx + sw + 1, sy + sh + 1, 0xFF000000);
        g.fill(sx, sy, sx + sw, sy + sh, Ui.SCREEN);
        g.fill(x0 + w / 2 - 12, y0 + h - 5, x0 + w / 2 + 12, y0 + h - 3, sys ? 0xFF7A2A2A : Ui.ACCENT_DARK);
        }

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
            if (!retro) g.fill(sx + 22, sy + 12, sx + 23, sy + sh, Ui.SEP);
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
                case LOOK -> renderLook(g, mx, my);
                case NOTES -> renderNotes(g, mx, my);
                case NOTE -> renderNote(g);
                case NOTE_SHARE -> renderNoteShare(g, mx, my);
                case APP -> {
                    if (app != null) app.render(g, mx, my, pt);
                }
                default -> {}
            }
            if (prompt != Prompt.NONE) g.fill(cx, cy + 19, cx + cw, cy + 39, Ui.PANEL);
        }
        if (retro) for (EditBox b : framedBoxes()) g.renderOutline(b.getX() - 3, b.getY() - 3, b.getWidth() + 6, b.getHeight() + 5, Ui.DIM);
        super.render(g, mx, my, pt);
        if (retro && toast != null && System.currentTimeMillis() < toastUntil) {
            String t = Ui.up(toast.getString());
            int tw = font.width(t) + 6;
            int tx = sx + sw / 2 - tw / 2, ty = sy + sh - 14;
            g.pose().pushPose();
            g.pose().translate(0, 0, 250);
            g.fill(tx, ty, tx + tw, ty + 11, Ui.TOAST_BG);
            g.drawString(font, t, tx + 3, ty + 2, Ui.SCREEN, false);
            g.pose().popPose();
        } else if (toast != null && System.currentTimeMillis() < toastUntil) {
            int tw = tw(toast) + 10;
            int tx = sx + sw / 2 - tw / 2, ty = sy + sh - 34;
            g.pose().pushPose();
            g.pose().translate(0, 0, 400);
            g.fill(tx, ty, tx + tw, ty + 14, Ui.TOAST_BG);
            g.fill(tx, ty + 13, tx + tw, ty + 14, toastColor);
            txt(g, toast, tx + 5, ty + 3, Ui.TEXT, false);
            g.pose().popPose();
        }
        if (retro && Ui.crt) crt(g);
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
        txt(g, c, sx + sw / 2 - tw(c) / 2, y, color, false);
    }

    private void statusBar(GuiGraphics g) {
        if (page == Page.LOOK && lookTarget == -1) {
            g.fill(sx, sy, sx + sw, sy + (retro ? 11 : 12), retro ? Ui.BAR : Ui.BAR);
            String t = Ui.up(Component.translatable("rppda.screen.new_device").getString());
            g.drawString(font, t, sx + 3, sy + 2, retro ? Ui.SCREEN : Ui.TEXT, false);
            return;
        }
        if (retro) {
            // Инверсная строка, как на терминале.
            g.fill(sx, sy, sx + sw, sy + 11, Ui.BAR);
            String clock = Ui.clock(System.currentTimeMillis());
            g.drawString(font, clock, sx + sw - font.width(clock) - 3, sy + 2, Ui.SCREEN, false);
            String left = system ? Ui.up(Component.translatable("rppda.screen.system").getString())
                    + (meId != 0 ? "  → " + Ui.up(meName) + " " + Ui.formatId(meId) : "")
                    : Ui.up(meName) + "  ID " + Ui.formatId(meId);
            g.drawString(font, Ui.fit(font, left, sw - 50), sx + 3, sy + 2, Ui.SCREEN, false);
            int rx = sx + sw - font.width(clock) - 6;
            if (silent && !system) Ui.icon(g, Icon.SILENT, (rx -= 13), sy - 1, 12, Ui.SCREEN);
            if (character) Ui.icon(g, Icon.MASK, (rx -= 13), sy - 1, 12, Ui.SCREEN);
            if (!signalOk && !system && (System.currentTimeMillis() / 600) % 2 == 0) {
                String ns = Ui.up(Component.translatable("rppda.screen.no_signal").getString());
                g.drawString(font, ns, rx - font.width(ns) - 2, sy + 2, Ui.SCREEN, false);
            }
            return;
        }
        g.fill(sx, sy, sx + sw, sy + 12, Ui.BAR);
        String clock = Ui.clock(System.currentTimeMillis());
        txt(g, clock, sx + sw - tw(clock) - 4, sy + 2, Ui.DIM, false);
        int x = sx + 4;
        if (system) {
            Component s = Component.translatable("rppda.screen.system");
            txt(g, s, x, sy + 2, Ui.RED, false);
            x += tw(s) + 6;
            if (meId != 0) {
                String who = "→ " + meName + "  " + Ui.formatId(meId);
                txt(g, who, x, sy + 2, Ui.GOLD, false);
            }
        } else {
            txt(g, meName, x, sy + 2, Ui.TEXT, false);
            txt(g, "ID " + Ui.formatId(meId), x + tw(meName) + 6, sy + 2, Ui.DIM, false);
        }
        int rx = sx + sw - tw(clock) - 6;
        if (silent && !system) Ui.icon(g, Icon.SILENT, (rx -= 12), sy + 2, 9, Ui.DIM);
        if (character) Ui.icon(g, Icon.MASK, (rx -= 12), sy + 2, 9, Ui.GOLD);
        if (!signalOk && !system) {
            Ui.icon(g, Icon.NO_SIGNAL, (rx -= 12), sy + 2, 9, Ui.RED);
            String ns = Component.translatable("rppda.screen.no_signal").getString();
            g.drawString(font, ns, rx - font.width(ns) - 2, sy + 2, Ui.RED, false);
        }
    }

    private void title(GuiGraphics g, Component c, int xOff) {
        txt(g, c, cx + xOff, cy + 5, Ui.TEXT, false);
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
            if (hover) {
                if (retro) g.renderOutline(cx, y, cw, rowH, Ui.TEXT);
                else g.fill(cx, y, cx + cw, y + rowH, Ui.ROW_HOVER);
            }
            draw.draw(g, it, y, hover);
            if (click != null && hover) {
                int yy = y;
                hots.add(new Hot(cx, Math.max(top, y), cw, Math.min(bottom, y + rowH) - Math.max(top, y), keyPrefix + i, null,
                        () -> click.click(it, yy)));
            }
        }
        g.disableScissor();
        if (items.isEmpty()) txt(g, Component.translatable("rppda.screen.empty"), cx + 6, top + 6, Ui.DIM, false);
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
        g.fill(cx + 4, y + 4, cx + 20, y + 20, Ui.TILE_INVITE);
        Ui.icon(g, r.getBoolean("channel") ? Icon.CHANNEL : Icon.GROUP, cx + 6, y + 6, 12, Ui.GOLD);
        txt(g, fit(r.getString("title"), cw - 80), cx + 24, y + 3, Ui.TEXT, false);
        txt(g, fit(Component.translatable("rppda.screen.invite_from", r.getString("from")).getString(), cw - 80),
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
                g.fill(x, y, x + size, y + size, Ui.TILE_GLOBE);
                Ui.icon(g, Icon.GLOBE, x + 1, y + 1, size - 2, onTile());
            }
            case "GROUP", "CHANNEL" -> {
                g.fill(x, y, x + size, y + size, Ui.TILE_GROUP);
                Ui.icon(g, type.equals("GROUP") ? Icon.GROUP : Icon.CHANNEL, x + 1, y + 1, size - 2, onTile());
            }
            default -> {
                if (r.contains("peer"))
                    Ui.avatar(g, x, y, size, r.hasUUID("owner") ? r.getUUID("owner") : null, r.getString("title"), r.getInt("peer"));
                else {
                    g.fill(x, y, x + size, y + size, Ui.TILE_GROUP);
                    Ui.icon(g, Icon.MESSAGE, x + 1, y + 1, size - 2, onTile());
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
        int tw = tw(time);
        txt(g, fit(chatTitle(r).getString(), cw - 34 - tw), cx + 24, y + 3, unread > 0 ? onTile() : Ui.TEXT, false);
        txt(g, time, cx + cw - tw - 4, y + 3, Ui.DIM, false);
        String last = r.getString("last");
        String from = r.getString("lastFrom");
        String preview = last.isEmpty() ? (r.getLong("time") > 0 ? "…" : "") : (from.isEmpty() ? last : from + ": " + last);
        int right = 6;
        String mode = r.getString("notify");
        if (mode.equals("NONE") || mode.equals("SILENT")) {
            Ui.icon(g, mode.equals("NONE") ? Icon.MUTE : Icon.SILENT, cx + cw - 14, y + 13, 9, Ui.DIM);
            right += 12;
        }
        if (unread > 0) {
            String n = unread > 99 ? "99+" : Integer.toString(unread);
            int bw = tw(n) + 6;
            g.fill(cx + cw - right - bw + 2, y + 12, cx + cw - right + 2, y + 22, mode.equals("NONE") ? Ui.DIM : Ui.ACCENT);
            g.drawString(font, n, cx + cw - right - bw + 5, y + 13, retro ? Ui.SCREEN : 0xFFFFFFFF, false);
            right += bw + 2;
        }
        txt(g, fit(preview, cw - 28 - right), cx + 24, y + 13, Ui.DIM, false);
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
        if (retro) {
            layoutRetro();
            return;
        }
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
                    : (fl & 8) != 0 ? Component.literal(noteCard(m.getString("x"))).withStyle(ChatFormatting.GOLD)
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
            for (FormattedCharSequence l : lines) textW = Math.max(textW, tw(l));
            String header = header(m, own, group);
            int headW = tw(header) + 8 + tw(Ui.clock(m.getLong("t")));
            int bw = Math.min(maxBubble(), Math.max(Math.max(textW, headW), quote == null ? 0 : Math.min(inner, tw(quote.get(0)) + 6)) + 10);
            int hgt = 4 + 9 + (quote != null ? 11 : 0) + lines.size() * 9 + 3;
            out.add(new Block(m, lines, hgt + 3 + (date != null ? 14 : 0), own, false, date, bw, quote));
        }
        blocks = out;
    }

    /** Заголовок пузыря: имя (у своих — пусто), у ГМа — пометка. */
    private String header(CompoundTag m, boolean own, boolean group) {
        String s = own ? "" : m.getString("n");
        if (m.contains("g")) s = (s.isEmpty() ? "" : s + " ") + Component.translatable("rppda.chat.gm", m.getString("g")).getString();
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
        txt(g, fit(chatTitle(chatRow).getString(), cw - 44 - toolW), cx + 42, cy + 1, Ui.TEXT, false);
        String type = chatRow.getString("type");
        Component sub = switch (type) {
            case "GROUP" -> Component.translatable("rppda.chat.members_n", chatRow.getInt("count"));
            case "CHANNEL" -> Component.translatable("rppda.chat.channel_n", chatRow.getInt("count"));
            case "GLOBAL" -> Component.translatable("rppda.chat.global_sub");
            default -> chatRow.contains("peer") ? Component.literal("ID " + Ui.formatId(chatRow.getInt("peer"))) : Component.empty();
        };
        txt(g, sub, cx + 42, cy + 10, Ui.DIM, false);
        g.fill(cx, cy + 19, cx + cw, cy + 20, Ui.SEP);

        boolean canWrite = canWrite();
        int top = cy + 21;
        int bottom = cy + ch - (canWrite ? 19 : 12) - (replySeq > 0 ? 14 : 0);
        if (!canWrite) {
            Component ro = Component.translatable(!signalOk && chatRow.getBoolean("write") ? "rppda.chat.no_signal"
                    : chatRow.getBoolean("member") ? "rppda.chat.read_only_channel" : "rppda.chat.read_only");
            txt(g, ro, cx + cw / 2 - tw(ro) / 2, cy + ch - 10, Ui.DIM, false);
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
            txt(g, l, cx + cw / 2 - tw(l) / 2, Math.max(top + 2, y - 12), Ui.DIM, false);
        }
        boolean group = !type.equals("DM");
        for (Block b : blocks) {
            int by = y;
            y += b.height;
            if (y < top || by > bottom) continue;
            if (retro) {
                retroBlock(g, b, by, mx, my, top, bottom, canWrite);
                continue;
            }
            if (b.date != null) {
                String d = b.date;
                int dw = tw(d) + 8;
                g.fill(cx + cw / 2 - dw / 2, by + 1, cx + cw / 2 + dw / 2, by + 11, Ui.DATE_BG);
                txt(g, d, cx + cw / 2 - dw / 2 + 4, by + 2, Ui.DIM, false);
                by += 14;
            }
            if (b.notice) {
                int ly = by + 2;
                for (FormattedCharSequence l : b.lines) {
                    txt(g, l, cx + cw / 2 - tw(l) / 2, ly, Ui.DIM, false);
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
            if (!head.isEmpty()) txt(g, fit(head, bw - 40), bx + 5, by + 3,
                    m.contains("g") ? Ui.GOLD : Ui.ACCENT, false);
            String clock = Ui.clock(m.getLong("t"));
            txt(g, clock, bx + bw - tw(clock) - 4, by + 3, Ui.TIME, false);
            int ty = by + 13;
            if (b.quote != null) {
                g.fill(bx + 5, ty, bx + 6, ty + 9, Ui.ACCENT);
                txt(g, b.quote.get(0), bx + 9, ty + 1, Ui.QUOTE, false);
                ty += 11;
            }
            for (FormattedCharSequence l : b.lines) {
                txt(g, l, bx + 5, ty, deleted ? Ui.DIM : Ui.TEXT, false);
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
                    ix += step;
                }
                if ((m.getInt("fl") & 8) != 0 && !b.own) acceptNoteIcon(g, ix, by + 1, m);
            }
        }
        g.disableScissor();
        if (retro && canWrite) {
            int iy = cy + ch - 16;
            for (int x = cx; x < cx + cw; x += 2) g.fill(x, iy - 1, x + 1, iy, Ui.DIM);
            txt(g, ">", cx + 3, iy + 5, Ui.TEXT, false);
        }
        if (replySeq > 0 && canWrite) {
            int ry = cy + ch - 32;
            g.fill(cx, ry, cx + cw, ry + 13, Ui.PANEL);
            g.fill(cx, ry, cx + 2, ry + 13, Ui.ACCENT);
            Ui.icon(g, Icon.REPLY, cx + 4, ry + 1, 11, Ui.ACCENT);
            txt(g, fit(replyName + ": " + replyText, cw - 36), cx + 18, ry + 3, Ui.DIM, false);
        }
        if (scroll > 0) {
            int ax = cx + cw - 16, ay = bottom - 16;
            g.fill(ax, ay, ax + 14, ay + 14, retro ? Ui.SCREEN : 0xCC24557F);
            if (retro) g.renderOutline(ax, ay, 14, 14, Ui.TEXT);
            Ui.icon(g, Icon.BACK, ax + 1, ay + 1, 12, onTile());
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
            txt(gg, fit(label.isEmpty() ? name : label, cw - 100), cx + 24, y + 2, blocked ? Ui.DIM : Ui.TEXT, false);
            String sub = (label.isEmpty() ? "" : name + " · ") + Ui.formatId(id) + (blocked ? " · " + Component.translatable("rppda.screen.blocked").getString() : "");
            txt(gg, fit(sub, cw - 100), cx + 24, y + 12, blocked ? Ui.RED : Ui.DIM, false);
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
        txt(g, fit(chatRow.getString("title"), cw - 90), cx + 22, cy + 5, Ui.TEXT, false);
        if (members == null) {
            txt(g, Component.translatable("rppda.screen.loading"), cx + 6, listTop() + 6, Ui.DIM, false);
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
            txt(gg, fit(c.getString("name"), cw - 110), nx, y + 2, invited ? Ui.DIM : Ui.TEXT, false);
            txt(gg, Ui.formatId(id) + (invited ? " · " + Component.translatable("rppda.screen.invited").getString() : ""),
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
        txt(g, meName, cx + 44, cy + 18, Ui.TEXT, false);
        txt(g, "ID " + Ui.formatId(meId), cx + 44, cy + 30, Ui.ACCENT, false);
        Component kind = Component.translatable(meKind.equals("NPC") ? "rppda.kind.npc" : "rppda.kind.player");
        txt(g, kind, cx + 44, cy + 41, Ui.DIM, false);
        int by = cy + (retro ? 54 : 64), st = retro ? 22 : 26;
        Component pw = Component.translatable(hasPw ? "rppda.profile.pw_on" : "rppda.profile.pw_off");
        txt(g, pw, cx + 6 + (hasPw ? 62 : 22), by + 5, hasPw ? Ui.GREEN : Ui.DIM, false);
        Component snd = Component.translatable(silent ? "rppda.profile.silent" : "rppda.profile.sound");
        txt(g, snd, cx + 28, by + st + 5, Ui.DIM, false);
        List<FormattedCharSequence> hint = font.split(Component.literal(Ui.up(Component.translatable("rppda.profile.hint").getString())), cw - 12);
        int hy = by + 2 * st + 4;
        for (FormattedCharSequence l : hint) {
            if (hy + 9 > cy + ch) break;
            txt(g, l, cx + 6, hy, Ui.HINT, false);
            hy += 9;
        }
    }

    // ---------------------------------------------------------------- системный КПК

    private void renderSysAccounts(GuiGraphics g, int mx, int my) {
        list(g, mx, my, sysAccounts, 22, "sacc", (gg, a, y, hover) -> {
            int id = a.getInt("id");
            String name = a.getString("name");
            Ui.avatar(gg, cx + 4, y + 3, 16, a.hasUUID("owner") ? a.getUUID("owner") : null, name, id);
            txt(gg, fit(name, cw - 60), cx + 24, y + 2, Ui.TEXT, false);
            txt(gg, fit(sysSub(a), cw - 40), cx + 24, y + 12, Ui.DIM, false);
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
        txt(g, name, cx + 44, cy + 22, Ui.TEXT, false);
        txt(g, sysSub(sysAcc), cx + 44, cy + 33, Ui.DIM, false);
        List<String> flags = new ArrayList<>();
        if (sysAcc.getBoolean("online")) flags.add(Component.translatable("rppda.sys.online").getString());
        if (sysAcc.getBoolean("pw")) flags.add(Component.translatable("rppda.sys.pw").getString());
        if (sysAcc.getBoolean("locked")) flags.add(Component.translatable("rppda.sys.locked").getString());
        if (sysAcc.contains("signal") && !sysAcc.getBoolean("signal")) flags.add(Component.translatable("rppda.sys.no_signal").getString());
        if (sysAcc.contains("ownerSignal") && !sysAcc.getBoolean("ownerSignal")) flags.add(Component.translatable("rppda.sys.owner_no_signal").getString());
        if (id != 0) flags.add(Component.translatable("rppda.sys.issue", sysAcc.getInt("issue")).getString());
        txt(g, fit(String.join(" · ", flags), cw - 50), cx + 44, cy + 44, Ui.GOLD, false);
        if (sysAcc.getLong("created") > 0)
            txt(g, Component.translatable("rppda.sys.created", Ui.day(sysAcc.getLong("created"))), cx + 44, cy + 55, Ui.DIM, false);
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
            txt(gg, fit(line, cw - 8), cx + 4, y + 1, Ui.DIM, false);
        }, null);
    }

    // ================================================================ текст: в ретро — заглавными и со свечением

    /** Текст: строка, компонент или готовая строка разбивки. В ретро — заглавными, с лёгким свечением люминофора. */
    private void txt(GuiGraphics g, Object s, int x, int y, int color, boolean unused) {
        FormattedCharSequence seq = s instanceof FormattedCharSequence f ? f : null;
        String str = seq != null ? null : Ui.up(s instanceof Component c ? c.getString() : String.valueOf(s));
        if (retro && Ui.crt && color != Ui.SCREEN) {
            int halo = (color & 0xFFFFFF) | 0x26000000;
            float[][] offs = {{0.5f, 0}, {-0.5f, 0}, {0, 0.5f}, {0, -0.5f}};
            for (float[] o : offs) {
                g.pose().pushPose();
                g.pose().translate(o[0], o[1], 0);
                if (seq != null) g.drawString(font, seq, x, y, halo, false);
                else g.drawString(font, str, x, y, halo, false);
                g.pose().popPose();
            }
        }
        if (seq != null) g.drawString(font, seq, x, y, color, false);
        else g.drawString(font, str, x, y, color, false);
    }

    private int tw(Object s) {
        if (s instanceof FormattedCharSequence f) return font.width(f);
        return font.width(Ui.up(s instanceof Component c ? c.getString() : String.valueOf(s)));
    }

    private String fit(String s, int w) {
        return Ui.fit(font, Ui.up(s), w);
    }

    private Component hint(String key) {
        return Component.literal(Ui.up(Component.translatable(key).getString()))
                .withStyle(Style.EMPTY.withColor(retro ? Ui.DIM & 0xFFFFFF : 0x555555));
    }

    /** Цвет значка/текста на плитке (в ретро плиток нет — люминофор). */
    private int onTile() {
        return retro ? Ui.TEXT : 0xFFFFFFFF;
    }

    /** Ретро: поля ввода без рамки Minecraft — цвет люминофора, рамка рисуется своя. */
    private void box(EditBox b) {
        if (!retro) return;
        b.setBordered(false);
        b.setTextColor(Ui.TEXT);
        b.setTextColorUneditable(Ui.DIM);
    }

    private List<EditBox> framedBoxes() {
        List<EditBox> out = new ArrayList<>();
        for (EditBox b : new EditBox[]{password, search, promptA, promptB, modelBox, noteTitleBox, npcBox}) if (b != null) out.add(b);
        return out;
    }

    // ================================================================ ретро: корпус, клавиши, кинескоп

    /** Клавиша корпуса: значок (или раздел аддона, или подпись «<</>>» для листания). */
    private record Key(@Nullable Icon icon, Component tip, boolean enabled, Runnable run, @Nullable faygolover.rppda.api.PdaApps.App app,
                       @Nullable String label) {
        Key(Icon icon, String tip, boolean enabled, Runnable run) {
            this(icon, Component.translatable(tip), enabled, run, null, null);
        }
    }

    private static final int KEY_H = 20;

    /** Клавиши корпуса слева направо (F3, F4…). F1 и F2 не трогаем — это скрытие интерфейса и снимок экрана. */
    private List<Key> keys() {
        boolean ready = page != Page.WAIT && page != Page.REGISTER && page != Page.LOCKED && !(page == Page.LOOK && lookTarget == -1);
        List<Key> secs = new ArrayList<>();
        for (Section sec : sections()) secs.add(new Key(sec.icon, sec.title, ready, sec.run, sec.app, null));
        List<Key> ctl = new ArrayList<>();
        if (system) {
            ctl.add(new Key(Icon.SYSTEM, "rppda.btn.back_system", ready && meId != 0, () -> send("sys.switch", t -> t.putInt("id", 0))));
        } else {
            ctl.add(new Key(Icon.BACK, "rppda.btn.back", (ready || page == Page.LOOK) && canGoBack(), this::goBack));
            ctl.add(new Key(Icon.LOCK, "rppda.btn.lock", ready && hasPw, () -> ClientPda.send("lock", new CompoundTag())));
        }
        ctl.add(new Key(Icon.CLOSE, "rppda.btn.close", true, this::onClose));
        // Помещаются — все; нет — разделы листаются клавишами «<<» и «>>», управление остаётся справа.
        int fit = Math.max(4, (sw + 6) / 36);
        List<Key> out = new ArrayList<>();
        if (secs.size() + ctl.size() <= fit) {
            out.addAll(secs);
        } else {
            int per = Math.max(1, fit - ctl.size() - 2);
            int pages = (secs.size() + per - 1) / per;
            keyPage = Math.max(0, Math.min(keyPage, pages - 1));
            out.add(new Key(null, Component.translatable("rppda.btn.prev"), keyPage > 0, () -> keyPage--, null, "<<"));
            out.addAll(secs.subList(keyPage * per, Math.min(secs.size(), keyPage * per + per)));
            out.add(new Key(null, Component.translatable("rppda.btn.next"), keyPage < pages - 1, () -> keyPage++, null, ">>"));
        }
        out.addAll(ctl);
        return out;
    }

    private boolean canGoBack() {
        return prompt != Prompt.NONE || page == Page.CHAT || page == Page.MEMBERS || page == Page.SYS_ACCOUNT || page == Page.LOOK
                || page == Page.NOTE || page == Page.NOTE_SHARE;
    }

    private void goBack() {
        if (prompt != Prompt.NONE) {
            prompt = Prompt.NONE;
            rebuild();
            return;
        }
        switch (page) {
            case CHAT -> {
                ClientPda.send("state", new CompoundTag());
                setPage(chatReturn);
            }
            case MEMBERS -> setPage(Page.CHAT);
            case SYS_ACCOUNT -> setPage(Page.SYS_ACCOUNTS);
            case LOOK -> cancelLook();
            case NOTE -> setPage(Page.NOTES);
            case NOTE_SHARE -> setPage(Page.NOTE);
            default -> {}
        }
    }

    private int keyW(int n) {
        return Math.min(44, (sw - (n - 1) * 6) / n);
    }

    private int keyX(int i, int n) {
        int kw = keyW(n);
        int gap = n > 1 ? (sw - n * kw) / (n - 1) : 0;
        return sx + i * (kw + gap);
    }

    private int keyY() {
        return sy + sh + 10;
    }

    /** Нажать клавишу корпуса (мышью — держится, пока не отпустят). */
    void pressKey(int i, boolean mouse) {
        List<Key> ks = keys();
        if (i < 0 || i >= ks.size()) return;
        if (heldKey == i && !mouse) return; // автоповтор клавиатуры
        heldKey = i;
        heldByMouse = mouse;
        Key k = ks.get(i);
        if (!k.enabled) return;
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.6f, 0.25f));
        k.run.run();
    }

    /** F3…F9 при открытом ретро-КПК — его клавиши (зовётся до остальных модов). Возвращает, забрали ли нажатие. */
    boolean functionKey(int key, boolean down) {
        if (!retro) return false;
        int i = key - GLFW.GLFW_KEY_F3;
        if (i < 0 || i >= keys().size()) return false;
        if (down) pressKey(i, false);
        else if (heldKey == i && !heldByMouse) heldKey = -1;
        return true;
    }

    /** Для снимков: открыть заметку. */
    void openNoteForShot(CompoundTag n) {
        openNote(n);
    }

    /** Для снимков: открыть «Внешний вид» нового КПК. */
    void openLookForShot() {
        openLook(-1, Page.REGISTER);
    }

    /** Для снимков: показать клавишу нажатой без действия. */
    void holdKeyForShot(int i) {
        heldKey = i;
        heldByMouse = true;
    }

    private void retroBody(GuiGraphics g, int mx, int my) {
        boolean sysSkin = systemSkin();
        faygolover.rppda.data.Look shown = shownLook();
        Skins.Skin skin = Skins.get(shown.body);
        if (!sysSkin && skin.textured()) {
            // Корпус с рисунком — текстура целиком (углы в ней уже срезаны).
            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            g.blit(skin.texture(), x0, y0, w, h, 0, 0, 360, 254, 360, 254);
            g.fill(x0 + 3, y0, x0 + w - 3, y0 + 1, 0x46FFFFFF);
            g.fill(x0, y0 + 3, x0 + 1, y0 + h - 3, 0x46FFFFFF);
            g.fill(x0 + w - 1, y0 + 3, x0 + w, y0 + h - 3, 0x5A000000);
            g.fill(x0 + 3, y0 + h - 1, x0 + w - 3, y0 + h, 0x5A000000);
        } else {
            int body = sysSkin ? Ui.BODY : skin.color();
            int light = sysSkin ? Ui.BODY_LIGHT : shade(body, 1.18f), dark = sysSkin ? Ui.BODY_DARK : shade(body, 0.68f);
            // Корпус со срезанными углами.
            g.fill(x0 + 3, y0, x0 + w - 3, y0 + h, body);
            g.fill(x0, y0 + 3, x0 + w, y0 + h - 3, body);
            g.fill(x0 + 1, y0 + 1, x0 + w - 1, y0 + h - 1, body);
            g.fill(x0 + 3, y0, x0 + w - 3, y0 + 1, light);
            g.fill(x0, y0 + 3, x0 + 1, y0 + h - 3, light);
            g.fill(x0 + w - 1, y0 + 3, x0 + w, y0 + h - 3, dark);
            g.fill(x0 + 3, y0 + h - 1, x0 + w - 3, y0 + h, dark);
        }
        int label = sysSkin ? 0xFF3C1412 : skin.label();
        Component model = sysSkin ? Component.translatable("rppda.body.system")
                : shown.model.isEmpty() ? Component.translatable("rppda.model." + skin.id()) : Component.literal(shown.model);
        g.drawString(font, model, x0 + (sysSkin ? 12 : skin.labelX() * w / 360), y0 + 3, label, false);
        // Решётка динамика и индикатор (мигает, когда есть непрочитанное).
        for (int i = 0; i < 6; i++) g.fill(x0 + w - 70 + i * 6, y0 + 6, x0 + w - 67 + i * 6, y0 + 12, 0x66000000);
        boolean blink = !system && ClientPda.unread > 0 && (System.currentTimeMillis() / 500) % 2 == 0;
        int led = sysSkin ? 0xFFFF3C28 : !signalOk ? 0xFF5A1E1E : blink ? 0xFF1E5A1E : 0xFF5AFF5A;
        g.fill(x0 + w - 23, y0 + 5, x0 + w - 16, y0 + 12, 0xFF201E1C);
        g.fill(x0 + w - 22, y0 + 6, x0 + w - 17, y0 + 11, led);
        // Рамка экрана.
        g.fill(sx - 7, sy - 6, sx + sw + 7, sy + sh + 6, sysSkin ? 0xFF2A2724 : skin.bezel());
        g.fill(sx - 7, sy - 6, sx + sw + 7, sy - 5, 0xFF181614);
        g.fill(sx - 1, sy - 1, sx + sw + 1, sy + sh + 1, 0xFF080A08);
        g.fill(sx, sy, sx + sw, sy + sh, Ui.SCREEN);
        // Клавиши.
        List<Key> ks = keys();
        int n = ks.size(), kw = keyW(n), ky = keyY();
        boolean darkBody = !sysSkin && skin.dark();
        int face = sysSkin ? 0xFF3C1816 : darkBody ? 0xFF5E5A54 : 0xFF46423C;
        int top = sysSkin ? 0xFF64302C : darkBody ? 0xFF8A867E : 0xFF6E6A62;
        int shadow = sysSkin ? 0xFF241010 : darkBody ? 0xFF161412 : 0xFF2A2724;
        for (int i = 0; i < n; i++) {
            Key k = ks.get(i);
            int kx = keyX(i, n);
            boolean down = heldKey == i;
            boolean hover = mx >= kx && mx < kx + kw && my >= ky && my < ky + KEY_H + 2;
            int y = down ? ky + 2 : ky;
            if (!down) g.fill(kx, ky + 2, kx + kw, ky + KEY_H + 2, shadow);
            g.fill(kx, y, kx + kw, y + KEY_H, down ? ((face & 0xFEFEFE) >>> 1) | 0xFF000000 : hover && k.enabled ? top : face);
            if (!down) g.fill(kx, y, kx + kw, y + 1, top);
            int ic = k.enabled ? 0xFFE2DCCE : 0xFF7E786E;
            if (k.app != null) k.app.icon(g, kx + kw / 2 - 8, y + KEY_H / 2 - 8, ic);
            else if (k.label != null) g.drawString(font, k.label, kx + kw / 2 - font.width(k.label) / 2, y + KEY_H / 2 - 4, ic, false);
            else Ui.icon(g, k.icon, kx + kw / 2 - 8, y + KEY_H / 2 - 8, ic);
            String f = "F" + (3 + i);
            g.drawString(font, f, kx + 2, ky + KEY_H + 5, label, false);
            hots.add(new Hot(kx, ky, kw, KEY_H + 2, "key" + i + k.tip.getString(), k.tip, () -> {}));
        }
    }

    private static int shade(int c, float k) {
        int r = Math.min(255, (int) (((c >> 16) & 255) * k));
        int g = Math.min(255, (int) (((c >> 8) & 255) * k));
        int b = Math.min(255, (int) ((c & 255) * k));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private boolean canWrite() {
        if (chatRow == null || !chatRow.getBoolean("write")) return false;
        // Без связи — только диалог с Системой.
        return signalOk || system || chatRow.getString("type").equals("DM") && chatRow.contains("peer") && chatRow.getInt("peer") == 0;
    }

    // ================================================================ «Внешний вид» (ГМ)

    private void openLook(int target, Page from) {
        lookTarget = target;
        lookReturn = from;
        editLook = target == -1 ? look.copy() : sysAcc == null ? new faygolover.rppda.data.Look()
                : faygolover.rppda.data.Look.load(sysAcc.getCompound("look"));
        page = Page.LOOK;
        prompt = Prompt.NONE;
        rebuildWidgets();
    }

    private void cancelLook() {
        editLook = null;
        page = lookReturn;
        rebuildWidgets();
    }

    private void applyLook() {
        if (editLook == null) return;
        CompoundTag l = editLook.save();
        if (lookTarget == -1) send("look.hand", t -> t.put("look", l));
        else send("sys.look", t -> {
            t.putInt("id", lookTarget);
            t.put("look", l);
        });
        if (lookTarget != -1 && sysAcc != null) sysAcc.put("look", l);
        editLook = null;
        page = lookReturn;
        rebuildWidgets();
    }

    /** Поменять правку и сразу показать на корпусе/экране. */
    private void editLook(java.util.function.Consumer<faygolover.rppda.data.Look> change) {
        if (editLook == null) return;
        change.accept(editLook);
        rebuildWidgets();
    }

    private void lookWidgets() {
        toolbarButton(0, Icon.CLOSE, "rppda.btn.cancel", this::cancelLook);
        toolbarButton(1, Icon.CHECK, "rppda.btn.ok", this::applyLook);
        if (editLook == null) return;
        modelBox = new EditBox(font, cx + 64, cy + 98, 150, retro ? 10 : 14, Component.translatable("rppda.look.model"));
        modelBox.setMaxLength(faygolover.rppda.data.Look.MODEL_MAX);
        modelBox.setHint(Component.literal(Ui.up(Component.translatable("rppda.model." + editLook.body).getString()))
                .withStyle(Style.EMPTY.withColor(Ui.DIM & 0xFFFFFF)));
        modelBox.setValue(editLook.model);
        box(modelBox);
        modelBox.setResponder(v -> editLook.model = v.strip());
        addRenderableWidget(modelBox);
    }

    /** Переключатель: выбранный — инверсией/акцентом. */
    private int chip(GuiGraphics g, int x, int y, String label, boolean on, String key, Runnable run) {
        String l = Ui.up(label);
        int wdt = font.width(l) + 6;
        if (on) {
            g.fill(x, y - 2, x + wdt, y + 9, retro ? Ui.TEXT : Ui.ACCENT);
            g.drawString(font, l, x + 3, y, retro ? Ui.SCREEN : 0xFFFFFFFF, false);
        } else {
            g.renderOutline(x, y - 2, wdt, 11, Ui.DIM);
            txt(g, l, x + 3, y, Ui.TEXT, false);
        }
        hots.add(new Hot(x, y - 2, wdt, 11, key, null, run));
        return x + wdt + 4;
    }

    private void renderLook(GuiGraphics g, int mx, int my) {
        if (editLook == null) return;
        faygolover.rppda.data.Look l = editLook;
        String who = lookTarget == -1 ? Component.translatable("rppda.screen.new_device").getString()
                : sysAcc == null ? "" : sysAcc.getString("name");
        title(g, Component.literal(Component.translatable("rppda.page.look").getString() + " · " + who), 4);
        int lx = cx + 4, vx = cx + 64;
        // Вид окна.
        int y = cy + 24;
        txt(g, Component.translatable("rppda.look.style"), lx, y, Ui.DIM, false);
        int x = vx;
        String[] styles = {"", "retro", "modern"};
        for (String st : styles) {
            String key = "rppda.look.style." + (st.isEmpty() ? "default" : st);
            x = chip(g, x, y, Component.translatable(key).getString(), l.style.equals(st), "st" + st, () -> editLook(e -> e.style = st));
        }
        // Корпус.
        y = cy + 42;
        txt(g, Component.translatable("rppda.look.body"), lx, y, Ui.DIM, false);
        txt(g, Component.translatable("rppda.skin." + l.body), vx, y, Ui.TEXT, false);
        int sy0 = y + 11;
        List<String> bodies = faygolover.rppda.data.Look.BODIES;
        int per = Math.max(1, (cw - 8 - 24) / 16);
        int pages = (bodies.size() + per - 1) / per;
        swatchPage = Math.max(0, Math.min(swatchPage, pages - 1));
        if (pages > 1) {
            int sp0 = swatchPage;
            int ax = lx + per * 16 + 2;
            txt(g, "<", ax, sy0 + 1, sp0 > 0 ? Ui.TEXT : Ui.DIM, false);
            txt(g, ">", ax + 12, sy0 + 1, sp0 < pages - 1 ? Ui.TEXT : Ui.DIM, false);
            hots.add(new Hot(ax - 2, sy0 - 1, 10, 12, "swp", Component.translatable("rppda.btn.prev"), () -> swatchPage = Math.max(0, sp0 - 1)));
            hots.add(new Hot(ax + 10, sy0 - 1, 10, 12, "swn", Component.translatable("rppda.btn.next"), () -> swatchPage = sp0 + 1));
        }
        for (int i = swatchPage * per; i < bodies.size() && i < swatchPage * per + per; i++) {
            String b = bodies.get(i);
            int bx = lx + (i - swatchPage * per) * 16;
            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            Skins.Skin sk = Skins.get(b);
            if (i < 24) g.blit(Skins.SWATCHES, bx, sy0, 14, 10, (i % 8) * 28, (i / 8) * 20, 28, 20, 224, 60);
            else if (sk.textured()) g.blit(sk.texture(), bx, sy0, 14, 10, 0, 0, 360, 254, 360, 254);
            else g.fill(bx, sy0, bx + 14, sy0 + 10, sk.color());
            if (b.equals(l.body)) g.renderOutline(bx - 1, sy0 - 1, 16, 12, Ui.TEXT);
            hots.add(new Hot(bx - 1, sy0 - 1, 16, 12, "body" + b, Component.translatable("rppda.skin." + b), () -> editLook(e -> e.body = b)));
        }
        // Экран (ретро) и акцент (современный).
        y = cy + 72;
        txt(g, Component.translatable("rppda.look.screen"), lx, y, Ui.DIM, false);
        x = vx;
        for (String sc : faygolover.rppda.data.Look.SCREENS) {
            int[] c = Ui.screenColors(sc);
            g.fill(x, y - 2, x + 20, y + 9, c[0]);
            g.fill(x + 3, y + 1, x + 17, y + 3, c[1]);
            g.fill(x + 3, y + 5, x + 12, y + 6, c[3]);
            g.renderOutline(x - 1, y - 3, 22, 13, sc.equals(l.screen) ? Ui.TEXT : Ui.DIM);
            hots.add(new Hot(x - 1, y - 3, 22, 13, "scr" + sc, Component.translatable("rppda.screen." + sc), () -> editLook(e -> e.screen = sc)));
            x += 25;
        }
        x += 8;
        txt(g, Component.translatable("rppda.look.accent"), x, y, Ui.DIM, false);
        x += tw(Component.translatable("rppda.look.accent")) + 6;
        for (String ac : faygolover.rppda.data.Look.ACCENTS) {
            g.fill(x, y - 2, x + 11, y + 9, Ui.accentColor(ac));
            g.renderOutline(x - 1, y - 3, 13, 13, ac.equals(l.accent) ? Ui.TEXT : Ui.DIM);
            hots.add(new Hot(x - 1, y - 3, 13, 13, "acc" + ac, Component.translatable("rppda.accent." + ac), () -> editLook(e -> e.accent = ac)));
            x += 16;
        }
        // Название модели.
        y = cy + 100;
        txt(g, Component.translatable("rppda.look.model"), lx, y, Ui.DIM, false);
        List<FormattedCharSequence> hint = font.split(Component.literal(Ui.up(Component.translatable("rppda.look.preview").getString())), cw - 10);
        int hy = cy + 118;
        for (FormattedCharSequence line : hint) {
            if (hy + 9 > cy + ch) break;
            txt(g, line, lx, hy, Ui.HINT, false);
            hy += 10;
        }
    }

    // ================================================================ заметки

    private void openNote(@Nullable CompoundTag n) {
        noteId = n == null ? 0 : n.getInt("id");
        noteTitle = n == null ? "" : n.getString("title");
        noteText = n == null ? "" : n.getString("text");
        noteDeleteArmed = 0;
        page = Page.NOTE;
        prompt = Prompt.NONE;
        rebuild();
    }

    private void noteWidgets() {
        addRenderableWidget(new IconButton(cx, cy, Icon.BACK, Component.translatable("rppda.btn.back"), b -> setPage(Page.NOTES)));
        toolbarButton(0, Icon.CHECK, "rppda.btn.note_save", this::saveNote);
        if (noteId > 0) {
            toolbarButton(1, Icon.SEND, "rppda.btn.note_share", () -> {
                saveNote();
                setPage(Page.NOTE_SHARE);
            });
            toolbarButton(2, Icon.TRASH, "rppda.btn.note_delete", () -> {
                long now = System.currentTimeMillis();
                if (now - noteDeleteArmed > 4000) {
                    noteDeleteArmed = now;
                    showToast(Component.translatable("rppda.toast.confirm_delete"), true);
                    return;
                }
                int id = noteId;
                send("notes.delete", t -> t.putInt("id", id));
                setPage(Page.NOTES);
            });
        }
        noteTitleBox = new EditBox(font, cx + 22, cy + 4, cw - 22 - 64, retro ? 10 : 12, Component.translatable("rppda.hint.note_title"));
        noteTitleBox.setMaxLength(faygolover.rppda.data.Note.TITLE_MAX);
        noteTitleBox.setHint(hint("rppda.hint.note_title"));
        noteTitleBox.setValue(noteTitle);
        box(noteTitleBox);
        addRenderableWidget(noteTitleBox);
        noteBody = new net.minecraft.client.gui.components.MultiLineEditBox(font, cx + 2, cy + 22, cw - 4, ch - 24,
                hint("rppda.hint.note_text"), Component.translatable("rppda.page.notes"));
        noteBody.setCharacterLimit(faygolover.rppda.data.Note.TEXT_MAX);
        noteBody.setValue(noteText);
        addRenderableWidget(noteBody);
        setInitialFocus(noteText.isEmpty() && noteTitle.isEmpty() ? noteTitleBox : noteBody);
    }

    private void saveNote() {
        if (noteTitleBox != null) noteTitle = noteTitleBox.getValue();
        if (noteBody != null) noteText = noteBody.getValue();
        if (noteTitle.isBlank() && noteText.isBlank()) return;
        int id = noteId;
        String title = noteTitle, text = noteText;
        send("notes.save", t -> {
            t.putInt("id", id);
            t.putString("title", title);
            t.putString("text", text);
        });
    }

    private void renderNotes(GuiGraphics g, int mx, int my) {
        title(g, Component.translatable("rppda.page.notes"), 4);
        list(g, mx, my, notes, 22, "note", (gg, n, y, hover) -> {
            Ui.icon(gg, Icon.NOTE, cx + 4, y + 3, 16, n.contains("from") ? Ui.GOLD : Ui.ACCENT);
            txt(gg, fit(n.getString("title"), cw - 34), cx + 24, y + 2, Ui.TEXT, false);
            String sub = Ui.time(n.getLong("updated")) + (n.contains("from")
                    ? " · " + Component.translatable("rppda.note.from", n.getString("from")).getString() : "");
            txt(gg, fit(sub, cw - 34), cx + 24, y + 12, Ui.DIM, false);
        }, (n, y) -> openNote(n));
    }

    private void renderNote(GuiGraphics g) {
        if (noteId == 0) return;
        for (CompoundTag n : notes) {
            if (n.getInt("id") == noteId && n.contains("from")) {
                txt(g, fit(Component.translatable("rppda.note.from", n.getString("from")).getString(), cw - 10), cx + 4, cy + ch - 9, Ui.GOLD, false);
            }
        }
    }

    private void renderNoteShare(GuiGraphics g, int mx, int my) {
        title(g, Component.translatable("rppda.screen.note_share"), 22);
        List<CompoundTag> rows = new ArrayList<>();
        for (CompoundTag r : chats) if (r.getBoolean("write")) rows.add(r);
        list(g, mx, my, rows, 24, "share", (gg, r, y, hover) -> drawChatRow(gg, r, y), (r, y) -> {
            int id = noteId, chat = r.getInt("id");
            send("notes.share", t -> {
                t.putInt("id", id);
                t.putInt("chat", chat);
            });
            setPage(Page.NOTE);
        });
    }

    /** Текст карточки заметки в переписке: «Заметка «Заголовок»» и начало текста. */
    private String noteCard(String raw) {
        int nl = raw.indexOf('\n');
        String title = nl < 0 ? raw : raw.substring(0, nl);
        String body = nl < 0 ? "" : raw.substring(nl + 1).strip();
        if (body.length() > 140) body = body.substring(0, 140) + "…";
        return Component.translatable("rppda.note.card", title).getString() + (body.isEmpty() ? "" : "\n" + body);
    }

    private void acceptNoteIcon(GuiGraphics g, int x, int y, CompoundTag m) {
        int seq = m.getInt("s");
        rowIcon(g, x, y, Icon.PLUS, "rppda.btn.note_accept", "nacc" + seq, Ui.GOLD, () -> send("notes.accept", t -> {
            t.putInt("chat", chatRow.getInt("id"));
            t.putInt("seq", seq);
        }));
    }

    /** Удаление учётки: NPC — два нажатия, учётка игрока — три (двойное подтверждение). */
    private void armDelete(int id, boolean player) {
        long now = System.currentTimeMillis();
        if (deleteArmId != id || now - deleteArmTime > 5000) {
            deleteArmId = id;
            deleteClicks = 0;
        }
        deleteArmTime = now;
        deleteClicks++;
        int need = player ? 3 : 2;
        if (deleteClicks < need) {
            showToast(Component.translatable(deleteClicks == 1 ? "rppda.toast.confirm_delete" : "rppda.toast.confirm_delete_player"), true);
            return;
        }
        deleteArmId = 0;
        send("sys.delete", t -> t.putInt("id", id));
        setPage(Page.SYS_ACCOUNTS);
    }

    /** Кинескоп: строки развёртки (в настоящих пикселях окна) и затемнение к краям. */
    private void crt(GuiGraphics g) {
        double gs = Minecraft.getInstance().getWindow().getGuiScale();
        int step = gs >= 3 ? 3 : 2;
        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        g.pose().scale((float) (1 / gs), (float) (1 / gs), 1);
        int rx0 = (int) Math.round(sx * gs), rx1 = (int) Math.round((sx + sw) * gs);
        int ry0 = (int) Math.round(sy * gs), ry1 = (int) Math.round((sy + sh) * gs);
        for (int y = ry0 + step - 1; y < ry1; y += step) g.fill(rx0, y, rx1, y + 1, 0x30000000);
        g.pose().popPose();
        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        g.blit(VIGNETTE, sx, sy, sw, sh, 0, 0, 160, 96, 160, 96);
        g.pose().popPose();
    }

    // ---------------------------------------------------------------- ретро: переписка как журнал терминала

    private void layoutRetro() {
        List<Block> out = new ArrayList<>();
        String lastDay = "";
        int inner = cw - 14;
        for (CompoundTag m : msgs) {
            String day = Ui.day(m.getLong("t"));
            String date = day.equals(lastDay) ? null : day;
            lastDay = day;
            int fl = m.getInt("fl");
            if ((fl & 2) != 0) {
                String n = "— " + Ui.up(noticeText(m.getString("x")).getString()) + " —";
                List<FormattedCharSequence> lines = font.split(Component.literal(n), cw - 10);
                out.add(new Block(m, lines, lines.size() * 10 + 2 + (date != null ? 12 : 0), false, true, date, 0, null));
                continue;
            }
            boolean own = m.getInt("f") == meId;
            boolean deleted = (fl & 1) != 0;
            String text = m.getString("x");
            String body = deleted && text.isEmpty() ? Component.translatable("rppda.chat.deleted").getString()
                    : (fl & 8) != 0 ? noteCard(text) : (fl & 4) != 0 ? "* " + text : text;
            List<FormattedCharSequence> lines = font.split(Component.literal(Ui.up(body)), inner);
            List<FormattedCharSequence> quote = null;
            if (m.getInt("r") > 0) {
                String q = "» " + (m.contains("rn") ? m.getString("rn") + ": " + (m.getString("rx").isEmpty() ? "…" : m.getString("rx")) : "…");
                quote = List.of(Language.fit(font, Ui.up(q), inner));
            }
            int hgt = 10 + (quote != null ? 10 : 0) + lines.size() * 10 + 3;
            out.add(new Block(m, lines, hgt + (date != null ? 12 : 0), own, false, date, 0, quote));
        }
        blocks = out;
    }

    /** Инверсная надпись (как выделение на терминале). Возвращает правый край. */
    private int inverse(GuiGraphics g, String s, int x, int y) {
        String u = Ui.up(s);
        g.fill(x, y - 1, x + font.width(u) + 3, y + 8, Ui.TEXT);
        g.drawString(font, u, x + 2, y, Ui.SCREEN, false);
        return x + font.width(u) + 3;
    }

    private void retroBlock(GuiGraphics g, Block b, int by, int mx, int my, int top, int bottom, boolean canWrite) {
        if (b.date != null) {
            String d = "== " + b.date + " ==";
            txt(g, d, cx + cw / 2 - tw(d) / 2, by + 2, Ui.DIM, false);
            by += 12;
        }
        if (b.notice) {
            int ly = by + 1;
            for (FormattedCharSequence l : b.lines) {
                txt(g, l, cx + cw / 2 - font.width(l) / 2, ly, Ui.DIM, false);
                ly += 10;
            }
            return;
        }
        CompoundTag m = b.m;
        int bh = b.height - (b.date != null ? 12 : 0);
        boolean deleted = (m.getInt("fl") & 1) != 0;
        boolean hover = mx >= cx && mx < cx + cw && my >= by && my < by + bh - 2 && my >= top && my < bottom;
        if (hover) g.fill(cx, by, cx + cw, by + bh - 2, Ui.ROW_HOVER);
        int x = cx + 2, y = by + 2;
        x = txtEnd(g, Ui.clock(m.getLong("t")), x, y, Ui.DIM) + 4;
        if (b.own) x = inverse(g, Component.translatable("rppda.chat.you").getString(), x, y);
        else x = txtEnd(g, fit(m.getString("n"), cw - 120) + ":", x, y, Ui.ACCENT);
        if (m.contains("g")) x = inverse(g, Component.translatable("rppda.chat.gm", m.getString("g")).getString(), x + 4, y);
        if (deleted) txtEnd(g, Component.translatable("rppda.chat.deleted_mark").getString(), x + 4, y, Ui.DIM);
        y += 10;
        if (b.quote != null) {
            txt(g, b.quote.get(0), cx + 10, y, Ui.DIM, false);
            y += 10;
        }
        for (FormattedCharSequence l : b.lines) {
            txt(g, l, cx + 10, y, deleted ? Ui.DIM : Ui.TEXT, false);
            y += 10;
        }
        if (hover && !deleted) {
            int seq = m.getInt("s");
            int ix = cx + cw - 15;
            if (b.own || system) {
                rowIcon(g, ix, by, Icon.TRASH, "rppda.btn.delete", "del" + seq, Ui.TEXT, () -> send("chat.delete", t -> {
                    t.putInt("chat", chatRow.getInt("id"));
                    t.putInt("seq", seq);
                }));
                ix -= 15;
            }
            if ((m.getInt("fl") & 8) != 0 && !b.own) {
                acceptNoteIcon(g, ix, by, m);
                ix -= 15;
            }
            if (canWrite) {
                String rn = b.own ? meName : m.getString("n");
                String rx = m.getString("x");
                rowIcon(g, ix, by, Icon.REPLY, "rppda.btn.reply", "rep" + seq, Ui.TEXT, () -> {
                    replySeq = seq;
                    replyName = rn;
                    replyText = rx;
                    rebuild();
                });
            }
        }
    }

    private int txtEnd(GuiGraphics g, String s, int x, int y, int color) {
        txt(g, s, x, y, color, false);
        return x + tw(s);
    }

    /** Обрезка одной строки по ширине (для цитат). */
    private static final class Language {
        static FormattedCharSequence fit(net.minecraft.client.gui.Font f, String s, int w) {
            return FormattedCharSequence.forward(Ui.fit(f, s, w), Style.EMPTY);
        }
    }
}
