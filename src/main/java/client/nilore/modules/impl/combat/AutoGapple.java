package client.nilore.modules.impl.combat;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import client.nilore.NiloreClient;
import client.nilore.event.EventTarget;
import client.nilore.event.impl.Render2DEvent;
import client.nilore.event.impl.StrafeEvent;
import client.nilore.event.impl.TickEvent;
import client.nilore.event.impl.WorldChangeEvent;
import client.nilore.hud.ModuleListHud;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.modules.impl.player.MidPearl;
import client.nilore.modules.impl.player.Stuck;
import client.nilore.modules.impl.movement.Scaffold;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.ModeSetting;
import client.nilore.settings.impl.NumberSetting;
import client.nilore.utils.misc.PacketUtil;

/**
 * Eats a golden apple without ever leaving combat: the use is started purely with
 * server-bound packets, so the local player never enters the vanilla "using item"
 * state. That means no item-use slowdown, no eating animation, and {@link KillAura}
 * keeps attacking the whole time.
 *
 * <p>Two ways to get the apple into the used hand:
 * <ul>
 *   <li>{@code Offhand} — swap the apple into the off hand ({@code SWAP_ITEM_WITH_OFFHAND}),
 *       exactly like {@code NoSlow} does for food. The main hand keeps the sword, which is
 *       the safest option for anticheats that validate the held item on attack.</li>
 *   <li>{@code Hotbar} — send {@code SET_CARRIED_ITEM} for the apple slot without touching
 *       {@code Inventory.selected}, so the client keeps rendering the sword. Fewer packets,
 *       but the server believes the main hand holds the apple while attacks go out.</li>
 * </ul>
 *
 * <p>Anticheat notes (Grim and friends), all of which shaped the state machine below:
 * <ul>
 *   <li>A duplicate {@code SET_CARRIED_ITEM} for an unchanged slot is a known flag — this is
 *       even what {@code Disabler}'s "Grim BadPacketsA" option exploits. Every slot packet
 *       here is only sent when the value actually differs, and never twice in one tick.</li>
 *   <li>Packets are spread over separate ticks ({@code Packet Gap}) instead of being flushed
 *       as one burst, so the slot change, the swap and the use land in plausible order.</li>
 *   <li>The use packet borrows the client's own prediction sequence via
 *       {@code PacketUtil.sendPredictiveDirect}, so the sequence stays monotonic and matches
 *       what vanilla would send.</li>
 *   <li>Attempts are rate limited with optional jitter — a machine-precise "eat at exactly
 *       32 ticks, every time" pattern is trivial to filter out statistically.</li>
 *   <li>{@code Freeze} zeroes movement input while the apple is going down, so the server's
 *       "using an item" movement expectation still matches if a check models it. Rotation and
 *       attacks are untouched, so KillAura keeps working.</li>
 *   <li>The original hotbar slot is always restored; state is reset on world change and on
 *       disable so nothing is left half swapped.</li>
 * </ul>
 *
 * <p>Do not run this together with {@code Stuck}: that module cancels outgoing
 * {@code ServerboundUseItemPacket}s in order to replay them later, which would swallow the
 * use packet this module relies on. {@link #canStart()} refuses to start in that case.
 */
public class AutoGapple extends Module {
    public static AutoGapple INSTANCE;

    /** Vanilla golden apple use duration is 32 ticks; the extra ticks are a safety margin. */
    private static final int USE_TICKS = 32;
    private static final int MAX_EAT_TICKS = 40;

    private enum Stage {
        IDLE,
        SELECT_SLOT,
        START_USE,
        EATING,
        FINISHED
    }

    public final ModeSetting handMode = new ModeSetting("Hand", "Offhand", "Hotbar");
    public final ModeSetting trigger = new ModeSetting("Trigger", "Health", "Always");

    public final NumberSetting health = new NumberSetting("Health", 16, 1, 20, 1,
            () -> this.trigger.is("Health"));
    public final BooleanSetting onlyWithKillAura = new BooleanSetting("Only With KillAura", true);
    public final BooleanSetting preferEnchanted = new BooleanSetting("Prefer Enchanted", true);
    public final BooleanSetting freeze = new BooleanSetting("Freeze", true);

    public final NumberSetting interval = new NumberSetting("Interval", 40, 0, 200, 5);
    public final NumberSetting jitter = new NumberSetting("Jitter", 10, 0, 60, 1);
    public final NumberSetting packetGap = new NumberSetting("Packet Gap", 1, 0, 5, 1);
    public final NumberSetting restoreDelay = new NumberSetting("Restore Delay", 2, 1, 10, 1);

    /**
     * 进食进度条。
     *
     * <p>这个模块走的是纯包驱动, 客户端从不进入 use-item 状态, 所以原生那套"抬手吃"的动画
     * 一个都不会出现 —— 玩家唯一的反馈只有血量回没回。这条进度条的进度源就是状态机自己的
     * {@code stageTicks}, 分母取原版金苹果的 32 拍, 不额外发任何包, 也不影响服务端判定。
     */
    public final BooleanSetting progressBar = new BooleanSetting("Progress Bar", true);

    private Stage stage = Stage.IDLE;
    private int stageTicks;
    private int cooldownTicks;
    private int appleSlot = -1;
    private int originalSlot = -1;
    private int countBefore;
    private boolean swappedHand;
    private boolean warnedAboutStuck;
    /** Scaffold 与进食互斥, 提示只打一次, 免得每拍刷日志。 */
    private boolean warnedAboutScaffold;

    public AutoGapple() {
        super("AutoGapple", Category.COMBAT);
        INSTANCE = this;
    }

    @Override
    public String getSuffix() {
        return this.handMode.getValue();
    }

    @Override
    public void onDisable() {
        this.abort();
        this.cooldownTicks = 0;
    }

    @EventTarget
    public void onWorldChange(WorldChangeEvent event) {
        this.abort();
        this.cooldownTicks = 0;
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (mc.player == null || mc.level == null) return;

        if (this.cooldownTicks > 0) {
            this.cooldownTicks--;
        }

        switch (this.stage) {
            case IDLE -> {
                if (this.cooldownTicks > 0) return;
                if (this.canStart()) this.beginEat();
            }
            case SELECT_SLOT -> {
                if (++this.stageTicks < this.packetGap.getValue().intValue()) return;
                this.stageTicks = 0;
                this.sendUsePacket();
            }
            case START_USE -> {
                // The use packet is out; wait one tick so the server has registered it
                // before anything else touches the inventory.
                this.stage = Stage.EATING;
                this.stageTicks = 0;
            }
            case EATING -> {
                this.stageTicks++;
                if (this.isEatDone()) {
                    this.finishEat();
                }
            }
            case FINISHED -> {
                if (++this.stageTicks < this.restoreDelay.getValue().intValue()) return;
                this.restoreSlot();
                this.stage = Stage.IDLE;
                this.stageTicks = 0;
                this.cooldownTicks = this.nextCooldown();
            }
        }
    }

    /** Zeroes movement input while the apple is being eaten — the "stuck" part of the trick. */
    @EventTarget
    public void onStrafe(StrafeEvent event) {
        if (!this.freeze.getValue()) return;
        if (this.stage != Stage.SELECT_SLOT && this.stage != Stage.START_USE && this.stage != Stage.EATING) {
            return;
        }
        event.setForward(0.0f);
        event.setStrafe(0.0f);
        event.setJumping(false);
    }

    /**
     * 准心下方偏上的进食进度条。
     *
     * <p>只读状态机, 零发包、零服务端交互 —— 纯客户端提示, 不存在任何可被服务端观测的新信号。
     * 位置压在快捷栏上方 (guiScaledHeight - 58), 不遮挡血量/饥饿值。
     */
    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!this.progressBar.getValue() || mc.player == null || mc.level == null) {
            return;
        }
        if (this.stage != Stage.SELECT_SLOT && this.stage != Stage.START_USE
                && this.stage != Stage.EATING && this.stage != Stage.FINISHED) {
            return;
        }
        GuiGraphics graphics = event.guiGraphics();
        if (graphics == null) {
            return;
        }

        // FINISHED 阶段 stageTicks 已经归零, 直接钉在满进度, 免得条子回缩一下显得没吃完。
        float progress = this.stage == Stage.FINISHED
                ? 1.0f
                : Mth.clamp(this.stageTicks / (float) USE_TICKS, 0.0f, 1.0f);

        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        int barWidth = 82;
        int barHeight = 4;
        int x = screenWidth / 2 - barWidth / 2;
        int y = screenHeight - 58;

        int accent = this.barColor();
        // 深色描边: 亮天空 / 亮方块背景下也能看清边界。
        graphics.fill(x - 1, y - 1, x + barWidth + 1, y + barHeight + 1, 0x9A000000);
        graphics.fill(x, y, x + barWidth, y + barHeight, 0x40FFFFFF);
        int filled = x + Math.round(barWidth * progress);
        if (filled > x) {
            graphics.fill(x, y, filled, y + barHeight, accent);
        }
    }

    /** 进度条主色: 跟随 HUD 主题色, 取不到就退回金苹果色。 */
    private int barColor() {
        try {
            NiloreClient client = NiloreClient.getInstance();
            if (client != null && client.getHudManager() != null) {
                ModuleListHud hud = client.getHudManager().getHudElement(ModuleListHud.class);
                if (hud != null) {
                    return hud.getThemeColor(0, 0.0f, 3) | 0xFF000000;
                }
            }
        } catch (RuntimeException ignored) {
            // HUD 还没初始化完 —— 退回兜底色, 不能因为画个条子把渲染管线搞崩。
        }
        return 0xFFFFC64A;
    }

    private boolean canStart() {
        if (!NiloreClient.isReady()) return false;
        if (mc.player.isUsingItem()) return false;
        if (mc.screen != null) return false;
        if (mc.player.isSpectator() || !mc.player.isAlive()) return false;

        Stuck stuck = Stuck.INSTANCE;
        if (stuck != null && stuck.isEnabled()) {
            if (!this.warnedAboutStuck) {
                this.warnedAboutStuck = true;
                logger.warn("AutoGapple: Stuck is enabled — it swallows the outgoing use packet, so eating is paused.");
            }
            return false;
        }

        // 主手所有权。这个模块独占主手的服务端槽位长达 40 拍 (Hotbar 模式更是靠主手进食),
        // 只在没人占用时启动:
        //  - 珍珠窗口(几拍)不撞: MidPearl 是按键触发的即时意图, 而且它反过来会 interrupt 我们。
        //  - 投掷窗口(1拍)不撞: 等它还回来。
        //  - Scaffold 长期持有主手槽位: 进食期间它每次 useItemOn 都会把 SET_CARRIED_ITEM
        //    打回方块槽, 和本模块的槽位收发形成逐拍对撞 —— 这正是 BadPacketsA 要抓的
        //    重复槽位包形状, 所以干脆不开, 而不是开着互相打。
        if (MidPearl.isHandActive() || AutoThrow.isSwapping()) return false;
        Scaffold scaffold = Scaffold.INSTANCE;
        if (scaffold != null && scaffold.isEnabled()) {
            if (!this.warnedAboutScaffold) {
                this.warnedAboutScaffold = true;
                logger.warn("AutoGapple: Scaffold is enabled — it owns the main-hand slot continuously; eating is paused while bridging.");
            }
            return false;
        }

        if (this.trigger.is("Health") && mc.player.getHealth() > this.health.getValue().floatValue()) {
            return false;
        }

        if (this.onlyWithKillAura.getValue()) {
            KillAura aura = KillAura.INSTANCE;
            if (aura == null || !aura.isEnabled() || KillAura.target == null) return false;
        }

        this.appleSlot = this.findAppleSlot();
        if (this.appleSlot < 0) return false;

        ItemStack apple = mc.player.getInventory().getItem(this.appleSlot);
        if (mc.player.getCooldowns().isOnCooldown(apple.getItem())) return false;

        this.originalSlot = mc.player.getInventory().selected;
        return true;
    }

    private void beginEat() {
        this.countBefore = this.countApples();
        this.swappedHand = false;
        this.stageTicks = 0;

        // Only send the carried-item packet when it actually changes the server's slot: a
        // duplicate SET_CARRIED_ITEM for the same slot is exactly what Grim's BadPacketsA
        // reports. When the apple already sits in the selected slot we go straight to the use.
        if (this.originalSlot != this.appleSlot) {
            PacketUtil.sendQueued(new ServerboundSetCarriedItemPacket(this.appleSlot));
            this.stage = Stage.SELECT_SLOT;
        } else {
            this.stage = Stage.SELECT_SLOT;
            this.stageTicks = this.packetGap.getValue().intValue();
        }
    }

    private void sendUsePacket() {
        boolean offhand = this.handMode.is("Offhand");
        if (offhand) {
            // Apple goes to the off hand so the main hand keeps the sword for KillAura.
            PacketUtil.sendQueued(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,
                    BlockPos.ZERO, Direction.DOWN));
            this.swappedHand = true;
        }
        InteractionHand hand = offhand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        // Borrowing the client's block-prediction sequence keeps the packet vanilla-shaped.
        PacketUtil.sendPredictiveDirect(sequence -> new ServerboundUseItemPacket(hand, sequence));
        this.stage = Stage.START_USE;
        this.stageTicks = 0;
    }

    private boolean isEatDone() {
        if (this.countApples() < this.countBefore) return true;
        return this.stageTicks >= MAX_EAT_TICKS;
    }

    private void finishEat() {
        // Stop the use, then undo the hand swap. Both packets are distinct types, so sending
        // them in the same tick is not the burst pattern the slot checks look for.
        PacketUtil.sendQueued(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM,
                BlockPos.ZERO, Direction.DOWN));
        if (this.swappedHand) {
            PacketUtil.sendQueued(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,
                    BlockPos.ZERO, Direction.DOWN));
            this.swappedHand = false;
        }
        this.stage = Stage.FINISHED;
        this.stageTicks = 0;
    }

    private void restoreSlot() {
        if (mc.player == null) return;
        int selected = mc.player.getInventory().selected;
        if (this.originalSlot >= 0 && this.originalSlot != selected) {
            PacketUtil.sendQueued(new ServerboundSetCarriedItemPacket(this.originalSlot));
        }
        this.originalSlot = -1;
        this.appleSlot = -1;
    }

    /** 从选槽到槽位恢复完成, 服务端主手/副手归本模块管。 */
    public boolean isBusy() {
        return this.stage != Stage.IDLE;
    }

    /**
     * 外部(目前是 MidPearl 的 claimHand)请求让出双手。
     *
     * <p>abort() 会 RELEASE_USE + 换回副手 + 恢复原槽位, 服务端侧不残留半交换状态。
     * 金苹果没吃完不会消耗, 损失的只是这一次的进食进度。</p>
     */
    public void interrupt() {
        if (this.stage == Stage.IDLE) return;
        this.abort();
    }

    private void abort() {
        if (this.stage != Stage.IDLE && this.stage != Stage.FINISHED) {
            // A use may still be running server side; release it before dropping state.
            PacketUtil.sendQueued(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM,
                    BlockPos.ZERO, Direction.DOWN));
            if (this.swappedHand) {
                PacketUtil.sendQueued(new ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,
                        BlockPos.ZERO, Direction.DOWN));
            }
        }
        this.swappedHand = false;
        this.restoreSlot();
        this.stage = Stage.IDLE;
        this.stageTicks = 0;
    }

    private int nextCooldown() {
        int base = this.interval.getValue().intValue();
        int spread = this.jitter.getValue().intValue();
        if (spread <= 0) return base;
        return base + (int) (Math.random() * (spread + 1));
    }

    /** @return hotbar index holding a golden apple, or {@code -1}. */
    private int findAppleSlot() {
        int fallback = -1;
        for (int slot = 0; slot < 9; slot++) {
            Item item = mc.player.getInventory().getItem(slot).getItem();
            if (item == Items.ENCHANTED_GOLDEN_APPLE) {
                if (this.preferEnchanted.getValue()) return slot;
                fallback = slot;
            } else if (item == Items.GOLDEN_APPLE && fallback < 0) {
                fallback = slot;
            }
        }
        return fallback;
    }

    private int countApples() {
        int total = 0;
        for (int slot = 0; slot < mc.player.getInventory().items.size(); slot++) {
            ItemStack stack = mc.player.getInventory().items.get(slot);
            if (stack.getItem() == Items.GOLDEN_APPLE || stack.getItem() == Items.ENCHANTED_GOLDEN_APPLE) {
                total += stack.getCount();
            }
        }
        ItemStack offhand = mc.player.getInventory().offhand.get(0);
        if (offhand.getItem() == Items.GOLDEN_APPLE || offhand.getItem() == Items.ENCHANTED_GOLDEN_APPLE) {
            total += offhand.getCount();
        }
        return total;
    }
}
