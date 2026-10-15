package btw.forge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The light-emission rule: FC decides the light emission of every FC-mapped vanilla-id {@link BlockState} whose
 * random tick FC owns ({@link TickPolicy}). Such a state emits 4.B's {@code Block.lightValue[id]} (1.5.2
 * {@code setLightValue(f)}: {@code lightValue[id] = (int) (15 * f)}, vanilla/server Block.java:522-526), as
 * ProxyBlocks already do (ProxyBlock.buildProperties, {@code props.lightLevel}). Enforced by
 * {@code btw.forge.mixin.BlockStateBaseTickMixin}, which writes the answer into the cached
 * {@code BlockStateBase.lightEmission} at initCache, right after the tick owners were decided there; the 1.20.1 light
 * engines read that field (BlockLightEngine.getEmission -> IForgeBlock.getLightEmission ->
 * BlockStateBase.getLightEmission).
 *
 * <p><b>Why.</b> FC's tick rules read the light at the block's own cell, which the block's own emission sets. 4.B
 * removed the brown mushroom's 1.5.2 light (vanilla/server Block.java:129, {@code setLightValue(0.125F)}, sits in the
 * FCMOD-commented block :108-185; the active FCBlockMushroomBrown line :205 sets none), and FCBlockMushroomBrown
 * spreads off mycelium only from a cell whose full light is 0 (Common FCBlockMushroomBrown.java:56-61, :67). 1.20.1
 * BROWN_MUSHROOM emits 1 (Blocks.java:208-210), so the source cell always read 1 and FC's spread in darkness was
 * dead.</p>
 *
 * <p><b>Scope.</b> A state is managed when its random owner is FC (so it is FC-mapped, not a {@link ProxyBlock},
 * and FC is initialised) and it has no {@link BlockStateProperties#LIT} or {@link BlockStateProperties#POWERED}
 * property. Those states stand for two legacy ids at once (furnace 61/62, redstone ore 73/74, redstone torch 75/76,
 * lamp 123/124, repeater 93/94, comparator 149/150), so one id's table entry cannot describe them. Every other state
 * keeps 1.20.1's emission: a block FC never ticks has no FC rule reading its own light, and a light 1.20.1 added to
 * a 1.5.2 block (the enchanting table's 7; 4.B sets none, vanilla/server Block.java:331) stays as the modern feature
 * it is, lighting FC's world as it lights 1.20.1's.</p>
 *
 * <p><b>Effect today</b> (logged by the self-audit as [SELF-AUDIT/light]): BROWN_MUSHROOM 1 -> 0. The other FC-ticked
 * emitters already match 4.B: lava, fire, torches, nether portal, jack o'lantern, end portal (with Modern-Common
 * BlockEndPortal's 1.5.2 constructor light).</p>
 *
 * <p><b>Existing saves.</b> Block light stored before this rule (1 at every brown mushroom) stays until a light
 * check runs at the cell. {@link #relightIfStale} queues that check from the state's FC random tick when the stored
 * light can no longer be explained by its emission or its neighbours.</p>
 *
 * <p><b>Determinism.</b> {@link #fcEmission} is a function of the BlockState, its cached tick owner and FC's static
 * tables, as pure as {@link TickPolicy#decide}: it runs at every registry bake and Blocks.rebuildCache, on both
 * sides, and client and server must agree on emission.</p>
 */
public final class LightPolicy {

    private static final Logger LOGGER = LogManager.getLogger("BTW-Light");

    private LightPolicy() {}

    /**
     * 4.B light emission of {@code s}, or -1 to keep the 1.20.1 emission. Reads the random owner cached on the state
     * (BlockStateBaseTickMixin decides it earlier in the same initCache), the state and FC's static data.
     */
    public static int fcEmission(BlockState s) {
        if (!BTWLifecycle.isFcReady()) return -1;
        if (((FcBlockStateAccess) (Object) s).btw$randomOwner() != TickPolicy.FC) return -1;
        Block block = s.getBlock();
        if (block instanceof ProxyBlock) return -1;   // ProxyBlock.buildProperties already emits lightValue[id]
        if (s.hasProperty(BlockStateProperties.LIT) || s.hasProperty(BlockStateProperties.POWERED)) return -1;
        int id = ProxyRegistry.getBlockId(block);
        if (id <= 0 || id >= btw.modern.Block.blocksList.length || btw.modern.Block.blocksList[id] == null) return -1;
        return Math.max(0, Math.min(15, btw.modern.Block.lightValue[id]));
    }

    /** True when the rule changed {@code s}'s emission from its 1.20.1 value. */
    public static boolean isOverridden(BlockState s) {
        int vanilla = ((FcBlockStateAccess) (Object) s).btw$vanillaLightEmission();
        return vanilla >= 0 && vanilla != s.getLightEmission();
    }

    /**
     * Queues a block-light check at {@code pos} when {@code state}'s emission was overridden and the block light
     * stored there is higher than both its emission and what a neighbour could pass on (neighbour - 1): light baked
     * with the 1.20.1 emission before this rule (a brown mushroom's own 1). The check runs on the light engine's
     * queue, so the FC tick of this call still reads the old value; the next one reads the corrected light.
     */
    static void relightIfStale(BlockState state, ServerLevel level, BlockPos pos) {
        try {
            if (!isOverridden(state)) return;
            int stored = level.getBrightness(LightLayer.BLOCK, pos);
            int explained = state.getLightEmission();
            if (stored <= explained) return;
            BlockPos.MutableBlockPos neighbour = new BlockPos.MutableBlockPos();
            for (Direction d : Direction.values()) {
                explained = Math.max(explained, level.getBrightness(LightLayer.BLOCK, neighbour.setWithOffset(pos, d)) - 1);
            }
            if (stored > explained) {
                level.getLightEngine().checkBlock(pos);
            }
        } catch (Throwable t) {
            warnOnce("RELIGHT|" + state.getBlock().getClass().getName() + '|' + t.getClass().getName(), state, t);
        }
    }

    /** Failure sites already logged. */
    private static final Set<String> LOGGED_FAILURES = ConcurrentHashMap.newKeySet();

    /** initCache must never throw from a registry bake: the failing state keeps its 1.20.1 emission. */
    public static void warnDecideFailure(BlockState s, Throwable t) {
        warnOnce("DECIDE|" + s.getBlock().getClass().getName() + '|' + t.getClass().getName(), s, t);
    }

    private static void warnOnce(String site, BlockState s, Throwable t) {
        if (LOGGED_FAILURES.add(site)) {
            LOGGER.warn("Light policy failed for {}; it keeps its 1.20.1 light (logged once)", s, t);
        }
    }
}
