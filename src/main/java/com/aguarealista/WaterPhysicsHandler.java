package com.aguarealista;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = AguaRealistaMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class WaterPhysicsHandler {

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        Player player = event.player;
        Level level = player.level();
        if (level.isClientSide() || !player.isInWater()) {
            return;
        }

        Vec3 motion = player.getDeltaMovement();
        double horizSpeed = Math.sqrt(motion.x * motion.x + motion.z * motion.z);
        double vertSpeed = Math.abs(motion.y);

        if (horizSpeed < 0.06 && vertSpeed < 0.14) {
            return;
        }

        AABB area = player.getBoundingBox().inflate(3.5, 1.5, 3.5);
        for (Entity target : level.getEntities(player, area)) {
            if (!target.isInWater()) {
                continue;
            }
            if (!(target instanceof ItemEntity || target instanceof Boat || target instanceof LivingEntity)) {
                continue;
            }

            double dx = target.getX() - player.getX();
            double dz = target.getZ() - player.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);

            if (dist > 0.15 && dist < 3.5) {
                double falloff = 1.0 - (dist / 3.5);
                double force = falloff * Math.min(0.085, horizSpeed * 0.22 + vertSpeed * 0.16);
                if (target instanceof ItemEntity) {
                    force *= 1.8;
                } else if (target instanceof Boat) {
                    force *= 0.8;
                }

                target.push((dx / dist) * force, 0.008 * force, (dz / dist) * force);
            }
        }
    }
}
