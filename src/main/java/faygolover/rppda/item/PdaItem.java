package faygolover.rppda.item;

import faygolover.rppda.server.PdaServer;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * КПК игрока. В предмете только ключ: {@code rppda.acc} (ID учётки, 0 — не зарегистрирован), {@code rppda.issue}
 * (номер выдачи: восстановление ГМом его увеличивает, и старые экземпляры отключаются) и имя для подсказки.
 */
public class PdaItem extends Item {
    public static final String TAG = "rppda";

    public PdaItem(Properties p) {
        super(p);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player instanceof ServerPlayer sp) PdaServer.openFromHand(sp, hand);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tip, TooltipFlag flag) {
        int acc = account(stack);
        if (acc <= 0) {
            tip.add(Component.translatable("item.rppda.pda.new").withStyle(ChatFormatting.GRAY));
            return;
        }
        String name = stack.getTag() == null ? "" : stack.getTag().getCompound(TAG).getString("name");
        tip.add(Component.literal(name).withStyle(ChatFormatting.AQUA)
                .append(Component.literal("  ID " + formatId(acc)).withStyle(ChatFormatting.DARK_GRAY)));
    }

    public static int account(ItemStack stack) {
        CompoundTag t = stack.getTag();
        return t == null ? 0 : t.getCompound(TAG).getInt("acc");
    }

    public static int issue(ItemStack stack) {
        CompoundTag t = stack.getTag();
        return t == null ? 0 : t.getCompound(TAG).getInt("issue");
    }

    public static void bind(ItemStack stack, int acc, int issue, String name) {
        CompoundTag t = stack.getOrCreateTagElement(TAG);
        t.putInt("acc", acc);
        t.putInt("issue", issue);
        t.putString("name", name);
    }

    /** Обновить имя в подсказке, если поменялось (без лишних изменений предмета — каждое уходит клиенту). */
    public static void refreshName(ItemStack stack, String name) {
        CompoundTag t = stack.getTag();
        if (t == null) return;
        CompoundTag p = t.getCompound(TAG);
        if (!name.equals(p.getString("name"))) {
            p.putString("name", name);
            t.put(TAG, p);
        }
    }

    /** Внешний вид, записанный в предмет (до регистрации — настоящий, после — копия для значка в инвентаре). */
    public static faygolover.rppda.data.Look look(ItemStack stack) {
        CompoundTag t = stack.getTag();
        return faygolover.rppda.data.Look.load(t == null ? new CompoundTag() : t.getCompound(TAG).getCompound("look"));
    }

    public static void setLook(ItemStack stack, faygolover.rppda.data.Look look) {
        CompoundTag t = stack.getOrCreateTagElement(TAG);
        t.put("look", look.save());
    }

    /** Корпус учётки поменялся — обновить копию в предмете (только если другая: каждое изменение уходит клиенту). */
    public static void refreshLook(ItemStack stack, faygolover.rppda.data.Look look) {
        CompoundTag t = stack.getTag();
        if (t == null) return;
        CompoundTag p = t.getCompound(TAG);
        if (!p.getCompound("look").equals(look.save())) {
            p.put("look", look.save());
            t.put(TAG, p);
        }
    }

    /** Номер корпуса для модели предмета (0 — бежевый, как без записи). */
    public static float skinIndex(ItemStack stack) {
        CompoundTag t = stack.getTag();
        if (t == null) return 0;
        String body = t.getCompound(TAG).getCompound("look").getString("body");
        int i = faygolover.rppda.data.Look.BODIES.indexOf(body);
        return i <= 0 ? 0 : i / 100f;
    }

    public static String formatId(int id) {
        String s = String.format("%08d", id);
        return s.substring(0, 4) + " " + s.substring(4);
    }
}
