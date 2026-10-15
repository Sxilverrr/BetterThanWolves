package btw.modern;

/**
 * Port helper: the legacy ids of the FC "twins", plain instances of FC plant classes (FCBlockSapling, FCBlockCarrot,
 * FCBlockWheatLegacy, FCBlockMushroom) that run FC's own growth rules for post-1.5.2 plants. The Forge bridge builds
 * them at reserved ids (btw.forge.TwinRegistry) and FC's world reads see the live 1.20.1 plant as the twin's id, so
 * FC's random tick, survival, stages, weeds, hydration and fertilizer apply to it unchanged.
 *
 * <p>A twin stands for a live 1.20.1 block that has its own loot. Where FC's code drops a twin
 * ({@link Block#dropBlockAsItemWithChance} and {@link BlockCrops#dropBlockAsItemWithChance}: BlockFlower
 * .checkFlowerChange, the saw, FC pistons), the live block's 1.20.1 loot drops through
 * {@link World#DropModernBlockLoot} instead of the 1.5.2 items of the twin's class (a block item of the twin's own
 * id, carrots, wheat seeds, red mushrooms).</p>
 *
 * <p>Written once, while FC's block table is built (before BTWLifecycle publishes it), and read-only afterwards.</p>
 */
public final class ModernTwins {

    private static final boolean[] TWIN = new boolean[Block.blocksList.length];

    private ModernTwins() {}

    /** Marks {@code id} as a twin id. Called by the bridge right after it built the twin object there. */
    public static void markTwin(int id) {
        if (id > 0 && id < TWIN.length) {
            TWIN[id] = true;
        }
    }

    /** True when blocksList[id] is a twin (an FC object standing for a live 1.20.1 plant). */
    public static boolean isTwin(int id) {
        return id > 0 && id < TWIN.length && TWIN[id];
    }
}
