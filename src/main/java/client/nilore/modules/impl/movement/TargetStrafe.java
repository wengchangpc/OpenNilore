package client.nilore.modules.impl.movement;

import net.minecraft.world.entity.Entity;
import client.nilore.event.impl.MotionEvent;
import client.nilore.event.impl.SneakEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.modules.impl.combat.KillAura;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.NumberSetting;
import client.nilore.utils.animation.Timer;
import client.nilore.utils.game.MovementUtil;
import client.nilore.utils.rotation.Rotation;
import client.nilore.event.EventTarget;

public class TargetStrafe
extends Module {
    public static TargetStrafe INSTANCE;
    private final BooleanSetting smartStrafe = new BooleanSetting("Jump Key Only", true);
    private final NumberSetting range = new NumberSetting("Range", 0.65, 0.6f, 2.0f, 0.05f);
    private final NumberSetting switchDelay = new NumberSetting("Switch Delay", 1000, 100, 5000, 100);
    /** 固定 +1：始终顺时针绕，不再按碰撞翻方向。 */
    public static int strafeDirectionSign;
    public static Entity strafeTarget;
    private final Timer switchTimer = new Timer();

    /**
     * 物理上能贴住的最小绕圈半径：双方半宽各 0.3，再留 0.05 余量。
     *
     * <p>默认 Range 是 0.5，比这个值还小 —— 0.5 的圈等于让玩家站进目标的碰撞箱里，
     * 每 tick 都在互相推挤。这里只做内部钳制，不动设置项的上下限。
     */
    private static final double MIN_ORBIT_RADIUS = 0.6;

    /**
     * 径向误差 → 径向分量权重。
     *
     * <p>以前这个修正基本看不到效果，原因不在增益：输入方向被量化到 8 个 45° 组合，
     * 十几度的径向偏角整个被吃掉（见 {@code MovementUtil.handleStrafe}）。现在方向是连续
     * 角度，增益才真正起作用 —— 误差 0.3 格偏 13°，0.75 格以上吃满 31°，几 tick 就能收拢。
     */
    private static final double RADIAL_GAIN = 0.8;
    /** 径向分量上限（tan 值），0.6 ≈ 31°。越大收半径越快，轨迹越不像正圆。 */
    private static final double RADIAL_LIMIT = 0.6;
    /** 径向低通：8 向量化每 tick 会把方向抖出 20 多度，直接跟会被带着反号。 */
    private static final double RADIAL_SMOOTHING = 0.25;
    /** 朝向低通：让绕圈方向连续变化，而不是每 tick 重新指一个方向。 */
    private static final double STEER_SMOOTHING = 0.35;

    /**
     * 径向修正的滞回带（格）。
     *
     * <p>输入只能是 8 个方向，径向修正的最小粒度就是 45°（forward 取 ±1）。没有滞回带
     * 的话，距离在阈值附近每 tick 都会在"纯切向 (0,±1)"和"45° (1,±1)"之间来回切，
     * 轨迹反而更抖。带上滞回，半径在 deadband 内就保持正圆。
     */
    private static final double RADIAL_DEADBAND = 0.2;

    private static Entity biasTarget;
    private static double radialBias;
    /** 本 tick 的径向步进：+1 朝目标收、-1 向外让、0 纯切向。 */
    private static int radialStep;
    /** 平滑后的绕圈朝向（弧度），给 CollisionSpeed 的注入方向用。 */
    private static double steerYawRad;
    /** 每 tick 只算一次的缓存，两个消费方拿同一个结果。 */
    private static long orbitTick = Long.MIN_VALUE;
    private static double orbitSteerYaw;
    private static double orbitTangentYaw;

    public TargetStrafe() {
        super("TargetStrafe", Category.MOVEMENT);
        INSTANCE = this;
    }

    public static float getRange() {
        return TargetStrafe.INSTANCE.range.getValue().floatValue();
    }

    public static boolean isSmartStrafe() {
        return TargetStrafe.INSTANCE.smartStrafe.getValue();
    }

    /**
     * 绕圈是否生效。MovementUtil 与 CollisionSpeed 都走这一个判断，避免两处条件漂移。
     */
    public static boolean isOrbiting() {
        return INSTANCE != null
                && INSTANCE.isEnabled()
                && strafeTarget != null
                && (!isSmartStrafe() || mc.options.keyJump.isDown());
    }

    /**
     * 本 tick 该朝哪个世界 yaw 走（弧度），含径向修正 —— 给玩家输入方向用。
     */
    public static double orbitYawRad() {
        return orbitYaw(true);
    }

    /**
     * 未平滑的纯切线方向。
     *
     * <p>注意：<b>CollisionSpeed 不再用这个</b>。它和 {@link #orbitYawRad()} 差着一个径向
     * 分量，两股力方向不同，合成的位移既不是切向也不是径向 —— 那就是绕圈时左右摆动的来源。
     * 注入和输入必须同向，所以那边也拿 {@link #orbitYawRad()}。
     */
    public static double orbitTangentRad() {
        return orbitYaw(false);
    }

    /**
     * 触发本 tick 的绕圈计算（幂等，同一 tick 只算一次）。
     *
     * <p>{@link #radialStep()} 的值是在 {@link #computeOrbit} 里算出来的，而它原本只由
     * {@code orbitYawRad()}（CollisionSpeed）和 {@code aimRotation()} 间接触发 —— 没开
     * CollisionSpeed 时没人调用，径向步进永远是 0，半径修正等于不存在。输入侧因此必须自己
     * 先把这个 tick 算出来。
     */
    public static void ensureOrbitComputed() {
        if (strafeTarget != null && mc.player != null) {
            orbitYaw(true);
        }
    }

    /**
     * 本 tick 的横移方向：+1 顺时针、-1 逆时针。配合"朝向目标"的 yaw 就是切向。
     */
    public static int strafeSign() {
        return strafeDirectionSign;
    }

    /**
     * 本 tick 的径向步进：+1 → forward=+1（朝目标收）、-1 → forward=-1（向外让）、
     * 0 → forward=0（纯切向）。由 {@link #RADIAL_DEADBAND} 滞回。
     */
    public static int radialStep() {
        return radialStep;
    }

    /**
     * 朝目标的朝向，供 TargetStrafe 独立工作（没有 KillAura 提供旋转时）使用。
     *
     * <p>只由"玩家 → 目标"这条连线决定，和玩家当前视角完全无关；俯仰沿用玩家自己的，
     * 不参与绕圈。配合横移输入，移动方向 = 这条朝向 ±90°，也就是绕目标的切向。
     */
    public static Rotation aimRotation() {
        if (mc.player == null || strafeTarget == null) {
            return new Rotation(mc.player != null ? mc.player.getYRot() : 0.0f,
                    mc.player != null ? mc.player.getXRot() : 0.0f);
        }
        double dx = strafeTarget.getX() - mc.player.getX();
        double dz = strafeTarget.getZ() - mc.player.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        return new Rotation(yaw, mc.player.getXRot());
    }

    /**
     * 一个 tick 只算一次。
     *
     * <p>MovementUtil（输入方向）和 CollisionSpeed（注入方向）在同一个 tick 里各调一次，
     * 下面的径向与朝向低通都是带状态的 —— 算两次等于一 tick 推了两步，两处拿到的角度还
     * 不一样，同 tick 两个方向互相打架。
     */
    private static double orbitYaw(boolean withRadial) {
        Entity target = strafeTarget;
        if (target == null || mc.player == null) {
            return MovementUtil.getMovementYaw();
        }
        long tick = mc.level != null ? mc.level.getGameTime() : mc.player.tickCount;
        if (tick != orbitTick) {
            orbitTick = tick;
            computeOrbit(target);
        }
        return withRadial ? orbitSteerYaw : orbitTangentYaw;
    }

    private static void computeOrbit(Entity target) {
        double dx = mc.player.getX() - target.getX();
        double dz = mc.player.getZ() - target.getZ();
        double distance = Math.hypot(dx, dz);
        if (distance < 1.0E-3) {
            // 和目标完全重叠时没有切线可言，先沿当前朝向走开
            orbitSteerYaw = MovementUtil.getMovementYaw();
            orbitTangentYaw = orbitSteerYaw;
            steerYawRad = orbitSteerYaw;
            return;
        }
        double desired = Math.max(INSTANCE.range.getValue().doubleValue(), MIN_ORBIT_RADIUS);
        // 切线：半径方向旋转 90°，方向由 sign 决定（固定顺时针）
        double tangentX = -dz / distance * strafeDirectionSign;
        double tangentZ = dx / distance * strafeDirectionSign;
        orbitTangentYaw = Math.atan2(-tangentX, tangentZ);

        if (target != biasTarget) {
            // 目标换了就重置径向与朝向状态，避免沿用上一个目标的偏差。
            biasTarget = target;
            radialBias = 0.0;
            steerYawRad = orbitTangentYaw;
        }

        // 径向修正：离远了往里收、太近了往外让。权重与误差成正比但有上限，半径才会平滑收敛。
        double error = desired - distance;
        double want = clamp(error * RADIAL_GAIN, -RADIAL_LIMIT, RADIAL_LIMIT);
        radialBias += (want - radialBias) * RADIAL_SMOOTHING;
        double dirX = tangentX + dx / distance * radialBias;
        double dirZ = tangentZ + dz / distance * radialBias;
        double rawSteer = Math.atan2(-dirX, dirZ);

        // 朝向低通：沿最短弧靠过去，绕圈方向因此是连续的曲线而不是每 tick 一个折角。
        steerYawRad = wrapRadians(steerYawRad + wrapRadians(rawSteer - steerYawRad) * STEER_SMOOTHING);
        orbitSteerYaw = steerYawRad;

        // 输入只有 8 个方向，径向修正的粒度就是 45°：用滞回带决定这一 tick 要不要带 forward。
        // forward=+1 是朝 yaw（= 目标）前进，-1 是后退。所以"太远"要 +1、"太近"要 -1。
        // 这里以前写反了，结果无论距离多远都只朝目标靠，Range 等于没设。
        radialStep = error < -RADIAL_DEADBAND ? 1 : (error > RADIAL_DEADBAND ? -1 : 0);
    }

    private static double wrapRadians(double angle) {
        double wrapped = angle % (Math.PI * 2.0);
        if (wrapped > Math.PI) {
            wrapped -= Math.PI * 2.0;
        } else if (wrapped < -Math.PI) {
            wrapped += Math.PI * 2.0;
        }
        return wrapped;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    @EventTarget
    public void onMotion(MotionEvent motionEvent) {
        if (!motionEvent.isPost() || mc.player == null) {
            return;
        }
        // 绕圈目标就是 KillAura 正在打的那个：发包 yaw 朝它，横移才会变成切向。
        Entity auraTarget = KillAura.target;
        if (auraTarget == null) {
            strafeTarget = null;
            return;
        }
        if (auraTarget != strafeTarget) {
            strafeTarget = auraTarget;
            this.switchTimer.reset();
        }
    }

    @EventTarget
    public void onSneak(SneakEvent sneakEvent) {
        if (!sneakEvent.isCancelled() && !FireballBlink.INSTANCE.isEnabled()) {
            sneakEvent.setCancelled(true);
        }
    }

    static {
        strafeDirectionSign = 1;
    }
}
