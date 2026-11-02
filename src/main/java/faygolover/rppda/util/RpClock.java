package faygolover.rppda.util;

import java.util.Arrays;

/**
 * RP-время на реальный момент (находка 56). Сообщения, заметки и журнал хранят реальные миллисекунды; RP-время — это
 * время суток мира (тики, 0 = 6:00, как часы RP Time). Таблица опорных точек «мс → тики»: между точками — по прямой.
 * Сервер раз в секунду кладёт сюда показания часов; пока они ложатся на одну прямую с точностью {@link #TOLERANCE}
 * (минута RP), точка не нужна; где ход сменился (пауза, ускоренная ночь во сне, {@code /rptime set|add}, сервер был
 * выключен) — ставится новая. Это «вращающаяся дверь»: от последней точки держим допустимый наклон {@code [lo, hi]},
 * пустеет — закрываем отрезок на предыдущем показании.
 */
public final class RpClock {
    /** Тики суток. */
    public static final long DAY = 24_000;
    /** Наибольшая ошибка перевода, тики (1000 тиков = час RP, 16 — около минуты). */
    public static final long TOLERANCE = 16;
    /** Точек не больше: старые отбрасываются, до первой — по наклону первого отрезка. */
    public static final int MAX = 1024;

    private long[] ms = new long[32], ticks = new long[32];
    private int n;
    /** Последнее показание открытого отрезка и допустимый наклон от последней точки до него. */
    private long lastMs, lastTicks;
    private boolean open;
    private double lo, hi;

    /**
     * Показание часов: реальное время и время суток мира. {@code true} — добавилась точка (клиентам пора прислать
     * таблицу заново).
     */
    public boolean sample(long now, long dayTime) {
        if (n == 0) {
            add(now, dayTime);
            return true;
        }
        long a = ms[n - 1];
        if (now <= (open ? lastMs : a)) return false; // реальные часы назад — пропустить
        double dt = now - a;
        double nlo = Math.max(open ? lo : Double.NEGATIVE_INFINITY, (dayTime - TOLERANCE - ticks[n - 1]) / dt);
        double nhi = Math.min(open ? hi : Double.POSITIVE_INFINITY, (dayTime + TOLERANCE - ticks[n - 1]) / dt);
        if (!open || nlo <= nhi) {
            lo = nlo;
            hi = nhi;
            lastMs = now;
            lastTicks = dayTime;
            open = true;
            return false;
        }
        // Новое показание с прямой не сходится: закрыть отрезок на прошлом, отсюда — новый.
        add(lastMs, openTicks());
        double d = now - lastMs;
        lo = (dayTime - TOLERANCE - ticks[n - 1]) / d;
        hi = (dayTime + TOLERANCE - ticks[n - 1]) / d;
        lastMs = now;
        lastTicks = dayTime;
        open = true;
        return true;
    }

    /** Время открытого отрезка на последнем показании: по допустимой прямой, ближе всего к самому показанию. */
    private long openTicks() {
        double dt = lastMs - ms[n - 1];
        double slope = (lastTicks - ticks[n - 1]) / dt;
        slope = Math.max(lo, Math.min(hi, slope));
        return ticks[n - 1] + Math.round(slope * dt);
    }

    private void add(long t, long d) {
        if (n == ms.length) {
            ms = Arrays.copyOf(ms, n * 2);
            ticks = Arrays.copyOf(ticks, n * 2);
        }
        ms[n] = t;
        ticks[n] = d;
        n++;
        open = false;
        if (n > MAX) {
            int drop = n - MAX;
            System.arraycopy(ms, drop, ms, 0, MAX);
            System.arraycopy(ticks, drop, ticks, 0, MAX);
            n = MAX;
        }
    }

    /** Точки и последнее показание: {@code [мс0, тики0, мс1, тики1, …]} — для сохранения и клиента. */
    public long[] points() {
        int k = n + (open ? 1 : 0);
        long[] out = new long[k * 2];
        for (int i = 0; i < n; i++) {
            out[i * 2] = ms[i];
            out[i * 2 + 1] = ticks[i];
        }
        if (open) {
            out[n * 2] = lastMs;
            out[n * 2 + 1] = openTicks();
        }
        return out;
    }

    /** Загрузить сохранённые {@link #points()}: последнее показание становится точкой. */
    public void load(long[] p) {
        n = 0;
        open = false;
        for (int i = 0; i + 1 < p.length; i += 2) if (n == 0 || p[i] > ms[n - 1]) add(p[i], p[i + 1]);
    }

    public long ticksAt(long when) {
        return at(points(), when);
    }

    /**
     * Время суток мира на реальный момент по точкам {@link #points()}. Между точками — по прямой, до первой и после
     * последней — по наклону крайнего отрезка; точка одна — её время.
     */
    public static long at(long[] p, long when) {
        int k = p.length / 2;
        if (k == 0) return 0;
        if (k == 1) return p[1];
        int i;
        if (when <= p[2]) i = 0;
        else if (when >= p[(k - 2) * 2]) i = k - 2;
        else {
            int lo = 0, hi = k - 1; // p[lo] <= when < p[hi]
            while (hi - lo > 1) {
                int mid = (lo + hi) >>> 1;
                if (p[mid * 2] <= when) lo = mid;
                else hi = mid;
            }
            i = lo;
        }
        long r0 = p[i * 2], t0 = p[i * 2 + 1], r1 = p[i * 2 + 2], t1 = p[i * 2 + 3];
        if (r1 == r0) return t1;
        return t0 + Math.round((double) (t1 - t0) * (when - r0) / (r1 - r0));
    }

    // ---------------------------------------------------------------- календарь RP

    /** Час RP: 0 тиков = 6:00 (часы RP Time и ванильные сутки). */
    public static int hour(long t) {
        return (int) ((Math.floorMod(t, DAY) / 1000 + 6) % 24);
    }

    public static int minute(long t) {
        return (int) (Math.floorMod(t, DAY) % 1000 * 60 / 1000);
    }

    /** Номер дня RP с первого; новый день — в 6:00, как у RP Time ({@code /rptime info}, таблички). */
    public static long day(long t) {
        return Math.floorDiv(t, DAY) + 1;
    }

    /** «06:05». */
    public static String clock(long t) {
        return String.format("%02d:%02d", hour(t), minute(t));
    }
}
