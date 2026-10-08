package com.koper.koper_lib.bedrock;

import com.google.gson.JsonObject;
import com.koper.koper_lib.KoperLib;
import com.koper.koper_lib.loader.KoperLibDirectories;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

// world.structureManager for bedrock scripts. pack structures are the .mcstructure files the converter
// copied to bedrock_structures/, read on first use and placed by hand (bedrock's layer by layer animation
// is a timed thing java's template placing can't do). structures a script saves from the world are java's
// own StructureTemplate, no translating either way
final class BedrockStruktury {

    private BedrockStruktury() {}

    // ── the pack ones ────────────────────────────────────────────────────────

    record Plik(Path path, String ns) {}

    record Struktura(String id, int sx, int sy, int sz, int[] w0, int[] w1, BlockState[] paleta,
                     Map<Integer, CompoundTag> be, List<CompoundTag> encje, int[] origin, String ns) {}

    private static final Map<String, Plik> PLIKI = new HashMap<>();
    private static final Map<String, Struktura> WCZYTANE = new HashMap<>();
    // createFromWorld / createEmpty, this session and (saveMode World) the world's generated/ folder
    private static final Map<String, StructureTemplate> ZAPISANE = new HashMap<>();

    static synchronized void indeks() {
        PLIKI.clear();
        WCZYTANE.clear();
        Path root = KoperLibDirectories.FULLPACKS;
        if (!Files.isDirectory(root)) return;
        try (Stream<Path> packs = Files.list(root)) {
            for (Path pack : packs.toList()) {
                Path dir = pack.resolve("bedrock_structures");
                if (!Files.isDirectory(dir)) continue;
                String ns = nsPaczki(pack);
                try (Stream<Path> s = Files.walk(dir)) {
                    for (Path f : s.filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".mcstructure")).toList()) {
                        Path rel = dir.relativize(f);
                        String file = rel.getFileName().toString().replaceAll("(?i)\\.mcstructure$", "").toLowerCase(Locale.ROOT);
                        String id = rel.getNameCount() > 1 ? rel.getName(0).toString().toLowerCase(Locale.ROOT) + ":" + file : "mystructure:" + file;
                        PLIKI.putIfAbsent(id, new Plik(f, ns));
                    }
                }
            }
        } catch (IOException e) {
            KoperLib.LOGGER.error("[Bedrock/structure] can't list pack structures", e);
        }
        if (!PLIKI.isEmpty()) KoperLib.LOGGER.info("[Bedrock/structure] {} pack structures", PLIKI.size());
    }

    private static String nsPaczki(Path pack) {
        JsonObject meta = BedrockTlumacz.czytajObj(pack.resolve("pack.kopermeta"));
        return meta != null && meta.has("namespace") ? meta.get("namespace").getAsString() : pack.getFileName().toString();
    }

    static synchronized List<String> idPaczek() {
        return new ArrayList<>(PLIKI.keySet());
    }

    static synchronized List<String> idSwiata() {
        return new ArrayList<>(ZAPISANE.keySet());
    }

    private static String norm(String id) {
        id = id.toLowerCase(Locale.ROOT);
        return id.contains(":") ? id : "mystructure:" + id;
    }

    // size of a structure, or null when there is none by that name
    static synchronized int[] rozmiar(MinecraftServer srv, String id) {
        id = norm(id);
        StructureTemplate t = ZAPISANE.get(id);
        if (t != null) return new int[] {t.getSize().getX(), t.getSize().getY(), t.getSize().getZ()};
        Struktura s = wczytaj(id);
        return s == null ? null : new int[] {s.sx, s.sy, s.sz};
    }

    private static Struktura wczytaj(String id) {
        Struktura s = WCZYTANE.get(id);
        if (s != null) return s;
        Plik p = PLIKI.get(id);
        if (p == null) return null;
        try {
            s = rozbierz(id, BedrockNbtLe.czytaj(Files.readAllBytes(p.path())), p.ns());
        } catch (IOException | RuntimeException e) {
            BedrockBloki.krzyknij("structure " + id + " (" + p.path().getFileName() + ") can't be read: " + e);
            return null;
        }
        WCZYTANE.put(id, s);
        return s;
    }

    private static int[] inty(Tag t) {
        if (!(t instanceof ListTag l)) return new int[0];
        int[] out = new int[l.size()];
        for (int i = 0; i < out.length; i++) out[i] = l.get(i) instanceof NumericTag n ? n.box().intValue() : 0;
        return out;
    }

    private static Struktura rozbierz(String id, CompoundTag root, String ns) {
        int[] size = inty(root.get("size"));
        CompoundTag st = root.getCompoundOrEmpty("structure");
        ListTag idx = st.getListOrEmpty("block_indices");
        int[] w0 = idx.size() > 0 ? inty(idx.get(0)) : new int[0];
        int[] w1 = idx.size() > 1 ? inty(idx.get(1)) : new int[0];
        CompoundTag pal = st.getCompoundOrEmpty("palette").getCompoundOrEmpty("default");
        ListTag bp = pal.getListOrEmpty("block_palette");
        BlockState[] paleta = new BlockState[bp.size()];
        for (int i = 0; i < paleta.length; i++) {
            CompoundTag e = bp.getCompoundOrEmpty(i);
            paleta[i] = BedrockBloki.doJavy(e.getStringOr("name", "minecraft:air"), e.getCompoundOrEmpty("states"));
        }
        Map<Integer, CompoundTag> be = new HashMap<>();
        CompoundTag pos = pal.getCompoundOrEmpty("block_position_data");
        for (String k : pos.keySet()) {
            CompoundTag d = pos.getCompoundOrEmpty(k).getCompoundOrEmpty("block_entity_data");
            if (!d.isEmpty()) try { be.put(Integer.parseInt(k), d); } catch (NumberFormatException ignored) {}
        }
        List<CompoundTag> encje = new ArrayList<>();
        for (Tag t : st.getListOrEmpty("entities")) if (t instanceof CompoundTag c) encje.add(c);
        int[] origin = inty(root.get("structure_world_origin"));
        if (size.length < 3) size = new int[] {0, 0, 0};
        if (origin.length < 3) origin = new int[] {0, 0, 0};
        return new Struktura(id, size[0], size[1], size[2], w0, w1, paleta, be, encje, origin, ns);
    }

    // ── placing ──────────────────────────────────────────────────────────────

    record Opcje(Rotation rot, Mirror mir, boolean bloki, boolean encje, boolean podWoda, float integrity, long seed,
                 String anim, float sekundy) {}

    private record Wstaw(BlockPos pos, BlockState state, CompoundTag be, String ns) {}

    private record Robota(ServerLevel level, List<Wstaw> kolejka, int naTick, List<Runnable> potem) {}

    private static final List<Robota> ROBOTY = new ArrayList<>();

    static synchronized boolean postaw(ServerLevel level, String id, BlockPos at, Opcje o) {
        id = norm(id);
        StructureTemplate t = ZAPISANE.get(id);
        if (t != null) {
            StructurePlaceSettings set = new StructurePlaceSettings().setRotation(o.rot()).setMirror(o.mir()).setIgnoreEntities(!o.encje());
            t.placeInWorld(level, at, at, set, RandomSource.create(o.seed()), Block.UPDATE_CLIENTS);
            return true;
        }
        Struktura s = wczytaj(id);
        if (s == null) return false;
        RandomSource rng = RandomSource.create(o.seed());
        List<Wstaw> kolejka = new ArrayList<>();
        if (o.bloki()) {
            for (int x = 0; x < s.sx(); x++) for (int y = 0; y < s.sy(); y++) for (int z = 0; z < s.sz(); z++) {
                int i = (x * s.sy() + y) * s.sz() + z;
                if (i >= s.w0().length) continue;
                int p = s.w0()[i];
                if (p < 0 || p >= s.paleta().length) continue; // -1 = structure void, the world stays
                if (o.integrity() < 1f && rng.nextFloat() > o.integrity()) continue;
                BlockState b = s.paleta()[p].mirror(o.mir()).rotate(o.rot());
                int q = i < s.w1().length ? s.w1()[i] : -1;
                boolean woda = q >= 0 && q < s.paleta().length && s.paleta()[q].getFluidState().is(net.minecraft.tags.FluidTags.WATER);
                if ((woda || o.podWoda()) && b.hasProperty(BlockStateProperties.WATERLOGGED))
                    b = b.setValue(BlockStateProperties.WATERLOGGED, true);
                kolejka.add(new Wstaw(at.offset(obroc(x, y, z, s, o)), b, s.be().get(i), s.ns()));
            }
        }
        List<Runnable> potem = new ArrayList<>();
        if (o.encje()) for (CompoundTag e : s.encje()) potem.add(() -> encja(level, e, s, at, o));
        // bedrock builds "Layers" bottom up and "Blocks" one at a time over animationSeconds
        int naTick = kolejka.size();
        if (!"none".equals(o.anim()) && o.sekundy() > 0) {
            kolejka.sort(java.util.Comparator.comparingInt(w -> w.pos().getY()));
            naTick = Math.max(1, (int) Math.ceil(kolejka.size() / (o.sekundy() * 20f)));
        }
        ROBOTY.add(new Robota(level, kolejka, naTick, potem));
        return true;
    }

    // inside the structure's box after mirror then rotation, bedrock keeps the min corner where it was
    private static Vec3i obroc(int x, int y, int z, Struktura s, Opcje o) {
        int mx = s.sx() - 1, mz = s.sz() - 1;
        if (o.mir() == Mirror.FRONT_BACK) x = mx - x;
        if (o.mir() == Mirror.LEFT_RIGHT) z = mz - z;
        return switch (o.rot()) {
            case CLOCKWISE_90 -> new Vec3i(mz - z, y, x);
            case CLOCKWISE_180 -> new Vec3i(mx - x, y, mz - z);
            case COUNTERCLOCKWISE_90 -> new Vec3i(z, y, mx - x);
            default -> new Vec3i(x, y, z);
        };
    }

    static synchronized void tick() {
        for (var it = ROBOTY.iterator(); it.hasNext(); ) {
            Robota r = it.next();
            int n = 0;
            while (n < r.naTick() && !r.kolejka().isEmpty()) {
                Wstaw w = r.kolejka().removeFirst();
                r.level().setBlock(w.pos(), w.state(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                if (w.be() != null) blokEncja(r.level(), w.pos(), w.be(), w.ns());
                n++;
            }
            if (r.kolejka().isEmpty()) {
                r.potem().forEach(Runnable::run);
                it.remove();
            }
        }
    }

    // ── block entities: bedrock nbt -> the java block entity, the parts java has ─────

    // bedrock banner/bed colors run black..white, java white..black
    private static final String[] DYE_JAVA = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
        "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};

    private static void blokEncja(ServerLevel level, BlockPos p, CompoundTag d, String ns) {
        String id = d.getStringOr("id", "");
        BlockState here = level.getBlockState(p);
        switch (id) {
            case "Chest", "Barrel", "Dispenser", "Dropper", "Hopper", "Furnace", "BlastFurnace", "Smoker", "BrewingStand", "ShulkerBox" -> {
                BlockEntity be = level.getBlockEntity(p);
                if (be instanceof RandomizableContainer rc && d.contains("LootTable")) {
                    rc.setLootTable(lootKey(d.getStringOr("LootTable", ""), ns), d.getLongOr("LootTableSeed", level.getRandom().nextLong()));
                } else if (be instanceof Container c) {
                    for (Tag t : d.getListOrEmpty("Items")) if (t instanceof CompoundTag it) {
                        int slot = it.getByteOr("Slot", (byte) 0);
                        ItemStack stack = przedmiot(level, it);
                        if (slot >= 0 && slot < c.getContainerSize() && !stack.isEmpty()) c.setItem(slot, stack);
                    }
                }
            }
            case "Sign", "HangingSign" -> {
                if (level.getBlockEntity(p) instanceof SignBlockEntity sign) {
                    sign.setText(napis(d.getCompoundOrEmpty("FrontText").getStringOr("Text", d.getStringOr("Text", ""))), SignTextSlot.FRONT);
                    sign.setText(napis(d.getCompoundOrEmpty("BackText").getStringOr("Text", "")), SignTextSlot.BACK);
                    sign.setWaxed(d.getBooleanOr("IsWaxed", false));
                }
            }
            case "FlowerPot" -> {
                CompoundTag plant = d.getCompoundOrEmpty("PlantBlock");
                if (plant.isEmpty()) return;
                BlockState inside = BedrockBloki.doJavy(plant.getStringOr("name", "minecraft:air"), plant.getCompoundOrEmpty("states"));
                Identifier pot = BuiltInRegistries.BLOCK.getKey(inside.getBlock()).withPrefix("potted_");
                if (BuiltInRegistries.BLOCK.containsKey(pot)) level.setBlock(p, BuiltInRegistries.BLOCK.getValue(pot).defaultBlockState(), Block.UPDATE_CLIENTS);
                else BedrockBloki.krzyknij("no java pot for " + inside.getBlock() + " in a flower pot");
            }
            case "Bed" -> {
                int c = d.getByteOr("color", (byte) 14);
                Block bed = BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(DYE_JAVA[c & 15] + "_bed"));
                if (bed != Blocks.AIR) level.setBlock(p, bed.withPropertiesOf(here), Block.UPDATE_CLIENTS);
            }
            case "Skull" -> {
                String typ = switch (d.getByteOr("SkullType", (byte) 0)) {
                    case 1 -> "wither_skeleton"; case 2 -> "zombie"; case 3 -> "player"; case 4 -> "creeper"; case 5 -> "dragon"; case 6 -> "piglin";
                    default -> "skeleton";
                };
                Direction8 kier = Direction8.z(here);
                if (kier.sciana()) {
                    Block b = BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(typ + ("dragon".equals(typ) || "piglin".equals(typ) || "player".equals(typ) || "zombie".equals(typ) || "creeper".equals(typ) ? "_wall_head" : "_wall_skull")));
                    level.setBlock(p, b.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, kier.facing()), Block.UPDATE_CLIENTS);
                } else {
                    Block b = BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(typ + ("skeleton".equals(typ) || "wither_skeleton".equals(typ) ? "_skull" : "_head")));
                    int rot = Math.floorMod(Math.round(d.getFloatOr("Rotation", 0f) / 22.5f), 16);
                    level.setBlock(p, b.defaultBlockState().setValue(BlockStateProperties.ROTATION_16, rot), Block.UPDATE_CLIENTS);
                }
            }
            case "Banner" -> {
                String c = DYE_JAVA[15 - (d.getIntOr("Base", 0) & 15)];
                boolean wall = here.hasProperty(BlockStateProperties.HORIZONTAL_FACING);
                Block b = BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(c + (wall ? "_wall_banner" : "_banner")));
                if (b != Blocks.AIR) level.setBlock(p, b.withPropertiesOf(here), Block.UPDATE_CLIENTS);
                if (!d.getListOrEmpty("Patterns").isEmpty()) BedrockBloki.krzyknij("banner patterns from bedrock structures are not carried over yet, plain banner placed");
            }
            case "MobSpawner" -> {
                if (level.getBlockEntity(p) instanceof SpawnerBlockEntity sp) {
                    String e = com.koper.koper_lib.api.core.BedrockNazwy.doJavy(d.getStringOr("EntityIdentifier", ""));
                    var type = typ(e);
                    if (type.isPresent()) sp.setEntityId(type.get(), level.getRandom());
                    else BedrockBloki.krzyknij("spawner in a structure wants " + e + ", java has no such entity");
                }
            }
            case "", "Music", "Comparator", "PistonArm", "EnchantTable", "Cauldron", "Lodestone", "SporeBlossom", "Bell", "SculkCatalyst",
                 "SculkSensor", "CreakingHeart", "JigsawBlock", "StructureBlock", "Beehive", "ChiseledBookshelf" -> {}
            default -> BedrockBloki.krzyknij("bedrock block entity " + id + " in a structure is placed without its data (not carried over yet)");
        }
    }

    // a little helper so the skull above can ask "wall or floor, which way" without five ifs
    private record Direction8(boolean sciana, net.minecraft.core.Direction facing) {
        static Direction8 z(BlockState s) {
            if (s.hasProperty(BlockStateProperties.FACING)) {
                var f = s.getValue(BlockStateProperties.FACING);
                return new Direction8(f.getAxis().isHorizontal(), f.getAxis().isHorizontal() ? f : net.minecraft.core.Direction.NORTH);
            }
            if (s.hasProperty(BlockStateProperties.HORIZONTAL_FACING))
                return new Direction8(true, s.getValue(BlockStateProperties.HORIZONTAL_FACING));
            return new Direction8(false, net.minecraft.core.Direction.NORTH);
        }
    }

    private static SignText napis(String text) {
        SignText.Mutable m = SignText.EMPTY.asMutable();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < Math.min(4, lines.length); i++) m.setLine(i, Component.literal(lines[i]));
        return m.asImmutable();
    }

    // "loot_tables/chests/x.json": the converted pack has it as <ns>:chests/x, else it is bedrock's own
    private static ResourceKey<LootTable> lootKey(String path, String ns) {
        String p = path.replace('\\', '/');
        if (p.startsWith("loot_tables/")) p = p.substring("loot_tables/".length());
        if (p.endsWith(".json")) p = p.substring(0, p.length() - 5);
        p = p.toLowerCase(Locale.ROOT);
        Identifier own = Identifier.fromNamespaceAndPath(ns, p);
        var srv = BedrockSkrypciarz.server();
        boolean jest = srv != null && srv.reloadableRegistries().lookup().lookup(Registries.LOOT_TABLE)
            .flatMap(r -> r.get(ResourceKey.create(Registries.LOOT_TABLE, own))).isPresent();
        return ResourceKey.create(Registries.LOOT_TABLE, jest ? own : Identifier.withDefaultNamespace(p));
    }

    private static final String[] ENCHANTY = {"protection", "fire_protection", "feather_falling", "blast_protection", "projectile_protection",
        "thorns", "respiration", "depth_strider", "aqua_affinity", "sharpness", "smite", "bane_of_arthropods", "knockback", "fire_aspect",
        "looting", "efficiency", "silk_touch", "unbreaking", "fortune", "power", "punch", "flame", "infinity", "luck_of_the_sea", "lure",
        "frost_walker", "mending", "binding_curse", "vanishing_curse", "impaling", "riptide", "loyalty", "channeling", "multishot",
        "piercing", "quick_charge", "soul_speed", "swift_sneak", "wind_burst", "density", "breach"};

    // a bedrock item nbt (Name, Count, Damage, tag{display, ench, Damage}) -> java stack
    static ItemStack przedmiot(ServerLevel level, CompoundTag it) {
        String name = it.getStringOr("Name", "");
        Identifier rl = Identifier.tryParse(name.contains(":") ? name : "minecraft:" + name);
        if (rl == null || !BuiltInRegistries.ITEM.containsKey(rl)) {
            if (!name.isEmpty()) BedrockBloki.krzyknij("item " + name + " in a structure container has no java twin, left out");
            return ItemStack.EMPTY;
        }
        Item item = BuiltInRegistries.ITEM.getValue(rl);
        ItemStack s = new ItemStack(item, Math.max(1, it.getByteOr("Count", (byte) 1)));
        CompoundTag tag = it.getCompoundOrEmpty("tag");
        int dmg = tag.getIntOr("Damage", 0);
        if (dmg > 0 && s.isDamageableItem()) s.setDamageValue(dmg);
        String custom = tag.getCompoundOrEmpty("display").getStringOr("Name", "");
        if (!custom.isEmpty()) s.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal(custom));
        var enchanty = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        for (Tag t : tag.getListOrEmpty("ench")) if (t instanceof CompoundTag e) {
            int eid = e.getShortOr("id", (short) -1);
            if (eid < 0 || eid >= ENCHANTY.length) continue;
            enchanty.get(ResourceKey.create(Registries.ENCHANTMENT, Identifier.withDefaultNamespace(ENCHANTY[eid])))
                .ifPresent(h -> s.enchant(h, e.getShortOr("lvl", (short) 1)));
        }
        return s;
    }

    private static java.util.Optional<EntityType<?>> typ(String id) {
        Identifier rl = Identifier.tryParse(id);
        return rl == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(rl) ? java.util.Optional.empty()
            : java.util.Optional.of(BuiltInRegistries.ENTITY_TYPE.getValue(rl));
    }

    private static void encja(ServerLevel level, CompoundTag e, Struktura s, BlockPos at, Opcje o) {
        String id = com.koper.koper_lib.api.core.BedrockNazwy.doJavy(e.getStringOr("identifier", ""));
        var type = typ(id);
        if (type.isEmpty()) {
            BedrockBloki.krzyknij("entity " + e.getStringOr("identifier", "?") + " in structure " + s.id() + " has no java twin, not spawned");
            return;
        }
        ListTag pos = e.getListOrEmpty("Pos");
        if (pos.size() < 3) return;
        double rx = pos.getFloatOr(0, 0f) - s.origin()[0], ry = pos.getFloatOr(1, 0f) - s.origin()[1], rz = pos.getFloatOr(2, 0f) - s.origin()[2];
        if (o.mir() == Mirror.FRONT_BACK) rx = s.sx() - rx;
        if (o.mir() == Mirror.LEFT_RIGHT) rz = s.sz() - rz;
        double x = rx, z = rz;
        switch (o.rot()) {
            case CLOCKWISE_90 -> { x = s.sz() - rz; z = rx; }
            case CLOCKWISE_180 -> { x = s.sx() - rx; z = s.sz() - rz; }
            case COUNTERCLOCKWISE_90 -> { x = rz; z = s.sx() - rx; }
            default -> {}
        }
        Entity ent = type.get().create(level, EntitySpawnReason.STRUCTURE);
        if (ent == null) return;
        ListTag rot = e.getListOrEmpty("Rotation");
        float yaw = (rot.size() > 0 ? rot.getFloatOr(0, 0f) : 0f) + switch (o.rot()) { case CLOCKWISE_90 -> 90f; case CLOCKWISE_180 -> 180f; case COUNTERCLOCKWISE_90 -> 270f; default -> 0f; };
        Vec3 w = new Vec3(at.getX() + x, at.getY() + ry, at.getZ() + z);
        ent.snapTo(w.x, w.y, w.z, yaw, rot.size() > 1 ? rot.getFloatOr(1, 0f) : 0f);
        level.addFreshEntity(ent);
    }

    // ── saved from the world ─────────────────────────────────────────────────

    static synchronized void zapisz(ServerLevel level, String id, BlockPos a, BlockPos b, boolean bloki, boolean encje, boolean doSwiata) {
        id = norm(id);
        BlockPos min = BlockPos.min(a, b), max = BlockPos.max(a, b);
        StructureTemplate t = new StructureTemplate();
        List<Block> pomin = bloki ? List.of() : new ArrayList<>(BuiltInRegistries.BLOCK.stream().toList());
        t.fillFromWorld(level, min, new Vec3i(max.getX() - min.getX() + 1, max.getY() - min.getY() + 1, max.getZ() - min.getZ() + 1), encje, pomin);
        ZAPISANE.put(id, t);
        if (doSwiata) {
            var mgr = level.getServer().getStructureTemplateManager();
            Identifier key = Identifier.tryParse(id.replace("mystructure:", "bedrock_mystructure:"));
            if (key != null) {
                mgr.getOrCreate(key).load(level.registryAccess().lookupOrThrow(Registries.BLOCK), t.save(new CompoundTag()));
                if (!mgr.save(key)) BedrockBloki.krzyknij("structure " + id + " could not be written to the world's generated folder");
            }
        }
    }

    static synchronized boolean usun(String id) {
        return ZAPISANE.remove(norm(id)) != null;
    }

    static synchronized void wyczysc() {
        ROBOTY.clear();
        ZAPISANE.clear();
    }
}
