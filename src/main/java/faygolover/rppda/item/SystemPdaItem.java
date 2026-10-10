package faygolover.rppda.item;

import faygolover.rppda.server.PdaServer;
import net.minecraft.ChatFormatting;
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

/** Системный КПК — инструмент ГМа (не его персонажа): все чаты и учётки. Работает только у админа. */
public class SystemPdaItem extends Item {
    public SystemPdaItem(Properties p) {
        super(p.rarity(Rarity.EPIC));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player instanceof ServerPlayer sp) PdaServer.openFromHand(sp, hand);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tip, TooltipFlag flag) {
        tip.add(Component.translatable("item.rppda.system_pda.warning").withStyle(ChatFormatting.RED));
        tip.add(Component.translatable("item.rppda.system_pda.desc").withStyle(ChatFormatting.GRAY));
    }
}
