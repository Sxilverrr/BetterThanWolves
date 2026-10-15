package btw.forge;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Set;

/**
 * Startup self-audit for the BTW Forge bridge — the runtime companion to the build-time
 * {@code tools/BridgeAudit.java} and {@code tools/LinkAudit.java}. It validates registry/asset
 * state that only exists once the mod is loaded, logging actionable warnings at launch so the
 * bug classes we kept hitting in game are caught at startup instead.
 *
 * <p>Checks here are COMMON (both sides). The client-only renderer-coverage check lives in
 * {@code FCEntityRenderer.auditRendererCoverage()} (it needs the client render map), run right
 * after the renderers register.</p>
 */
public final class BridgeSelfAudit {

    private static final Logger LOGGER = LogManager.getLogger("BTW-SelfAudit");

    private BridgeSelfAudit() {}

    /** Run all common-side checks. Called from FMLCommonSetupEvent (registries frozen by then). */
    public static void runCommon() {
        auditBlockSpeedFactors();
    }

    private static boolean tickPolicyAudited;

    /**
     * Server-start checks, once per JVM. The tick audit reads the per-state caches, which are first filled at
     * the registry freeze (after FMLCommonSetupEvent, so every cached flag still reads false there) and rebuilt
     * by Blocks.rebuildCache when the server loads its resources, before ServerStartingEvent.
     */
    public static void runServerStart() {
        if (tickPolicyAudited) return;
        tickPolicyAudited = true;
        auditTickPolicy();
        auditLightPolicy();
    }

    /**
     * Mapped ids whose 4.B light is below 1.20.1's on purpose ({@link LightPolicy}): the brown mushroom 39 (4.B
     * drops 1.5.2's {@code setLightValue(0.125F)}: vanilla/server Block.java:129 is FCMOD-commented, the active :205
     * sets none; 1.20.1 Blocks.java:208-210 gives 1). Any other FC-ticked emitter that goes dark is a
     * Block.lightValue gap in the port's 1.5.2 constructors (BlockEndPortal sets its 15 there).
     */
    private static final Set<Integer> EXPECTED_DARKENED_IDS = Set.of(39);

    /**
     * Light-emission self-audit ({@link LightPolicy}): logs each FC-mapped vanilla block whose emission the rule
     * changed from 1.20.1's, and WARNs on (a) a state whose cached emission differs from the rule's answer
     * (BlockStateBaseTickMixin not applied, or the answer changed after the bake) and (b) a mapped emitter made dark
     * that 4.B does not make dark ({@link #EXPECTED_DARKENED_IDS}).
     */
    private static void auditLightPolicy() {
        if (!BTWLifecycle.isFcReady()) return;   // the tick audit already warned
        int changed = 0, warnings = 0;
        for (Block b : ForgeRegistries.BLOCKS) {
            if (b instanceof ProxyBlock || ProxyRegistry.getFcBlock(b) == null) continue;
            Object key = ForgeRegistries.BLOCKS.getKey(b);
            try {
                int id = ProxyRegistry.getBlockId(b);
                List<BlockState> states = b.getStateDefinition().getPossibleStates();
                Set<String> transitions = new java.util.LinkedHashSet<>();
                int changedStates = 0;
                boolean darkened = false;
                boolean mismatch = false;
                for (BlockState s : states) {
                    int vanilla = ((FcBlockStateAccess) (Object) s).btw$vanillaLightEmission();
                    if (vanilla < 0) continue;   // never baked
                    int now = s.getLightEmission();
                    int fc = LightPolicy.fcEmission(s);
                    if (now != (fc >= 0 ? fc : vanilla)) mismatch = true;
                    if (now != vanilla) {
                        changedStates++;
                        transitions.add(vanilla + "->" + now);
                        if (now == 0) darkened = true;
                    }
                }
                if (mismatch) {
                    LOGGER.warn("[SELF-AUDIT/light] {} (id {}): a cached light emission differs from the LightPolicy "
                            + "answer (BlockStateBaseTickMixin not applied?)", key, id);
                    warnings++;
                }
                if (changedStates == 0) continue;
                changed++;
                LOGGER.info("[SELF-AUDIT/light] {} id={} emission {} ({}/{} states)", key, id,
                        String.join(", ", transitions), changedStates, states.size());
                if (darkened && !EXPECTED_DARKENED_IDS.contains(id)) {
                    LOGGER.warn("[SELF-AUDIT/light] {} (id {}) emits light in 1.20.1 but Block.lightValue[{}] is 0: "
                            + "check the 1.5.2 constructor light of its port base class", key, id, id);
                    warnings++;
                }
            } catch (Throwable t) {
                LOGGER.warn("[SELF-AUDIT/light] could not audit {}", key, t);
                warnings++;
            }
        }
        LOGGER.info("[SELF-AUDIT/light] {} mapped blocks emit 4.B light that differs from 1.20.1, {} warnings "
                + "(0 expected)", changed, warnings);
    }

    /**
     * Mapped ids whose 1.20.1 class overrides tick and is expected to keep its scheduled ticks (TickPolicy
     * class javadoc): leaves, dispenser/dropper, detector rail, stairs, chests, farmland/cactus/sugar cane
     * survival, plates, torches, buttons, soul sand, repeater, cauldron, lamp, tripwire, comparator.
     */
    private static final Set<Integer> EXPECTED_VANILLA_SCHED_IDS = Set.of(18, 23, 28, 53, 54, 60, 67, 70, 72, 76,
            77, 81, 83, 88, 93, 108, 109, 114, 118, 123, 128, 130, 131, 132, 134, 135, 136, 143, 146, 147, 148, 149,
            156, 158);

    /**
     * Legacy ids whose FC object random-ticks in 4.B (needsRandomTick after the 1.5.2 constructors), from the
     * 1.5.2 / FC constructor chains: BlockGrass.java:23, BlockFlower.java:30, BlockFluid.java:29,
     * BlockStationary.java:25-28 (lava), BlockLeaves.java:28, BlockDetectorRail.java:24, FCBlockTorchBase.java:36,
     * BlockFire.java:44, BlockCrops.java:23, FCBlockFarmlandBase.java:37, BlockBasePressurePlate.java:27,
     * FCBlockRedstoneOre.java:33 (glowing 74), BlockRedstoneTorch.java:73 (both 75 and 76), BlockButton.java:27,
     * FCBlockSnowCover.java:29, BlockIce.java:24, BlockSnowBlock.java:23, BlockCactus.java:23, BlockReed.java:25,
     * BlockPumpkin.java:24, BlockPortal.java:23, FCBlockJackOLantern.java:29, BlockCake.java:23,
     * FCBlockSilverfish.java:36, FCBlockStoneBrick.java:38, FCBlockGourd.java:36, BlockStem.java:32,
     * BlockVine.java:23, BlockMycelium.java:23, BlockNetherStalk.java:25, FCBlockEndPortal.java:31,
     * BlockCocoa.java:25, BlockTripWireSource.java:24, BlockTripWire.java:26.
     */
    private static final Set<Integer> EXPECTED_4B_RANDOM = Set.of(2, 6, 8, 10, 11, 18, 28, 31, 32, 37, 38, 39, 40,
            50, 51, 59, 60, 70, 72, 74, 75, 76, 77, 78, 79, 80, 81, 83, 86, 90, 91, 92, 97, 98, 103, 104, 105, 106, 110,
            111, 115, 119, 127, 131, 132, 141, 142, 143);

    /**
     * Accepted flag gaps, reported at INFO instead of WARN: blocksList[75/76] are the ConcreteTorchBlock
     * placeholders (Modern-Common Block.java:2093-2094), not FC data, so they cannot carry 4.B's redstone-torch
     * random re-validation. Redstone torches stay 1.20.1-owned (their 160-tick burnout recheck stands in).
     */
    private static final Set<Integer> KNOWN_FLAG_GAPS = Set.of(75, 76);

    /** Vanilla legacy ids run 1..174; ProxyBlocks (FC-only blocks) start at 175 (BTWRegistration). */
    private static final int FIRST_PROXY_ID = 175;

    /**
     * Tick-ownership self-audit ({@link TickPolicy}): logs each FC-mapped vanilla block's random and scheduled
     * owner and WARNs on
     * (a) a class that overrides tick but is neither FC_SCHED nor an expected 1.20.1-owned scheduled tick;
     * (b) a class that overrides randomTick while its random owner is VANILLA without an exception entry;
     * (c) a 4.B random-tick flag the port gets wrong (constructor parity of the Modern-Common 1.5.2 bases);
     * (d) a state whose cached isRandomlyTicking differs from the decided gate (BlockStateBaseTickMixin not
     *     applied, or the gate changed after the bake).
     */
    private static void auditTickPolicy() {
        if (!BTWLifecycle.isFcReady()) {
            LOGGER.warn("[SELF-AUDIT/tick] FC is not initialised; every block keeps its 1.20.1 ticks.");
            return;
        }
        int blocks = 0, fcRandomStates = 0, fcSchedBlocks = 0, warnings = 0;
        for (Block b : ForgeRegistries.BLOCKS) {
            if (b instanceof ProxyBlock || ProxyRegistry.getFcBlock(b) == null) continue;
            try {
                List<BlockState> states = b.getStateDefinition().getPossibleStates();
                TickPolicy.Decision first = null;
                int gated = 0;
                boolean gateMismatch = false;
                for (BlockState s : states) {
                    TickPolicy.Decision d = TickPolicy.decide(s);
                    if (d == null) continue;
                    if (first == null) first = d;
                    if (s.isRandomlyTicking()) gated++;
                    if (d.random() == TickPolicy.FC && s.isRandomlyTicking()) fcRandomStates++;
                    if (d.gate() != null && s.isRandomlyTicking() != d.gate()) gateMismatch = true;
                }
                Object key = ForgeRegistries.BLOCKS.getKey(b);
                if (first == null) {
                    LOGGER.warn("[SELF-AUDIT/tick] {} is FC-mapped but TickPolicy left it unmanaged", key);
                    warnings++;
                    continue;
                }
                blocks++;
                int id = ProxyRegistry.getBlockId(b);
                if (first.sched() == TickPolicy.FC) fcSchedBlocks++;
                LOGGER.info("[SELF-AUDIT/tick] {} id={} random={} gated={}/{} sched={}", key, first.fcId(),
                        ownerName(first.random()), gated, states.size(), ownerName(first.sched()));

                Class<?> cls = b.getClass();
                if (TickPolicy.overridesTick(cls) && !TickPolicy.isFcScheduled(b)
                        && !EXPECTED_VANILLA_SCHED_IDS.contains(id)) {
                    LOGGER.warn("[SELF-AUDIT/tick] {} (id {}, {}) overrides tick but is neither FC_SCHED nor an "
                            + "expected 1.20.1-owned scheduled tick: classify it in TickPolicy", key, id, cls.getName());
                    warnings++;
                }
                if (first.random() == TickPolicy.VANILLA && TickPolicy.overridesRandomTick(cls)
                        && !"redstone-ore".equals(first.rule()) && !"ice".equals(first.rule())) {
                    LOGGER.warn("[SELF-AUDIT/tick] {} (id {}, {}) overrides randomTick and keeps the 1.20.1 random "
                            + "tick without an exception entry (rule {})", key, id, cls.getName(), first.rule());
                    warnings++;
                }
                if (gateMismatch) {
                    LOGGER.warn("[SELF-AUDIT/tick] {} (id {}): a cached isRandomlyTicking differs from the TickPolicy "
                            + "gate (BlockStateBaseTickMixin not applied?)", key, id);
                    warnings++;
                }
            } catch (Throwable t) {
                LOGGER.warn("[SELF-AUDIT/tick] could not audit {}", ForgeRegistries.BLOCKS.getKey(b), t);
                warnings++;
            }
        }

        // (c) 4.B random-tick flags of the vanilla-id FC objects (mapped or not: 9, 11, 74 ... have no own state).
        btw.modern.Block[] list = btw.modern.Block.blocksList;
        for (int id = 1; id < FIRST_PROXY_ID && id < list.length; id++) {
            btw.modern.Block fc = list[id];
            if (fc == null) continue;
            boolean expected = EXPECTED_4B_RANDOM.contains(id);
            if (expected == fc.getTickRandomly()) continue;
            if (KNOWN_FLAG_GAPS.contains(id)) {
                LOGGER.info("[SELF-AUDIT/tick] id {} ({}) getTickRandomly={}: known gap, 1.20.1 keeps its ticks",
                        id, fc.getClass().getSimpleName(), fc.getTickRandomly());
                continue;
            }
            LOGGER.warn("[SELF-AUDIT/tick] id {} ({}) getTickRandomly={} but 4.B {} it",
                    id, fc.getClass().getSimpleName(), fc.getTickRandomly(),
                    expected ? "random-ticks" : "does not random-tick");
            warnings++;
        }

        LOGGER.info("[SELF-AUDIT/tick] {} blocks, {} FC-random states, {} FC-scheduled blocks, {} warnings (0 expected)",
                blocks, fcRandomStates, fcSchedBlocks, warnings);
    }

    private static String ownerName(byte owner) {
        return switch (owner) {
            case TickPolicy.FC -> "FC";
            case TickPolicy.NONE -> "NONE";
            default -> "VANILLA";
        };
    }

    /**
     * Speed-factor sanity: MC 1.20.1 multiplies {@link Block#getSpeedFactor()} into the carried
     * {@code deltaMovement} every tick in {@code Entity.move()}. A factor &gt; 1.0 therefore
     * compounds into momentum and, while airborne, the retention product {@code 0.91 × factor}
     * exceeds 1.0 → unbounded speed ("flying"). This is exactly the bug BTW's +20% hard-surface
     * bonus caused when it was (wrongly) routed through getSpeedFactor. Any block reporting a
     * factor &gt; 1.0 is a regression of that class. (Slowdowns &lt; 1.0 are always safe.)
     */
    private static void auditBlockSpeedFactors() {
        int bad = 0, checked = 0;
        for (Block b : ForgeRegistries.BLOCKS) {
            try {
                float sf = b.getSpeedFactor();
                checked++;
                if (sf > 1.0F) {
                    LOGGER.warn("[SELF-AUDIT/speed] {} getSpeedFactor={} > 1.0 -> compounds into airborne "
                            + "momentum (flying-class regression; bonuses must go through getSpeed, not getSpeedFactor)",
                            ForgeRegistries.BLOCKS.getKey(b), sf);
                    bad++;
                }
            } catch (Throwable ignored) {}
        }
        LOGGER.info("[SELF-AUDIT/speed] checked {} blocks, {} with getSpeedFactor > 1.0 (0 expected).",
                checked, bad);
    }
}
