package org.vmstudio.itemtaker.core.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.List;

public class ItemTakerLogic {
    public interface NetworkBridge {
        void sendSync(Entity entity, double x, double y, double z, double vx, double vy, double vz, boolean noGravity);
    }
    public static NetworkBridge bridge;

    private static final double RANGE = 3.0;
    private static final double PICKUP_DISTANCE = 0.5;

    private static ItemEntity pickedItem = null;
    private static final Vector3f UP_VECTOR = new Vector3f(0, 1, 0);
    private static int syncTimer = 0;

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        if (pickedItem == null) {
            handleDetection(mc);
        } else {
            handlePulling(mc);
        }
    }

    private static void handleDetection(Minecraft mc) {
        Vec3 eyePos = mc.player.getEyePosition();
        AABB searchBox = mc.player.getBoundingBox().inflate(RANGE);
        List<ItemEntity> items = mc.level.getEntitiesOfClass(ItemEntity.class, searchBox);

        ItemEntity bestTarget = null;
        double bestAngle = 0.99;
        Vec3 lookVec = mc.player.getViewVector(1.0F);

        // todo if there are a lot of items in one place, then all the things should be picked up at once
        // todo if the inventory is full, this item WON’T be picked up
        for (ItemEntity item : items) {
            Vec3 toItem = item.position().add(0, 0.2, 0).subtract(eyePos).normalize();
            double dot = lookVec.dot(toItem);
            if (dot > bestAngle) {
                bestAngle = dot;
                bestTarget = item;
            }
        }

        if (bestTarget != null) {
            Vector3f playerUp = new Vector3f(0, 1, 0);
            float upDot = playerUp.dot(UP_VECTOR);

            if (upDot > 0.2f) {
                spawnHoverParticles(bestTarget);

                if (mc.options.keyUse.isDown()) {
                    pickedItem = bestTarget;
                    pickedItem.setNoGravity(true);
                }
            }
        }
    }

    private static void handlePulling(Minecraft mc) {
        if (!pickedItem.isAlive()) {
            pickedItem = null;
            return;
        }

        Vec3 targetPos = mc.player.position().add(0, 0.8, 0);
        Vec3 itemPos = pickedItem.position();
        double dist = itemPos.distanceTo(targetPos);

        if (dist < PICKUP_DISTANCE) {
            pickedItem.setPos(targetPos.x, targetPos.y, targetPos.z);
            syncWithServer(pickedItem, targetPos, Vec3.ZERO, true);
            pickedItem = null;
            return;
        }

        Vec3 motion = targetPos.subtract(itemPos).normalize().scale(0.5);
        pickedItem.setDeltaMovement(motion);
        pickedItem.hasImpulse = true;

        syncTimer++;
        if (syncTimer >= 2) {
            syncWithServer(pickedItem, pickedItem.position(), motion, true);
            syncTimer = 0;
        }

        if (mc.level.random.nextBoolean()) {
            mc.level.addParticle(ParticleTypes.CRIT, pickedItem.getX(), pickedItem.getY(), pickedItem.getZ(), 0, 0, 0);
        }
    }

    private static void syncWithServer(Entity entity, Vec3 pos, Vec3 motion, boolean noGravity) {
        if (bridge != null) {
            bridge.sendSync(entity, pos.x, pos.y, pos.z, motion.x, motion.y, motion.z, noGravity);
        }
    }

    private static void spawnHoverParticles(ItemEntity item) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level.random.nextInt(2) == 0) {
            mc.level.addParticle(ParticleTypes.GLOW, item.getX(), item.getY() + 0.2, item.getZ(), 0, 0.02, 0);
        }
    }
}
