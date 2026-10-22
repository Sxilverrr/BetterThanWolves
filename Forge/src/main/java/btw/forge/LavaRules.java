package btw.forge;

import btw.forge.compat.FcRefs;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FluidState;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.fluids.FluidInteractionRegistry.InteractionInformation;
import net.minecraftforge.fluids.FluidType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FCMOD-INTEGRATION(1.20.1): hook_ownership (lava pillows; hook design P3(i), D14(b))
 *
 * <p><b>Feature:</b> 1.20.1's fluid engine owns lava flow (TickPolicy: "flow belongs to the 1.20.1 engine") and two
 * lava/water rules with it. Contact: Forge's first built-in lava interaction hardens a lava cell that has water above
 * or beside it, a source into obsidian and every other cell into cobblestone (FluidInteractionRegistry.java:81-85),
 * run from LiquidBlock.onPlace / neighborChanged (LiquidBlock.java:109-129). Lava flowing down onto water:
 * LavaFluid.spreadTo places stone (LavaFluid.java:168-182, the stone at :173).</p>
 *
 * <p><b>Conflict with FC:</b> 4.B changed both rules (hook design H8). Contact (vanilla/server
 * BlockFluid.java:303-357): a source (meta 0) becomes obsidian, flowing lava of meta 1-4 becomes FC's lava pillow
 * (FCMOD :346-350), and lava of meta 5-7 or falling lava (meta 8 and up) does not harden. Lava flowing down onto
 * water makes the pillow instead of stone (vanilla/server BlockFlowing.java:167-178, FCMOD :171-175). The port ran
 * neither: the contact rule's 1.5.2 hooks (BlockFluid.onBlockAdded / onNeighborBlockChange, :286-298) are
 * overridden without super by LiquidBlock (hook scan, LAVA onPlace / neighborChanged), and the flow-down rule sits in
 * 1.5.2's flow tick, which the 1.20.1 engine replaces. Cobblestone and stone generators worked as in 1.20.1.</p>
 *
 * <p><b>Integration pattern:</b> D, the 4.B rules on 1.20.1's own machinery (no rerouting). The contact rule gates
 * Forge's built-in lava+water interaction in place ({@code FluidInteractionRegistryMixin}), and the flow-down rule
 * maps LavaFluid.spreadTo's stone ({@code LavaFluidMixin}). Everything else stays 1.20.1: flow (save that no fluid
 * displaces lava, below), timing, the fizz of every hardening, FluidPlaceBlockEvent (both placements still fire it,
 * FluidInteractionRegistry.java:148 and LavaFluid.java:173), basalt (FluidInteractionRegistry.java:87-91) and any
 * interaction another mod registers for lava.</p>
 *
 * <p><b>Rebalance:</b> none beyond 4.B. Generators make drop-less pillows (FCBlockLavaPillow.idDropped 0, Common
 * FCBlockLavaPillow.java:40-43) instead of cobblestone and stone; obsidian is unchanged.</p>
 *
 * <p><b>The meta mapping:</b> LiquidBlock's LEVEL is 1.5.2's metadata (LiquidBlock.java:180-188;
 * FlowingFluid.getLegacyLevel, FlowingFluid.java:421-423): 0 for a source, {@code 8 - amount} for flowing lava, plus 8
 * when falling. Overworld lava drops 2 levels per block in both versions (LavaFluid.java:139-141; vanilla/server
 * BlockFlowing.java:63-68), the Nether 1. So "meta 1-4" is a flowing, non-falling cell with {@code amount >= 4}.</p>
 *
 * <p><b>A declined contact keeps the lava's tick.</b> canInteract answering false is what makes LiquidBlock schedule
 * the lava's flow tick (LiquidBlock.java:110-111, 125-126), so lava that 4.B would not harden keeps flowing and recedes
 * when unfed. Rejected (design D14(a)): a FluidPlaceBlockEvent listener that restores the lava. canInteract has already
 * answered true by then, so the restored cell never ticks again (proof row P3d).</p>
 *
 * <p><b>No fluid displaces lava.</b> 1.20.1 lets water flow into lava whose height is at least 4/9
 * (LavaFluid.java:143-145, asked only by FlowingFluid.canSpreadTo, FlowingFluid.java:392-393); 1.5.2 never let a fluid
 * displace lava (vanilla/server BlockFlowing.java:443-447). 1.20.1's own contact rule hid the difference, because
 * every lava cell water touched hardened at the water's arrival, before the water's next flow tick. 4.B leaves falling
 * lava unhardened, so with 1.20.1's flow alone a water stream entering a lava fall would replace the touched cell, run
 * down the column one cell per water tick (5 ticks, against lava's 30), could harden the pool at the bottom, and would
 * only be capped by a pillow when the lava above fell onto it; 4.B keeps the column. {@code LavaFluidMixin} therefore
 * answers LavaFluid.canBeReplacedWith false, as 1.5.2 did, and the lava fall stays lava beside the water (proof rows
 * P3g, P3h). Vanilla already answers false for every fluid that is not water, and buckets do not ask (BucketItem uses
 * BlockState.canBeReplaced).</p>
 *
 * <p><b>Known difference from 4.B (accepted):</b> 4.B fizzed on every water contact, also when nothing hardened
 * (BlockFluid.java:353); 1.20.1 only fizzes with a hardening, so a declined contact is silent.</p>
 *
 * <p>Never throws into the fluid engine. The pillow is named by its FC field ({@link FcRefs}), so a moved BTW ID
 * config still finds it; when it does not resolve, the cell takes 1.20.1's block (cobblestone, stone) and a WARN is
 * logged once.</p>
 */
public final class LavaRules {

    private static final Logger LOGGER = LogManager.getLogger("BTW-Lava");

    /** FCBetterThanWolves field of the lava pillow (ParseID "fcBlockLavaPillowID", 1077 by default). */
    static final String PILLOW_FIELD = "fcBlockLavaPillow";

    /**
     * The smallest flowing amount that hardens: 4.B hardens meta 4 and below (BlockFluid.java:344), and meta is
     * 8 - amount.
     */
    private static final int MIN_HARDENING_AMOUNT = 8 - 4;

    /** The pillow's default state (FC meta 0, as 4.B's setBlock(id) gives), cached once resolved. */
    private static volatile BlockState pillowState;

    /** Fallback blocks already reported by {@link #pillowOr}. */
    private static final Set<String> FALLBACKS_LOGGED = ConcurrentHashMap.newKeySet();

    private LavaRules() {}

    /**
     * Installed by FluidInteractionRegistryMixin in place of Forge's built-in lava+water interaction
     * (FluidInteractionRegistry.java:82-85), so it keeps that entry's first-match position ahead of basalt (:87-91) and
     * of every interaction registered later. Forge's own water test still decides which neighbour counts (:135); 4.B's
     * meta gate is added in front of it, and the result is {@link #contactState}. The record's (predicate, function)
     * constructor builds the same placement as Forge's: FluidPlaceBlockEvent, then the fizz (:146-150).
     *
     * @param source the source fluid type of the registration the mixin modifies
     * @param forge  Forge's interaction for it
     * @return the gated interaction, or {@code forge} unchanged when the registration is not lava's
     */
    public static InteractionInformation contactRule(FluidType source, InteractionInformation forge) {
        try {
            if (forge == null || source != ForgeMod.LAVA_TYPE.get()) {
                LOGGER.warn("[LAVA] Forge's first built-in fluid interaction is not lava's ({}); the 4.B lava contact"
                        + " rule is NOT installed and lava meeting water hardens as in 1.20.1", source);
                return forge;
            }
            InteractionInformation gated = new InteractionInformation(
                    (level, lavaPos, neighbourPos, lava) -> hardensOnContact(lava)
                            && forge.predicate().test(level, lavaPos, neighbourPos, lava),
                    LavaRules::contactState);
            LOGGER.info("[LAVA] 4.B lava contact rule installed on Forge's lava+water interaction: source -> obsidian,"
                    + " flowing meta 1-4 -> FC lava pillow, meta 5-7 and falling lava keep flowing");
            return gated;
        } catch (Throwable t) {
            LOGGER.warn("[LAVA] the 4.B lava contact rule could not be installed; lava meeting water hardens as"
                    + " in 1.20.1", t);
            return forge;
        }
    }

    /**
     * 4.B's contact gate (vanilla/server BlockFluid.java:338-351): a source, or flowing lava of meta 1-4. Falling lava
     * (meta 8 and up) and meta 5-7 do not harden.
     */
    static boolean hardensOnContact(FluidState lava) {
        if (lava.isSource()) {
            return true;
        }
        if (lava.hasProperty(BlockStateProperties.FALLING) && lava.getValue(BlockStateProperties.FALLING)) {
            return false;
        }
        return lava.getAmount() >= MIN_HARDENING_AMOUNT;
    }

    /**
     * What a hardening lava cell becomes: obsidian for a source (BlockFluid.java:340-342), else the pillow (:344-350).
     */
    static BlockState contactState(FluidState lava) {
        return lava.isSource() ? Blocks.OBSIDIAN.defaultBlockState() : pillowOr(Blocks.COBBLESTONE.defaultBlockState());
    }

    /**
     * The block LavaFluid.spreadTo places when lava flows down onto water (LavaFluid.java:173): 1.20.1's stone becomes
     * the pillow (vanilla/server BlockFlowing.java:169-175). Any other state, for example one another mod already
     * substituted, is left alone.
     */
    public static BlockState flowOntoWater(BlockState vanilla) {
        return vanilla != null && vanilla.is(Blocks.STONE) ? pillowOr(vanilla) : vanilla;
    }

    /**
     * The pillow's default state, or {@code fallback} (with one WARN per fallback block) while it does not resolve.
     * Never throws.
     */
    private static BlockState pillowOr(BlockState fallback) {
        BlockState cached = pillowState;
        if (cached != null) {
            return cached;
        }
        Throwable failure = null;
        try {
            Block pillow = FcRefs.block(PILLOW_FIELD);
            if (pillow != null) {
                cached = pillow.defaultBlockState();
                pillowState = cached;
                return cached;
            }
        } catch (Throwable t) {
            failure = t;
        }
        warnFallback(fallback.getBlock().toString(), failure);
        return fallback;
    }

    private static void warnFallback(String fallback, Throwable failure) {
        if (FALLBACKS_LOGGED.add(fallback)) {
            if (failure != null) {
                LOGGER.warn("[LAVA] the FC lava pillow (FCBetterThanWolves.{}) failed to resolve; lava meeting water"
                        + " makes 1.20.1's {} instead", PILLOW_FIELD, fallback, failure);
            } else {
                LOGGER.warn("[LAVA] the FC lava pillow (FCBetterThanWolves.{}) did not resolve; lava meeting water"
                        + " makes 1.20.1's {} instead", PILLOW_FIELD, fallback);
            }
        }
    }
}
