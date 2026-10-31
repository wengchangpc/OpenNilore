package client.nilore.modules.impl.world;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import client.nilore.event.EventTarget;
import client.nilore.event.impl.PacketEvent;
import client.nilore.event.impl.RenderEvent;
import client.nilore.event.impl.TickEvent;
import client.nilore.event.impl.WorldChangeEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.ModeSetting;
import client.nilore.settings.impl.NumberSetting;
import client.nilore.utils.game.BlockUtil;
import client.nilore.utils.game.RotationUtil;
import client.nilore.utils.misc.PacketUtil;
import client.nilore.utils.render.RenderUtil;
import client.nilore.utils.rotation.Rotation;

public class PacketBreak
extends Module {
    private final NumberSetting range = new NumberSetting("Range", 4.5, 1.0, 6.0, 0.1);
    private final BooleanSetting swingSetting = new BooleanSetting("Swing", true);
    private final BooleanSetting hold = new BooleanSetting("Hold", true);
    private final BooleanSetting esp = new BooleanSetting("ESP", true);
    private final BooleanSetting showProgress = new BooleanSetting("Progress", true);
    private final ModeSetting progressMode = new ModeSetting("Mode", "Up", "In", "Out");
    private final NumberSetting red = new NumberSetting("Red", 255, 0, 255, 1);
    private final NumberSetting green = new NumberSetting("Green", 255, 0, 255, 1);
    private final NumberSetting blue = new NumberSetting("Blue", 255, 0, 255, 1);

    private BlockPos targetPos;
    private float accumulated;
    private boolean landed;
    private boolean finishPending;

    public PacketBreak() {
        super("PacketBreak", Category.WORLD);
    }

    @Override
    protected void onDisable() {
        this.clearTarget();
    }

    @EventTarget
    public void onWorldChange(WorldChangeEvent worldChangeEvent) {
        this.clearTarget();
    }

    @EventTarget
    public void onPacket(PacketEvent packetEvent) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (!(packetEvent.getPacket() instanceof ServerboundPlayerActionPacket actionPacket)) {
            return;
        }
        BlockPos pos = actionPacket.getPos();
        if (actionPacket.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK) {
            if (!this.isRealViewTarget(pos)) {
                return;
            }
            this.targetPos = pos.immutable();
            this.accumulated = 0.0f;
            this.landed = false;
            this.finishPending = false;
            if (mc.player.onGround()) {
                this.landed = true;
            }
        } else if (actionPacket.getAction() == ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK
                && this.hold.getValue()
                && this.targetPos != null
                && this.targetPos.equals(pos)) {
            packetEvent.setCancelled(true);
        }
    }

    @EventTarget
    public void onTick(TickEvent tickEvent) {
        BlockPos pos = this.targetPos;
        if (pos == null || mc.player == null || mc.level == null || mc.gameMode == null) {
            return;
        }
        if (!this.isValid(pos)) {
            this.clearTarget();
            return;
        }
        if (mc.player.onGround()) {
            this.landed = true;
        }
        float damage = this.tickDamage(pos, this.landed);
        if (damage <= 0.0f) {
            this.clearTarget();
            return;
        }
        this.accumulated = Math.min(1.0f, this.accumulated + damage);
        if (this.swingSetting.getValue()) {
            mc.player.swing(InteractionHand.MAIN_HAND);
        } else {
            PacketUtil.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        }
        if (!this.finishPending && this.accumulated >= 1.0f) {
            this.finishPending = true;
            return;
        }
        if (this.finishPending) {
            Direction face = this.resolveFace(pos);
            PacketUtil.sendPredictiveDirect(sequence -> new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, face, sequence));
            this.clearTarget();
        }
    }

    private float tickDamage(BlockPos pos, boolean forceGround) {
        BlockState blockState = mc.level.getBlockState(pos);
        float damage = blockState.getDestroyProgress(mc.player, mc.level, pos);
        if (forceGround && !mc.player.onGround()) {
            damage *= 5.0f;
        }
        return damage;
    }

    private boolean isRealViewTarget(BlockPos pos) {
        if (mc.hitResult instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(pos)) {
            return true;
        }
        Vec3 eye = mc.player.getEyePosition(1.0f);
        double reach = this.range.getValue().doubleValue();
        Vec3 end = eye.add(RotationUtil.directionFromRotation(this.realRotation()).scale(reach));
        return BlockUtil.getBoundingBox(pos).clip(eye, end).isPresent();
    }

    private Rotation realRotation() {
        return new Rotation(mc.player.getYRot(), mc.player.getXRot());
    }

    private Direction resolveFace(BlockPos pos) {
        Vec3 eye = mc.player.getEyePosition(1.0f);
        Vec3 center = Vec3.atCenterOf(pos);
        double dx = eye.x - center.x;
        double dy = eye.y - center.y;
        double dz = eye.z - center.z;
        double ax = Math.abs(dx);
        double ay = Math.abs(dy);
        double az = Math.abs(dz);
        if (ax >= ay && ax >= az) {
            return dx >= 0.0 ? Direction.EAST : Direction.WEST;
        }
        if (ay >= az) {
            return dy >= 0.0 ? Direction.UP : Direction.DOWN;
        }
        return dz >= 0.0 ? Direction.SOUTH : Direction.NORTH;
    }

    private boolean isValid(BlockPos pos) {
        BlockState blockState = mc.level.getBlockState(pos);
        if (blockState.isAir()) {
            return false;
        }
        if (blockState.getDestroySpeed(mc.level, pos) < 0.0f) {
            return false;
        }
        double distance = mc.player.getEyePosition(1.0f).distanceTo(Vec3.atCenterOf(pos));
        return distance <= this.range.getValue().doubleValue();
    }

    @EventTarget
    public void onRender(RenderEvent renderEvent) {
        BlockPos pos = this.targetPos;
        if (!this.esp.getValue() || pos == null || mc.level == null || mc.player == null) {
            return;
        }
        PoseStack poseStack = renderEvent.poseStack();
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        AABB box = BlockUtil.getBoundingBox(pos);
        float red = this.red.getValue().floatValue() / 255.0f;
        float green = this.green.getValue().floatValue() / 255.0f;
        float blue = this.blue.getValue().floatValue() / 255.0f;
        poseStack.pushPose();
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        RenderSystem.setShaderColor(red, green, blue, 0.85f);
        RenderUtil.drawOutlineBox(box, poseStack);
        if (this.showProgress.getValue() && this.accumulated > 0.0f) {
            RenderSystem.setShaderColor(red, green, blue, 0.35f);
            RenderUtil.drawSolidBox(this.progressBox(box), poseStack);
        }
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.disableBlend();
        RenderSystem.enableDepthTest();
        poseStack.popPose();
    }

    private AABB progressBox(AABB box) {
        float progress = Math.max(0.0f, Math.min(1.0f, this.accumulated));
        if (this.progressMode.is("In") || this.progressMode.is("Out")) {
            double scale = Math.max(0.001, this.progressMode.is("In") ? 1.0 - progress : progress);
            double centerX = (box.minX + box.maxX) / 2.0;
            double centerY = (box.minY + box.maxY) / 2.0;
            double centerZ = (box.minZ + box.maxZ) / 2.0;
            double halfX = (box.maxX - box.minX) / 2.0 * scale;
            double halfY = (box.maxY - box.minY) / 2.0 * scale;
            double halfZ = (box.maxZ - box.minZ) / 2.0 * scale;
            return new AABB(centerX - halfX, centerY - halfY, centerZ - halfZ,
                    centerX + halfX, centerY + halfY, centerZ + halfZ).inflate(0.002);
        }
        double height = box.maxY - box.minY;
        return new AABB(box.minX, box.minY, box.minZ, box.maxX, box.minY + height * progress, box.maxZ)
                .inflate(0.002);
    }

    private void clearTarget() {
        this.targetPos = null;
        this.accumulated = 0.0f;
        this.landed = false;
        this.finishPending = false;
    }
}
