package faygolover.rppda.item;

import faygolover.rppda.config.PdaConfig;
import faygolover.rppda.server.Hack;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Отмычка КПК. Отмычка в одной руке, чужой КПК с паролем в другой → ПКМ: взлом (как терминал Fallout). Попытки хранит
 * сама отмычка ({@code rppda.left}; нет — полная): вышел — отмычка с оставшимися попытками у тебя; кончились во время
 * взлома — КПК мёртв до перевыпуска ГМом; успешный взлом тратит отмычку целиком.
 */
public class LockpickItem extends Item {
    public LockpickItem(Properties p) {
        super(p.rarity(Rarity.UNCOMMON));
    }

    public static int left(ItemStack s) {
        CompoundTag t = s.getTag();
        return t != null && t.getCompound(PdaItem.TAG).contains("left") ? t.getCompound(PdaItem.TAG).getInt("left") : PdaConfig.hackAttempts;
    }

    public static void setLeft(ItemStack s, int left) {
        s.getOrCreateTagElement(PdaItem.TAG).putInt("left", left);
    }

    /** Скобками уже вернули попытку (раз на отмычку). */
    public static boolean restored(ItemStack s) {
        CompoundTag t = s.getTag();
        return t != null && t.getCompound(PdaItem.TAG).getBoolean("restored");
    }

    public static void setRestored(ItemStack s) {
        s.getOrCreateTagElement(PdaItem.TAG).putBoolean("restored", true);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player instanceof ServerPlayer sp) Hack.start(sp, hand);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tip, TooltipFlag flag) {
        tip.add(Component.translatable("item.rppda.lockpick.left", left(stack), PdaConfig.hackAttempts).withStyle(ChatFormatting.GOLD));
        tip.add(Component.translatable("item.rppda.lockpick.hint").withStyle(ChatFormatting.GRAY));
    }
}
