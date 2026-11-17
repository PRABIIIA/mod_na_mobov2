package com.example.mobmorph;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.EvokerFangsEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.projectile.DragonFireballEntity;
import net.minecraft.entity.projectile.FireballEntity;
import net.minecraft.entity.projectile.LlamaSpitEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.entity.projectile.ShulkerBulletEntity;
import net.minecraft.entity.projectile.SmallFireballEntity;
import net.minecraft.entity.projectile.TridentEntity;
import net.minecraft.entity.projectile.WitherSkullEntity;
import net.minecraft.entity.projectile.thrown.PotionEntity;
import net.minecraft.entity.projectile.thrown.SnowballEntity;
import net.minecraft.item.BoneMealItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.GameStateChangeS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionUtil;
import net.minecraft.potion.Potions;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Способности (R / G), полёт (V) и пассивные эффекты каждого моба. */
public final class MobAbilities {

    @FunctionalInterface
    public interface Act { void run(ServerPlayerEntity p); }

    public static final class Spec {
        public String rDesc = "голос моба";
        public Act r;
        public String gDesc;
        public Act g;
        public int cd = 20;
        public float fly = 0f;
    }

    private static final Map<EntityType<?>, Spec> SPECS = new HashMap<>();
    private static final Spec DEFAULT = new Spec();

    public static Spec spec(EntityType<?> t) {
        Spec s = SPECS.get(t);
        return s != null ? s : DEFAULT;
    }

    public static String describe(EntityType<?> t) {
        Spec s = spec(t);
        StringBuilder b = new StringBuilder("R: ").append(s.rDesc);
        if (s.g != null) b.append(" | G: ").append(s.gDesc);
        if (s.fly > 0) b.append(" | V: полёт");
        return b.toString();
    }

    private static Spec sp(EntityType<?> t) { return SPECS.computeIfAbsent(t, k -> new Spec()); }

    private static void r(String desc, int cd, Act a, EntityType<?>... ts) {
        for (EntityType<?> t : ts) { Spec s = sp(t); s.rDesc = desc; s.cd = cd; s.r = a; }
    }
    private static void g(String desc, Act a, EntityType<?>... ts) {
        for (EntityType<?> t : ts) { Spec s = sp(t); s.gDesc = desc; s.g = a; }
    }
    private static void fly(float speed, EntityType<?>... ts) {
        for (EntityType<?> t : ts) sp(t).fly = speed;
    }

    // ===================== помощники =====================

    private static void play(ServerPlayerEntity p, SoundEvent s) {
        p.getWorld().playSound(null, p.getX(), p.getY(), p.getZ(), s, SoundCategory.PLAYERS, 1.0f, 1.0f);
    }
    private static Vec3d look(ServerPlayerEntity p) { return p.getRotationVec(1.0f); }
    private static Vec3d muzzle(ServerPlayerEntity p) {
        return p.getEyePos().add(look(p).multiply(p.getWidth() / 2 + 0.7));
    }
    private static float melee(ServerPlayerEntity p) {
        return (float) p.getAttributeValue(EntityAttributes.GENERIC_ATTACK_DAMAGE);
    }
    private static void effect(LivingEntity e, net.minecraft.entity.effect.StatusEffect fx, int ticks, int amp) {
        e.addStatusEffect(new StatusEffectInstance(fx, ticks, amp));
    }
    private static void drop(ServerPlayerEntity p, ItemStack st) {
        Vec3d l = look(p);
        ItemEntity it = new ItemEntity(p.getWorld(), p.getX(), p.getEyeY() - 0.3, p.getZ(), st);
        it.setVelocity(l.x * 0.25, 0.15, l.z * 0.25);
        p.getWorld().spawnEntity(it);
    }

    public static void ambient(ServerPlayerEntity p, EntityType<?> t) {
        Entity e = t.create(p.getWorld());
        if (e instanceof MobEntity m) {
            m.setPosition(p.getPos());
            m.playAmbientSound();
        }
        if (e != null) e.discard();
    }

    /** Удар перед собой по конусу. */
    private static void swipe(ServerPlayerEntity p, double reach, float dmg, double kb, double up,
                              Consumer<LivingEntity> fx) {
        Vec3d look = look(p), eye = p.getEyePos();
        Box box = p.getBoundingBox().expand(reach);
        for (LivingEntity e : p.getWorld().getEntitiesByClass(LivingEntity.class, box,
                x -> x != p && x.isAlive() && !x.isSpectator())) {
            Vec3d to = e.getPos().add(0, e.getHeight() * 0.5, 0).subtract(eye);
            if (to.length() > reach + p.getWidth() / 2 + e.getWidth() / 2) continue;
            if (to.normalize().dotProduct(look) < 0.3) continue;
            if (dmg > 0) e.damage(p.getDamageSources().playerAttack(p), dmg);
            e.takeKnockback(kb, p.getX() - e.getX(), p.getZ() - e.getZ());
            if (up > 0) { e.addVelocity(0, up, 0); e.velocityModified = true; }
            fx.accept(e);
        }
        play(p, SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP);
    }

    /** Удар вокруг себя. */
    private static void area(ServerPlayerEntity p, double r, float dmg, double up, Consumer<LivingEntity> fx) {
        for (LivingEntity e : p.getWorld().getEntitiesByClass(LivingEntity.class, p.getBoundingBox().expand(r),
                x -> x != p && x.isAlive() && !x.isSpectator())) {
            if (e.distanceTo(p) > r + e.getWidth()) continue;
            if (dmg > 0) e.damage(p.getDamageSources().playerAttack(p), dmg);
            e.takeKnockback(0.8, p.getX() - e.getX(), p.getZ() - e.getZ());
            if (up > 0) { e.addVelocity(0, up, 0); e.velocityModified = true; }
            fx.accept(e);
        }
        p.getServerWorld().spawnParticles(ParticleTypes.EXPLOSION, p.getX(), p.getY() + 0.2, p.getZ(),
                3, r / 2, 0.1, r / 2, 0);
    }

    private static void dash(ServerPlayerEntity p, double speed, double up, boolean full3d) {
        Vec3d l = look(p);
        Vec3d v = full3d ? l.multiply(speed)
                : new Vec3d(l.x, 0, l.z).normalize().multiply(speed).add(0, up, 0);
        p.setVelocity(v);
        p.velocityModified = true;
        p.fallDistance = 0;
    }

    private static void leap(ServerPlayerEntity p, double h, double up) { dash(p, h, up, false); }

    private static void dashHit(ServerPlayerEntity p, double speed, float dmg, double up, double kb, boolean full3d) {
        swipe(p, 3.5, dmg, kb, up, e -> {});
        dash(p, speed, 0.2, full3d);
    }

    private static LivingEntity targetAhead(ServerPlayerEntity p, double range) {
        Vec3d s = p.getEyePos(), l = look(p), e = s.add(l.multiply(range));
        EntityHitResult h = ProjectileUtil.raycast(p, s, e, p.getBoundingBox().stretch(l.multiply(range)).expand(1.0),
                en -> en instanceof LivingEntity && !en.isSpectator() && en.canHit(), range * range);
        return h == null ? null : (LivingEntity) h.getEntity();
    }

    private static void arrow(ServerPlayerEntity p, StatusEffectInstance fx, float speed) {
        ArrowEntity a = new ArrowEntity(p.getWorld(), p);
        a.setVelocity(p, p.getPitch(), p.getYaw(), 0.0f, speed, 1.0f);
        a.pickupType = PersistentProjectileEntity.PickupPermission.DISALLOWED;
        if (fx != null) a.addEffect(fx);
        p.getWorld().spawnEntity(a);
        play(p, SoundEvents.ENTITY_SKELETON_SHOOT);
    }

    private static void teleportTo(ServerPlayerEntity p, double range) {
        ServerWorld w = p.getServerWorld();
        HitResult hit = p.raycast(range, 1.0f, false);
        Vec3d pos;
        if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos bp = bhr.getBlockPos().offset(bhr.getSide());
            pos = new Vec3d(bp.getX() + 0.5, bp.getY(), bp.getZ() + 0.5);
            if (bhr.getSide() == Direction.DOWN) pos = pos.add(0, -Math.max(1, p.getHeight() - 1), 0);
        } else {
            pos = hit.getPos();
        }
        w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.HOSTILE, 1f, 1f);
        w.spawnParticles(ParticleTypes.PORTAL, p.getX(), p.getY() + 1, p.getZ(), 30, 0.3, 0.5, 0.3, 0.1);
        p.requestTeleport(pos.x, pos.y, pos.z);
        p.fallDistance = 0;
        w.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.HOSTILE, 1f, 1f);
        w.spawnParticles(ParticleTypes.PORTAL, pos.x, pos.y + 1, pos.z, 30, 0.3, 0.5, 0.3, 0.1);
    }

    private static void breakLookedBlock(ServerPlayerEntity p, double reach) {
        HitResult hit = p.raycast(reach, 1.0f, false);
        if (!(hit instanceof BlockHitResult bhr) || hit.getType() != HitResult.Type.BLOCK) return;
        BlockPos pos = bhr.getBlockPos();
        World w = p.getWorld();
        float hardness = w.getBlockState(pos).getHardness(w, pos);
        if (hardness < 0 || hardness > 10) return;
        w.breakBlock(pos, true, p);
    }

    private static void fangsAt(ServerPlayerEntity p, double x, double z, int warmup) {
        World w = p.getWorld();
        BlockPos.Mutable bp = new BlockPos.Mutable(x, p.getY() + 2, z);
        for (int i = 0; i < 6; i++) {
            BlockPos below = bp.down();
            if (w.getBlockState(below).isSideSolidFullSquare(w, below, Direction.UP)) {
                w.spawnEntity(new EvokerFangsEntity(w, x, bp.getY(), z, p.getYaw(), warmup, p));
                return;
            }
            bp.move(Direction.DOWN);
        }
    }

    private static void fangsLine(ServerPlayerEntity p) {
        double rad = Math.toRadians(p.getYaw());
        Vec3d dir = new Vec3d(-Math.sin(rad), 0, Math.cos(rad));
        for (int i = 1; i <= 14; i++) fangsAt(p, p.getX() + dir.x * i * 1.4, p.getZ() + dir.z * i * 1.4, i);
        play(p, SoundEvents.ENTITY_EVOKER_CAST_SPELL);
    }

    private static void fangsRing(ServerPlayerEntity p) {
        for (int i = 0; i < 16; i++) {
            double a = i * Math.PI / 8;
            double rr = i % 2 == 0 ? 1.5 : 2.8;
            fangsAt(p, p.getX() + Math.cos(a) * rr, p.getZ() + Math.sin(a) * rr, i % 2 == 0 ? 0 : 6);
        }
        play(p, SoundEvents.ENTITY_EVOKER_PREPARE_ATTACK);
    }

    private static void growCrops(ServerPlayerEntity p, int radius) {
        World w = p.getWorld();
        int n = 0;
        for (BlockPos bp : BlockPos.iterate(p.getBlockPos().add(-radius, -1, -radius), p.getBlockPos().add(radius, 2, radius))) {
            if (n >= 24) break;
            if (w.getBlockState(bp).getBlock() instanceof CropBlock
                    && BoneMealItem.useOnFertilizable(new ItemStack(Items.BONE_MEAL), w, bp)) n++;
        }
        if (n > 0) play(p, SoundEvents.ITEM_BONE_MEAL_USE);
    }

    private static void ink(ServerPlayerEntity p, boolean glow) {
        for (LivingEntity e : p.getWorld().getEntitiesByClass(LivingEntity.class, p.getBoundingBox().expand(6),
                x -> x != p && x.isAlive())) {
            effect(e, glow ? StatusEffects.GLOWING : StatusEffects.BLINDNESS, 120, 0);
        }
        p.getServerWorld().spawnParticles(glow ? ParticleTypes.GLOW_SQUID_INK : ParticleTypes.SQUID_INK,
                p.getX(), p.getY() + p.getHeight() / 2, p.getZ(), 40, 0.5, 0.5, 0.5, 0.1);
        play(p, SoundEvents.ENTITY_SQUID_SQUIRT);
        if (p.isTouchingWater()) dash(p, 1.0, 0, true);
    }

    private static void sonicBoom(ServerPlayerEntity p) {
        Vec3d eye = p.getEyePos(), l = look(p);
        ServerWorld w = p.getServerWorld();
        for (int i = 1; i <= 15; i++)
            w.spawnParticles(ParticleTypes.SONIC_BOOM, eye.x + l.x * i, eye.y + l.y * i, eye.z + l.z * i, 1, 0, 0, 0, 0);
        for (LivingEntity e : w.getEntitiesByClass(LivingEntity.class, p.getBoundingBox().stretch(l.multiply(15)).expand(2),
                x -> x != p && x.isAlive())) {
            Vec3d to = e.getEyePos().subtract(eye);
            double proj = to.dotProduct(l);
            if (proj > 0 && proj < 15 && to.subtract(l.multiply(proj)).length() < 1.6) {
                e.damage(p.getDamageSources().sonicBoom(p), 10.0f);
                e.takeKnockback(2.0, -l.x, -l.z);
            }
        }
        play(p, SoundEvents.ENTITY_WARDEN_SONIC_BOOM);
    }

    private static void guardianBeam(ServerPlayerEntity p, float dmg) {
        LivingEntity t = targetAhead(p, 16);
        play(p, SoundEvents.ENTITY_GUARDIAN_ATTACK);
        if (t == null) return;
        t.damage(p.getDamageSources().indirectMagic(p, p), dmg);
        Vec3d a = p.getEyePos(), b = t.getEyePos();
        for (int i = 0; i <= 20; i++) {
            double f = i / 20.0;
            p.getServerWorld().spawnParticles(ParticleTypes.BUBBLE_POP, a.x + (b.x - a.x) * f,
                    a.y + (b.y - a.y) * f, a.z + (b.z - a.z) * f, 1, 0, 0, 0, 0);
        }
    }

    private static void elderCurse(ServerPlayerEntity p) {
        for (ServerPlayerEntity o : p.getServerWorld().getPlayers()) {
            if (o == p || o.distanceTo(p) > 40) continue;
            o.addStatusEffect(new StatusEffectInstance(StatusEffects.MINING_FATIGUE, 6000, 2));
            o.networkHandler.sendPacket(new GameStateChangeS2CPacket(GameStateChangeS2CPacket.ELDER_GUARDIAN_EFFECT, 1.0f));
        }
        play(p, SoundEvents.ENTITY_ELDER_GUARDIAN_CURSE);
    }

    private static void zombieAttack(ServerPlayerEntity p) {
        HitResult hit = p.raycast(3.5, 1.0f, false);
        if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK
                && p.getWorld().getBlockState(bhr.getBlockPos()).isIn(BlockTags.WOODEN_DOORS)) {
            p.getWorld().breakBlock(bhr.getBlockPos(), true, p);
            play(p, SoundEvents.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR);
            return;
        }
        swipe(p, 3.0, melee(p), 0.5, 0, e -> {});
    }

    private static void piglinBarter(ServerPlayerEntity p) {
        ItemStack s = p.getMainHandStack();
        if (!s.isOf(Items.GOLD_INGOT)) { swipe(p, 3.0, melee(p) + 3, 0.5, 0, e -> {}); return; }
        s.decrement(1);
        Item[] loot = { Items.IRON_NUGGET, Items.GRAVEL, Items.CRYING_OBSIDIAN, Items.OBSIDIAN, Items.ENDER_PEARL,
                Items.STRING, Items.QUARTZ, Items.LEATHER, Items.FIRE_CHARGE, Items.SOUL_SAND, Items.NETHER_BRICK,
                Items.SPECTRAL_ARROW, Items.GLOWSTONE_DUST, Items.BLACKSTONE };
        drop(p, new ItemStack(loot[p.getRandom().nextInt(loot.length)], 1 + p.getRandom().nextInt(4)));
        play(p, SoundEvents.ENTITY_PIGLIN_ADMIRING_ITEM);
    }

    private static void sheepEat(ServerPlayerEntity p) {
        World w = p.getWorld();
        BlockPos below = p.getBlockPos().down();
        if (w.getBlockState(below).isOf(Blocks.GRASS_BLOCK)) {
            w.syncWorldEvent(2001, below, Block.getRawIdFromState(Blocks.GRASS_BLOCK.getDefaultState()));
            w.setBlockState(below, Blocks.DIRT.getDefaultState());
            p.heal(4.0f);
        } else if (w.getBlockState(p.getBlockPos()).isOf(Blocks.GRASS)) {
            w.breakBlock(p.getBlockPos(), false, p);
            p.heal(4.0f);
        }
        play(p, SoundEvents.ENTITY_SHEEP_AMBIENT);
    }

    private static void sniff(ServerPlayerEntity p) {
        BlockState below = p.getWorld().getBlockState(p.getBlockPos().down());
        if (below.isIn(BlockTags.DIRT)) {
            drop(p, new ItemStack(p.getRandom().nextBoolean() ? Items.TORCHFLOWER_SEEDS : Items.PITCHER_POD));
            play(p, SoundEvents.ENTITY_SNIFFER_DIGGING);
        } else {
            play(p, SoundEvents.ENTITY_SNIFFER_SNIFFING);
        }
    }

    private static void allayMagnet(ServerPlayerEntity p) {
        for (ItemEntity it : p.getWorld().getEntitiesByClass(ItemEntity.class, p.getBoundingBox().expand(12), x -> true)) {
            Vec3d d = p.getEyePos().subtract(it.getPos()).normalize().multiply(0.5);
            it.setVelocity(d);
            it.velocityModified = true;
        }
        play(p, SoundEvents.ENTITY_ALLAY_ITEM_TAKEN);
    }

    private static void parrotMimic(ServerPlayerEntity p) {
        EntityType<?>[] pool = { EntityType.ZOMBIE, EntityType.SKELETON, EntityType.CREEPER, EntityType.SPIDER,
                EntityType.ENDERMAN, EntityType.WITCH, EntityType.BLAZE, EntityType.GHAST, EntityType.PHANTOM,
                EntityType.SLIME, EntityType.WARDEN, EntityType.PILLAGER };
        ambient(p, pool[p.getRandom().nextInt(pool.length)]);
    }

    private static void witchPotion(ServerPlayerEntity p) {
        Potion[] ps = { Potions.HARMING, Potions.POISON, Potions.SLOWNESS, Potions.WEAKNESS };
        PotionEntity pe = new PotionEntity(p.getWorld(), p);
        pe.setItem(PotionUtil.setPotion(new ItemStack(Items.SPLASH_POTION), ps[p.getRandom().nextInt(ps.length)]));
        pe.setVelocity(p, p.getPitch(), p.getYaw(), -10.0f, 0.7f, 1.0f);
        p.getWorld().spawnEntity(pe);
        play(p, SoundEvents.ENTITY_WITCH_THROW);
    }

    private static void witchDrink(ServerPlayerEntity p) {
        int k = p.getRandom().nextInt(4);
        if (k == 0) effect(p, StatusEffects.REGENERATION, 200, 1);
        else if (k == 1) effect(p, StatusEffects.SPEED, 600, 0);
        else if (k == 2) effect(p, StatusEffects.FIRE_RESISTANCE, 600, 0);
        else effect(p, StatusEffects.WATER_BREATHING, 600, 0);
        p.heal(4.0f);
        play(p, SoundEvents.ENTITY_WITCH_DRINK);
    }

    private static void llamaSpit(ServerPlayerEntity p) {
        LlamaSpitEntity spit = EntityType.LLAMA_SPIT.create(p.getWorld());
        if (spit == null) return;
        Vec3d l = look(p);
        spit.setOwner(p);
        spit.setPosition(muzzle(p));
        spit.setVelocity(l.x, l.y + 0.1, l.z, 1.5f, 4.0f);
        p.getWorld().spawnEntity(spit);
        play(p, SoundEvents.ENTITY_LLAMA_SPIT);
    }

    private static void shulkerShot(ServerPlayerEntity p) {
        LivingEntity t = targetAhead(p, 32);
        if (t == null) { p.sendMessage(net.minecraft.text.Text.literal("Нет цели перед тобой"), true); return; }
        ShulkerBulletEntity b = new ShulkerBulletEntity(p.getWorld(), p, t, Direction.Axis.Y);
        b.setPosition(muzzle(p));
        p.getWorld().spawnEntity(b);
        play(p, SoundEvents.ENTITY_SHULKER_SHOOT);
    }

    private static void tridentThrow(ServerPlayerEntity p) {
        TridentEntity t = new TridentEntity(p.getWorld(), p, new ItemStack(Items.TRIDENT));
        t.setVelocity(p, p.getPitch(), p.getYaw(), 0.0f, 2.5f, 1.0f);
        t.pickupType = PersistentProjectileEntity.PickupPermission.CREATIVE_ONLY;
        p.getWorld().spawnEntity(t);
        play(p, SoundEvents.ENTITY_DROWNED_SHOOT);
    }

    private static void snowball(ServerPlayerEntity p) {
        SnowballEntity s = new SnowballEntity(p.getWorld(), p);
        s.setVelocity(p, p.getPitch(), p.getYaw(), 0.0f, 1.5f, 1.0f);
        p.getWorld().spawnEntity(s);
        play(p, SoundEvents.ENTITY_SNOW_GOLEM_SHOOT);
    }

    private static void smallFireballs(ServerPlayerEntity p) {
        Vec3d l = look(p);
        for (int i = 0; i < 3; i++) {
            double sp = i == 0 ? 0 : 0.12;
            SmallFireballEntity f = new SmallFireballEntity(p.getWorld(), p,
                    l.x + p.getRandom().nextGaussian() * sp, l.y + p.getRandom().nextGaussian() * sp,
                    l.z + p.getRandom().nextGaussian() * sp);
            f.setPosition(muzzle(p));
            p.getWorld().spawnEntity(f);
        }
        play(p, SoundEvents.ENTITY_BLAZE_SHOOT);
    }

    private static void ghastFireball(ServerPlayerEntity p) {
        Vec3d l = look(p);
        FireballEntity f = new FireballEntity(p.getWorld(), p, l.x, l.y, l.z, 2);
        f.setPosition(muzzle(p));
        p.getWorld().spawnEntity(f);
        play(p, SoundEvents.ENTITY_GHAST_SHOOT);
    }

    private static void witherSkull(ServerPlayerEntity p) {
        Vec3d l = look(p);
        WitherSkullEntity s = new WitherSkullEntity(p.getWorld(), p, l.x, l.y, l.z);
        s.setPosition(muzzle(p));
        p.getWorld().spawnEntity(s);
        play(p, SoundEvents.ENTITY_WITHER_SHOOT);
    }

    private static void dragonBreath(ServerPlayerEntity p) {
        Vec3d l = look(p);
        DragonFireballEntity f = new DragonFireballEntity(p.getWorld(), p, l.x, l.y, l.z);
        f.setPosition(muzzle(p));
        p.getWorld().spawnEntity(f);
        play(p, SoundEvents.ENTITY_ENDER_DRAGON_SHOOT);
    }

    // ===================== таблица способностей =====================

    static {
        // --- Крипер / Эндермен / Эндермит / Чешуйница
        r("взрыв (запал 1,5 сек)", 40, MorphManager::startFuse, EntityType.CREEPER);
        r("телепорт туда, куда смотришь", 10, p -> teleportTo(p, 32), EntityType.ENDERMAN);
        g("взять / поставить блок", MorphManager::grabOrPlaceBlock, EntityType.ENDERMAN);
        r("короткий телепорт (8 блоков)", 15, p -> teleportTo(p, 8), EntityType.ENDERMITE);
        r("ломает блок, на который смотришь", 8, p -> breakLookedBlock(p, 5), EntityType.SILVERFISH);

        // --- Скелеты и стрелки
        r("выстрел из лука", 12, p -> arrow(p, null, 2.5f), EntityType.SKELETON);
        r("стрела с замедлением", 12,
                p -> arrow(p, new StatusEffectInstance(StatusEffects.SLOWNESS, 200, 1), 2.5f), EntityType.STRAY);
        r("удар мечом + иссушение", 10, p -> swipe(p, 3.0, melee(p) + 4, 0.6, 0,
                e -> effect(e, StatusEffects.WITHER, 200, 0)), EntityType.WITHER_SKELETON);
        r("выстрел из арбалета", 25, p -> arrow(p, null, 3.15f), EntityType.PILLAGER);

        // --- Зомби и ближний бой
        r("ломает деревянные двери / удар", 10, MobAbilities::zombieAttack,
                EntityType.ZOMBIE, EntityType.HUSK, EntityType.ZOMBIE_VILLAGER, EntityType.VINDICATOR);
        r("бросок трезубца", 30, MobAbilities::tridentThrow, EntityType.DROWNED);
        r("удар золотым мечом", 10, p -> swipe(p, 3.0, melee(p) + 4, 0.6, 0, e -> {}), EntityType.ZOMBIFIED_PIGLIN);
        r("обмен золота (золото в руке) / удар", 20, MobAbilities::piglinBarter, EntityType.PIGLIN);
        r("мощный удар топором", 12, p -> swipe(p, 3.2, melee(p) + 5, 0.9, 0, e -> {}), EntityType.PIGLIN_BRUTE);
        r("удар клыками вверх", 20, p -> dashHit(p, 1.1, melee(p), 0.8, 1.2, false), EntityType.HOGLIN, EntityType.ZOGLIN);
        r("таран с мощным ударом", 40, p -> dashHit(p, 1.3, melee(p) + 6, 0.5, 2.0, false), EntityType.RAVAGER);
        r("топот: удар вокруг себя", 40, p -> area(p, 6, 20, 1.2, e -> {}), EntityType.GIANT);
        r("удар кулаком вокруг себя (подбрасывает)", 40, p -> area(p, 4.5, 14, 0.9, e -> {}), EntityType.IRON_GOLEM);

        // --- Пауки
        r("прыжок; пассивно: лазание по стенам", 30, p -> leap(p, 0.9, 0.45), EntityType.SPIDER);
        r("ядовитый прыжок; лазание по стенам", 30, p -> leap(p, 0.9, 0.45), EntityType.CAVE_SPIDER);

        // --- Слизни
        r("высокий прыжок", 20, p -> leap(p, 0.7, 1.0), EntityType.SLIME);
        r("огненный прыжок (поджигает рядом)", 30, p -> { leap(p, 0.9, 0.9); area(p, 3.5, 0, 0, e -> e.setOnFireFor(5)); },
                EntityType.MAGMA_CUBE);

        // --- Ведьма, призыватели, колдуны
        r("метает зелья (вред / яд / замедление / слабость)", 25, MobAbilities::witchPotion, EntityType.WITCH);
        g("выпить зелье (лечение / бафф)", MobAbilities::witchDrink, EntityType.WITCH);
        r("клыки вдоль взгляда", 60, MobAbilities::fangsLine, EntityType.EVOKER);
        g("кольцо клыков вокруг себя", MobAbilities::fangsRing, EntityType.EVOKER);
        r("ослепляет всех рядом и делает тебя невидимым", 120, p -> {
            area(p, 8, 0, 0, e -> effect(e, StatusEffects.BLINDNESS, 100, 0));
            effect(p, StatusEffects.INVISIBILITY, 160, 0);
        }, EntityType.ILLUSIONER);

        // --- Стражи, хранитель, шалкер
        r("лазерный луч стража", 30, p -> guardianBeam(p, 6), EntityType.GUARDIAN);
        r("лазерный луч (сильнее)", 30, p -> guardianBeam(p, 9), EntityType.ELDER_GUARDIAN);
        g("проклятье: усталость + жуткое лицо", MobAbilities::elderCurse, EntityType.ELDER_GUARDIAN);
        r("звуковой удар", 60, MobAbilities::sonicBoom, EntityType.WARDEN);
        g("рёв: тьма всем игрокам рядом", p -> {
            for (ServerPlayerEntity o : p.getServerWorld().getPlayers())
                if (o != p && o.distanceTo(p) < 24) effect(o, StatusEffects.DARKNESS, 200, 0);
            play(p, SoundEvents.ENTITY_WARDEN_ROAR);
        }, EntityType.WARDEN);
        r("самонаводящаяся пуля (на цель перед тобой)", 40, MobAbilities::shulkerShot, EntityType.SHULKER);

        // --- Летающие и стрелки огнём
        r("три огненных шара", 30, MobAbilities::smallFireballs, EntityType.BLAZE);
        r("огненный шар", 40, MobAbilities::ghastFireball, EntityType.GHAST);
        r("череп иссушителя", 25, MobAbilities::witherSkull, EntityType.WITHER);
        r("огненное дыхание дракона", 30, MobAbilities::dragonBreath, EntityType.ENDER_DRAGON);
        r("пикирование с ударом", 40, p -> dashHit(p, 1.6, 6, 0.1, 0.8, true), EntityType.PHANTOM);
        r("рывок-атака сквозь врагов", 30, p -> dashHit(p, 1.5, 5, 0.1, 0.6, true), EntityType.VEX);
        r("опыляет посевы рядом (ускоряет рост)", 40, p -> growCrops(p, 4), EntityType.BEE);
        r("ночное зрение + писк", 100, p -> { effect(p, StatusEffects.NIGHT_VISION, 1200, 0); play(p, SoundEvents.ENTITY_BAT_AMBIENT); },
                EntityType.BAT);
        r("притягивает предметы вокруг", 40, MobAbilities::allayMagnet, EntityType.ALLAY);
        r("подражает случайному мобу", 40, MobAbilities::parrotMimic, EntityType.PARROT);

        // --- Мирные и нейтральные
        r("храп: исцеление + очищение эффектов", 100, p -> { p.clearStatusEffects(); effect(p, StatusEffects.REGENERATION, 100, 1); },
                EntityType.MOOSHROOM);
        r("молоко: снимает все эффекты", 100, p -> { p.clearStatusEffects(); play(p, SoundEvents.ENTITY_COW_AMBIENT); }, EntityType.COW);
        r("щиплет траву: лечение", 60, MobAbilities::sheepEat, EntityType.SHEEP);
        r("несёт яйцо", 100, p -> { drop(p, new ItemStack(Items.EGG)); play(p, SoundEvents.ENTITY_CHICKEN_EGG); }, EntityType.CHICKEN);
        r("ускорение (как морковка на палке)", 60, p -> { effect(p, StatusEffects.SPEED, 80, 1); play(p, SoundEvents.ENTITY_PIG_AMBIENT); },
                EntityType.PIG);
        r("ускорение по лаве/суше", 60, p -> { effect(p, StatusEffects.SPEED, 120, 1); play(p, SoundEvents.ENTITY_STRIDER_HAPPY); },
                EntityType.STRIDER);
        r("вой: скорость + сила", 200, p -> { effect(p, StatusEffects.SPEED, 200, 1); effect(p, StatusEffects.STRENGTH, 200, 0);
            play(p, SoundEvents.ENTITY_WOLF_HOWL); }, EntityType.WOLF);
        r("прыжок кошки", 25, p -> { leap(p, 0.9, 0.55); play(p, SoundEvents.ENTITY_CAT_AMBIENT); }, EntityType.CAT, EntityType.OCELOT);
        r("прыжок лисы", 25, p -> leap(p, 0.9, 0.55), EntityType.FOX);
        r("огромный прыжок кролика", 20, p -> leap(p, 1.0, 0.8), EntityType.RABBIT);
        r("прыжок лягушки", 20, p -> leap(p, 0.9, 0.9), EntityType.FROG);
        r("маленький прыжок", 15, p -> leap(p, 0.5, 0.45), EntityType.TADPOLE);
        r("таран козла", 40, p -> dashHit(p, 1.3, 5, 0.3, 1.8, false), EntityType.GOAT);
        r("рывок верблюда", 50, p -> dash(p, 1.7, 0.2, false), EntityType.CAMEL);
        r("галоп: скорость", 100, p -> { effect(p, StatusEffects.SPEED, 120, 1); play(p, SoundEvents.ENTITY_HORSE_GALLOP); },
                EntityType.HORSE, EntityType.DONKEY, EntityType.MULE, EntityType.SKELETON_HORSE, EntityType.ZOMBIE_HORSE);
        r("плюётся", 20, MobAbilities::llamaSpit, EntityType.LLAMA, EntityType.TRADER_LLAMA);
        r("кидает снежок", 8, MobAbilities::snowball, EntityType.SNOW_GOLEM);
        r("рык: сила", 200, p -> { effect(p, StatusEffects.STRENGTH, 200, 0); play(p, SoundEvents.ENTITY_POLAR_BEAR_WARNING); },
                EntityType.POLAR_BEAR);
        r("чихает: отбрасывает рядом + слизь", 120, p -> {
            area(p, 4, 0, 0.3, e -> {});
            drop(p, new ItemStack(Items.SLIME_BALL));
            play(p, SoundEvents.ENTITY_PANDA_SNEEZE);
        }, EntityType.PANDA);
        r("ускоряет рост посевов рядом", 40, p -> growCrops(p, 4), EntityType.VILLAGER);
        r("пьёт зелье невидимости", 400, p -> { effect(p, StatusEffects.INVISIBILITY, 1200, 0); play(p, SoundEvents.ENTITY_WANDERING_TRADER_DRINK_POTION); },
                EntityType.WANDERING_TRADER);
        r("копает: семена (на земле)", 100, MobAbilities::sniff, EntityType.SNIFFER);
        r("панцирь: защита, но медлительность", 200, p -> { effect(p, StatusEffects.RESISTANCE, 200, 3); effect(p, StatusEffects.SLOWNESS, 200, 3); },
                EntityType.TURTLE);
        r("притворяется мёртвым: регенерация", 200, p -> { effect(p, StatusEffects.REGENERATION, 100, 1); effect(p, StatusEffects.RESISTANCE, 100, 1); },
                EntityType.AXOLOTL);

        // --- Водные
        r("чернильное облако: ослепляет рядом + рывок", 60, p -> ink(p, false), EntityType.SQUID);
        r("светящиеся чернила: подсвечивает рядом + рывок", 60, p -> ink(p, true), EntityType.GLOW_SQUID);
        r("надувается: яд всем рядом", 100, p -> {
            area(p, 3.5, 0, 0, e -> { effect(e, StatusEffects.POISON, 200, 0); effect(e, StatusEffects.NAUSEA, 300, 0); });
            play(p, SoundEvents.ENTITY_PUFFER_FISH_BLOW_UP);
        }, EntityType.PUFFERFISH);
        r("рывок в воде", 25, p -> { if (p.isTouchingWater()) dash(p, 1.1, 0, true); else leap(p, 0.2, 0.35); },
                EntityType.COD, EntityType.SALMON, EntityType.TROPICAL_FISH);
        r("рывок дельфина в воде", 25, p -> { if (p.isTouchingWater()) dash(p, 1.6, 0, true); else leap(p, 0.2, 0.35); },
                EntityType.DOLPHIN);

        // --- Полёт (кнопка V)
        fly(0.05f, EntityType.BAT, EntityType.PARROT, EntityType.ALLAY);
        fly(0.04f, EntityType.BEE, EntityType.GHAST);
        fly(0.06f, EntityType.BLAZE, EntityType.WITHER);
        fly(0.07f, EntityType.VEX);
        fly(0.09f, EntityType.PHANTOM);
        fly(0.12f, EntityType.ENDER_DRAGON);
    }

    // ===================== полёт =====================

    public static boolean canFly(EntityType<?> t) { return spec(t).fly > 0; }
    public static float flySpeed(EntityType<?> t) { return spec(t).fly; }

    // ===================== пассивные эффекты =====================

    private static final Set<EntityType<?>> FIRE_IMMUNE = Set.<EntityType<?>>of(EntityType.BLAZE, EntityType.GHAST,
            EntityType.MAGMA_CUBE, EntityType.STRIDER, EntityType.WITHER_SKELETON, EntityType.ZOMBIFIED_PIGLIN, EntityType.WITHER);
    private static final Set<EntityType<?>> SWIMMERS = Set.<EntityType<?>>of(EntityType.COD, EntityType.SALMON,
            EntityType.TROPICAL_FISH, EntityType.PUFFERFISH, EntityType.SQUID, EntityType.GLOW_SQUID, EntityType.DOLPHIN,
            EntityType.GUARDIAN, EntityType.ELDER_GUARDIAN, EntityType.AXOLOTL);
    private static final Set<EntityType<?>> BREATHES_WATER = Set.<EntityType<?>>of(EntityType.COD, EntityType.SALMON,
            EntityType.TROPICAL_FISH, EntityType.PUFFERFISH, EntityType.SQUID, EntityType.GLOW_SQUID, EntityType.DOLPHIN,
            EntityType.GUARDIAN, EntityType.ELDER_GUARDIAN, EntityType.AXOLOTL, EntityType.DROWNED, EntityType.TURTLE,
            EntityType.TADPOLE);
    /** Рыбы и кальмары задыхаются на суше. */
    private static final Set<EntityType<?>> DRY_OUT = Set.<EntityType<?>>of(EntityType.COD, EntityType.SALMON,
            EntityType.TROPICAL_FISH, EntityType.PUFFERFISH, EntityType.SQUID, EntityType.GLOW_SQUID, EntityType.DOLPHIN);
    private static final Set<EntityType<?>> SUNBURN = Set.<EntityType<?>>of(EntityType.ZOMBIE, EntityType.SKELETON,
            EntityType.STRAY, EntityType.ZOMBIE_VILLAGER, EntityType.PHANTOM);
    public static final Set<EntityType<?>> NO_FALL_DAMAGE = Set.<EntityType<?>>of(EntityType.CAT, EntityType.OCELOT,
            EntityType.FROG, EntityType.SLIME, EntityType.MAGMA_CUBE, EntityType.BAT, EntityType.BEE, EntityType.PARROT,
            EntityType.ALLAY, EntityType.CHICKEN, EntityType.GHAST, EntityType.PHANTOM, EntityType.VEX, EntityType.BLAZE,
            EntityType.WITHER, EntityType.ENDER_DRAGON, EntityType.COD, EntityType.SALMON, EntityType.TROPICAL_FISH,
            EntityType.PUFFERFISH, EntityType.SQUID, EntityType.GLOW_SQUID, EntityType.DOLPHIN, EntityType.TADPOLE);

    public static void passive(ServerPlayerEntity p, EntityType<?> t) {
        World w = p.getWorld();
        if (p.age % 20 == 0) {
            if (FIRE_IMMUNE.contains(t)) p.addStatusEffect(new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE, 60, 0, false, false, false));
            if (BREATHES_WATER.contains(t)) p.addStatusEffect(new StatusEffectInstance(StatusEffects.WATER_BREATHING, 60, 0, false, false, false));
            if (SWIMMERS.contains(t)) p.addStatusEffect(new StatusEffectInstance(StatusEffects.DOLPHINS_GRACE, 60, 0, false, false, false));
            if (DRY_OUT.contains(t) && !p.isTouchingWater()) p.damage(p.getDamageSources().dryOut(), 1.0f);
        }
        if (p.age % 10 == 0) {
            if (SUNBURN.contains(t) && w.isDay() && !w.isRaining() && !p.isWet()
                    && w.isSkyVisible(p.getBlockPos()) && p.getEquippedStack(EquipmentSlot.HEAD).isEmpty()) {
                p.setOnFireFor(8);
            }
            if (t == EntityType.ENDERMAN && p.isWet()) p.damage(p.getDamageSources().drown(), 1.0f);
        }
        if ((t == EntityType.CHICKEN || t == EntityType.PARROT) && !p.isOnGround() && p.getVelocity().y < -0.1) {
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOW_FALLING, 5, 0, false, false, false));
        }
        if (t == EntityType.SNOW_GOLEM && p.age % 4 == 0) {
            BlockPos bp = p.getBlockPos();
            if (w.getBlockState(bp).isAir() && Blocks.SNOW.getDefaultState().canPlaceAt(w, bp))
                w.setBlockState(bp, Blocks.SNOW.getDefaultState());
        }
    }

    /** Эффекты при обычном ударе (левая кнопка мыши). */
    public static void onHit(EntityType<?> t, LivingEntity target) {
        if (t == EntityType.CAVE_SPIDER) effect(target, StatusEffects.POISON, 140, 0);
        else if (t == EntityType.HUSK) effect(target, StatusEffects.HUNGER, 140, 0);
        else if (t == EntityType.WITHER_SKELETON) effect(target, StatusEffects.WITHER, 200, 0);
        else if (t == EntityType.BEE) effect(target, StatusEffects.POISON, 200, 0);
        else if (t == EntityType.BLAZE || t == EntityType.MAGMA_CUBE) target.setOnFireFor(4);
        else if (t == EntityType.STRAY) effect(target, StatusEffects.SLOWNESS, 100, 0);
    }
}
