package com.example.mobmorph;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.*;

/** Серверная логика: состояние превращений, характеристики, тики, запуск способностей. */
public final class MorphManager {
    private static final Map<UUID, BlockState> CARRIED = new HashMap<>();  // блок в руках эндермена
    private static final Map<UUID, Integer> FUSE = new HashMap<>();        // запал крипера
    private static final Map<String, Integer> LAST = new HashMap<>();      // перезарядки

    private MorphManager() {}

    // ---------- проверка / установка / снятие ----------

    public static boolean valid(Identifier id, World w) {
        EntityType<?> t = MorphState.typeOf(id);
        return t != null && t != EntityType.PLAYER && MorphState.profile(t, w) != null;
    }

    public static void set(ServerPlayerEntity p, Identifier id) {
        EntityType<?> t = MorphState.typeOf(id);
        if (t == null) return;
        MorphState.SERVER.put(p.getUuid(), id);
        CARRIED.remove(p.getUuid());
        FUSE.remove(p.getUuid());
        applyStats(p, t);
        broadcast(p.getServer(), p.getUuid());
    }

    public static void clear(ServerPlayerEntity p) {
        boolean was = MorphState.SERVER.remove(p.getUuid()) != null;
        CARRIED.remove(p.getUuid());
        FUSE.remove(p.getUuid());
        String prefix = p.getUuid().toString();
        LAST.keySet().removeIf(k -> k.startsWith(prefix));
        if (was) resetStats(p);
        broadcast(p.getServer(), p.getUuid());
    }

    public static void onJoin(ServerPlayerEntity p) {
        if (!MorphState.SERVER.containsKey(p.getUuid())) resetStats(p); // подстраховка после вылета
        for (UUID id : MorphState.SERVER.keySet()) ServerPlayNetworking.send(p, MobMorph.SYNC, packet(id));
    }

    public static void onRespawn(ServerPlayerEntity p) {
        Identifier id = MorphState.SERVER.get(p.getUuid());
        EntityType<?> t = id == null ? null : MorphState.typeOf(id);
        if (t != null) {
            applyStats(p, t);
            broadcast(p.getServer(), p.getUuid());
        }
    }

    // ---------- характеристики: здоровье, урон, скорость, полёт ----------

    private static void setBase(ServerPlayerEntity p, EntityAttribute attr, double value) {
        EntityAttributeInstance a = p.getAttributeInstance(attr);
        if (a != null) a.setBaseValue(value);
    }

    private static void applyStats(ServerPlayerEntity p, EntityType<?> t) {
        MorphState.Profile pr = MorphState.profile(t, p.getWorld());
        if (pr == null) return;
        setBase(p, EntityAttributes.GENERIC_MAX_HEALTH, pr.maxHealth());
        setBase(p, EntityAttributes.GENERIC_ATTACK_DAMAGE, pr.hasDamage() ? pr.damage() : 1.0);
        setBase(p, EntityAttributes.GENERIC_ARMOR, pr.armor());
        setBase(p, EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, pr.knockback());
        double ratio = pr.hasSpeed() ? Math.min(2.2, Math.max(0.35, pr.speed() / 0.23)) : 1.0;
        setBase(p, EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.1 * ratio);
        p.setHealth(p.getMaxHealth());

        float fly = MobAbilities.flySpeed(t);
        if (fly > 0) {
            p.getAbilities().allowFlying = true;
            p.getAbilities().setFlySpeed(fly);
            p.sendAbilitiesUpdate();
        } else {
            restoreFlight(p);
        }
        p.calculateDimensions();
    }

    private static void resetStats(ServerPlayerEntity p) {
        setBase(p, EntityAttributes.GENERIC_MAX_HEALTH, 20.0);
        setBase(p, EntityAttributes.GENERIC_ATTACK_DAMAGE, 1.0);
        setBase(p, EntityAttributes.GENERIC_ARMOR, 0.0);
        setBase(p, EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 0.0);
        setBase(p, EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.10000000149011612);
        if (p.getHealth() > 20.0f) p.setHealth(20.0f);
        restoreFlight(p);
        p.calculateDimensions();
    }

    private static void restoreFlight(ServerPlayerEntity p) {
        p.interactionManager.getGameMode().setAbilities(p.getAbilities());
        p.getAbilities().setFlySpeed(0.05f);
        p.sendAbilitiesUpdate();
    }

    // ---------- синхронизация ----------

    private static void broadcast(MinecraftServer server, UUID id) {
        if (server == null) return;
        for (ServerPlayerEntity pl : server.getPlayerManager().getPlayerList())
            ServerPlayNetworking.send(pl, MobMorph.SYNC, packet(id));
    }

    private static PacketByteBuf packet(UUID id) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeUuid(id);
        Identifier type = MorphState.SERVER.get(id);
        buf.writeBoolean(type != null);
        if (type != null) {
            buf.writeIdentifier(type);
            BlockState carried = CARRIED.get(id);
            buf.writeInt(carried == null ? 0 : Block.getRawIdFromState(carried));
        }
        return buf;
    }

    // ---------- каждый тик ----------

    public static void tick(MinecraftServer server) {
        for (Map.Entry<UUID, Identifier> e : new ArrayList<>(MorphState.SERVER.entrySet())) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(e.getKey());
            if (p == null || !p.isAlive() || p.isSpectator()) continue;
            EntityType<?> t = MorphState.typeOf(e.getValue());
            if (t == null) continue;

            Integer fuse = FUSE.get(p.getUuid());
            if (fuse != null) {
                if (fuse <= 1) {
                    FUSE.remove(p.getUuid());
                    p.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                            net.minecraft.entity.effect.StatusEffects.RESISTANCE, 20, 4, false, false));
                    p.getWorld().createExplosion(null, p.getX(), p.getY(), p.getZ(), 3.0f,
                            World.ExplosionSourceType.MOB);
                } else {
                    FUSE.put(p.getUuid(), fuse - 1);
                }
            }

            MobAbilities.passive(p, t);
            if (MobAbilities.canFly(t)) p.fallDistance = 0;
            if (p.fallDistance > 0 && p.isTouchingWater()) p.fallDistance = 0;

            EntityDimensions d = p.getDimensions(p.getPose());
            if (Math.abs(d.width - p.getWidth()) > 0.01f || Math.abs(d.height - p.getHeight()) > 0.01f)
                p.calculateDimensions();

            if (p.getRandom().nextInt(160) == 0) MobAbilities.ambient(p, t);
        }
    }

    // ---------- способности ----------

    public static void useAbility(ServerPlayerEntity p, boolean secondary) {
        Identifier id = MorphState.SERVER.get(p.getUuid());
        if (id == null || !p.isAlive() || p.isSpectator()) return;
        EntityType<?> t = MorphState.typeOf(id);
        if (t == null) return;
        MobAbilities.Spec s = MobAbilities.spec(t);

        String key = p.getUuid() + (secondary ? "g" : "r");
        int now = p.getServer().getTicks();
        Integer last = LAST.get(key);
        int cd = secondary ? 10 : s.cd;
        if (last != null && now - last < cd) return;

        if (secondary) {
            if (s.g == null) {
                p.sendMessage(Text.literal("У этого моба нет второй способности"), true);
                return;
            }
            s.g.run(p);
        } else if (s.r == null) {
            MobAbilities.ambient(p, t);
        } else {
            s.r.run(p);
        }
        LAST.put(key, now);
    }

    public static void startFuse(ServerPlayerEntity p) {
        if (FUSE.containsKey(p.getUuid())) return;
        FUSE.put(p.getUuid(), 30);
        p.getWorld().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_CREEPER_PRIMED,
                SoundCategory.HOSTILE, 1.0f, 0.5f);
    }

    public static void grabOrPlaceBlock(ServerPlayerEntity p) {
        World w = p.getWorld();
        HitResult hit = p.raycast(5.0, 1.0f, false);
        if (!(hit instanceof BlockHitResult bhr) || hit.getType() != HitResult.Type.BLOCK) return;
        BlockPos pos = bhr.getBlockPos();
        BlockState carried = CARRIED.get(p.getUuid());

        if (carried == null) {
            BlockState state = w.getBlockState(pos);
            if (state.getHardness(w, pos) < 0 || w.getBlockEntity(pos) != null) {
                p.sendMessage(Text.literal("Этот блок взять нельзя"), true);
                return;
            }
            CARRIED.put(p.getUuid(), state);
            w.removeBlock(pos, false);
        } else {
            BlockPos target = pos.offset(bhr.getSide());
            if (!w.getBlockState(target).isReplaceable()) return;
            w.setBlockState(target, carried);
            CARRIED.remove(p.getUuid());
        }
        w.playSound(null, pos, SoundEvents.ENTITY_ENDERMAN_AMBIENT, SoundCategory.HOSTILE, 1.0f, 1.0f);
        broadcast(p.getServer(), p.getUuid());
    }
}
