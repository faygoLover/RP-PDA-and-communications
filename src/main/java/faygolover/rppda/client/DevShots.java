package faygolover.rppda.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Для разработки: снимки окна КПК с примерными данными в {@code run/client/screenshots}, потом выход.
 * Запуск: {@code gradlew runClient -Pdevshot=true}. Без свойства {@code rppda.devshot} не подключается.
 */
final class DevShots {
    private DevShots() {}

    private static final List<Object[]> steps = new ArrayList<>();
    private static int step = -1, wait;
    private static PdaScreen screen;

    static void init() {
        MinecraftForge.EVENT_BUS.addListener(DevShots::tick);
    }

    private static CompoundTag row(int id, String type, String title, String last, String from, long time, int unread, String mode) {
        CompoundTag t = new CompoundTag();
        t.putInt("id", id);
        t.putString("type", type);
        t.putString("title", title);
        t.putString("last", last);
        if (!from.isEmpty()) t.putString("lastFrom", from);
        t.putLong("time", time);
        t.putInt("unread", unread);
        t.putString("mode", mode);
        t.putBoolean("write", !type.equals("CHANNEL"));
        t.putBoolean("member", true);
        t.putInt("count", type.equals("DM") ? 2 : 5);
        if (type.equals("DM")) t.putInt("peer", 48213377 + id);
        return t;
    }

    private static CompoundTag acc(int id, String name, String kind) {
        CompoundTag t = new CompoundTag();
        t.putInt("id", id);
        t.putString("name", name);
        t.putString("kind", kind);
        return t;
    }

    private static CompoundTag msg(int seq, int from, String name, long t, String text, int fl, int reply, String rn, String rx) {
        CompoundTag m = new CompoundTag();
        m.putInt("s", seq);
        m.putInt("f", from);
        m.putString("n", name);
        m.putLong("t", t);
        m.putString("x", text);
        m.putInt("fl", fl);
        if (reply > 0) {
            m.putInt("r", reply);
            m.putString("rn", rn);
            m.putString("rx", rx);
        }
        return m;
    }

    private static CompoundTag look(String body, String screen, String style, String accent) {
        faygolover.rppda.data.Look l = new faygolover.rppda.data.Look();
        l.body = body;
        l.screen = screen;
        l.style = style;
        l.accent = accent;
        return l.save();
    }

    private static CompoundTag state(boolean system) {
        long now = System.currentTimeMillis();
        CompoundTag d = new CompoundTag();
        d.put("me", system ? acc(0, "Система", "SYSTEM") : acc(31415926, "Док Хорхе", "PLAYER"));
        d.putBoolean("system", system);
        d.putBoolean("admin", system);
        d.putBoolean("pw", true);
        ListTag chats = new ListTag();
        chats.add(row(5, "DM", "Барыга с рынка", "Есть патроны 5.45, заходи", "Барыга с рынка", now - 60_000, 2, "CHAT"));
        chats.add(row(7, "GROUP", "Отряд «Север»", "Выдвигаемся в 18:00", "Сержант", now - 600_000, 5, "PDA"));
        chats.add(row(1, "GLOBAL", "", "Кто видел мою собаку?", "Лиза", now - 3_600_000, 0, "CHAT"));
        chats.add(row(9, "CHANNEL", "Новости Департамента", "Комендантский час с 23:00", "", now - 86_400_000L * 2, 1, "MUTED"));
        chats.add(row(11, "DM", "Сержант", "Принял.", "", now - 86_400_000L * 3, 0, "CHAT"));
        d.put("chats", chats);
        ListTag contacts = new ListTag();
        CompoundTag c1 = acc(48213382, "Ахмед", "PLAYER");
        c1.putString("label", "Барыга с рынка");
        contacts.add(c1);
        CompoundTag c2 = acc(48213388, "Сержант Петров", "PLAYER");
        c2.putString("label", "");
        contacts.add(c2);
        CompoundTag c3 = acc(77001234, "Спамер", "PLAYER");
        c3.putString("label", "");
        c3.putBoolean("blocked", true);
        contacts.add(c3);
        d.put("contacts", contacts);
        ListTag invites = new ListTag();
        CompoundTag inv = new CompoundTag();
        inv.putInt("chat", 15);
        inv.putString("title", "Клуб любителей чая");
        inv.putString("from", "Лиза");
        invites.add(inv);
        d.put("invites", invites);
        return d;
    }

    private static void plan() {
        long now = System.currentTimeMillis();
        steps.add(new Object[]{(Runnable) () -> screen.receive("register", new CompoundTag()), "pda_register"});
        steps.add(new Object[]{(Runnable) () -> {
            CompoundTag l = new CompoundTag();
            l.putString("name", "Док Хорхе");
            l.putInt("id", 31415926);
            screen.receive("locked", l);
        }, "pda_locked"});
        steps.add(new Object[]{(Runnable) () -> screen.receive("state", state(false)), "pda_chats"});
        steps.add(new Object[]{(Runnable) () -> {
            CompoundTag g = new CompoundTag();
            CompoundTag r = row(7, "GROUP", "Отряд «Север»", "", "", now, 0, "PDA");
            g.put("row", r);
            screen.receive("goto", g);
            CompoundTag h = new CompoundTag();
            h.putInt("chat", 7);
            h.putInt("before", 0);
            h.putBoolean("more", true);
            h.put("row", r);
            ListTag ms = new ListTag();
            ms.add(msg(40, 0, "Система", now - 7_200_000, "rppda.notice.joined\nДок Хорхе", 2, 0, "", ""));
            ms.add(msg(41, 48213388, "Сержант Петров", now - 3_000_000, "Всем собраться у ворот. Берём аптечки и воду, идём до старой станции.", 0, 0, "", ""));
            ms.add(msg(42, 31415926, "Док Хорхе", now - 2_900_000, "Понял, беру носилки.", 0, 41, "Сержант Петров", "Всем собраться у ворот."));
            ms.add(msg(43, 48213390, "Лиза", now - 2_000_000, "проверяет рацию", 4, 0, "", ""));
            CompoundTag gm = msg(44, 48213391, "Связной", now - 600_000, "Внимание: на маршруте замечена аномалия.", 0, 0, "", "");
            gm.putString("g", "GameMaster");
            ms.add(gm);
            ms.add(msg(45, 48213388, "Сержант Петров", now - 60_000, "Выдвигаемся в 18:00", 0, 0, "", ""));
            h.put("msgs", ms);
            screen.receive("history", h);
        }, "pda_chat"});
        steps.add(new Object[]{(Runnable) () -> {
            screen.setPage(PdaScreen.Page.CONTACTS);
            screen.holdKeyForShot(1);
        }, "pda_contacts"});
        steps.add(new Object[]{(Runnable) () -> {
            screen.holdKeyForShot(-1);
            screen.setPage(PdaScreen.Page.PROFILE);
        }, "pda_profile"});
        steps.add(new Object[]{(Runnable) () -> {
            screen.receive("state", state(true));
            screen.setPage(PdaScreen.Page.SYS_ACCOUNTS);
            CompoundTag l = new CompoundTag();
            ListTag list = new ListTag();
            list.add(acc(0, "Система", "SYSTEM"));
            CompoundTag a1 = acc(31415926, "Док Хорхе", "PLAYER");
            a1.putString("ownerName", "faygoLover");
            a1.putBoolean("online", true);
            a1.putBoolean("pw", true);
            list.add(a1);
            CompoundTag a2 = acc(48213391, "Связной", "NPC");
            a2.putBoolean("locked", true);
            list.add(a2);
            l.put("list", list);
            screen.receive("sys.accounts", l);
            CompoundTag t = new CompoundTag();
            t.putString("key", "rppda.toast.given");
            t.putBoolean("err", false);
            ListTag args = new ListTag();
            args.add(StringTag.valueOf("Связной"));
            t.put("args", args);
            screen.receive("toast", t);
        }, "pda_sys_accounts"});
        steps.add(new Object[]{(Runnable) () -> {
            CompoundTag a = new CompoundTag();
            CompoundTag a1 = acc(31415926, "Док Хорхе", "PLAYER");
            a1.putString("ownerName", "faygoLover");
            a1.putBoolean("online", true);
            a1.putBoolean("pw", true);
            a1.putBoolean("locked", true);
            a1.putInt("issue", 2);
            a1.putLong("created", now - 86_400_000L * 5);
            a.put("acc", a1);
            screen.receive("sys.account", a);
            screen.setPage(PdaScreen.Page.SYS_ACCOUNT);
        }, "pda_sys_account"});
        // Внешний вид: страница ГМа на новом КПК и несколько корпусов на списке чатов.
        steps.add(new Object[]{(Runnable) () -> {
            CompoundTag r = new CompoundTag();
            r.putBoolean("gm", true);
            r.put("look", look("disco", "synth", "", "neon"));
            screen.receive("register", r);
            screen.openLookForShot();
        }, "pda_look_disco"});
        String[][] combos = {{"sunset", "synth", ""}, {"wave", "green", ""}, {"neon", "synth", ""}, {"wood", "lcd", ""},
                {"camo", "white", ""}, {"memphis", "blue", ""}, {"rainbow", "green", ""}, {"cassette", "white", ""},
                {"navy", "blue", ""}, {"beige", "green", "modern"}};
        for (String[] c : combos) {
            steps.add(new Object[]{(Runnable) () -> {
                CompoundTag st = state(false);
                st.put("look", look(c[0], c[1], c[2], "neon"));
                if (c[0].equals("wave")) st.putBoolean("signal", false);
                screen.receive("state", st);
                screen.setPage(PdaScreen.Page.CHATS);
            }, "pda_skin_" + c[0] + (c[2].isEmpty() ? "" : "_" + c[2])});
        }
    }

    private static void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (step < 0) {
            if (mc.screen == null || mc.getOverlay() != null || mc.level != null) return;
            plan();
            screen = new PdaScreen();
            mc.setScreen(screen);
            step = 0;
            wait = 20;
            ((Runnable) steps.get(0)[0]).run();
            return;
        }
        if (--wait > 0) return;
        Consumer<net.minecraft.network.chat.Component> log = c -> faygolover.rppda.RpPda.LOGGER.info("devshot: {}", c.getString());
        Screenshot.grab(mc.gameDirectory, steps.get(step)[1] + ".png", mc.getMainRenderTarget(), log);
        step++;
        if (step >= steps.size()) {
            mc.stop();
            return;
        }
        if (mc.screen != screen) mc.setScreen(screen);
        ((Runnable) steps.get(step)[0]).run();
        wait = 15;
    }
}
