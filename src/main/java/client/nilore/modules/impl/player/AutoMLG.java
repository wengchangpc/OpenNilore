package client.nilore.modules.impl.player;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import client.nilore.event.impl.TickEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.NumberSetting;
import client.nilore.utils.game.ItemUtil;
import client.nilore.utils.game.RotationUtil;
import client.nilore.utils.rotation.Rotation;
import client.nilore.event.EventTarget;

public class AutoMLG
extends Module {
    public static AutoMLG INSTANCE;
    /** 一次回收最多维持多少 tick（3 秒）。超时只可能是射线一直被挡/水在视线外。 */
    private static final int RECOVERY_TIMEOUT = 60;

    private final NumberSetting triggerDistanceSetting = new NumberSetting("Fall distance", 3, 1.0f, 10.0f, 0.1f);
    private final NumberSetting predictTicksSetting = new NumberSetting("Predict Ticks", 2, 1.0f, 5.0f, 1.0f);
    private final BooleanSetting solidCheckSetting = new BooleanSetting("Solid check", true);
    private final BooleanSetting recoverySetting = new BooleanSetting("Recorvey", true);
    public Rotation targetRotation = null;
    private float accumulatedFall;
    private double lastY;
    private Integer slotToRestore;
    private boolean waterPlaced;
    private boolean recoveryActive;
    private int recoveryDelay;
    private int recoveryTicks;
    private Integer waterBucketSlot;
    private BlockPos placedWaterPos;
    private boolean readyToPlace;
    private int postPlaceCooldown;
    private int postActionCooldown;

    public AutoMLG() {
        super("AutoMLG", Category.PLAYER);
        INSTANCE = this;
    }

    @Override
    protected void onEnable() {
        this.slotToRestore = null;
        this.waterPlaced = false;
        this.recoveryActive = false;
        this.recoveryDelay = 0;
        this.recoveryTicks = 0;
        this.waterBucketSlot = null;
        this.placedWaterPos = null;
        this.readyToPlace = false;
        this.postPlaceCooldown = 0;
        this.postActionCooldown = 0;
        this.accumulatedFall = 0.0f;
        this.lastY = mc.player != null ? mc.player.getY() : 0.0;
        super.onEnable();
    }

    @Override
    protected void onDisable() {
        this.slotToRestore = null;
        this.waterPlaced = false;
        this.recoveryActive = false;
        this.recoveryDelay = 0;
        this.recoveryTicks = 0;
        this.waterBucketSlot = null;
        this.placedWaterPos = null;
        this.readyToPlace = false;
        this.postPlaceCooldown = 0;
        this.postActionCooldown = 0;
        this.accumulatedFall = 0.0f;
        super.onDisable();
    }

    public boolean isInCooldown() {
        return this.postPlaceCooldown > 0;
    }

    @EventTarget
    public void onTick(TickEvent tickEvent) {
        Rotation rotation;
        BlockHitResult hit;
        BlockPos bucketPos;
        int slot;
        double deltaY;
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (mc.player.isFallFlying() || mc.player.onClimbable()) {
            this.lastY = mc.player.getY();
            this.accumulatedFall = 0.0f;
            return;
        }
        if (mc.player.onGround() || mc.player.getAbilities().flying || mc.player.isInWaterRainOrBubble() || mc.player.isInLava()) {
            this.accumulatedFall = 0.0f;
        } else {
            deltaY = mc.player.getY() - this.lastY;
            if (deltaY < 0.0) {
                this.accumulatedFall -= (float)deltaY;
            }
        }
        this.lastY = mc.player.getY();
        if (this.postPlaceCooldown > 0) {
            --this.postPlaceCooldown;
        }
        if (this.postActionCooldown > 0) {
            --this.postActionCooldown;
        }
        if (this.slotToRestore != null) {
            mc.player.getInventory().selected = this.slotToRestore;
            this.slotToRestore = null;
        }
        if (mc.player.onGround() || this.accumulatedFall <= 0.0f) {
            this.waterPlaced = false;
            this.readyToPlace = false;
        }
        if (this.recoveryActive) {
            this.tickRecovery();
            return;
        }
        if (!this.waterPlaced && !this.recoveryActive && this.placedWaterPos == null && this.postPlaceCooldown == 0 && this.postActionCooldown == 0 && this.accumulatedFall <= 0.5f && ItemUtil.findItemInRange(0, 9, Items.WATER_BUCKET) < 0 && (slot = ItemUtil.findItemInRange(0, 9, Items.BUCKET)) >= 0 && (bucketPos = this.findBucketPos()) != null && (hit = this.raycastFluid(rotation = RotationUtil.rotationToBlock(bucketPos, 0.0f), 4.5)).getType() != HitResult.Type.MISS && hit.getBlockPos().equals(bucketPos)) {
            this.setTargetRotation(rotation);
            this.selectSlot(slot);
            this.useItem(rotation);
            this.postActionCooldown = 8;
            this.postPlaceCooldown = Math.max(this.postPlaceCooldown, 1);
            return;
        }
        if (this.waterPlaced && !this.readyToPlace && mc.player.getDeltaMovement().y < 0.0 && (deltaY = this.distanceToGround(2.5)) > 0.0 && deltaY <= 1.05) {
            this.readyToPlace = true;
        }
        if (this.waterPlaced) {
            return;
        }
        if (this.accumulatedFall < this.triggerDistanceSetting.getValue().floatValue()) {
            return;
        }
        slot = ItemUtil.findItemInRange(0, 9, Items.WATER_BUCKET);
        if (slot < 0) {
            return;
        }
        int ticksLeft = this.ticksUntilGround();
        if (ticksLeft <= this.predictTicksSetting.getValue().intValue()) {
            if (this.solidCheckSetting.getValue() && !this.hasSolidBelow(BlockPos.containing(mc.player.getX(), mc.player.getY(), mc.player.getZ()))) {
                return;
            }
            rotation = new Rotation(mc.player.getYRot(), 90.0f);
            hit = this.raycastSolid(rotation, 5.0);
            if (hit.getType() == HitResult.Type.MISS) {
                return;
            }
            this.placeWaterBucket(slot, true);
        }
    }

    private int ticksUntilGround() {
        if (mc.player.getDeltaMovement().y >= 0.0) {
            return 999;
        }
        double distance = this.distanceToGround(30.0);
        if (distance == Double.POSITIVE_INFINITY) {
            return 999;
        }
        double simulatedDrop = 0.0;
        double simulatedVelocity = mc.player.getDeltaMovement().y;
        for (int i = 1; i <= 20; ++i) {
            simulatedDrop += simulatedVelocity;
            simulatedVelocity = (simulatedVelocity - 0.08) * 0.98;
            if (!(Math.abs(simulatedDrop) >= distance)) continue;
            return i;
        }
        return 999;
    }

    private void useItem(Rotation rotation) {
        if (mc.gameMode != null && mc.player != null) {
            float originalPitch = mc.player.getXRot();
            float originalYaw = mc.player.getYRot();
            if (rotation != null) {
                mc.player.setXRot(rotation.getPitch());
                mc.player.setYRot(rotation.getYaw());
            }
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            mc.player.swing(InteractionHand.MAIN_HAND);
            if (rotation != null) {
                mc.player.setXRot(originalPitch);
                mc.player.setYRot(originalYaw);
            }
        }
    }

    /**
     * 收水：只收回本模块自己放下去的那一格水。
     *
     * <p>旧实现是一条 10 tick 的倒计时：{@code recoveryDelay} 等 2 tick、{@code recoveryCountdown}
     * 每 tick 自减，到点无条件放弃。水还没变成源方块、射线被方块挡住、槽位被别的模块切走，
     * 任意一种都会把这次回收耗掉 —— 这正是"有概率不收自己放的水"。
     *
     * <p>现在改成围绕一个明确目标的重试循环，收不到就一直收：
     * <ul>
     *   <li>目标只可能是 {@link #placedWaterPos} 及其六邻格（桶按在方块侧面时实际落点会偏一格），
     *       别处的水一律不碰；</li>
     *   <li>目标必须是<b>完整的一格水源</b>（见 {@link #isCompleteWater}），半格水/流动水/
     *       waterlogged 方块收不了，直接不点；</li>
     *   <li>目标不存在了（被方块顶掉、流干）= 没有可收的东西，正常结束；</li>
     *   <li>空桶槽位每 tick 重新确认，被切走就重找；桶变回水桶 = 收成功；</li>
     *   <li>只有射线一直被挡住才会走 {@link #RECOVERY_TIMEOUT} 的兜底。</li>
     * </ul>
     */
    private void tickRecovery() {
        if (mc.player == null || mc.level == null) {
            this.stopRecovery();
            return;
        }
        if (this.recoveryDelay > 0) {
            --this.recoveryDelay;
            return;
        }
        if (this.waterBucketSlot != null && this.slotItem(this.waterBucketSlot) == Items.WATER_BUCKET) {
            this.stopRecovery();
            return;
        }
        if (this.waterBucketSlot == null || this.slotItem(this.waterBucketSlot) != Items.BUCKET) {
            this.waterBucketSlot = ItemUtil.findItemInRange(0, 9, Items.BUCKET);
        }
        if (this.waterBucketSlot == null) {
            this.stopRecovery();
            return;
        }
        BlockPos target = this.resolveRecoveryTarget();
        if (target == null) {
            this.stopRecovery();
            return;
        }
        if (++this.recoveryTicks > RECOVERY_TIMEOUT) {
            this.stopRecovery();
            return;
        }
        Rotation rotation = RotationUtil.rotationToBlock(target, 0.0f);
        BlockHitResult hit = this.raycastFluid(rotation, 4.5);
        if (hit.getType() == HitResult.Type.MISS || !hit.getBlockPos().equals(target)) {
            return;
        }
        this.setTargetRotation(rotation);
        this.selectSlot(this.waterBucketSlot);
        this.useItem(rotation);
    }

    /**
     * 锁定这一桶水的实际位置：预测点优先，偏了就看它的六邻格。
     *
     * <p>{@link #getPlacementBlockPos} 是拿射线命中的面推出来的，桶按在方块侧面时预测点可能落在
     * 方块本体上，实际水在相邻一格 —— 这是"偶尔不收自己放的水"的另一半原因。
     */
    private BlockPos resolveRecoveryTarget() {
        if (this.placedWaterPos == null) {
            return null;
        }
        if (this.isCompleteWater(this.placedWaterPos)) {
            return this.placedWaterPos;
        }
        for (Direction direction : Direction.values()) {
            BlockPos candidate = this.placedWaterPos.relative(direction);
            if (this.isCompleteWater(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private void stopRecovery() {
        this.recoveryActive = false;
        this.recoveryDelay = 0;
        this.recoveryTicks = 0;
        this.waterBucketSlot = null;
        this.placedWaterPos = null;
        this.postPlaceCooldown = Math.max(this.postPlaceCooldown, 1);
    }

    private Item slotItem(int slot) {
        return mc.player.getInventory().items.get(slot).getItem();
    }

    private BlockPos findBucketPos() {
        BlockPos playerPos = BlockPos.containing(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        BlockPos closestPos = null;
        double closestDistSq = Double.POSITIVE_INFINITY;
        int radius = 4;
        for (int dy = -1; dy <= 1; ++dy) {
            for (int dx = -radius; dx <= radius; ++dx) {
                for (int dz = -radius; dz <= radius; ++dz) {
                    BlockPos candidatePos = playerPos.offset(dx, dy, dz);
                    // Cheap rejections first: the distance test used to run after the fluid
                    // classification, so all 243 candidates paid for a block-state lookup even
                    // when a closer source had already been accepted.
                    double distSq = mc.player.position().distanceToSqr(
                            (double)candidatePos.getX() + 0.5, (double)candidatePos.getY() + 0.5, (double)candidatePos.getZ() + 0.5);
                    if (distSq >= closestDistSq) continue;
                    if (!this.isCollectableWater(candidatePos)) continue;
                    Rotation rotation = RotationUtil.rotationToBlock(candidatePos, 0.0f);
                    BlockHitResult hit = this.raycastFluid(rotation, 4.5);
                    if (hit.getType() == HitResult.Type.MISS || !hit.getBlockPos().equals(candidatePos)) continue;
                    closestPos = candidatePos;
                    closestDistSq = distSq;
                }
            }
        }
        return closestPos;
    }

    /**
     * 装水（给空桶找水源）的目标筛选：完整的一格天然水源。
     *
     * <p>自己刚放下去的那一桶是孤立单格，{@link #isNaturalWaterBody} 数不出两个水源邻居，
     * 所以不会被这里选走；waterlogged 方块里的半格水、流动水也过不了 {@link #isCompleteWater}。
     */
    private boolean isCollectableWater(BlockPos pos) {
        return this.isCompleteWater(pos) && this.isNaturalWaterBody(pos);
    }

    /**
     * 「完整方块的水」：整格水源。
     *
     * <p>三种不完整的情况全部排除：
     * <ul>
     *   <li>waterlogged 方块（台阶、楼梯、栅栏里的水）—— block 不是 {@code Blocks.WATER}；</li>
     *   <li>流动水 —— {@code isSource()} 为假；</li>
     *   <li>被方块占位/未流满的水 —— 碰撞形状非空，或水面高度不足一格。</li>
     * </ul>
     */
    private boolean isCompleteWater(BlockPos pos) {
        if (pos == null) {
            return false;
        }
        BlockState state = mc.level.getBlockState(pos);
        if (state.getBlock() != Blocks.WATER) {
            return false;
        }
        FluidState fluidState = state.getFluidState();
        if (fluidState.getType() != Fluids.WATER || !fluidState.isSource()) {
            return false;
        }
        if (!state.getCollisionShape(mc.level, pos).isEmpty()) {
            return false;
        }
        return fluidState.getOwnHeight() >= 0.99f;
    }

    private void setTargetRotation(Rotation rotation) {
        this.targetRotation = rotation;
    }

    private void selectSlot(int slot) {
        this.slotToRestore = mc.player.getInventory().selected;
        mc.player.getInventory().selected = slot;
    }

    private void placeWaterBucket(int slot, boolean markPlaced) {
        Rotation rotation = new Rotation(mc.player.getYRot(), 90.0f);
        this.setTargetRotation(rotation);
        this.selectSlot(slot);
        this.useItem(rotation);
        if (markPlaced) {
            this.waterPlaced = true;
        }
        this.placedWaterPos = this.getPlacementBlockPos(rotation);
        this.recoveryActive = (Boolean) this.recoverySetting.getValue();
        this.recoveryDelay = 2;
        this.recoveryTicks = 0;
        this.waterBucketSlot = null;
    }

    private BlockPos getPlacementBlockPos(Rotation rotation) {
        BlockHitResult hit = this.raycastSolid(rotation, 4.5);
        if (hit.getType() == HitResult.Type.MISS) {
            return null;
        }
        return hit.getBlockPos().relative(hit.getDirection());
    }

    private BlockHitResult raycastSolid(Rotation rotation, double range) {
        Vec3 eyePos = mc.player.getEyePosition(1.0f);
        Vec3 direction = Vec3.directionFromRotation(rotation.getPitch(), rotation.getYaw());
        Vec3 endPos = eyePos.add(direction.scale(range));
        return mc.level.clip(new ClipContext(eyePos, endPos, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
    }

    private BlockHitResult raycastFluid(Rotation rotation, double range) {
        Vec3 eyePos = mc.player.getEyePosition(1.0f);
        Vec3 direction = Vec3.directionFromRotation(rotation.getPitch(), rotation.getYaw());
        Vec3 endPos = eyePos.add(direction.scale(range));
        return mc.level.clip(new ClipContext(eyePos, endPos, ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, mc.player));
    }

    private boolean isNaturalWaterBody(BlockPos blockPos) {
        int waterNeighbours = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (this.isCompleteWater(blockPos.relative(direction))) {
                waterNeighbours++;
            }
        }
        return waterNeighbours >= 2;
    }

    private boolean hasSolidBelow(BlockPos blockPos) {
        return this.isSolidNonMenu(blockPos.below()) || this.isSolidNonMenu(blockPos.below(2));
    }

    private boolean isSolidNonMenu(BlockPos blockPos) {
        BlockState blockState = mc.level.getBlockState(blockPos);
        boolean hasCollision = !blockState.getCollisionShape(mc.level, blockPos).isEmpty();
        boolean noMenu = blockState.getMenuProvider(mc.level, blockPos) == null;
        return hasCollision && noMenu;
    }

    private double distanceToGround(double maxDist) {
        Vec3 endPos;
        Vec3 startPos = new Vec3(mc.player.getX(), mc.player.getBoundingBox().minY, mc.player.getZ());
        BlockHitResult hit = mc.level.clip(new ClipContext(startPos, endPos = startPos.add(0.0, -maxDist, 0.0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        if (hit.getType() == HitResult.Type.MISS) {
            return Double.POSITIVE_INFINITY;
        }
        return startPos.y - hit.getLocation().y;
    }
}
