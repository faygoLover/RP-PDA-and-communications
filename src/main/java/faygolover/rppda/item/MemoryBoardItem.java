package faygolover.rppda.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Плата памяти для ретро-КПК ({@code docs/20_retro_memory.md}): ставится в ячейку расширения в профиле и добавляет
 * {@link #kb} КБ. Пока только от ГМа (творческое меню, рецепта нет).
 */
public class MemoryBoardItem extends Item {
    public final int kb;

    public MemoryBoardItem(Properties p, int kb) {
        super(p);
        this.kb = kb;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tip, TooltipFlag flag) {
        tip.add(Component.translatable("item.rppda.memory.size", kb).withStyle(ChatFormatting.GOLD));
        tip.add(Component.translatable("item.rppda.memory.hint").withStyle(ChatFormatting.GRAY));
    }
}
