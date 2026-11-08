package client.nilore.modules.impl.combat;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.Random;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.AbstractGolem;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Squid;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import client.nilore.NiloreClient;
import client.nilore.event.EventTarget;
import client.nilore.event.impl.TickEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.modules.impl.movement.Scaffold;
import client.nilore.modules.impl.player.Stuck;
import client.nilore.modules.impl.world.Teams;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.ModeSetting;
import client.nilore.settings.impl.NumberSetting;
import client.nilore.utils.game.RotationUtil;
import client.nilore.utils.rotation.Rotation;
import client.nilore.utils.rotation.RotationHandler;

/**
 * BowAimbot — 拉弓期间把服务端朝向压在弹道解上, 蓄满就放。
 *
 * <h2>反作弊优先的三条底线</h2>
 *
 * <p><b>1. 一次物品包都不伪造。</b>开弓走 {@code mc.options.keyUse.setDown(true)}, 放箭走
 * {@code setDown(false)} —— 让原生 {@code Minecraft.handleKeybinds -> startUseItem ->
 * MultiPlayerGameMode.useItem()} 自己去发 USE_ITEM / RELEASE_USE_ITEM。那条路径自带
 * {@code ensureHasSentCarriedItem()}、自带 sequence、自带 rightClickDelay 节流, 服务端看到的
 * 和你手点鼠标完全同构。任何"自己 new 一个 ServerboundUseItemPacket"的做法都会绕过槽位同步
 * (MidPearl 就踩过这个坑), 不用。
 *
 * <h2>2. 朝向永远落在鼠标灵敏度网格上。</h2>
 * 每一拍给出的朝向都过一遍 {@link RotationUtil#getSensitivitySnappedRotation}: 位移量必须是
 * {@code f^3 * 1.2}(f = 灵敏度*0.6+0.2) 的整数倍。这是真人动鼠标在服务端留下的唯一形状,
 * Grim 的 aim 检查核心就是在验证这个量化关系。不做这一步, 角度再平滑也是小数位移, 一眼假。
 *
 * <h2>3. 不放箭就没有失配。</h2>
 * 放箭发生在 TickEvent 头部, 那一刻服务端手上的朝向是**上一拍** movement 包里的值, 也就是
 * {@link RotationHandler#sentRotation}。所以判定条件比的是"已发出去的朝向 vs 理想弹道朝向",
 * 而不是"这一拍算出来的朝向"。因为拉弓全程都在持续跟踪, 蓄满时两者早已重合 —— 不需要在放箭
 * 瞬间猛甩一下, 那正是最容易被抓的动作。
 *
 * <p>另外 {@link #returnRotation()} 负责收招: 目标丢失或放完箭之后, 不把朝向瞬间弹回玩家镜头
 * (那等于一次大角位移), 而是按 {@code Return Speed} 用几拍走回去, 全程照样过灵敏度对齐。
 *
 * <h2>弹道</h2>
 * 箭不是抛物线闭式解: 每拍 {@code v *= 0.99} 再 {@code v.y -= 0.05}。这里用无阻力闭式解做初值,
 * 再在真实模拟上做 4 次修正迭代收敛 (见 {@link #solvePitch}), 全程 double 运算、零对象分配。
 * 目标预判做两轮外循环: 先用当前位置解一次拿到飞行时间, 再按飞行时间 + ping 补偿推目标位置重解。
 */
public class BowAimbot extends Module {
    public static BowAimbot INSTANCE;

    /** 由 {@code RotationHandler.onTickHigh} 拾取的朝向。 */
    public Rotation targetRotation;

    // ===== 箭的物理常量 (1.20.1 AbstractArrow#tick) =====
    private static final double ARROW_GRAVITY = 0.05;
    private static final double ARROW_DRAG = 0.99;
    /** 满蓄力初速 = power * 3.0。 */
    private static final double ARROW_BASE_SPEED = 3.0;
    private static final int SIM_MAX_TICKS = 300;
    /** 收招判定: 与镜头朝向差小于此角度就算回正完毕。 */
    private static final float RETURN_EPSILON = 0.6f;
    private static final Random RANDOM = new Random();

    // ===== 目标筛选 =====
    public final ModeSetting priority = new ModeSetting("Priority", "Angle", "Distance", "Health")
            .withDefault("Angle");
    public final NumberSetting range = new NumberSetting("Range", 32.0, 3.0, 96.0, 1.0);
    public final NumberSetting fov = new NumberSetting("FoV", 120.0, 10.0, 360.0, 1.0);
    public final BooleanSetting attackPlayers = new BooleanSetting("Attack Players", true);
    public final BooleanSetting attackMobs = new BooleanSetting("Attack Mobs", false);
    public final BooleanSetting attackAnimals = new BooleanSetting("Attack Animals", false);
    public final BooleanSetting attackInvisible = new BooleanSetting("Attack Invisible", false);
    public final BooleanSetting ignoreTeams = new BooleanSetting("Ignore Teams", true);
    public final BooleanSetting onlyVisible = new BooleanSetting("Only Visible", true);

    // ===== 朝向 (反作弊核心参数) =====
    public final NumberSetting aimSpeed = new NumberSetting("Aim Speed", 22.0, 1.0, 180.0, 1.0);
    public final NumberSetting deadZone = new NumberSetting("Dead Zone", 0.9, 0.0, 5.0, 0.1);
    public final NumberSetting jitter = new NumberSetting("Jitter", 0.25, 0.0, 2.0, 0.05);
    public final BooleanSetting sensitivitySnap = new BooleanSetting("Sensitivity Snap", true);
    public final NumberSetting returnSpeed = new NumberSetting("Return Speed", 35.0, 5.0, 90.0, 1.0);

    // ===== 弹道 =====
    public final BooleanSetting aimHead = new BooleanSetting("Aim Head", true);
    public final NumberSetting predict = new NumberSetting("Predict", 1.0, 0.0, 2.0, 0.05);
    public final NumberSetting pingComp = new NumberSetting("Ping Comp (ms)", 0, 0, 300, 10);

    // ===== 放箭 =====
    public final BooleanSetting autoDraw = new BooleanSetting("Auto Draw", true);
    public final NumberSetting minPower = new NumberSetting("Min Power", 1.0, 0.1, 1.0, 0.05);
    public final NumberSetting releaseAngle = new NumberSetting("Release Angle", 2.5, 0.0, 15.0, 0.1);
    public final NumberSetting shotCooldown = new NumberSetting("Shot Cooldown", 3, 0, 40, 1);
    public final BooleanSetting stopWhenStuck = new BooleanSetting("Bail On Stuck", true);

    private Entity currentTarget;
    private int cooldown;
    private int noTargetTicks;
    /** 弓是我们帮忙拉开的 (而不是玩家自己按着)。 */
    private boolean ownsDraw;
    /** 放开按键后原生没有放箭的兜底计时; 0 表示不跟踪。 */
    private int releaseWatchdog;

    // 无分配模拟的回传值 (solvePitch 内部写, 外部读)
    private double simMiss;
    private int simTicks;

    public BowAimbot() {
        super("BowAimbot", Category.COMBAT);
        INSTANCE = this;
    }

    @Override
    protected void onEnable() {
        this.reset();
        super.onEnable();
    }

    @Override
    protected void onDisable() {
        // 关模块时把手里的弓放下, 别把玩家卡在"一直举着"的状态里。
        if (this.ownsDraw && mc.options != null) {
            mc.options.keyUse.setDown(false);
        }
        this.reset();
        super.onDisable();
    }

    private void reset() {
        this.targetRotation = null;
        this.currentTarget = null;
        this.cooldown = 0;
        this.noTargetTicks = 0;
        this.ownsDraw = false;
        this.releaseWatchdog = 0;
    }

    @Override
    public String getSuffix() {
        if (this.currentTarget != null) {
            return "Locked";
        }
        return this.ownsDraw ? "Draw" : "";
    }

    // ===== 主循环 =====

    @EventTarget
    public void onTick(TickEvent event) {
        if (!NiloreClient.isReady() || mc.player == null || mc.level == null || mc.gameMode == null) {
            this.reset();
            return;
        }
        if (this.cooldown > 0) {
            --this.cooldown;
        }
        this.tickReleaseWatchdog();

        // 界面打开 / 卡住模块启用时完全不介入: Stuck 会取消出站 USE_ITEM, 弓根本蓄不满。
        if (mc.screen != null
                || (this.stopWhenStuck.getValue() && Stuck.INSTANCE != null && Stuck.INSTANCE.isEnabled())
                || (Scaffold.INSTANCE != null && Scaffold.INSTANCE.isEnabled())) {
            this.releaseDraw();
            this.returnRotation();
            return;
        }

        ItemStack main = mc.player.getMainHandItem();
        if (!(main.getItem() instanceof BowItem)) {
            this.currentTarget = null;
            this.returnRotation();
            return;
        }

        boolean drawing = mc.player.isUsingItem() && mc.player.getUseItem().getItem() instanceof BowItem;
        if (!drawing && this.ownsDraw) {
            this.ownsDraw = false;
        }

        Entity target = this.pickTarget();
        this.currentTarget = target;

        if (target == null) {
            ++this.noTargetTicks;
            // 自己拉开的弓, 空拉 20 拍就放下 —— 一直举着弓既不自然也没意义。
            if (this.ownsDraw && this.noTargetTicks > 20) {
                this.releaseDraw();
            }
            this.returnRotation();
            return;
        }
        this.noTargetTicks = 0;

        if (!drawing) {
            if (this.autoDraw.getValue() && !this.isUseKeyPhysicallyDown()) {
                mc.options.keyUse.setDown(true);
                this.ownsDraw = true;
            }
            this.returnRotation();
            return;
        }

        // 已有蓄力: 持续跟踪 + 择机放箭
        float power = this.currentPower(main);
        Rotation aim = this.solveAim(target, power);
        if (aim == null) {
            this.returnRotation();
            return;
        }
        this.targetRotation = this.stepToward(aim, this.aimSpeed.getValue().doubleValue(), true);

        if (this.shouldRelease(target, aim, power)) {
            this.releaseDraw();
        }
    }

    private void tickReleaseWatchdog() {
        if (this.releaseWatchdog <= 0) {
            return;
        }
        if (this.isUseKeyPhysicallyDown()) {
            // 玩家自己按着右键, 重新拉起是玩家的意图, 不要替他放。
            this.releaseWatchdog = 0;
            return;
        }
        if (!mc.player.isUsingItem()) {
            this.releaseWatchdog = 0;
            return;
        }
        if (--this.releaseWatchdog == 0 && mc.player.isUsingItem()) {
            // 已经等了 4 拍原生还没放: 说明它没接这个键位状态, 自己补一发。
            // 此时原生确定没发过 RELEASE_USE_ITEM, 所以不存在重复包。
            mc.gameMode.releaseUsingItem(mc.player);
        }
    }

    /**
     * 是否可以放箭。
     *
     * <p>关键在 {@link #aimError}: 比的是 {@code sentRotation} (服务端手上已经有的朝向) 和理想
     * 弹道朝向。放箭包在 TickEvent 头部发出, 服务端此刻用的就是上一拍 movement 包里的角度,
     * 所以只有"已发出的"够准, 箭才真的打得中。
     */
    private boolean shouldRelease(Entity target, Rotation aim, float power) {
        if (this.cooldown > 0) {
            return false;
        }
        if (power < this.minPower.getValue().floatValue()) {
            return false;
        }
        if (this.aimError(aim) > this.releaseAngle.getValue().floatValue()) {
            return false;
        }
        if (this.onlyVisible.getValue() && !this.hasLineOfSight(target)) {
            return false;
        }
        return this.hasAmmo(mc.player.getMainHandItem());
    }

    private void releaseDraw() {
        if (mc.options == null) {
            return;
        }
        mc.options.keyUse.setDown(false);
        if (this.ownsDraw || this.releaseWatchdog > 0) {
            this.releaseWatchdog = 4;
        }
        this.ownsDraw = false;
        this.cooldown = this.shotCooldown.getValue().intValue();
    }

    // ===== 朝向 =====

    /** {@code sentRotation} 与理想朝向的角距离 (度)。 */
    private float aimError(Rotation want) {
        Rotation sent = RotationHandler.sentRotation;
        if (sent == null) {
            sent = new Rotation(mc.player.getYRot(), mc.player.getXRot());
        }
        float dy = Mth.wrapDegrees(want.getYaw() - sent.getYaw());
        float dp = want.getPitch() - sent.getPitch();
        return (float) Math.sqrt(dy * dy + dp * dp);
    }

    /**
     * 朝理想朝向走一拍。
     *
     * <p>{@code holdWhenClose} 为真且已在死区内时直接维持已发朝向 —— 真人不会每拍都抠 0.05 度,
     * 持续微调反而是"完美跟踪"的特征。
     */
    private Rotation stepToward(Rotation want, double maxStep, boolean holdWhenClose) {
        Rotation sent = RotationHandler.sentRotation;
        if (sent == null) {
            sent = new Rotation(mc.player.getYRot(), mc.player.getXRot());
        }
        float dy = Mth.wrapDegrees(want.getYaw() - sent.getYaw());
        float dp = want.getPitch() - sent.getPitch();
        double dist = Math.sqrt(dy * dy + dp * dp);

        if (holdWhenClose && dist <= this.deadZone.getValue().doubleValue()) {
            return new Rotation(sent.getYaw(), sent.getPitch());
        }
        if (maxStep > 0.0 && dist > maxStep) {
            float k = (float) (maxStep / dist);
            dy *= k;
            dp *= k;
        }
        float yaw = sent.getYaw() + dy;
        float pitch = Mth.clamp(sent.getPitch() + dp, -90.0f, 90.0f);

        if (holdWhenClose) {
            float j = this.jitter.getValue().floatValue();
            if (j > 0.0f) {
                yaw += (RANDOM.nextFloat() - 0.5f) * j;
                pitch = Mth.clamp(pitch + (RANDOM.nextFloat() - 0.5f) * j * 0.6f, -90.0f, 90.0f);
            }
        }
        if (this.sensitivitySnap.getValue()) {
            Rotation snapped = RotationUtil.getSensitivitySnappedRotation(
                    yaw, pitch, sent.getYaw(), sent.getPitch());
            if (snapped != null) {
                yaw = snapped.getYaw();
                pitch = Mth.clamp(snapped.getPitch(), -90.0f, 90.0f);
            }
        }
        return new Rotation(yaw, pitch);
    }

    /**
     * 收招: 把服务端朝向慢慢送回玩家真实镜头, 而不是一拍弹回去。
     *
     * <p>不停摆直接清空 {@code targetRotation} 的话, 下一拍 {@code RotationHandler} 会把玩家镜头
     * 朝向原样发出去 —— 服务端看到的就是"瞄准了 N 拍, 然后单拍位移几十上百列"。和 KillAura
     * 投掷停摆那次修的是同一类问题。
     */
    private void returnRotation() {
        Rotation sent = RotationHandler.sentRotation;
        if (sent == null) {
            this.targetRotation = null;
            return;
        }
        float dy = Mth.wrapDegrees(mc.player.getYRot() - sent.getYaw());
        float dp = mc.player.getXRot() - sent.getPitch();
        if (Math.sqrt(dy * dy + dp * dp) <= RETURN_EPSILON) {
            this.targetRotation = null;
            return;
        }
        this.targetRotation = this.stepToward(
                new Rotation(mc.player.getYRot(), mc.player.getXRot()),
                this.returnSpeed.getValue().doubleValue(), false);
    }

    // ===== 弹道解算 =====

    /**
     * 解出命中所需朝向。两轮外循环处理目标移动: 第一轮按当前位置算飞行时间, 第二轮按
     * 飞行时间 + ping 推位置重解。
     */
    private Rotation solveAim(Entity target, float power) {
        Vec3 eye = mc.player.getEyePosition();
        double speed = ARROW_BASE_SPEED * Math.max(power, 0.05f);
        double flight = 0.0;
        float yaw = 0.0f;
        float pitch = 0.0f;

        for (int pass = 0; pass < 2; pass++) {
            Vec3 pos = this.predictPosition(target, flight);
            double aimY = this.aimHead.getValue()
                    ? pos.y + target.getEyeHeight() * 0.85
                    : pos.y + target.getBbHeight() * 0.5;
            double dx = pos.x - eye.x;
            double dz = pos.z - eye.z;
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            if (horizontal < 0.5) {
                return null;
            }
            yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
            pitch = (float) this.solvePitch(horizontal, aimY - eye.y, speed);
            flight = this.simTicks;
        }
        return new Rotation(Mth.wrapDegrees(yaw), Mth.clamp(pitch, -90.0f, 90.0f));
    }

    private Vec3 predictPosition(Entity target, double flightTicks) {
        double scale = this.predict.getValue().doubleValue();
        if (scale <= 0.0) {
            return target.position();
        }
        // ping 折半: 补偿的是"我们看到的已经是 RTT/2 之前的位置"。
        double ticks = Math.max(0.0, flightTicks) + this.pingComp.getValue().doubleValue() / 50.0;
        if (ticks <= 0.0) {
            return target.position();
        }
        Vec3 delta = target.getDeltaMovement();
        return new Vec3(
                target.getX() + delta.x * ticks * scale,
                target.getY() + delta.y * ticks * scale,
                target.getZ() + delta.z * ticks * scale);
    }

    /**
     * 低弹道俯仰角。
     *
     * <p>初值取忽略阻力的闭式解 {@code tan = (v² - sqrt(v⁴ - g(g·d² + 2·y·v²))) / (g·d)}
     * (取负号那支 = 平射), 然后拿真实模拟的落点误差反向修正 4 次。阻力 0.99 下平射误差通常在
     * 1~2 度内, 4 次足够收敛。判别式为负说明超出射程, 返回 45 度让箭尽量飞远 (调用方的
     * {@code releaseAngle} 会因为打不准而不放箭)。
     */
    private double solvePitch(double horizontal, double vertical, double speed) {
        double g = ARROW_GRAVITY;
        double v2 = speed * speed;
        double disc = v2 * v2 - g * (g * horizontal * horizontal + 2.0 * vertical * v2);
        if (disc < 0.0 || horizontal <= 0.0) {
            this.simMiss = Double.NaN;
            this.simTicks = 0;
            return 45.0;
        }
        double pitch = Math.toDegrees(Math.atan((v2 - Math.sqrt(disc)) / (g * horizontal)));

        for (int i = 0; i < 4; i++) {
            this.simulate(pitch, horizontal, speed);
            if (Double.isNaN(this.simMiss)) {
                break;
            }
            if (Math.abs(this.simMiss) < 0.02) {
                break;
            }
            // 高度差折算成角度修正量, 相当于在落点误差上的牛顿步。
            pitch = Mth.clamp(pitch + Math.toDegrees(Math.atan2(this.simMiss, horizontal)), -89.0, 89.0);
        }
        // 最后一次模拟的飞行时间用于下一轮位置预判。
        this.simulate(pitch, horizontal, speed);
        return pitch;
    }

    /** 模拟一条箭: 逐拍 {@code pos += v; v *= drag; v.y -= g}, 记录穿过水平距离时的高度误差。 */
    private void simulate(double pitchDeg, double horizontal, double speed) {
        double pitchRad = Math.toRadians(pitchDeg);
        double vh = speed * Math.cos(pitchRad);
        double vy = -speed * Math.sin(pitchRad);
        double travelled = 0.0;
        double height = 0.0;
        for (int tick = 1; tick <= SIM_MAX_TICKS; tick++) {
            double prevTravelled = travelled;
            travelled += vh;
            height += vy;
            vh *= ARROW_DRAG;
            vy = vy * ARROW_DRAG - ARROW_GRAVITY;
            if (travelled >= horizontal) {
                double span = travelled - prevTravelled;
                double frac = span <= 1.0e-9 ? 1.0 : (horizontal - prevTravelled) / span;
                // 用本拍位移回插到刚好穿过水平距离的那一刻。
                this.simMiss = height - vy * (1.0 - frac);
                this.simTicks = tick;
                return;
            }
            if (height < -512.0) {
                break;
            }
        }
        this.simMiss = Double.NaN;
        this.simTicks = SIM_MAX_TICKS;
    }

    // ===== 蓄力 / 弹药 =====

    /** 复刻 {@code BowItem.getPowerForTime}: (t/20)² + 2(t/20) / 3, 封顶 1。 */
    private float currentPower(ItemStack bow) {
        int remaining = mc.player.getUseItemRemainingTicks();
        int used = Math.max(0, bow.getUseDuration() - remaining);
        float t = used / 20.0f;
        return Math.min((t * t + t * 2.0f) / 3.0f, 1.0f);
    }

    private boolean hasAmmo(ItemStack bow) {
        if (mc.player.isCreative()) {
            return true;
        }
        if (EnchantmentHelper.getItemEnchantmentLevel(Enchantments.INFINITY_ARROWS, bow) > 0) {
            return true;
        }
        for (int slot = 0; slot < mc.player.getInventory().getContainerSize(); slot++) {
            if (mc.player.getInventory().getItem(slot).getItem() instanceof ArrowItem) {
                return true;
            }
        }
        return false;
    }

    private boolean hasLineOfSight(Entity target) {
        return mc.player.hasLineOfSight(target);
    }

    /** 玩家是不是**真的**按着右键 (绕过我们自己 setDown 造成的假状态)。 */
    private boolean isUseKeyPhysicallyDown() {
        if (mc.getWindow() == null) {
            return false;
        }
        InputConstants.Key bound = InputConstants.getKey(mc.options.keyUse.saveString());
        long window = mc.getWindow().getWindow();
        return bound.getType() == InputConstants.Type.MOUSE
                ? GLFW.glfwGetMouseButton(window, bound.getValue()) == 1
                : InputConstants.isKeyDown(window, bound.getValue());
    }

    // ===== 目标筛选 =====

    private Entity pickTarget() {
        Entity best = null;
        double bestScore = Double.MAX_VALUE;
        double rangeValue = this.range.getValue().doubleValue();
        double rangeSq = rangeValue * rangeValue;
        double fovValue = this.fov.getValue().doubleValue();

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!this.isValidTarget(entity, rangeSq, fovValue)) {
                continue;
            }
            double score;
            if (this.priority.is("Angle")) {
                Vec3 eye = mc.player.getEyePosition();
                float targetYaw = (float) (Math.toDegrees(Math.atan2(
                        entity.getZ() - eye.z, entity.getX() - eye.x)) - 90.0);
                score = RotationUtil.angleDiff(mc.player.getYRot(), targetYaw);
            } else if (this.priority.is("Health")) {
                LivingEntity living = (LivingEntity) entity;
                score = living.getHealth() + living.getAbsorptionAmount();
            } else {
                score = entity.distanceToSqr(mc.player);
            }
            if (score < bestScore) {
                bestScore = score;
                best = entity;
            }
        }
        return best;
    }

    private boolean isValidTarget(Entity entity, double rangeSq, double fovValue) {
        if (!(entity instanceof LivingEntity living) || entity == mc.player) {
            return false;
        }
        if (living.isDeadOrDying() || living.getHealth() <= 0.0f) {
            return false;
        }
        if (entity.isInvisible() && !this.attackInvisible.getValue()) {
            return false;
        }
        if (this.ignoreTeams.getValue() && Teams.isSameTeam(entity)) {
            return false;
        }
        AntiBots bots = AntiBots.INSTANCE;
        if (bots != null && bots.isEnabled() && (AntiBots.isBot(entity) || AntiBots.isBedWarsBot(entity))) {
            return false;
        }
        if (entity instanceof Player player) {
            if (!this.attackPlayers.getValue() || player.isSpectator() || player.isSleeping()) {
                return false;
            }
        } else {
            boolean mob = entity instanceof Mob || entity instanceof Slime
                    || entity instanceof Bat || entity instanceof AbstractGolem;
            boolean animal = entity instanceof Animal || entity instanceof Squid || entity instanceof Villager;
            if (mob && !this.attackMobs.getValue()) {
                return false;
            }
            if (animal && !this.attackAnimals.getValue()) {
                return false;
            }
            if (!mob && !animal) {
                return false;
            }
        }
        if (entity.distanceToSqr(mc.player) > rangeSq) {
            return false;
        }
        if (!RotationUtil.isEntityInFov(entity, fovValue)) {
            return false;
        }
        return !this.onlyVisible.getValue() || this.hasLineOfSight(entity);
    }
}
