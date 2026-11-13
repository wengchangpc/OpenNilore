package client.nilore.modules.impl.player;

import java.util.Comparator;
import java.util.Optional;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import client.nilore.event.impl.TickEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.modules.impl.combat.AutoGapple;
import client.nilore.modules.impl.combat.AutoThrow;
import client.nilore.modules.impl.combat.KillAura;
import client.nilore.modules.impl.misc.AutoRod;
import client.nilore.modules.impl.movement.FireballBlink;
import client.nilore.modules.impl.render.Projectiles;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.ModeSetting;
import client.nilore.utils.animation.Timer;
import client.nilore.utils.game.ItemUtil;
import client.nilore.utils.game.RotationUtil;
import client.nilore.utils.misc.PacketUtil;
import client.nilore.utils.rotation.Rotation;
import client.nilore.event.EventTarget;

public class MidPearl extends Module {
    public static MidPearl INSTANCE;
    public static Rotation targetRotation;

    /**
     * 投掷窗口让 KillAura 停摆的拍数。为什么要 3 拍:
     *
     * <p>use 包本身不带朝向, 服务端用的是"上一拍收到的 rotation"。光环的静默转向是在
     * {@code MotionEvent} POST 里发出去的, 所以松手这一拍 (T) 必须已经开始停摆, 才能保证
     * 服务端在 T+1 处理 use 包时手上的朝向还是玩家的准心。T 拍的 movement 包 + T+1 拍
     * 的出刀 + T+1 拍珍珠离手, 三拍刚好盖住。
     */
    private static final int AURA_PAUSE_TICKS = 3;

    private final ModeSetting mainButtonSetting = new ModeSetting("Button", "Middle", "Mouse 4", "Mouse 5").withDefault("Middle");
    private final BooleanSetting interceptSetting = new BooleanSetting("Intercept", false);
    private final ModeSetting interceptButtonSetting = new ModeSetting("Intercept Button", "Mouse 4", "Mouse 5").withDefault("Mouse 4");
    private final BooleanSetting holdModeSetting = new BooleanSetting("On Release", false);
    private final BooleanSetting losCheckSetting = new BooleanSetting("Block Check", true);
    private final BooleanSetting pauseAuraSetting = new BooleanSetting("Pause Aura", true);
    private final Timer throwTimer = new Timer();
    private boolean isHoldingIntercept = false;
    private boolean isHoldingButton = false;
    private boolean pearlSlotSelected = false;
    private int throwCountdown = 0;
    private int disableDelay = 0;
    private int pearlSavedSlot = -1;
    private boolean throwPhaseActive = false;
    private int preThrowTicks = 0;
    private int rodSavedSlot = -1;
    private int postThrowTicks = 0;

    public MidPearl() {
        super("MidPearl", Category.PLAYER);
        INSTANCE = this;
    }

    @Override
    public void onEnable() {
        this.reset();
    }

    @Override
    public void onDisable() {
        if (this.pearlSavedSlot != -1 && mc.player != null) {
            mc.player.getInventory().selected = this.pearlSavedSlot;
        }
        if (this.rodSavedSlot != -1 && mc.player != null) {
            mc.player.getInventory().selected = this.rodSavedSlot;
        }
        this.reset();
    }

    private void reset() {
        this.pearlSlotSelected = false;
        this.throwCountdown = 0;
        this.disableDelay = 0;
        this.pearlSavedSlot = -1;
        this.throwPhaseActive = false;
        targetRotation = null;
        this.preThrowTicks = 0;
        this.isHoldingIntercept = false;
        this.isHoldingButton = false;
        this.rodSavedSlot = -1;
        this.postThrowTicks = 0;
    }

    private void cancelThrow() {
        this.throwPhaseActive = false;
        targetRotation = null;
        this.preThrowTicks = 0;
        this.isHoldingIntercept = false;
    }

    /**
     * 主手当前是否被本模块占用: 从按下选槽到槽位完全恢复的整个窗口。
     *
     * <p>Scaffold 的 onTick 是 HIGH 优先级、每拍最先跑, 靠这个标志给珍珠让路;
     * AutoGapple 靠它拒绝在窗口中间开吃。判据是"本模块还有恢复动作没做完",
     * 而不是 enabled —— 光开着没按按钮不占手。</p>
     */
    public static boolean isHandActive() {
        MidPearl instance = INSTANCE;
        if (instance == null || !instance.isEnabled()) {
            return false;
        }
        return instance.throwPhaseActive || instance.pearlSlotSelected
                || instance.throwCountdown > 0 || instance.disableDelay > 0
                || instance.postThrowTicks > 0;
    }

    /**
     * 拿主手之前清场。
     *
     * <p>优先级规则: 珍珠是按键触发的即时意图, 窗口只有几拍; 吃苹果窗口 40 拍,
     * 打断它代价小(金苹果没吃完不消耗, 只是这次作废)且收益明确 —— 所以
     * AutoGapple 被抢占。AutoThrow 的窗口只有 1 拍, 抢它反而更容易撞车,
     * 等一拍再继续。</p>
     *
     * @return false = 本拍拿不到手, 调用方等下一拍重试
     */
    private boolean claimHand() {
        AutoGapple autoGapple = AutoGapple.INSTANCE;
        if (autoGapple != null && autoGapple.isBusy()) {
            autoGapple.interrupt();
        }
        return !AutoThrow.isSwapping();
    }

    /**
     * 投掷瞬间让 KillAura 停摆, 而不是把它关掉。
     *
     * <p>旧实现在选中珍珠槽时 {@code KillAura.INSTANCE.setEnabled(false)}: 目标列表、APS 蓄力、
     * 朝向全部清零, 扔完还得手动开回来, 中间被人贴脸就是白挨一刀; 手速慢一点甚至会出现
     * "珍珠还没扔出去光环先没了"。现在只在投掷窗口吞掉出刀与朝向接管。
     */
    private void pauseAura(int ticks) {
        if (!this.pauseAuraSetting.getValue()) {
            return;
        }
        KillAura.pauseFor(ticks);
    }

    private int getMouseButton(ModeSetting setting) {
        return switch (setting.getValue()) {
            case "Mouse 4" -> 3;
            case "Mouse 5" -> 4;
            default -> 2;
        };
    }

    public boolean isInterceptButton(int button) {
        return this.interceptSetting.getValue()
                && this.getMouseButton(this.interceptButtonSetting) == button
                && this.hasTargetRotation();
    }

    private boolean isAutoRodActive() {
        AutoRod autoRod = AutoRod.INSTANCE;
        return autoRod != null && autoRod.isEnabled()
                && autoRod.shouldInterceptButton(this.getMouseButton(this.mainButtonSetting));
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (FireballBlink.INSTANCE != null && FireballBlink.INSTANCE.isEnabled()) return;
        if (mc.player == null || mc.level == null) {
            if (this.throwPhaseActive) this.reset();
            return;
        }
        if (this.postThrowTicks > 0) {
            --this.postThrowTicks;
            if (this.postThrowTicks == 0 && this.rodSavedSlot != -1) {
                mc.player.getInventory().selected = this.rodSavedSlot;
                this.rodSavedSlot = -1;
            }
        }
        if (this.disableDelay > 0) {
            --this.disableDelay;
            if (this.disableDelay == 0) {
                if (this.pearlSavedSlot != -1) {
                    mc.player.getInventory().selected = this.pearlSavedSlot;
                }
                this.reset();
            }
            return;
        }
        if (this.throwPhaseActive) {
            if (this.preThrowTicks > 0) --this.preThrowTicks;
            if (this.preThrowTicks == 0) this.throwPearl();
            return;
        }
        if (this.interceptSetting.getValue()) {
            boolean pressed = GLFW.glfwGetMouseButton(mc.getWindow().getWindow(),
                    this.getMouseButton(this.interceptButtonSetting)) == 1;
            if (pressed) {
                if (this.isHoldingButton) return;
            } else {
                this.isHoldingButton = false;
            }
            if (this.holdModeSetting.getValue()) {
                if (pressed) {
                    this.updateTargetRotation();
                    this.isHoldingIntercept = true;
                } else {
                    if (this.isHoldingIntercept) {
                        if (targetRotation != null) {
                            this.selectPearlSlot(ItemUtil.getSlot(Items.ENDER_PEARL));
                        } else {
                            this.cancelThrow();
                        }
                    }
                    this.isHoldingIntercept = false;
                }
            } else if (pressed) {
                this.updateTargetRotation();
                if (targetRotation != null) {
                    this.selectPearlSlot(ItemUtil.getSlot(Items.ENDER_PEARL));
                }
            } else {
                this.cancelThrow();
            }
            boolean sameButton = this.getMouseButton(this.interceptButtonSetting) == this.getMouseButton(this.mainButtonSetting);
            if (sameButton && (pressed || this.isHoldingIntercept)) return;
        }
        if (this.isAutoRodActive()) return;
        this.tickNormalMode();
    }

    private void updateTargetRotation() {
        targetRotation = this.calculateTargetRotation().orElse(null);
    }

    private boolean hasTargetRotation() {
        return this.calculateTargetRotation().isPresent();
    }

    private Optional<Rotation> calculateTargetRotation() {
        Optional<Projectiles.ProjectileEntry> projectile = this.findNearestEnemyPearl();
        if (projectile.isPresent()) {
            int slot = ItemUtil.getSlot(Items.ENDER_PEARL);
            if (slot != -1 && slot < 9) {
                Vec3 velocity = projectile.get().getVelocity();
                if (this.losCheckSetting.getValue()
                        && mc.level.clip(new ClipContext(
                                mc.player.getEyePosition(), velocity,
                                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                                mc.player)).getType() != HitResult.Type.MISS) {
                    return Optional.empty();
                }
                float[] angles = RotationUtil.getBallisticAngles(velocity);
                if (angles != null) {
                    return Optional.of(new Rotation(angles[0], angles[1]));
                }
            }
        }
        return Optional.empty();
    }

    private void selectPearlSlot(int slot) {
        if (slot == -1 || slot > 8) return;
        if (this.throwPhaseActive) return;
        // 手上有别的短窗口主人时这一拍先不抢, 按键还按着下一拍会重试。
        if (!this.claimHand()) return;
        this.throwPhaseActive = true;
        this.preThrowTicks = 2;
        this.rodSavedSlot = mc.player.getInventory().selected;
        mc.player.getInventory().selected = slot;
    }

    private void throwPearl() {
        // 拦截模式下朝向由 targetRotation 驱动, 这里只是顺手把光环的出刀也吞掉, 避免同拍双击。
        this.pauseAura(2);
        if (mc.player.getMainHandItem().getItem() == Items.ENDER_PEARL) {
            PacketUtil.sendPredictive(n -> new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, n));
            mc.player.swing(InteractionHand.MAIN_HAND);
        }
        this.postThrowTicks = 2;
        this.throwPhaseActive = false;
        this.preThrowTicks = -1;
        this.isHoldingButton = true;
    }

    private void tickNormalMode() {
        if (this.throwCountdown > 0) {
            --this.throwCountdown;
            if (this.throwCountdown == 0) {
                if (mc.player.getMainHandItem().getItem() == Items.ENDER_PEARL) {
                    PacketUtil.sendPredictive(n -> new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, n));
                    mc.player.swing(InteractionHand.MAIN_HAND);
                    // 兜一层: 前面若被别的模块打断, 出包这一拍也要保证光环是停的。
                    this.pauseAura(2);
                }
                this.disableDelay = 2;
            }
            return;
        }
        boolean pressed = GLFW.glfwGetMouseButton(mc.getWindow().getWindow(),
                this.getMouseButton(this.mainButtonSetting)) == 1;
        if (pressed) {
            if (!this.pearlSlotSelected) {
                int slot = ItemUtil.getSlot(Items.ENDER_PEARL);
                if (slot != -1 && slot < 9 && this.claimHand()) {
                    this.pearlSlotSelected = true;
                    this.pearlSavedSlot = mc.player.getInventory().selected;
                    // 原来这里 KillAura.INSTANCE.setEnabled(false) —— 松手前光环照常打。
                    mc.player.getInventory().selected = slot;
                }
            }
        } else if (this.pearlSlotSelected) {
            this.pearlSlotSelected = false;
            this.throwCountdown = 1;
            // 松手这一拍就开始停摆: use 包带的是上一拍服务端朝向, 早一拍停手珍珠才朝准心飞。
            this.pauseAura(AURA_PAUSE_TICKS);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Optional<Projectiles.ProjectileEntry> findNearestEnemyPearl() {
        if (Projectiles.projectileMap.isEmpty()) return Optional.empty();
        return Projectiles.projectileMap.entrySet().stream()
                .filter(entry -> {
                    if (mc.level == null || mc.player == null) return false;
                    Entity entity = mc.level.getEntity(entry.getKey());
                    if (!(entity instanceof ThrownEnderpearl pearl)) return false;
                    Entity owner = pearl.getOwner();
                    return owner != null && !owner.equals(mc.player);
                })
                .map(e -> e.getValue())
                .min(Comparator.comparingDouble(
                        entry -> entry.getVelocity().distanceTo(mc.player.position())));
    }
}
