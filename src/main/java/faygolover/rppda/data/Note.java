package faygolover.rppda.data;

import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

/** Заметка учётки. {@code from} — от кого получена (заметкой поделились в чате), иначе своя. */
public final class Note {
    public static final int TITLE_MAX = 40;
    public static final int TEXT_MAX = 2000;
    public static final int LIMIT = 50;

    public final int id;
    public String title;
    public String text;
    public long created;
    public long updated;
    @Nullable
    public String from;
    /** Зашифрована: без расшифровки её читают только владелец учётки (своя заметка) и ГМ. */
    public boolean cipher;
    /** Какие из трёх частей уже расшифрованы (биты 0–2). */
    public int opened;
    /**
     * Файл с ретро-КПК ещё качается или передача оборвалась: текст — сколько успело прийти, {@code expect} — сколько
     * знаков должно было быть. Такая заметка не открывается, но место занимает.
     */
    public boolean partial;
    public int expect;

    public static final int PARTS = 3;

    public Note(int id, String title, String text) {
        this.id = id;
        this.title = title;
        this.text = text;
    }

    /** Заголовок по умолчанию — первая строка текста. */
    public static String firstLine(String text) {
        String s = text.strip();
        int nl = s.indexOf('\n');
        s = nl < 0 ? s : s.substring(0, nl);
        return s.isEmpty() ? "…" : s;
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putInt("id", id);
        t.putString("title", title);
        t.putString("text", text);
        t.putLong("created", created);
        t.putLong("updated", updated);
        if (from != null) t.putString("from", from);
        if (cipher) {
            t.putBoolean("cipher", true);
            t.putInt("opened", opened);
        }
        if (partial) {
            t.putBoolean("partial", true);
            t.putInt("expect", expect);
        }
        return t;
    }

    public static Note load(CompoundTag t) {
        Note n = new Note(t.getInt("id"), t.getString("title"), t.getString("text"));
        n.created = t.getLong("created");
        n.updated = t.getLong("updated");
        if (t.contains("from")) n.from = t.getString("from");
        n.cipher = t.getBoolean("cipher");
        n.opened = t.getInt("opened");
        n.partial = t.getBoolean("partial");
        n.expect = t.getInt("expect");
        return n;
    }

    public boolean fullyOpened() {
        return opened == (1 << PARTS) - 1;
    }

    /** Границы трёх равных частей текста (по символам): [0, a), [a, b), [b, len). */
    public static int[] bounds(String text) {
        int len = text.length();
        return new int[]{0, len / 3, len * 2 / 3, len};
    }

    /**
     * Текст для того, кто не может читать без расшифровки: неоткрытые части — случайные буквы и цифры той же
     * длины (пробелы и переносы на месте), всегда одни и те же для этой заметки.
     */
    public String masked() {
        int[] b = bounds(text);
        java.util.Random rnd = new java.util.Random(id * 31L + created);
        String abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            int part = i < b[1] ? 0 : i < b[2] ? 1 : 2;
            char r = abc.charAt(rnd.nextInt(abc.length()));
            if ((opened & (1 << part)) != 0 || ch == ' ' || ch == '\n') sb.append(ch);
            else sb.append(r);
        }
        return sb.toString();
    }
}
