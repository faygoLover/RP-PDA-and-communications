package faygolover.rppda.item;

import faygolover.rppda.config.PdaConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Дешифратор: шифрует свою заметку или начинает расшифровку чужой (1 заряд, лежит в инвентаре). После каждого заряда
 * перегенерирует матрицу ({@code decryptor_cooldown_minutes}). В предмете: {@code rppda.left} — осталось зарядов
 * (нет — полный), {@code rppda.ready} — когда снова готов (мс).
 */
public class DecryptorItem extends Item {
    public DecryptorItem(Properties p) {
        super(p.rarity(Rarity.UNCOMMON));
    }

    public static int max() {
        return PdaConfig.decryptorCharges;
    }

    public static int left(ItemStack s) {
        CompoundTag t = s.getTag();
        return t != null && t.getCompound(PdaItem.TAG).contains("left") ? t.getCompound(PdaItem.TAG).getInt("left") : max();
    }

    public static long readyAt(ItemStack s) {
        CompoundTag t = s.getTag();
        return t == null ? 0 : t.getCompound(PdaItem.TAG).getLong("ready");
    }

    public static boolean ready(ItemStack s, long now) {
        return left(s) > 0 && readyAt(s) <= now;
    }

    /** Потратить заряд: КД на перегенерацию; последний заряд — дешифратор рассыпается. Возвращает, сколько осталось. */
    public static int use(ItemStack s, long now) {
        int left = left(s) - 1;
        if (left <= 0) {
            s.shrink(1);
            return 0;
        }
        CompoundTag t = s.getOrCreateTagElement(PdaItem.TAG);
        t.putInt("left", left);
        t.putLong("ready", now + PdaConfig.decryptorCooldown * 60_000L);
        return left;
    }

    /** Модель: доля оставшихся зарядов (лампочки). */
    public static float chargeFraction(ItemStack s) {
        return Math.min(1f, left(s) / (float) Math.max(1, max()));
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tip, TooltipFlag flag) {
        tip.add(Component.translatable("item.rppda.decryptor.charges", left(stack), max()).withStyle(ChatFormatting.GOLD));
        long wait = readyAt(stack) - System.currentTimeMillis();
        if (wait > 0) tip.add(Component.translatable("item.rppda.decryptor.cooldown", (wait + 59_999) / 60_000).withStyle(ChatFormatting.DARK_AQUA));
        tip.add(Component.translatable("item.rppda.decryptor.hint").withStyle(ChatFormatting.GRAY));
    }
}
