package com.trypc;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import com.mojang.serialization.DataResult;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.Uuids;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * trypc: при смерти игрока вещи не разлетаются, а появляется труп (манекен со скином игрока).
 * Поза трупа зависит от причины смерти. Если игрок упал, тело кружится и замирает.
 * В обычном режиме при возрождении труп исчезает, а вещи выпадают из него.
 * В хардкоре труп остаётся, вещи хранятся в нём; их можно забрать, ударив/нажав ПКМ по трупу.
 */
public class TrypcMod implements ModInitializer {
    public static final String TAG = "trypc_corpse";
    private static final Identifier STORAGE = Identifier.of("trypc", "corpses");

    /** Тело, которое сейчас кружится после падения. */
    private static final class Spin {
        final ServerWorld world;
        final UUID id;
        float speed;
        Spin(ServerWorld world, UUID id, float speed) { this.world = world; this.id = id; this.speed = speed; }
    }

    private static final List<Spin> SPINS = new ArrayList<>();

    @Override
    public void onInitialize() {
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayerEntity player) {
                try {
                    onPlayerDeath(player, source);
                } catch (Throwable t) {
                    System.err.println("[trypc] ошибка при создании трупа: " + t);
                }
            }
            return true;
        });

        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            if (alive) return; // возвращение из Края, а не смерть
            MinecraftServer server = ((ServerWorld) newPlayer.getEntityWorld()).getServer();
            if (server == null || server.isHardcore()) return;
            try {
                releaseAllOf(server, newPlayer.getUuid());
            } catch (Throwable t) {
                System.err.println("[trypc] ошибка при возрождении: " + t);
            }
        });

        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClient() || !entity.getCommandTags().contains(TAG)) return ActionResult.PASS;
            MinecraftServer server = world.getServer();
            if (server == null || !server.isHardcore()) return ActionResult.PASS;
            try {
                releaseByCorpse(server, entity.getUuid());
            } catch (Throwable t) {
                System.err.println("[trypc] ошибка при открытии трупа: " + t);
            }
            return ActionResult.SUCCESS;
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> tickSpins());
    }

    // ------------------------------------------------------------------ смерть

    private static void onPlayerDeath(ServerPlayerEntity player, DamageSource source) {
        ServerWorld world = (ServerWorld) player.getEntityWorld();
        MinecraftServer server = world.getServer();
        RegistryOps<NbtElement> ops = server.getRegistryManager().getOps(NbtOps.INSTANCE);

        // 0. Запоминаем броню, чтобы труп остался в ней
        String equipment = equipmentSnbt(player, ops);

        // 1. Забираем вещи из инвентаря, чтобы они не разлетелись
        NbtList items = new NbtList();
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            DataResult<NbtElement> enc = ItemStack.CODEC.encodeStart(ops, stack.copy());
            enc.result().ifPresent(items::add);
            inv.setStack(i, ItemStack.EMPTY);
        }

        // 2. Причина смерти -> поза и подпись
        String cause = causeOf(source);
        String pose;
        String text;
        boolean spin = false;
        boolean starve = false;
        switch (cause) {
            case "fall", "flyIntoWall", "stalagmite" -> { pose = "sleeping"; text = "Разбился при падении"; spin = true; }
            case "drown" -> { pose = "swimming"; text = "Утонул"; }
            case "inFire", "onFire", "lava", "hotFloor", "campfire" -> { pose = "crouching"; text = "Сгорел"; }
            case "starve" -> { pose = "sleeping"; text = "Умер от голода"; starve = true; }
            case "freeze" -> { pose = "crouching"; text = "Замёрз"; }
            case "explosion", "explosion.player", "fireworks" -> { pose = "sleeping"; text = "Взорван"; }
            default -> {
                pose = "sleeping";
                text = (cause.startsWith("mob") || cause.startsWith("player") || cause.contains("arrow")) ? "Убит" : "Погиб";
            }
        }

        // 3. Создаём труп командой summon (манекен со скином игрока)
        UUID corpseId = UUID.randomUUID();
        int[] u = Uuids.toIntArray(corpseId);
        float yaw = spin ? (float) (Math.random() * 360.0) : player.getYaw();
        double cx = player.getX();
        double cz = player.getZ();
        if (starve) {
            // Если рядом стена, тело садится спиной к ней; иначе падает и лежит
            BlockPos feet = player.getBlockPos();
            for (Direction d : new Direction[] { Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST }) {
                BlockPos wall = feet.offset(d);
                if (world.getBlockState(wall).isSolidBlock(world, wall)
                        && world.getBlockState(wall.up()).isSolidBlock(world, wall.up())) {
                    pose = "crouching";
                    text = "Умер от голода у стены";
                    yaw = d.getOpposite().getPositiveHorizontalDegrees();   // лицом от стены, спиной к ней
                    cx += d.getOffsetX() * 0.28;
                    cz += d.getOffsetZ() * 0.28;
                    break;
                }
            }
        }
        String name = player.getGameProfile().name();
        String dim = world.getRegistryKey().getValue().toString();
        String cmd = String.format(java.util.Locale.ROOT,
                "execute in %s run summon minecraft:mannequin %f %f %f {UUID:[I;%d,%d,%d,%d],Tags:[\"%s\"],"
                        + "Invulnerable:1b,immovable:1b,Silent:1b,pose:\"%s\",profile:%s,Rotation:[%ff,0f],%s"
                        + "CustomName:{text:\"Труп: %s\"},CustomNameVisible:1b,description:{text:\"%s\"}}",
                dim, cx, player.getY(), cz, u[0], u[1], u[2], u[3], TAG, pose, profileSnbt(player, name), yaw, equipment, name, text);
        try {
            server.getCommandManager().getDispatcher().execute(cmd, server.getCommandSource());
        } catch (Exception ex) {
            System.err.println("[trypc] не удалось создать труп: " + ex);
        }

        // 4. Запоминаем содержимое трупа
        NbtCompound root = server.getDataCommandStorage().get(STORAGE);
        NbtCompound rec = new NbtCompound();
        rec.putString("owner", player.getUuidAsString());
        rec.putString("dim", dim);
        rec.putDouble("x", player.getX());
        rec.putDouble("y", player.getY());
        rec.putDouble("z", player.getZ());
        rec.put("items", items);
        root.put(corpseId.toString(), rec);
        server.getDataCommandStorage().set(STORAGE, root);

        if (spin) {
            float dir = Math.random() < 0.5 ? -1f : 1f;
            SPINS.add(new Spin(world, corpseId, dir * (25f + (float) (Math.random() * 20.0))));
        }
    }

    /** Броня игрока в виде SNBT для манекена: труп остаётся в ней. */
    private static String equipmentSnbt(ServerPlayerEntity player, RegistryOps<NbtElement> ops) {
        StringBuilder sb = new StringBuilder();
        String[] keys = { "head", "chest", "legs", "feet" };
        EquipmentSlot[] slots = { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET };
        for (int i = 0; i < slots.length; i++) {
            try {
                ItemStack st = player.getEquippedStack(slots[i]);
                if (st.isEmpty()) continue;
                NbtElement el = ItemStack.CODEC.encodeStart(ops, st.copy()).result().orElse(null);
                if (el == null) continue;
                if (sb.length() > 0) sb.append(',');
                sb.append(keys[i]).append(':').append(el.toString());
            } catch (Throwable t) {
                System.err.println("[trypc] броня не прочитана: " + t);
            }
        }
        return sb.length() == 0 ? "" : "equipment:{" + sb + "},";
    }

    /** Скин берём прямо из профиля игрока (работает и без лицензии), иначе по имени. */
    private static String profileSnbt(ServerPlayerEntity player, String name) {
        try {
            for (com.mojang.authlib.properties.Property prop : player.getGameProfile().properties().get("textures")) {
                String v = prop.value();
                if (v != null && v.matches("[A-Za-z0-9+/=_-]+")) {
                    return "{name:\"" + name + "\",properties:[{name:\"textures\",value:\"" + v + "\"}]}";
                }
            }
        } catch (Throwable t) {
            System.err.println("[trypc] скин из профиля не прочитан: " + t);
        }
        return "\"" + name + "\"";
    }

    private static String causeOf(DamageSource source) {
        try {
            return source.getType().msgId();
        } catch (Throwable t) {
            return "generic";
        }
    }

    // ------------------------------------------------------------------ вращение

    private static void tickSpins() {
        if (SPINS.isEmpty()) return;
        Iterator<Spin> it = SPINS.iterator();
        while (it.hasNext()) {
            Spin s = it.next();
            Entity e = s.world.getEntity(s.id);
            if (e == null) { it.remove(); continue; }
            float yaw = e.getYaw() + s.speed;
            e.setYaw(yaw);
            if (e instanceof LivingEntity le) {
                le.setBodyYaw(yaw);
                le.setHeadYaw(yaw);
            }
            s.speed *= 0.96f;
            if (Math.abs(s.speed) < 0.5f) it.remove();
        }
    }

    // ------------------------------------------------------------------ выдача вещей

    private static void releaseAllOf(MinecraftServer server, UUID owner) {
        NbtCompound root = server.getDataCommandStorage().get(STORAGE);
        List<String> ids = new ArrayList<>();
        for (String key : root.getKeys()) {
            if (root.getCompoundOrEmpty(key).getString("owner", "").equals(owner.toString())) ids.add(key);
        }
        for (String id : ids) releaseByCorpse(server, UUID.fromString(id));
    }

    private static void releaseByCorpse(MinecraftServer server, UUID corpseId) {
        NbtCompound root = server.getDataCommandStorage().get(STORAGE);
        String key = corpseId.toString();
        NbtCompound rec = root.getCompoundOrEmpty(key);
        if (rec.isEmpty()) return;

        Identifier dimId = Identifier.tryParse(rec.getString("dim", "minecraft:overworld"));
        ServerWorld world = server.getWorld(RegistryKey.of(RegistryKeys.WORLD, dimId));
        if (world == null) return;
        double x = rec.getDouble("x", 0), y = rec.getDouble("y", 0), z = rec.getDouble("z", 0);

        // Прогружаем чанк, чтобы труп можно было найти и убрать
        world.getChunk(BlockPos.ofFloored(x, y, z));
        Entity corpse = world.getEntity(corpseId);
        if (corpse != null) {
            x = corpse.getX(); y = corpse.getY(); z = corpse.getZ();
            corpse.discard();
        }

        RegistryOps<NbtElement> ops = server.getRegistryManager().getOps(NbtOps.INSTANCE);
        NbtList items = rec.getListOrEmpty("items");
        for (NbtElement el : items) {
            ItemStack stack = ItemStack.CODEC.parse(ops, el).result().orElse(ItemStack.EMPTY);
            if (stack.isEmpty()) continue;
            world.spawnEntity(new ItemEntity(world, x, y + 0.3, z, stack));
        }

        root.remove(key);
        server.getDataCommandStorage().set(STORAGE, root);
        SPINS.removeIf(s -> s.id.equals(corpseId));
    }
}
