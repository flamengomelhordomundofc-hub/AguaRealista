package com.aguarealista.client;

import com.aguarealista.AguaRealistaMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

@Mod.EventBusSubscriber(modid = AguaRealistaMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class WaterWaveManager {

    public static final int MAX_WAVES = 90;
    private static final int SURFACE_SCAN_RADIUS = 13;

    private static final List<Wave> WAVES = new ArrayList<>();
    private static final List<WaterCell> WATER_CELLS = new ArrayList<>();
    private static final Map<Integer, Boolean> WAS_IN_WATER = new HashMap<>();
    private static final Random RANDOM = new Random();

    private static int tickCounter = 0;

    public static class Wave {
        public final double x;
        public final double y;
        public final double z;
        public final float maxRadius;
        public final float speed;
        public final float strength;
        public final boolean foamRing;
        public float radius;
        public float prevRadius;
        public int age;
        public final int maxAge;

        public Wave(double x, double y, double z, float maxRadius, float speed, float strength, boolean foamRing) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.maxRadius = maxRadius;
            this.speed = speed;
            this.strength = strength;
            this.foamRing = foamRing;
            this.radius = 0.12f;
            this.prevRadius = 0.12f;
            this.age = 0;
            this.maxAge = Math.max(12, (int) (maxRadius / speed));
        }

        public void tick() {
            this.prevRadius = this.radius;
            this.radius += this.speed;
            this.age++;
        }

        public float getInterpolatedRadius(float partialTick) {
            return this.prevRadius + (this.radius - this.prevRadius) * partialTick;
        }

        public float getProgress(float partialTick) {
            return Math.min(1.0f, (this.age + partialTick) / (float) this.maxAge);
        }

        public boolean isExpired() {
            return this.age >= this.maxAge || this.radius >= this.maxRadius;
        }
    }

    public static class WaterCell {
        public final int x;
        public final int y;
        public final int z;
        public final float surfaceY;
        public final int shoreMask;
        public final boolean skyVisible;

        public WaterCell(int x, int y, int z, float surfaceY, int shoreMask, boolean skyVisible) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.surfaceY = surfaceY;
            this.shoreMask = shoreMask;
            this.skyVisible = skyVisible;
        }
    }

    public static List<Wave> getWaves() {
        return WAVES;
    }

    public static List<WaterCell> getWaterCells() {
        return WATER_CELLS;
    }

    public static void addWave(double x, double y, double z, float maxRadius, float speed, float strength, boolean foamRing) {
        if (WAVES.size() >= MAX_WAVES) {
            WAVES.remove(0);
        }
        WAVES.add(new Wave(x, y, z, maxRadius, speed, strength, foamRing));
    }

    public static float getWaveDisplacementAt(double wx, double wz, float partialTick) {
        float total = 0.0f;
        for (int i = 0; i < WAVES.size(); i++) {
            Wave wave = WAVES.get(i);
            double dx = wx - wave.x;
            double dz = wz - wave.z;
            double distSq = dx * dx + dz * dz;
            float r = wave.getInterpolatedRadius(partialTick);
            float bandWidth = wave.foamRing ? 0.55f : 0.28f;

            if (distSq > (r + bandWidth) * (r + bandWidth)) {
                continue;
            }

            double dist = Math.sqrt(distSq);
            float diff = (float) Math.abs(dist - r);
            if (diff < bandWidth) {
                float progress = wave.getProgress(partialTick);
                float fade = (1.0f - progress) * (1.0f - progress);
                float bell = (float) Math.cos((diff / bandWidth) * (Math.PI * 0.5));
                total += bell * bell * wave.strength * 0.14f * fade;
            }
        }
        return Math.min(0.24f, total);
    }

    public static boolean isWaterAt(Level level, double x, double surfaceY, double z) {
        BlockPos pos = BlockPos.containing(x, surfaceY - 0.25, z);
        if (level.getFluidState(pos).is(FluidTags.WATER)) {
            return true;
        }
        return level.getFluidState(pos.below()).is(FluidTags.WATER);
    }

    public static double findWaterSurfaceY(Level level, double x, double y, double z) {
        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y);
        int bz = (int) Math.floor(z);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos above = new BlockPos.MutableBlockPos();

        for (int offset = 3; offset >= -2; offset--) {
            pos.set(bx, by + offset, bz);
            FluidState fluid = level.getFluidState(pos);
            if (fluid.is(FluidTags.WATER)) {
                above.set(bx, by + offset + 1, bz);
                if (!level.getFluidState(above).is(FluidTags.WATER)) {
                    return pos.getY() + fluid.getHeight(level, pos);
                }
            }
        }
        return Double.NaN;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        Player localPlayer = mc.player;

        if (level == null || localPlayer == null) {
            WAVES.clear();
            WATER_CELLS.clear();
            WAS_IN_WATER.clear();
            return;
        }

        if (mc.isPaused()) {
            return;
        }

        tickCounter++;

        Iterator<Wave> it = WAVES.iterator();
        while (it.hasNext()) {
            Wave wave = it.next();
            wave.tick();
            if (wave.isExpired()) {
                it.remove();
            }
        }

        if (tickCounter % 4 == 0) {
            scanWaterSurface(level, localPlayer);
        }

        handleEntityWaves(level, localPlayer);
        handleRainRipples(level);

        if (tickCounter % 200 == 0 && WAS_IN_WATER.size() > 150) {
            WAS_IN_WATER.clear();
        }
    }

    private static void scanWaterSurface(ClientLevel level, Player player) {
        WATER_CELLS.clear();

        int px = (int) Math.floor(player.getX());
        int py = (int) Math.floor(player.getY());
        int pz = (int) Math.floor(player.getZ());

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos check = new BlockPos.MutableBlockPos();

        for (int dx = -SURFACE_SCAN_RADIUS; dx <= SURFACE_SCAN_RADIUS; dx++) {
            for (int dz = -SURFACE_SCAN_RADIUS; dz <= SURFACE_SCAN_RADIUS; dz++) {
                if (dx * dx + dz * dz > SURFACE_SCAN_RADIUS * SURFACE_SCAN_RADIUS) {
                    continue;
                }
                for (int dy = 5; dy >= -6; dy--) {
                    int wx = px + dx;
                    int wy = py + dy;
                    int wz = pz + dz;
                    pos.set(wx, wy, wz);

                    FluidState fluid = level.getFluidState(pos);
                    if (!fluid.is(FluidTags.WATER)) {
                        continue;
                    }

                    check.set(wx, wy + 1, wz);
                    if (level.getFluidState(check).is(FluidTags.WATER)) {
                        continue;
                    }

                    float surfaceY = wy + fluid.getHeight(level, pos);
                    int shoreMask = 0;

                    if (isSolidShore(level, check.set(wx, wy, wz - 1))) shoreMask |= 1; // Norte
                    if (isSolidShore(level, check.set(wx, wy, wz + 1))) shoreMask |= 2; // Sul
                    if (isSolidShore(level, check.set(wx + 1, wy, wz))) shoreMask |= 4; // Leste
                    if (isSolidShore(level, check.set(wx - 1, wy, wz))) shoreMask |= 8; // Oeste

                    boolean skyVisible = level.canSeeSky(check.set(wx, wy + 1, wz));
                    WATER_CELLS.add(new WaterCell(wx, wy, wz, surfaceY, shoreMask, skyVisible));
                    break;
                }
            }
        }
    }

    private static boolean isSolidShore(ClientLevel level, BlockPos pos) {
        if (level.getFluidState(pos).is(FluidTags.WATER)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && !state.canBeReplaced();
    }

    private static void handleEntityWaves(ClientLevel level, Player localPlayer) {
        AABB searchArea = localPlayer.getBoundingBox().inflate(18.0, 8.0, 18.0);
        List<Entity> entities = level.getEntitiesOfClass(Entity.class, searchArea);

        for (Entity entity : entities) {
            if (!(entity instanceof LivingEntity || entity instanceof Boat || entity instanceof ItemEntity)) {
                continue;
            }

            boolean inWater = entity.isInWater();
            boolean wasIn = WAS_IN_WATER.getOrDefault(entity.getId(), false);
            WAS_IN_WATER.put(entity.getId(), inWater);

            if (!inWater) {
                continue;
            }

            double surfaceY = findWaterSurfaceY(level, entity.getX(), entity.getY(), entity.getZ());
            if (Double.isNaN(surfaceY)) {
                continue;
            }

            double dx = entity.getX() - entity.xo;
            double dy = entity.getY() - entity.yo;
            double dz = entity.getZ() - entity.zo;
            double horizSpeed = Math.sqrt(dx * dx + dz * dz);

            // Entrada brusca / pulo na agua
            if (!wasIn) {
                float impact = (float) Math.min(1.5, Math.abs(dy) * 2.2 + 0.35);
                addWave(entity.getX(), surfaceY, entity.getZ(), 2.4f + impact * 2.6f, 0.085f, 0.75f + impact * 0.55f, true);
                addWave(entity.getX(), surfaceY, entity.getZ(), 1.6f + impact * 1.4f, 0.055f, 0.55f + impact * 0.3f, true);

                int splashCount = (int) (8 + impact * 14);
                for (int i = 0; i < splashCount; i++) {
                    double angle = RANDOM.nextDouble() * Math.PI * 2.0;
                    double sp = 0.06 + RANDOM.nextDouble() * 0.16 * impact;
                    level.addParticle(
                            ParticleTypes.SPLASH,
                            entity.getX() + Math.cos(angle) * 0.25,
                            surfaceY + 0.05,
                            entity.getZ() + Math.sin(angle) * 0.25,
                            Math.cos(angle) * sp,
                            0.12 + RANDOM.nextDouble() * 0.18 * impact,
                            Math.sin(angle) * sp
                    );
                }
                continue;
            }

            // Andando, correndo, nadando ou de barco
            if (horizSpeed > 0.03) {
                int interval = (horizSpeed > 0.16) ? 2 : 4;
                if ((tickCounter + entity.getId()) % interval == 0) {
                    float sizeScale = entity instanceof Boat ? 1.5f : (entity instanceof ItemEntity ? 0.55f : 1.0f);
                    float maxR = (1.5f + (float) Math.min(2.5, horizSpeed * 7.5)) * sizeScale;
                    float strength = (0.45f + (float) Math.min(0.65, horizSpeed * 2.4)) * sizeScale;

                    // Cria onda ligeiramente atras do movimento (rastro / esteira)
                    double wakeX = entity.getX() - (dx / horizSpeed) * 0.22;
                    double wakeZ = entity.getZ() - (dz / horizSpeed) * 0.22;
                    addWave(wakeX, surfaceY, wakeZ, maxR, 0.062f, strength, true);

                    // Respingos e espuma do movimento
                    int particles = horizSpeed > 0.15 ? 3 : 1;
                    for (int i = 0; i < particles; i++) {
                        double ox = (RANDOM.nextDouble() - 0.5) * 0.45;
                        double oz = (RANDOM.nextDouble() - 0.5) * 0.45;
                        level.addParticle(
                                ParticleTypes.SPLASH,
                                wakeX + ox,
                                surfaceY + 0.03,
                                wakeZ + oz,
                                -dx * 0.4 + (RANDOM.nextDouble() - 0.5) * 0.05,
                                0.06 + horizSpeed * 0.35,
                                -dz * 0.4 + (RANDOM.nextDouble() - 0.5) * 0.05
                        );
                        if (RANDOM.nextFloat() < 0.45f) {
                            level.addParticle(
                                    ParticleTypes.BUBBLE_POP,
                                    wakeX + ox * 1.2,
                                    surfaceY + 0.02,
                                    wakeZ + oz * 1.2,
                                    0.0, 0.01, 0.0
                            );
                        }
                    }

                    if (entity == localPlayer && tickCounter % 12 == 0 && horizSpeed > 0.11) {
                        level.playLocalSound(
                                entity.getX(), surfaceY, entity.getZ(),
                                SoundEvents.GENERIC_SWIM,
                                SoundSource.PLAYERS,
                                0.18f,
                                1.05f + RANDOM.nextFloat() * 0.3f,
                                false
                        );
                    }
                }
            } else if ((tickCounter + entity.getId() * 7) % 26 == 0) {
                // Ondinha suave mesmo parado boiando na agua
                addWave(entity.getX(), surfaceY, entity.getZ(), 1.35f, 0.032f, 0.28f, false);
            }
        }
    }

    private static void handleRainRipples(ClientLevel level) {
        if (!level.isRaining() || WATER_CELLS.isEmpty()) {
            return;
        }

        int attempts = 4;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < attempts; i++) {
            WaterCell cell = WATER_CELLS.get(RANDOM.nextInt(WATER_CELLS.size()));
            if (!cell.skyVisible) {
                continue;
            }
            pos.set(cell.x, cell.y + 1, cell.z);
            if (level.isRainingAt(pos)) {
                double rx = cell.x + 0.15 + RANDOM.nextDouble() * 0.7;
                double rz = cell.z + 0.15 + RANDOM.nextDouble() * 0.7;
                addWave(rx, cell.surfaceY, rz, 0.78f, 0.042f, 0.35f, false);
            }
        }
    }
                                         }
