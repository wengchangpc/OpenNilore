package client.nilore.modules.impl.movement;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import client.nilore.event.impl.GameTickEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.modules.impl.combat.KillAura;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.ModeSetting;
import client.nilore.settings.impl.NumberSetting;
import client.nilore.utils.rotation.Rotation;
import client.nilore.utils.rotation.RotationHandler;
import client.nilore.event.EventTarget;

public class TargetStrafe
extends Module {
    public static TargetStrafe INSTANCE;
    private final ModeSetting mode = new ModeSetting("Mode", "Round", "Strict");
    private final BooleanSetting voidCheck = new BooleanSetting("Void Check", true);
    private final NumberSetting distance = new NumberSetting("Distance", ORBIT_RADIUS, 0.5, 3.0, 0.05,
            () -> this.mode.is("Round"));

    public static int strafeDirectionSign = 1;
    public static Entity strafeTarget;

    private static final double ORBIT_RADIUS = 0.75;
    private static final double CHASE_MARGIN = 0.3;
    private static final double RADIAL_GAIN = 2.5;
    private static final double MIN_DISTANCE = 0.5;
    private static final double MIN_ORBIT = 0.55;
    private static final double RADIAL_STEP_SIN = 0.7071067811865475;
    private static final double VOID_LOOKAHEAD = 0.8;
    private static final int VOID_SCAN_DEPTH = 5;
    private static final int VOID_FLIP_COOLDOWN = 10;
    private static final double SLOT_HYSTERESIS = 0.05;
    private static final double STRICT_LEAD_FACTOR = 1.5;
    private static final double STRICT_MAX_LEAD = 0.5;
    private static final double CHASE_CLOSING = 0.03;
    private static final double CHASE_ESCAPE = 0.35;

    private static int inputForward;
    private static int inputStrafe;
    private static long orbitTick = Long.MIN_VALUE;
    private static int flipCooldown;
    private static int lastSlot;
    private static double radialAccumulator;

    public TargetStrafe() {
        super("TargetStrafe", Category.MOVEMENT);
        INSTANCE = this;
    }

    public static boolean isOrbiting() {
        return INSTANCE != null
                && INSTANCE.isEnabled()
                && strafeTarget != null
                && mc.player != null;
    }

    @EventTarget
    public void onGameTick(GameTickEvent event) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        strafeTarget = KillAura.target;
    }

    public static void ensureOrbitComputed() {
        if (strafeTarget != null && mc.player != null) {
            orbitYaw();
        }
    }

    public static int strafeSign() {
        return inputStrafe;
    }

    public static int radialStep() {
        return inputForward;
    }

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

    private static void orbitYaw() {
        Entity target = strafeTarget;
        if (target == null || mc.player == null) {
            return;
        }
        long tick = mc.level != null ? mc.level.getGameTime() : mc.player.tickCount;
        if (tick != orbitTick) {
            orbitTick = tick;
            computeOrbit(target);
        }
    }

    private static void computeOrbit(Entity target) {
        double dx = mc.player.getX() - target.getX();
        double dz = mc.player.getZ() - target.getZ();
        double distance = Math.hypot(dx, dz);
        if (distance < 1.0E-3) {
            inputForward = 1;
            inputStrafe = 0;
            return;
        }

        double radialX = dx / distance;
        double radialZ = dz / distance;
        Vec3 targetMotion = target.getDeltaMovement();
        Vec3 ownMotion = mc.player.getDeltaMovement();
        double closing = (ownMotion.x - targetMotion.x) * radialX + (ownMotion.z - targetMotion.z) * radialZ;
        double dirX;
        double dirZ;
        double orbitRadius = INSTANCE.distance.getValue().doubleValue();
        double minOrbit = Math.min(MIN_ORBIT, orbitRadius);
        double chaseStart = orbitRadius + CHASE_MARGIN;
        if (INSTANCE.mode.is("Strict")) {
            Vec3 velocity = target.getDeltaMovement();
            double lead = Math.min(STRICT_MAX_LEAD, Math.hypot(velocity.x, velocity.z) * STRICT_LEAD_FACTOR);
            double aheadX = dx - velocity.x * lead;
            double aheadZ = dz - velocity.z * lead;
            double ahead = Math.hypot(aheadX, aheadZ);
            if (ahead < 1.0E-6) {
                aheadX = dx;
                aheadZ = dz;
                ahead = 1.0;
            }
            dirX = -aheadX / ahead;
            dirZ = -aheadZ / ahead;
            radialAccumulator = 0.0;
        } else if (distance > chaseStart || closing > CHASE_CLOSING) {
            radialAccumulator = 0.0;
            double tangentX = -radialZ * strafeDirectionSign;
            double tangentZ = radialX * strafeDirectionSign;
            double kappa = Math.max(0.35 + 0.65 * clamp((distance - chaseStart) / chaseStart, 0.0, 1.0),
                    clamp(closing / CHASE_ESCAPE, 0.0, 1.0));
            dirX = -radialX * kappa + tangentX * (1.0 - kappa);
            dirZ = -radialZ * kappa + tangentZ * (1.0 - kappa);
        } else {
            double tangentX = -radialZ * strafeDirectionSign;
            double tangentZ = radialX * strafeDirectionSign;
            if (INSTANCE.voidCheck.getValue()) {
                if (flipCooldown > 0) {
                    flipCooldown--;
                } else if (isVoidAhead(tangentX, tangentZ)) {
                    strafeDirectionSign = -strafeDirectionSign;
                    flipCooldown = VOID_FLIP_COOLDOWN;
                    tangentX = -tangentX;
                    tangentZ = -tangentZ;
                }
            }
            double speed = horizontalSpeed();
            double chord = speed * speed / (2.0 * Math.max(distance, MIN_DISTANCE));
            double desired = (orbitRadius - distance) * RADIAL_GAIN - chord;
            double stepRadial = Math.max(0.02, speed * RADIAL_STEP_SIN);
            radialAccumulator = clamp(radialAccumulator + desired, -stepRadial, stepRadial);
            int radialMode = 0;
            if (radialAccumulator <= -stepRadial && distance - stepRadial > minOrbit) {
                radialAccumulator += stepRadial;
                radialMode = -1;
            } else if (radialAccumulator >= stepRadial) {
                radialAccumulator -= stepRadial;
                radialMode = 1;
            }
            dirX = tangentX + radialX * radialMode;
            dirZ = tangentZ + radialZ * radialMode;
        }

        double length = Math.hypot(dirX, dirZ);
        if (length < 1.0E-6) {
            dirX = -radialX;
            dirZ = -radialZ;
            length = 1.0;
        }
        dirX /= length;
        dirZ /= length;

        float moveYaw = movementYawDegrees();
        boolean strict = INSTANCE.mode.is("Strict");
        int bestSlot = 0;
        double bestScore = -2.0;
        for (int slot = -4; slot <= 4; slot++) {
            if (strict && (slot >= 3 || slot <= -3)) {
                continue;
            }
            double angle = Math.toRadians(moveYaw + slot * 45.0);
            double dot = -Math.sin(angle) * dirX + Math.cos(angle) * dirZ;
            if (slot == lastSlot) {
                dot += SLOT_HYSTERESIS;
            }
            if (dot > bestScore) {
                bestScore = dot;
                bestSlot = slot;
            }
        }
        lastSlot = bestSlot;
        applySlot(bestSlot);
    }

    private static void applySlot(int slot) {
        switch (slot) {
            case 0 -> {
                inputForward = 1;
                inputStrafe = 0;
            }
            case 1 -> {
                inputForward = 1;
                inputStrafe = -1;
            }
            case -1 -> {
                inputForward = 1;
                inputStrafe = 1;
            }
            case 2 -> {
                inputForward = 0;
                inputStrafe = -1;
            }
            case -2 -> {
                inputForward = 0;
                inputStrafe = 1;
            }
            case 3 -> {
                inputForward = -1;
                inputStrafe = -1;
            }
            case -3 -> {
                inputForward = -1;
                inputStrafe = 1;
            }
            default -> {
                inputForward = -1;
                inputStrafe = 0;
            }
        }
    }

    private static double horizontalSpeed() {
        Vec3 motion = mc.player.getDeltaMovement();
        return Math.hypot(motion.x, motion.z);
    }

    private static float movementYawDegrees() {
        if (RotationHandler.isRotating && RotationHandler.targetRotation != null) {
            return RotationHandler.targetRotation.getYaw();
        }
        return mc.player.getYRot();
    }

    private static boolean isVoidAhead(double tangentX, double tangentZ) {
        if (mc.level == null) {
            return false;
        }
        double x = mc.player.getX() + tangentX * VOID_LOOKAHEAD;
        double z = mc.player.getZ() + tangentZ * VOID_LOOKAHEAD;
        BlockPos origin = BlockPos.containing(x, mc.player.getY(), z);
        for (int i = 0; i < VOID_SCAN_DEPTH; i++) {
            BlockPos pos = origin.below(i);
            if (!mc.level.getBlockState(pos).getCollisionShape(mc.level, pos).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public void onDisable() {
        strafeTarget = null;
        inputForward = 0;
        inputStrafe = 0;
        super.onDisable();
    }
}
