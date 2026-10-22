package btw.forge;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FCMOD-INTEGRATION(1.20.1): vanilla_pair_drops (maintainer ruling 2026-10-20; triage CF2-DR1, CF2-DR6)
 *
 * <p><b>Feature:</b> 1.20.1's "one item, two cells" handling of beds and doors. Each loot table yields the item from
 * one half only ({@code part=head}, {@code half=lower}); removing either half makes the other half's
 * {@code updateShape} return AIR, and {@code Block.updateOrDestroy} pops it with its own loot (BedBlock.java:148-154,
 * DoorBlock.java:76-83, Block.java:155-166); a creative break clears the partner without drops
 * ({@code playerWillDestroy}, flag 35). So every removal yields exactly one item, of the bed's colour.</p>
 *
 * <p><b>Conflict with FC:</b> FC's harvest ({@code ItemInWorldManager.tryHarvestBlock}) drops the legacy id of
 * whichever half it removes (Modern-Common BlockBed and BlockDoor carry no 1.5.2 drop rules, so
 * {@code Block.idDropped} is the block's own id), and 1.20.1 then pops the partner with its loot: two items from a
 * bed's foot or a door's top, the canonical red bed from any bed, and a door from a creative top break. FC's other
 * drop entry points at a live cell (the saw, {@code FCExplosionMining}) meet the same partner pop.</p>
 *
 * <p><b>Integration pattern:</b> E (leave vanilla intact), by ruling: a bed or door, {@link #isRuled}, takes
 * 1.20.1's own break, drops and partner handling (ServerPlayerGameModeMixin.btw$destroyBlock returns without
 * cancelling), and an FC drop of its legacy id at its live cell is that cell's 1.20.1 loot
 * (Modern-Common World.UsesModernBlockLoot, overridden by WorldBridge). FC keeps everything that is not a drop:
 * dig speed (BlockBehaviorMixin.getDestroyProgress), harvest exhaustion ({@link #chargeHarvest}, through
 * BlockPlayerDestroyMixin), tool wear (ItemStackMixin.btw$mineBlock and ProxyItem.mineBlock, through
 * ProxyItem.onBlockDestroyedLive) and the panic of FC animals ({@link #afterPlayerBreak}).</p>
 *
 * <p><b>Rebalance:</b> one item per bed or door, as in 4.B; the bed keeps its colour; creative breaks drop nothing.
 * 1.20.1 differences kept under the ruling: an iron door's top broken without a pickaxe pops the bottom with its door
 * (4.B did the same, FCBlockDoor.java:94-99), and a door whose top burns first pops its bottom (4.B dropped
 * nothing).</p>
 *
 * <p><b>Injection:</b> no injector here. The predicate is state- and class-agnostic: a non-ProxyBlock whose default
 * state has {@code BED_PART} or {@code DOUBLE_BLOCK_HALF} (the two properties 1.20.1's half handling keys on) and
 * that has an FC identity. Today that is the 16 beds (legacy 26), the oak door (64) and the iron door (71).
 * {@link #audit} names every ruled block at server start and WARNs on anything unexpected.</p>
 *
 * <p><b>Alternatives considered and rejected:</b> porting 1.5.2's BlockBed/BlockDoor drop rules (idDropped by half,
 * the orphaned-half neighbour drop) into Modern-Common: it needs bed and door metadata codecs, keeps the red bed,
 * and still meets 1.20.1's partner pop on every vanilla path (explosions, pistons). Suppressing 1.20.1's partner pop
 * (flag 32) for FC removals: it would lose the item on every path where the dropping half is the popped one.</p>
 *
 * <p>Never throws: failures are logged once per site and answer the pre-BD behaviour.</p>
 */
public final class VanillaPairDrops {

    private static final Logger LOGGER = LogManager.getLogger("BTW-PairDrops");

    /** FC's default harvest exhaustion (Modern-Common EntityPlayer.AddHarvestBlockExhaustion, EntityPlayer.java:962-975). */
    static final float FC_DEFAULT_HARVEST_EXHAUSTION = 0.025F;

    /** The legacy ids the audit expects to be ruled today: the bed, the oak door and the iron door. */
    private static final Set<Integer> EXPECTED_RULED_IDS = Set.of(26, 64, 71);

    /** isRuled per Block, filled only once FC's block table is published (BTWLifecycle.isFcReady). */
    private static final Map<Block, Boolean> RULED = new ConcurrentHashMap<>();

    private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();

    private VanillaPairDrops() {}

    /** One item, two cells: a 1.20.1 two-part block (BED_PART / DOUBLE_BLOCK_HALF), never a ProxyBlock. */
    public static boolean isPairBlock(Block b) {
        if (b == null || b instanceof ProxyBlock) return false;
        BlockState s = b.defaultBlockState();
        return s.hasProperty(BlockStateProperties.BED_PART) || s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF);
    }

    /**
     * Contract C-BD1: a pair block with an FC identity; 1.20.1 owns its break, drops and partner handling. Cached per
     * Block only once FC's block table is published: before that it answers false uncached (getFcBlock is null then
     * anyway), as TickPolicy.decide answers nothing before FC is ready (TickPolicy.java:224).
     */
    public static boolean isRuled(Block b) {
        if (b == null) return false;
        Boolean cached = RULED.get(b);
        if (cached != null) return cached;
        if (!BTWLifecycle.isFcReady()) return false;
        boolean ruled;
        try {
            ruled = isPairBlock(b) && ProxyRegistry.getFcBlock(b) != null;
        } catch (Throwable t) {
            failOnce("isRuled", t);
            ruled = false;
        }
        RULED.put(b, ruled);
        return ruled;
    }

    public static boolean isRuled(BlockState s) {
        return s != null && isRuled(s.getBlock());
    }

    /**
     * S1, at the RETURN of Forge's ServerPlayerGameMode.removeBlock: FC's animals hear a player's removal of a ruled
     * block, as FC's own harvest notifies them (Modern-Common ItemInWorldManager.java:142-146; 1.5.2
     * ItemInWorldManager.removeBlock): after the removal, before the drops. World.NotifyNearbyAnimalsOfPlayerBlockAddOrRemove
     * skips creative players and blocks whose material does not block movement (Modern-Common World.java:995-996).
     * Public: called from btw.forge.mixin.ServerPlayerGameModeMixin.
     */
    public static void afterPlayerBreak(ServerPlayer p, ServerLevel level, BlockPos pos, BlockState broken) {
        if (p == null || level == null || pos == null || broken == null) return;
        try {
            btw.modern.Block fcBlock = ProxyRegistry.getFcBlock(broken.getBlock());
            if (fcBlock == null || fcBlock.blockMaterial == null) return;
            PlayerBridge pb = PlayerBridge.getOrCreate(p);   // syncs from the ServerPlayer
            WorldBridge.getOrCreate(level).NotifyNearbyAnimalsOfPlayerBlockAddOrRemove(pb, fcBlock,
                    pos.getX(), pos.getY(), pos.getZ());
        } catch (Throwable t) {
            failOnce("afterPlayerBreak", t);
        }
    }

    /**
     * B3, at the HEAD of Block.playerDestroy for a ruled block (BlockPlayerDestroyMixin, which zeroes 1.20.1's 0.005
     * under the same condition): FC's harvest exhaustion, PlayerBridge.AddHarvestBlockExhaustion(id, x, y, z, meta), the
     * held item's GetExhaustionOnUsedToHarvestBlock, 0.025 by default (Modern-Common EntityPlayer.java:962-975;
     * precedent FCLogRules.chargeLogHarvest, FCLogRules.java:241-253). The id comes from {@code block} (the class whose
     * playerDestroy runs), not from {@code state}: DoublePlantBlock.playerDestroy passes AIR as the state
     * (DoublePlantBlock.java:84-86), so the metadata is derived only when the state is that block's. Any other player,
     * level or failure pays FC's default through Player.causeFoodExhaustion (PlayerMixin routes it to FC on the
     * server), so the zeroed 0.005 is always replaced. Public: called from btw.forge.mixin.BlockPlayerDestroyMixin.
     */
    public static void chargeHarvest(Player p, Level level, BlockPos pos, Block block, BlockState state) {
        if (p == null) return;
        try {
            if (p instanceof ServerPlayer sp && level instanceof ServerLevel && pos != null) {
                int id = ProxyRegistry.getBlockId(block);
                if (id > 0) {
                    int meta = state != null && state.getBlock() == block
                            ? WorldBridge.deriveVanillaMetadata(level, state, pos) : 0;
                    PlayerBridge.getOrCreate(sp).AddHarvestBlockExhaustion(id, pos.getX(), pos.getY(), pos.getZ(), meta);
                    return;
                }
            }
        } catch (Throwable t) {
            failOnce("chargeHarvest", t);
        }
        p.causeFoodExhaustion(FC_DEFAULT_HARVEST_EXHAUSTION);
    }

    /**
     * [SELF-AUDIT/pairdrops], once at server start (BridgeSelfAudit.runServerStart, registries frozen): one line per
     * ruled block and a summary. WARNs:
     * W1 a ruled block that is neither a BedBlock nor a DoorBlock (a tall plant or a modded block gained an FC
     * identity: confirm it should take 1.20.1 drops);
     * W2 a ruled block whose 1.20.1 class does not override neighborChanged (so BlockBehaviorMixin runs FC's
     * onNeighborBlockChange) while its FC class declares onNeighborBlockChange below btw.modern.Block (the D7 trap: a
     * 1.5.2 BlockBed port would pop and FC-drop beside 1.20.1);
     * W3 a legacy id that maps both ruled and unruled blocks (S2 then applies per cell, to the ruled ones only);
     * W4 an expected ruled id (26, 64, 71) with no ruled block.
     */
    public static void audit() {
        if (!BTWLifecycle.isFcReady()) {
            LOGGER.warn("[SELF-AUDIT/pairdrops] FC is not initialised; no bed or door is ruled (FC's harvest keeps them)");
            return;
        }
        int beds = 0, doors = 0, other = 0, warnings = 0;
        Map<Integer, List<String>> ruledById = new TreeMap<>();
        Map<Integer, List<String>> unruledById = new TreeMap<>();
        String neighborChanged = resolveNeighborChanged();
        for (Block b : ForgeRegistries.BLOCKS) {
            int id;
            try {
                id = ProxyRegistry.getBlockId(b);
            } catch (Throwable t) {
                continue;
            }
            if (id <= 0) continue;
            String key = String.valueOf(ForgeRegistries.BLOCKS.getKey(b));
            if (!isRuled(b)) {
                unruledById.computeIfAbsent(id, k -> new ArrayList<>()).add(key);
                continue;
            }
            ruledById.computeIfAbsent(id, k -> new ArrayList<>()).add(key);
            String cls = b.getClass().getSimpleName();
            LOGGER.info("[SELF-AUDIT/pairdrops] {} id={} class={}: break, drops and partner = 1.20.1", key, id, cls);
            if (b instanceof BedBlock) beds++;
            else if (b instanceof DoorBlock) doors++;
            else {
                other++;
                warnings++;
                LOGGER.warn("[SELF-AUDIT/pairdrops] W1 {} (id {}, {}) is a two-part block with an FC identity but neither a "
                        + "bed nor a door: it now takes 1.20.1's break and drops; confirm with a ruling", key, id, cls);
            }
            btw.modern.Block fc = ProxyRegistry.getFcBlock(b);
            if (fc != null && !overridesNeighborChanged(b.getClass(), neighborChanged) && fcDeclaresNeighborChange(fc.getClass())) {
                warnings++;
                LOGGER.warn("[SELF-AUDIT/pairdrops] W2 {} (id {}): {} does not override neighborChanged, so "
                        + "BlockBehaviorMixin runs FC's {}.onNeighborBlockChange beside 1.20.1's partner pop", key, id, cls,
                        fc.getClass().getSimpleName());
            }
        }
        for (Map.Entry<Integer, List<String>> e : ruledById.entrySet()) {
            List<String> mixed = unruledById.get(e.getKey());
            if (mixed != null) {
                warnings++;
                LOGGER.warn("[SELF-AUDIT/pairdrops] W3 legacy id {} maps ruled {} and unruled {}: FC drops of it take 1.20.1 "
                        + "loot only at the ruled blocks' cells", e.getKey(), e.getValue(), mixed);
            }
        }
        for (int expected : EXPECTED_RULED_IDS) {
            if (!ruledById.containsKey(expected)) {
                warnings++;
                LOGGER.warn("[SELF-AUDIT/pairdrops] W4 legacy id {} has no ruled block: FC's harvest still drops it", expected);
            }
        }
        LOGGER.info("[SELF-AUDIT/pairdrops] {} ruled blocks ({} BedBlock, {} DoorBlock, {} other); FC drops of ids {} at a "
                + "live cell take that cell's 1.20.1 loot; {} warnings (0 expected)", beds + doors + other, beds, doors,
                other, ruledById.keySet(), warnings);
    }

    /** BlockBehaviour.neighborChanged's runtime name (SRG m_6861_ in production, the official name in dev). */
    private static String resolveNeighborChanged() {
        try {
            return ObfuscationReflectionHelper.findMethod(BlockBehaviour.class, "m_6861_", BlockState.class, Level.class,
                    BlockPos.class, Block.class, BlockPos.class, boolean.class).getName();
        } catch (Throwable t) {
            return "neighborChanged";
        }
    }

    private static boolean overridesNeighborChanged(Class<?> start, String name) {
        for (Class<?> c = start; c != null && c != BlockBehaviour.class; c = c.getSuperclass()) {
            try {
                c.getDeclaredMethod(name, BlockState.class, Level.class, BlockPos.class, Block.class, BlockPos.class,
                        boolean.class);
                return true;
            } catch (NoSuchMethodException ignored) {
                // not declared here; keep walking up
            } catch (Throwable t) {
                return true;   // unreadable: no WARN
            }
        }
        return false;
    }

    private static boolean fcDeclaresNeighborChange(Class<?> fcClass) {
        try {
            return fcClass.getMethod("onNeighborBlockChange", btw.modern.World.class, int.class, int.class, int.class,
                    int.class).getDeclaringClass() != btw.modern.Block.class;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void failOnce(String site, Throwable t) {
        if (FAILED.add(site + '|' + t.getClass().getName())) {
            LOGGER.warn("[vanilla_pair_drops] {} failed (logged once per site): isRuled answers false (FC's harvest), "
                    + "the panic is skipped, the charge falls back to FC's default 0.025", site, t);
        }
    }
}
