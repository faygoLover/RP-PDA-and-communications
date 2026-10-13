package faygolover.rppda.client;

import faygolover.rppda.RpPda;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/** Звуки окна КПК (только у игрока, в sounds.json — {@code rppda:pda.*}; громкость файлов выровнена). */
public final class Sfx {
    private Sfx() {}

    public static final SoundEvent PRESS = ev("press"), CANCEL = ev("cancel"), SELECT = ev("select"), SELECT_RETRO = ev("select_retro"), PAGE = ev("page"),
            FOCUS = ev("focus"), CLICK = ev("click"), GOOD = ev("good"), BAD = ev("bad"), DONE = ev("done"),
            INSERT = ev("insert"), WORK = ev("work"), MODEM = ev("modem");

    private static SoundEvent ev(String name) {
        return SoundEvent.createVariableRangeEvent(new ResourceLocation(RpPda.MODID, "pda." + name));
    }

    public static void play(SoundEvent e, float volume) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(e, 1f, volume));
    }

    public static void play(SoundEvent e) {
        play(e, 0.5f);
    }

    /** Долгая работа КПК: петля, плавно нарастает при старте и плавно стихает перед итогом. */
    public static final class Loop extends AbstractTickableSoundInstance {
        private final float max, step;
        private boolean fading;

        private Loop(SoundEvent e, float max) {
            super(e, SoundSource.MASTER, RandomSource.create());
            looping = true;
            delay = 0;
            volume = 0.01f;
            this.max = max;
            this.step = max / 10f;
            relative = true;
            attenuation = SoundInstance.Attenuation.NONE;
        }

        public static Loop start(SoundEvent e) {
            return start(e, 0.45f);
        }

        /** Петля с другой громкостью (модем при передаче файла — тихо). */
        public static Loop start(SoundEvent e, float max) {
            Loop l = new Loop(e, max);
            Minecraft.getInstance().getSoundManager().play(l);
            return l;
        }

        /** Стихнуть (~0,5 с) и замолчать. */
        public void fadeOut() {
            fading = true;
        }

        @Override
        public void tick() {
            volume = fading ? volume - step : Math.min(max, volume + step);
            if (fading && volume <= 0.01f) stop();
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }
    }
}
