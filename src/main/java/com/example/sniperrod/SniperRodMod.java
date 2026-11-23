package com.example.sniperrod;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.TntEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.entity.projectile.WitherSkullEntity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Railgun (OP ONLY). Each rod stores its own settings in custom_data.
 *
 * GUI:  SNEAK + RIGHT-CLICK the rod (or /railgun gui) opens a chest menu with every setting.
 *       Left-click = +, right-click = -, shift-click = x10.
 *
 * /railgun give [arrow|wither|pearl|mob|tnt|onetnt] [uses]   uses = 0 means never breaks
 * /railgun gui | info
 * /railgun set projectile <arrow|wither|pearl|mob|tnt|onetnt>
 *
 * Shared:   arrows (count per shot / per ring / per row), spread, speed, cooldown,
 *           sky, skyheight, skyradius, range
 * Arrow:    damage, pierce, crit
 * Wither:   charged
 * Mob:      mob <entity id>  (max 200 per shot)
 * Mob/TNT:  pattern <ring|grid|scatter|shot>  (shot = mobs only: fired straight ahead)
 *           rings, ringradius, ringspacing, fuse
 *             ring    = concentric rings (arrows per ring)
 *             grid    = rings rows x arrows columns, spaced by ringspacing
 *             scatter = arrows*rings random points inside the ring area
 * OneTNT:   a single primed TNT at the spot you aim at (uses the fuse setting;
 *           sky mode drops it from above).
 */
public class SniperRodMod implements ModInitializer {

    private static final String TAG = "railgun";
    private static final String K_PROJECTILE = "projectile";
    private static final String K_ARROWS = "arrows";
    private static final String K_SPREAD = "spread";
    private static final String K_SPEED = "speed";
    private static final String K_DAMAGE = "damage";
    private static final String K_PIERCE = "pierce";
    private static final String K_COOLDOWN = "cooldown";
    private static final String K_CRIT = "crit";
    private static final String K_CHARGED = "charged";
    private static final String K_USES = "uses";
    private static final String K_SKY = "sky";
    private static final String K_SKY_HEIGHT = "skyheight";
    private static final String K_SKY_RADIUS = "skyradius";
    private static final String K_RANGE = "range";
    private static final String K_MOB = "mob";
    private static final String K_RINGS = "rings";
    private static final String K_RING_RADIUS = "ringradius";
    private static final String K_RING_SPACING = "ringspacing";
    private static final String K_FUSE = "fuse";
    private static final String K_PATTERN = "pattern";

    private static final int MAX_MOBS_PER_SHOT = 200;
    private static final int MAX_TNT_PER_SHOT = 500;

    private static final String[] MODES = {"arrow", "wither", "pearl", "mob", "tnt", "onetnt"};
    private static final String[] PATTERNS = {"ring", "grid", "scatter", "shot"};

    // ---------- permissions (OP = permission level 2 / game master) ----------
    // If 1.21.11's permission API differs, these two methods are the ONLY places to fix.

    private static boolean isOp(ServerCommandSource src) {
        return CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK).test(src);
    }

    private static boolean isOp(ServerPlayerEntity player, World world) {
        return isOp(player.getCommandSource((ServerWorld) world));
    }

    // ---------- item helpers ----------

    private static Text rodName(String type) {
        String name;
        Formatting color;
        switch (type) {
            case "wither" -> { name = "Wither Railgun"; color = Formatting.DARK_GRAY; }
            case "pearl"  -> { name = "Pearl Railgun";  color = Formatting.DARK_PURPLE; }
            case "mob"    -> { name = "Mob Railgun";    color = Formatting.GREEN; }
            case "tnt"    -> { name = "TNT Railgun";    color = Formatting.RED; }
            case "onetnt" -> { name = "Single TNT Railgun"; color = Formatting.GOLD; }
            default       -> { name = "Arrow Railgun";  color = Formatting.AQUA; }
        }
        return Text.literal(name).formatted(color, Formatting.BOLD);
    }

    private static ItemStack createRod(int uses, String type) {
        ItemStack rod = new ItemStack(Items.FISHING_ROD);
        if (uses > 0) {
            rod.set(DataComponentTypes.MAX_DAMAGE, uses);
            rod.set(DataComponentTypes.DAMAGE, 0);
        }

        double speed;
        int cooldown;
        switch (type) {
            case "wither" -> { speed = 1.5; cooldown = 10; }
            case "pearl"  -> { speed = 3.0; cooldown = 20; }
            case "mob"    -> { speed = 1.5; cooldown = 20; }
            case "tnt"    -> { speed = 1.0; cooldown = 40; }
            case "onetnt" -> { speed = 1.0; cooldown = 20; }
            default       -> { speed = 6.0; cooldown = 10; }
        }
        rod.set(DataComponentTypes.CUSTOM_NAME, rodName(type));

        NbtCompound nbt = new NbtCompound();
        nbt.putBoolean(TAG, true);
        nbt.putString(K_PROJECTILE, type);
        nbt.putInt(K_ARROWS, type.equals("tnt") ? 12 : 1);
        nbt.putDouble(K_SPREAD, 0.0);
        nbt.putDouble(K_SPEED, speed);
        nbt.putDouble(K_DAMAGE, 100000.0);
        nbt.putInt(K_PIERCE, 5);
        nbt.putInt(K_COOLDOWN, cooldown);
        nbt.putBoolean(K_CRIT, true);
        nbt.putBoolean(K_CHARGED, false);
        nbt.putInt(K_USES, uses);
        nbt.putBoolean(K_SKY, false);
        nbt.putInt(K_SKY_HEIGHT, 50);
        nbt.putDouble(K_SKY_RADIUS, 5.0);
        nbt.putInt(K_RANGE, 128);
        nbt.putString(K_MOB, "minecraft:zombie");
        nbt.putInt(K_RINGS, 3);
        nbt.putDouble(K_RING_RADIUS, 4.0);
        nbt.putDouble(K_RING_SPACING, 3.0);
        nbt.putInt(K_FUSE, 40);
        nbt.putString(K_PATTERN, "ring");
        rod.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
        return rod;
    }

    private static NbtCompound data(ItemStack stack) {
        NbtComponent c = stack.get(DataComponentTypes.CUSTOM_DATA);
        return c == null ? new NbtCompound() : c.copyNbt();
    }

    private static boolean isRailgun(ItemStack stack) {
        return stack.isOf(Items.FISHING_ROD) && data(stack).getBoolean(TAG, false);
    }

    private static void edit(ItemStack stack, java.util.function.Consumer<NbtCompound> change) {
        NbtCompound nbt = data(stack);
        change.accept(nbt);
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
    }

    // ---------- GUI (server-side chest menu, works with a vanilla client) ----------

    private enum Kind { MODE, INT, DOUBLE, BOOL, CYCLE, MOB }

    private record Setting(int slot, String key, String label, Item icon, Kind kind,
                           double min, double max, double step, double def,
                           String[] options, String desc) { }

    private static final List<Setting> SETTINGS = new ArrayList<>();

    private static void s(int slot, String key, String label, Item icon, Kind kind,
                          double min, double max, double step, double def, String[] opts, String desc) {
        SETTINGS.add(new Setting(slot, key, label, icon, kind, min, max, step, def, opts, desc));
    }

    static {
        // Row 0: projectile type
        Item[] modeIcons = {Items.ARROW, Items.WITHER_SKELETON_SKULL, Items.ENDER_PEARL,
                Items.ZOMBIE_SPAWN_EGG, Items.TNT, Items.TNT_MINECART};
        String[] modeDesc = {"Fast piercing arrows", "Wither skulls", "Ender pearls (teleports you)",
                "Summon any mob", "Primed TNT", "One primed TNT where you aim"};
        for (int i = 0; i < MODES.length; i++) {
            s(i, K_PROJECTILE, MODES[i].toUpperCase(), modeIcons[i], Kind.MODE, 0, 0, 0, 0,
                    new String[]{MODES[i]}, modeDesc[i]);
        }
        // Row 1: shared
        s(9,  K_ARROWS, "Count", Items.ARROW, Kind.INT, 1, 1000, 1, 1, null, "Projectiles / mobs / TNT per shot (per ring or row)");
        s(10, K_SPREAD, "Spread", Items.COBWEB, Kind.DOUBLE, 0, 60, 1, 0, null, "Random inaccuracy");
        s(11, K_SPEED, "Speed", Items.SUGAR, Kind.DOUBLE, 0.1, 20, 0.5, 6, null, "Projectile speed");
        s(12, K_COOLDOWN, "Cooldown", Items.CLOCK, Kind.INT, 0, 1200, 5, 0, null, "Ticks between shots (20 = 1s)");
        s(13, K_SKY, "Sky mode", Items.FEATHER, Kind.BOOL, 0, 0, 0, 0, null, "Drop from the sky onto where you aim");
        s(14, K_SKY_HEIGHT, "Sky height", Items.LADDER, Kind.INT, 5, 300, 5, 50, null, "How high things start");
        s(15, K_SKY_RADIUS, "Sky radius", Items.COMPASS, Kind.DOUBLE, 0, 50, 1, 5, null, "Scatter radius in sky mode");
        s(16, K_RANGE, "Range", Items.SPYGLASS, Kind.INT, 8, 512, 8, 128, null, "How far the aim ray reaches");
        // Row 2: patterns / TNT / mob
        s(18, K_PATTERN, "Pattern", Items.TARGET, Kind.CYCLE, 0, 0, 0, 0, PATTERNS, "ring / grid / scatter / shot (shot = mobs only)");
        s(19, K_RINGS, "Rings / rows", Items.LEAD, Kind.INT, 1, 20, 1, 3, null, "Number of rings (or grid rows)");
        s(20, K_RING_RADIUS, "Ring radius", Items.HEAVY_CORE, Kind.DOUBLE, 0, 100, 1, 4, null, "First ring radius");
        s(21, K_RING_SPACING, "Ring spacing", Items.STICK, Kind.DOUBLE, 0.5, 50, 0.5, 3, null, "Gap between rings / grid cells");
        s(22, K_FUSE, "TNT fuse", Items.FLINT_AND_STEEL, Kind.INT, 1, 600, 5, 40, null, "Ticks before TNT explodes");
        s(23, K_MOB, "Mob type", Items.SPAWNER, Kind.MOB, 0, 0, 1, 0, null, "Click to cycle through every mob");
        // Row 3: arrow / wither
        s(27, K_DAMAGE, "Arrow damage", Items.IRON_SWORD, Kind.DOUBLE, 0, 100000, 1000, 100000, null, "Arrow damage");
        s(29, K_CRIT, "Arrow crit", Items.GOLDEN_SWORD, Kind.BOOL, 0, 0, 0, 1, null, "Critical arrows");
        s(30, K_CHARGED, "Blue skulls", Items.SOUL_SAND, Kind.BOOL, 0, 0, 0, 0, null, "Charged wither skulls");
    }

    private static List<Identifier> mobIds;

    private static List<Identifier> mobIds() {
        if (mobIds == null) {
            List<Identifier> ids = new ArrayList<>();
            for (EntityType<?> t : Registries.ENTITY_TYPE) {
                if (t != EntityType.PLAYER && t.isSummonable()) ids.add(Registries.ENTITY_TYPE.getId(t));
            }
            ids.sort(Comparator.comparing(Identifier::toString));
            mobIds = ids;
        }
        return mobIds;
    }

    private static Text plain(String text, Formatting color) {
        return Text.literal(text).formatted(color).styled(st -> st.withItalic(false));
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static ItemStack settingIcon(Setting st, NbtCompound n) {
        ItemStack it = new ItemStack(st.icon());
        String type = n.getString(K_PROJECTILE, "arrow");
        List<Text> lore = new ArrayList<>();
        boolean glow = false;
        String value;

        switch (st.kind()) {
            case MODE -> {
                glow = type.equals(st.options()[0]);
                value = glow ? "SELECTED" : "click to select";
                it.set(DataComponentTypes.CUSTOM_NAME, plain(st.label(), glow ? Formatting.GREEN : Formatting.YELLOW));
                lore.add(plain(st.desc(), Formatting.GRAY));
                lore.add(plain(value, glow ? Formatting.GREEN : Formatting.DARK_GRAY));
                if (glow) it.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
                it.set(DataComponentTypes.LORE, new LoreComponent(lore));
                return it;
            }
            case INT -> value = String.valueOf(n.getInt(st.key(), (int) st.def()));
            case DOUBLE -> value = String.valueOf(round2(n.getDouble(st.key(), st.def())));
            case BOOL -> {
                boolean b = n.getBoolean(st.key(), st.def() != 0);
                value = b ? "ON" : "OFF";
                glow = b;
            }
            case CYCLE -> value = n.getString(st.key(), st.options()[0]);
            default -> value = n.getString(K_MOB, "minecraft:zombie"); // MOB
        }

        it.set(DataComponentTypes.CUSTOM_NAME, plain(st.label(), Formatting.YELLOW));
        lore.add(plain(st.desc(), Formatting.GRAY));
        lore.add(plain("Value: " + value, Formatting.WHITE));
        switch (st.kind()) {
            case INT, DOUBLE -> {
                lore.add(plain("Left: +" + st.step() + "   Right: -" + st.step(), Formatting.DARK_GRAY));
                lore.add(plain("Shift: x10", Formatting.DARK_GRAY));
            }
            case BOOL -> lore.add(plain("Click to toggle", Formatting.DARK_GRAY));
            case CYCLE -> lore.add(plain("Left: next   Right: previous", Formatting.DARK_GRAY));
            case MOB -> {
                lore.add(plain("Left: next   Right: previous", Formatting.DARK_GRAY));
                lore.add(plain("Shift: jump 10", Formatting.DARK_GRAY));
                lore.add(plain("Or: /railgun set mob <id>", Formatting.DARK_GRAY));
            }
            default -> { }
        }
        if (glow) it.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        it.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return it;
    }

    private static final class RailgunGui extends GenericContainerScreenHandler {
        private final SimpleInventory inv;

        RailgunGui(int syncId, PlayerInventory playerInv, SimpleInventory inv) {
            super(ScreenHandlerType.GENERIC_9X6, syncId, playerInv, inv, 6);
            this.inv = inv;
            refresh(playerInv.player);
        }

        private void refresh(PlayerEntity player) {
            ItemStack rod = player.getMainHandStack();
            if (!isRailgun(rod)) { closeScreen(player); return; }
            NbtCompound n = data(rod);

            ItemStack filler = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
            filler.set(DataComponentTypes.CUSTOM_NAME, Text.literal(" "));
            for (int i = 0; i < 54; i++) inv.setStack(i, filler.copy());

            for (Setting st : SETTINGS) inv.setStack(st.slot(), settingIcon(st, n));

            ItemStack close = new ItemStack(Items.BARRIER);
            close.set(DataComponentTypes.CUSTOM_NAME, plain("Close", Formatting.RED));
            inv.setStack(49, close);
        }

        @Override
        public void onSlotClick(int slotIndex, int button, SlotActionType type, PlayerEntity player) {
            if (slotIndex < 0 || slotIndex >= 54) {
                // Player inventory side: block anything that could pull our menu items out
                if (type == SlotActionType.QUICK_CRAFT || type == SlotActionType.PICKUP_ALL) return;
                super.onSlotClick(slotIndex, button, type, player);
                return;
            }
            if (type != SlotActionType.PICKUP && type != SlotActionType.QUICK_MOVE) return;

            if (slotIndex == 49) { closeScreen(player); return; }

            Setting st = null;
            for (Setting c : SETTINGS) if (c.slot() == slotIndex) { st = c; break; }
            if (st == null) return;

            ItemStack rod = player.getMainHandStack();
            if (!isRailgun(rod)) { closeScreen(player); return; }

            int dir = (button == 0) ? 1 : -1;
            int mult = (type == SlotActionType.QUICK_MOVE) ? 10 : 1;
            final Setting fs = st;

            switch (st.kind()) {
                case MODE -> {
                    String mode = st.options()[0];
                    edit(rod, n -> n.putString(K_PROJECTILE, mode));
                    rod.set(DataComponentTypes.CUSTOM_NAME, rodName(mode));
                }
                case INT -> edit(rod, n -> {
                    long v = Math.round(n.getInt(fs.key(), (int) fs.def()) + dir * fs.step() * mult);
                    n.putInt(fs.key(), (int) Math.max(fs.min(), Math.min(fs.max(), v)));
                });
                case DOUBLE -> edit(rod, n -> {
                    double v = n.getDouble(fs.key(), fs.def()) + dir * fs.step() * mult;
                    n.putDouble(fs.key(), round2(Math.max(fs.min(), Math.min(fs.max(), v))));
                });
                case BOOL -> edit(rod, n -> n.putBoolean(fs.key(), !n.getBoolean(fs.key(), fs.def() != 0)));
                case CYCLE -> edit(rod, n -> {
                    String[] o = fs.options();
                    String cur = n.getString(fs.key(), o[0]);
                    int idx = 0;
                    for (int i = 0; i < o.length; i++) if (o[i].equals(cur)) idx = i;
                    idx = Math.floorMod(idx + dir, o.length);
                    n.putString(fs.key(), o[idx]);
                });
                case MOB -> edit(rod, n -> {
                    List<Identifier> ids = mobIds();
                    String cur = n.getString(K_MOB, "minecraft:zombie");
                    int idx = 0;
                    for (int i = 0; i < ids.size(); i++) if (ids.get(i).toString().equals(cur)) { idx = i; break; }
                    idx = Math.floorMod(idx + dir * mult, ids.size());
                    n.putString(K_MOB, ids.get(idx).toString());
                });
            }
            refresh(player);
        }

        @Override
        public ItemStack quickMove(PlayerEntity player, int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean canUse(PlayerEntity player) {
            return isRailgun(player.getMainHandStack());
        }
    }

    private static void closeScreen(PlayerEntity player) {
        if (player instanceof ServerPlayerEntity sp) sp.closeHandledScreen();
    }

    private static void openGui(ServerPlayerEntity player) {
        if (!isRailgun(player.getMainHandStack())) {
            player.sendMessage(Text.literal("Hold a Railgun in your main hand.").formatted(Formatting.RED), true);
            return;
        }
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, playerInv, p) -> new RailgunGui(syncId, playerInv, new SimpleInventory(54)),
                Text.literal("Railgun Control")));
    }

    // ---------- pattern positions (x/z offsets from the aim point) ----------

    private static List<Vec3d> patternOffsets(String pattern, int count, int rings,
                                              double ringRadius, double ringSpacing, int cap, Random rng) {
        List<Vec3d> out = new ArrayList<>();
        rings = Math.max(1, rings);
        switch (pattern) {
            case "grid" -> {
                int cols = Math.max(1, Math.min(count, cap / rings));
                for (int r = 0; r < rings; r++) {
                    for (int c = 0; c < cols; c++) {
                        out.add(new Vec3d((c - (cols - 1) / 2.0) * ringSpacing, 0,
                                (r - (rings - 1) / 2.0) * ringSpacing));
                    }
                }
            }
            case "scatter" -> {
                int total = Math.max(1, Math.min(count * rings, cap));
                double maxR = ringRadius + (rings - 1) * ringSpacing;
                for (int i = 0; i < total; i++) {
                    double r = maxR * Math.sqrt(rng.nextDouble());
                    double a = rng.nextDouble() * Math.PI * 2.0;
                    out.add(new Vec3d(Math.cos(a) * r, 0, Math.sin(a) * r));
                }
            }
            default -> { // ring
                int perRing = Math.max(1, Math.min(count, cap / rings));
                for (int ring = 0; ring < rings; ring++) {
                    double radius = ringRadius + ring * ringSpacing;
                    double offset = (ring % 2 == 0) ? 0.0 : Math.PI / perRing; // stagger rings
                    for (int i = 0; i < perRing; i++) {
                        double a = offset + (Math.PI * 2.0 * i) / perRing;
                        out.add(new Vec3d(Math.cos(a) * radius, 0, Math.sin(a) * radius));
                    }
                }
            }
        }
        return out;
    }

    // ---------- commands ----------

    private static ItemStack heldRailgun(ServerCommandSource src) {
        try {
            ServerPlayerEntity p = src.getPlayerOrThrow();
            ItemStack main = p.getMainHandStack();
            if (isRailgun(main)) return main;
        } catch (Exception ignored) { }
        src.sendError(Text.literal("Hold a Railgun in your main hand."));
        return null;
    }

    private static LiteralArgumentBuilder<ServerCommandSource> intSetting(
            String name, int min, int max, String key) {
        return CommandManager.literal(name).then(
            CommandManager.argument("value", IntegerArgumentType.integer(min, max))
                .executes(ctx -> {
                    ItemStack rod = heldRailgun(ctx.getSource());
                    if (rod == null) return 0;
                    int v = IntegerArgumentType.getInteger(ctx, "value");
                    edit(rod, n -> n.putInt(key, v));
                    ctx.getSource().sendFeedback(() -> Text.literal(name + " = " + v), false);
                    return Command.SINGLE_SUCCESS;
                }));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> doubleSetting(
            String name, double min, double max, String key) {
        return CommandManager.literal(name).then(
            CommandManager.argument("value", DoubleArgumentType.doubleArg(min, max))
                .executes(ctx -> {
                    ItemStack rod = heldRailgun(ctx.getSource());
                    if (rod == null) return 0;
                    double v = DoubleArgumentType.getDouble(ctx, "value");
                    edit(rod, n -> n.putDouble(key, v));
                    ctx.getSource().sendFeedback(() -> Text.literal(name + " = " + v), false);
                    return Command.SINGLE_SUCCESS;
                }));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> boolSetting(String name, String key) {
        return CommandManager.literal(name).then(
            CommandManager.argument("value", BoolArgumentType.bool())
                .executes(ctx -> {
                    ItemStack rod = heldRailgun(ctx.getSource());
                    if (rod == null) return 0;
                    boolean v = BoolArgumentType.getBool(ctx, "value");
                    edit(rod, n -> n.putBoolean(key, v));
                    ctx.getSource().sendFeedback(() -> Text.literal(name + " = " + v), false);
                    return Command.SINGLE_SUCCESS;
                }));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> choiceSetting(
            String name, String key, String[] options) {
        LiteralArgumentBuilder<ServerCommandSource> b = CommandManager.literal(name);
        for (String opt : options) {
            b.then(CommandManager.literal(opt).executes(ctx -> {
                ItemStack rod = heldRailgun(ctx.getSource());
                if (rod == null) return 0;
                edit(rod, n -> n.putString(key, opt));
                ctx.getSource().sendFeedback(() -> Text.literal(name + " = " + opt), false);
                return Command.SINGLE_SUCCESS;
            }));
        }
        return b;
    }

    private static LiteralArgumentBuilder<ServerCommandSource> projectileMode(String type) {
        return CommandManager.literal(type).executes(ctx -> {
            ItemStack rod = heldRailgun(ctx.getSource());
            if (rod == null) return 0;
            edit(rod, n -> n.putString(K_PROJECTILE, type));
            rod.set(DataComponentTypes.CUSTOM_NAME, rodName(type));
            ctx.getSource().sendFeedback(() -> Text.literal("projectile = " + type), false);
            return Command.SINGLE_SUCCESS;
        });
    }

    private static LiteralArgumentBuilder<ServerCommandSource> giveBranch(String type) {
        return CommandManager.literal(type)
            .executes(ctx -> give(ctx.getSource(), 0, type))
            .then(CommandManager.argument("uses", IntegerArgumentType.integer(0, 10000))
                .executes(ctx -> give(ctx.getSource(),
                        IntegerArgumentType.getInteger(ctx, "uses"), type)));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> mobSetting() {
        return CommandManager.literal("mob").then(
            CommandManager.argument("entity", IdentifierArgumentType.identifier())
                .suggests((c, b) -> CommandSource.suggestIdentifiers(Registries.ENTITY_TYPE.getIds(), b))
                .executes(ctx -> {
                    ItemStack rod = heldRailgun(ctx.getSource());
                    if (rod == null) return 0;
                    Identifier id = IdentifierArgumentType.getIdentifier(ctx, "entity");
                    if (!Registries.ENTITY_TYPE.containsId(id) || id.getPath().equals("player")) {
                        ctx.getSource().sendError(Text.literal("Unknown or unusable entity: " + id));
                        return 0;
                    }
                    edit(rod, n -> n.putString(K_MOB, id.toString()));
                    ctx.getSource().sendFeedback(() -> Text.literal("mob = " + id), false);
                    return Command.SINGLE_SUCCESS;
                }));
    }

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            LiteralArgumentBuilder<ServerCommandSource> root = CommandManager.literal("railgun")
                .requires(SniperRodMod::isOp);

            root.then(CommandManager.literal("give")
                .executes(ctx -> give(ctx.getSource(), 0, "arrow"))
                .then(giveBranch("arrow"))
                .then(giveBranch("wither"))
                .then(giveBranch("pearl"))
                .then(giveBranch("mob"))
                .then(giveBranch("tnt"))
                .then(giveBranch("onetnt")));

            root.then(CommandManager.literal("gui").executes(ctx -> {
                try {
                    openGui(ctx.getSource().getPlayerOrThrow());
                    return Command.SINGLE_SUCCESS;
                } catch (Exception e) {
                    ctx.getSource().sendError(Text.literal("Players only."));
                    return 0;
                }
            }));

            root.then(CommandManager.literal("info").executes(ctx -> {
                ItemStack rod = heldRailgun(ctx.getSource());
                if (rod == null) return 0;
                NbtCompound n = data(rod);
                String s = "projectile=" + n.getString(K_PROJECTILE, "arrow")
                        + " arrows=" + n.getInt(K_ARROWS, 1)
                        + " spread=" + n.getDouble(K_SPREAD, 0.0)
                        + " speed=" + n.getDouble(K_SPEED, 6.0)
                        + " cooldown=" + n.getInt(K_COOLDOWN, 0)
                        + " uses=" + n.getInt(K_USES, 0)
                        + " sky=" + n.getBoolean(K_SKY, false)
                        + " skyheight=" + n.getInt(K_SKY_HEIGHT, 50)
                        + " skyradius=" + n.getDouble(K_SKY_RADIUS, 5.0)
                        + " range=" + n.getInt(K_RANGE, 128)
                        + " | arrow: damage=" + n.getDouble(K_DAMAGE, 100000.0)
                        + " pierce=" + n.getInt(K_PIERCE, 0)
                        + " crit=" + n.getBoolean(K_CRIT, true)
                        + " | wither: charged=" + n.getBoolean(K_CHARGED, false)
                        + " | mob=" + n.getString(K_MOB, "minecraft:zombie")
                        + " | pattern=" + n.getString(K_PATTERN, "ring")
                        + " rings=" + n.getInt(K_RINGS, 3)
                        + " ringradius=" + n.getDouble(K_RING_RADIUS, 4.0)
                        + " ringspacing=" + n.getDouble(K_RING_SPACING, 3.0)
                        + " fuse=" + n.getInt(K_FUSE, 40);
                ctx.getSource().sendFeedback(() -> Text.literal(s), false);
                return Command.SINGLE_SUCCESS;
            }));

            LiteralArgumentBuilder<ServerCommandSource> set = CommandManager.literal("set");
            set.then(CommandManager.literal("projectile")
                .then(projectileMode("arrow"))
                .then(projectileMode("wither"))
                .then(projectileMode("pearl"))
                .then(projectileMode("mob"))
                .then(projectileMode("tnt"))
                .then(projectileMode("onetnt")));
            set.then(intSetting("arrows", 1, 1000, K_ARROWS));
            set.then(doubleSetting("spread", 0.0, 60.0, K_SPREAD));
            set.then(doubleSetting("speed", 0.1, 20.0, K_SPEED));
            set.then(doubleSetting("damage", 0.0, 100000.0, K_DAMAGE));
            set.then(intSetting("cooldown", 0, 1200, K_COOLDOWN));
            set.then(boolSetting("crit", K_CRIT));
            set.then(boolSetting("charged", K_CHARGED));
            set.then(boolSetting("sky", K_SKY));
            set.then(intSetting("skyheight", 5, 300, K_SKY_HEIGHT));
            set.then(doubleSetting("skyradius", 0.0, 50.0, K_SKY_RADIUS));
            set.then(intSetting("range", 8, 512, K_RANGE));
            set.then(mobSetting());
            set.then(choiceSetting("pattern", K_PATTERN, PATTERNS));
            set.then(intSetting("rings", 1, 20, K_RINGS));
            set.then(doubleSetting("ringradius", 0.0, 100.0, K_RING_RADIUS));
            set.then(doubleSetting("ringspacing", 0.5, 50.0, K_RING_SPACING));
            set.then(intSetting("fuse", 1, 600, K_FUSE));
            root.then(set);

            dispatcher.register(root);
        });

        // Right-click fires, sneak + right-click opens the GUI
        UseItemCallback.EVENT.register((player, world, hand) -> {
            ItemStack stack = player.getStackInHand(hand);
            if (!isRailgun(stack)) return ActionResult.PASS;
            if (world.isClient()) return ActionResult.SUCCESS;

            // OP check: a non-op who somehow holds a railgun can't fire it
            if (!(player instanceof ServerPlayerEntity sp) || !isOp(sp, world)) {
                player.sendMessage(Text.literal("Only operators can use the Railgun.")
                        .formatted(Formatting.RED), true);
                return ActionResult.FAIL;
            }

            if (player.isSneaking()) {
                if (hand == net.minecraft.util.Hand.MAIN_HAND) openGui(sp);
                return ActionResult.SUCCESS;
            }

            if (player.getItemCooldownManager().isCoolingDown(stack)) return ActionResult.FAIL;

            NbtCompound n = data(stack);
            String type = n.getString(K_PROJECTILE, "arrow");
            int count = n.getInt(K_ARROWS, 1);
            float spread = (float) n.getDouble(K_SPREAD, 0.0);
            float speed = (float) n.getDouble(K_SPEED, 6.0);
            double damage = n.getDouble(K_DAMAGE, 100000.0);
            int pierce = n.getInt(K_PIERCE, 0);
            int cooldown = n.getInt(K_COOLDOWN, 0);
            boolean crit = n.getBoolean(K_CRIT, true);
            boolean charged = n.getBoolean(K_CHARGED, false);
            int uses = n.getInt(K_USES, 0);
            boolean sky = n.getBoolean(K_SKY, false);
            int skyHeight = n.getInt(K_SKY_HEIGHT, 50);
            double skyRadius = n.getDouble(K_SKY_RADIUS, 5.0);
            int range = n.getInt(K_RANGE, 128);
            int rings = n.getInt(K_RINGS, 3);
            double ringRadius = n.getDouble(K_RING_RADIUS, 4.0);
            double ringSpacing = n.getDouble(K_RING_SPACING, 3.0);

            String pattern = n.getString(K_PATTERN, type.equals("mob") ? "shot" : "ring");
            if (type.equals("tnt") && pattern.equals("shot")) pattern = "ring";
            boolean patterned = type.equals("tnt") || (type.equals("mob") && !pattern.equals("shot"));

            Vec3d eye = player.getEyePos();
            Vec3d look = player.getRotationVec(1.0F);
            Random rng = player.getRandom();
            int topY = world.getBottomY() + world.getHeight() - 1;
            Vec3d target = (sky || patterned || type.equals("onetnt")) ? findTarget(player, world, range) : null;

            if (type.equals("onetnt")) {
                int fuse = n.getInt(K_FUSE, 40);
                double y = sky ? Math.min(target.y + skyHeight, topY) : target.y + 0.5;
                TntEntity tnt = new TntEntity(world, target.x, y, target.z, player);
                tnt.setFuse(fuse);
                world.spawnEntity(tnt);
            } else if (type.equals("tnt")) {
                int fuse = n.getInt(K_FUSE, 40);
                double y = sky ? Math.min(target.y + skyHeight, topY) : target.y + 0.5;
                for (Vec3d o : patternOffsets(pattern, count, rings, ringRadius, ringSpacing,
                        MAX_TNT_PER_SHOT, rng)) {
                    TntEntity tnt = new TntEntity(world, target.x + o.x, y, target.z + o.z, player);
                    tnt.setFuse(fuse);
                    world.spawnEntity(tnt);
                }
            } else if (type.equals("mob")) {
                Identifier id = Identifier.tryParse(n.getString(K_MOB, "minecraft:zombie"));
                if (id == null || !Registries.ENTITY_TYPE.containsId(id)) {
                    player.sendMessage(Text.literal("Invalid mob type on this rod.")
                            .formatted(Formatting.RED), true);
                    return ActionResult.FAIL;
                }
                EntityType<?> et = Registries.ENTITY_TYPE.get(id);

                if (patterned) {
                    double y = sky ? Math.min(target.y + skyHeight, topY) : target.y + 0.1;
                    for (Vec3d o : patternOffsets(pattern, count, rings, ringRadius, ringSpacing,
                            MAX_MOBS_PER_SHOT, rng)) {
                        Entity e = et.create(world, SpawnReason.COMMAND);
                        if (e == null) {
                            player.sendMessage(Text.literal("That entity can't be spawned.")
                                    .formatted(Formatting.RED), true);
                            return ActionResult.FAIL;
                        }
                        e.setPosition(target.x + o.x, y, target.z + o.z);
                        e.setYaw((float) Math.toDegrees(Math.atan2(o.x, -o.z))); // face the centre
                        world.spawnEntity(e);
                    }
                } else {
                    int mobs = Math.min(count, MAX_MOBS_PER_SHOT);
                    for (int i = 0; i < mobs; i++) {
                        Entity e = et.create(world, SpawnReason.COMMAND);
                        if (e == null) {
                            player.sendMessage(Text.literal("That entity can't be spawned.")
                                    .formatted(Formatting.RED), true);
                            return ActionResult.FAIL;
                        }
                        Vec3d pos;
                        Vec3d dir;
                        if (sky) {
                            double r = skyRadius * Math.sqrt(rng.nextDouble());
                            double a = rng.nextDouble() * Math.PI * 2.0;
                            pos = new Vec3d(target.x + Math.cos(a) * r,
                                    Math.min(target.y + skyHeight, topY),
                                    target.z + Math.sin(a) * r);
                            dir = jitter(new Vec3d(0, -1, 0), spread, rng);
                        } else {
                            pos = eye.add(look.multiply(1.5));
                            dir = jitter(look, spread, rng);
                        }
                        e.setPosition(pos.x, pos.y - e.getHeight() / 2.0, pos.z);
                        e.setVelocity(dir.multiply(speed));
                        world.spawnEntity(e);
                    }
                }
            } else {
                for (int i = 0; i < count; i++) {
                    ProjectileEntity proj;
                    switch (type) {
                        case "wither" -> {
                            WitherSkullEntity skull = new WitherSkullEntity(EntityType.WITHER_SKULL, world);
                            skull.setOwner(player);
                            skull.setCharged(charged);
                            skull.setPosition(eye.x + look.x, eye.y + look.y - 0.1, eye.z + look.z);
                            proj = skull;
                        }
                        case "pearl" -> {
                            // owned by the player, so it teleports YOU where it lands
                            proj = new EnderPearlEntity(world, player, new ItemStack(Items.ENDER_PEARL));
                        }
                        default -> {
                            ArrowEntity arrow = new ArrowEntity(world, player, new ItemStack(Items.ARROW), null);
                            arrow.setDamage(damage);
                            arrow.setCritical(crit);
                                            arrow.pickupType = PersistentProjectileEntity.PickupPermission.DISALLOWED;
                            proj = arrow;
                        }
                    }

                    if (sky) {
                        double r = skyRadius * Math.sqrt(rng.nextDouble());
                        double a = rng.nextDouble() * Math.PI * 2.0;
                        proj.setPosition(target.x + Math.cos(a) * r,
                                Math.min(target.y + skyHeight, topY),
                                target.z + Math.sin(a) * r);
                        proj.setVelocity(0.0, -1.0, 0.0, speed, spread);
                    } else {
                        proj.setVelocity(player, player.getPitch(), player.getYaw(), 0.0F, speed, spread);
                    }
                    world.spawnEntity(proj);
                }
            }

            SoundEvent sound = switch (type) {
                case "wither" -> SoundEvents.ENTITY_WITHER_SHOOT;
                case "pearl" -> SoundEvents.ENTITY_ENDER_PEARL_THROW;
                case "tnt", "onetnt" -> SoundEvents.ENTITY_TNT_PRIMED;
                case "mob" -> SoundEvents.ENTITY_ILLUSIONER_CAST_SPELL;
                default -> SoundEvents.ENTITY_ARROW_SHOOT;
            };
            world.playSound(null, player.getX(), player.getY(), player.getZ(),
                    sound, SoundCategory.PLAYERS, 1.0F, 0.8F);

            if (cooldown > 0) player.getItemCooldownManager().set(stack, cooldown);
            if (uses > 0) stack.damage(1, player, (hand == net.minecraft.util.Hand.MAIN_HAND ? net.minecraft.entity.EquipmentSlot.MAINHAND : net.minecraft.entity.EquipmentSlot.OFFHAND));

            return ActionResult.SUCCESS;
        });
    }

    /** Random deviation around a direction, same maths as vanilla projectiles. */
    private static Vec3d jitter(Vec3d dir, float spread, Random rng) {
        double s = 0.0172275 * spread;
        return dir.normalize().add(
                rng.nextTriangular(0.0, s),
                rng.nextTriangular(0.0, s),
                rng.nextTriangular(0.0, s)).normalize();
    }

    /** Where the player is looking: first entity or block within range. */
    private static Vec3d findTarget(PlayerEntity player, World world, int range) {
        Vec3d eye = player.getEyePos();
        Vec3d look = player.getRotationVec(1.0F);
        Vec3d end = eye.add(look.multiply(range));

        BlockHitResult bh = world.raycast(new RaycastContext(
                eye, end, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, player));
        Vec3d target = bh.getPos();
        double bestSq = eye.squaredDistanceTo(target);

        Box box = player.getBoundingBox().stretch(look.multiply(range)).expand(1.0);
        EntityHitResult eh = ProjectileUtil.raycast(player, eye, end, box,
                e -> !e.isSpectator() && e.canHit(), bestSq);
        if (eh != null) target = eh.getPos();
        return target;
    }

    private static int give(ServerCommandSource src, int uses, String type) {
        try {
            src.getPlayerOrThrow().giveItemStack(createRod(uses, type));
            src.sendFeedback(() -> Text.literal("Given " + type + " railgun"), false);
            return Command.SINGLE_SUCCESS;
        } catch (Exception e) {
            src.sendError(Text.literal("Players only."));
            return 0;
        }
    }
}
