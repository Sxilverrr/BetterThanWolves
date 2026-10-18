package btw.forge.compat.coldsweat;

import btw.forge.compat.FcRefs;
import btw.forge.compat.thermal.BtwThermalTable;
import btw.forge.compat.thermal.BtwThermalTable.ClothingSet;
import btw.forge.compat.thermal.BtwThermalTable.HeatTier;
import btw.forge.compat.thermal.BtwThermalTable.MealTier;
import btw.forge.compat.thermal.BtwThermalTable.Slot;
import btw.forge.compat.thermal.BtwThermalTable.TextileKind;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.momosoftworks.coldsweat.api.event.core.registry.LoadRegistriesEvent;
import com.momosoftworks.coldsweat.api.util.Temperature;
import com.momosoftworks.coldsweat.data.ModRegistries;
import com.momosoftworks.coldsweat.data.RegistryHolder;
import com.momosoftworks.coldsweat.data.codec.configuration.BlockTempData;
import com.momosoftworks.coldsweat.data.codec.impl.ConfigData;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Cold Sweat integration (INTEGRATIONS #030): BTW's climate content becomes Cold Sweat data, and freezing costs FC
 * exhaustion ({@link ColdSweatShivering}).
 *
 * <ul>
 *   <li><b>Modern feature:</b> Cold Sweat 2.4.1+ (pinned 2.4.3.2): body temperature from nearby blocks, worn
 *       insulation, warming food and fuelled devices (hearth, boiler).</li>
 *   <li><b>Conflict with FC:</b> Cold Sweat knows only vanilla content. BTW's fire ladder, clothing, cauldron meals and
 *       fuels are invisible to it: a stoked fire is colder than vanilla fire, and knit wool insulates nothing.</li>
 *   <li><b>Pattern:</b> D, nothing disabled. On every Cold Sweat config load (server start, {@code /coldsweat reload})
 *       {@code LoadRegistriesEvent.Pre} adds BTW entries built from {@link BtwThermalTable} with live keys
 *       ({@link FcRefs}, so a moved BTW ID config never strands them), decoded by Cold Sweat's own codecs:
 *       <ul>
 *         <li>block temperatures: the fire ladder (embers 6 F up to the stoked fire 35 F, decision #14), the hibachi,
 *             the brick oven, BTW's torches and candle, the smouldering log, the burning ladder, FC's lava-cracked
 *             loose stone (Cold Sweat's own magma value), and the vanilla-key rows (the legacy torch, and candles, candle
 *             cakes and the lantern by their nearest FC relative);</li>
 *         <li>insulators: knit wool (the cold set), padded (balanced), tanned (leather plus), gimp (as leather);
 *             plate has none; BTW textiles as sewing ingredients;</li>
 *         <li>foods: the cauldron soups and stew, mushroom stew included, warm over time;</li>
 *         <li>fuels: every BTW item fuel at FC's ratio to coal, in the hearth and the boiler.</li>
 *       </ul></li>
 *   <li><b>Alt rejected:</b> shipped Cold Sweat data files. Their keys would hold only for the default BTW ID config,
 *       and Cold Sweat's BlockTemp classes break binary compatibility between 2.4.1 and 2.4.3, so neither is used.</li>
 * </ul>
 *
 * <p><b>Decoding is strict.</b> An entry whose decode reports any error, partial results included, is rejected with one
 * WARN. The decoder of Minecraft 1.20.1 (DataFixerUpper 6) replaces an invalid optional value with its default without
 * an error, so the fields that matter are checked after decoding (insulators: cold and heat; block temperatures: range,
 * max effect and units; fuels: the fuel value) and a mismatch rejects the entry with one WARN. Foods are not checked:
 * their accessors need an entity.</p>
 *
 * <p><b>Isolation (SR1).</b> {@code BTWCompat} calls {@link #init} only when Cold Sweat is loaded, inside a
 * {@code catch (Throwable)}. {@code init} probes every Cold Sweat type before it registers anything. The registry
 * handler catches {@code RuntimeException | LinkageError}, logs one ERROR, adds nothing and switches itself off; it
 * never throws into Cold Sweat's config load. A BTW row whose field does not resolve is skipped ({@link FcRefs} warns
 * once). No class of this package carries a Forge mod or event-bus-subscriber annotation; both listeners are added by
 * {@link #init}.</p>
 */
// FCMOD-INTEGRATION(1.20.1): Cold Sweat: BTW heat sources, clothing, meals and fuels become Cold Sweat data at runtime, with live keys; freezing costs FC exhaustion
public final class ColdSweatCompat {

    private static final Logger LOGGER = LogManager.getLogger("BTW-ColdSweat");

    /** Cold Sweat's Fahrenheit unit id; every BTW block temperature is written in F. */
    private static final String UNITS_F = "f";

    /** Cold Sweat fuel units of one coal in its own hearth data. */
    private static final float COLD_SWEAT_COAL_FUEL = 55F;

    private static final String[] FUEL_DEVICES = {"hearth", "boiler"};

    /** Tolerance of the post-decode checks. */
    private static final double EPSILON = 1e-6;

    /** Keys of the one-time WARNs (a tier, set, kind or meal tier without a Cold Sweat value). */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private static volatile boolean disabled;

    private ColdSweatCompat() {}

    /**
     * The entry point {@code BTWCompat} calls during mod construction, before FC init. Probes Cold Sweat's types, then
     * registers the {@code LoadRegistriesEvent.Pre} and {@code PlayerTickEvent} listeners on the Forge bus (Cold Sweat
     * posts its events there; {@code modBus} is not used). It touches no FcRefs, BtwThermalTable, ProxyRegistry or
     * registry. An incompatible Cold Sweat fails the probe before any listener exists.
     */
    // FCMOD-INTEGRATION(1.20.1): Cold Sweat: probe first, then register both listeners, so an incompatible version leaves nothing half-registered
    public static void init(IEventBus modBus) {
        Objects.requireNonNull(ModRegistries.BLOCK_TEMP_DATA, "ModRegistries.BLOCK_TEMP_DATA");
        Objects.requireNonNull(ModRegistries.INSULATOR_DATA, "ModRegistries.INSULATOR_DATA");
        Objects.requireNonNull(ModRegistries.FOOD_DATA, "ModRegistries.FOOD_DATA");
        Objects.requireNonNull(ModRegistries.FUEL_DATA, "ModRegistries.FUEL_DATA");
        Objects.requireNonNull(Temperature.Trait.BODY, "Temperature.Trait.BODY");
        Class<LoadRegistriesEvent.Pre> registriesEvent = LoadRegistriesEvent.Pre.class;
        Consumer<LoadRegistriesEvent.Pre> onRegistries = ColdSweatCompat::onLoadRegistries;
        Consumer<TickEvent.PlayerTickEvent> onPlayerTick = ColdSweatShivering::onPlayerTick;

        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, registriesEvent, onRegistries);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, TickEvent.PlayerTickEvent.class,
                onPlayerTick);
    }

    /**
     * Cold Sweat's config load, after its own data and before its registry modifiers: adds the BTW entries. Runs on the
     * server thread at server start (after FC init) and on every {@code /coldsweat reload}.
     */
    // FCMOD-INTEGRATION(1.20.1): Cold Sweat: BTW entries join Cold Sweat's registries on every config load; a failure adds nothing and never reaches Cold Sweat
    private static void onLoadRegistries(LoadRegistriesEvent.Pre event) {
        if (disabled) return;
        try {
            new Load(event).run();
        } catch (RuntimeException | LinkageError t) {
            disabled = true;
            LOGGER.error("[BTW-ColdSweat] Cold Sweat data could not be built; BTW continues without its Cold Sweat "
                    + "entries", t);
        }
    }

    // ---- BTW values on Cold Sweat's scale ----

    /** A block-temperature calibration in F: temperature, range (blocks) and max effect. */
    private record Calibration(double temperature, double range, double maxEffect) {}

    /** Insulation points: cold and heat. */
    private record Insulation(double cold, double heat) {}

    /** A food's temperature change and its duration in ticks. */
    private record Warmth(double temperature, int duration) {}

    /**
     * Decision #14, in F. LARGE_FIRE equals Cold Sweat's own vanilla fire and campfire (25 / 7 / 50), and LAVA_CRACKS its
     * magma block (20 / 3 / 48); the brick oven is 0.25 / 0.66 MC (x45). Null, with one WARN, for a tier without a value.
     */
    // FCMOD-INTEGRATION(1.20.1): Cold Sweat: the BTW fire ladder on Cold Sweat's scale (decision #14); an unknown tier is skipped, never guessed
    private static @Nullable Calibration calibration(HeatTier tier) {
        return switch (tier) {
            case EMBERS -> new Calibration(6, 3, 12);
            case SMALL_FIRE -> new Calibration(12, 5, 24);
            case MEDIUM_FIRE -> new Calibration(20, 6, 40);
            case LARGE_FIRE -> new Calibration(25, 7, 50);
            case STOKED_FIRE -> new Calibration(35, 7, 70);
            case HIBACHI_BODY -> new Calibration(6, 2, 12);
            case BRICK_OVEN -> new Calibration(11.25, 5, 29.7);
            case TORCH -> new Calibration(2, 2, 4);
            case CANDLE -> new Calibration(1, 2, 2);
            case SMOULDERING_LOG -> new Calibration(6, 3, 12);
            case BURNING_LADDER -> new Calibration(25, 7, 50);
            case LAVA_CRACKS -> new Calibration(20, 3, 48);
            default -> {
                warnOnce("tier " + tier, "[BTW-ColdSweat] heat tier {} has no Cold Sweat calibration; its rows are "
                        + "skipped", tier);
                yield null;
            }
        };
    }

    /** Worn insulation (cold/heat) of a BTW clothing piece; null for plate, which has none. */
    private static @Nullable Insulation armourInsulation(ClothingSet set, Slot slot) {
        return switch (set) {
            case WOOL -> bySlot(slot, new Insulation(6, 0), new Insulation(8, 0), new Insulation(7, 0),
                    new Insulation(6, 0));
            case PADDED -> bySlot(slot, new Insulation(7, 4), new Insulation(10, 5), new Insulation(9, 5),
                    new Insulation(7, 4));
            case TANNED -> bySlot(slot, new Insulation(6, 6), new Insulation(8, 8), new Insulation(7, 7),
                    new Insulation(6, 6));
            // Gimp as Cold Sweat's own leather armour
            case GIMP -> bySlot(slot, new Insulation(5, 5), new Insulation(7, 7), new Insulation(6, 6),
                    new Insulation(5, 5));
            case PLATE -> null;
            default -> {
                warnOnce("set " + set, "[BTW-ColdSweat] clothing set {} has no Cold Sweat insulation; skipped", set);
                yield null;
            }
        };
    }

    private static Insulation bySlot(Slot slot, Insulation head, Insulation chest, Insulation legs, Insulation feet) {
        return switch (slot) {
            case HEAD -> head;
            case CHEST -> chest;
            case LEGS -> legs;
            case FEET -> feet;
        };
    }

    /** Insulation (cold/heat) of a BTW textile sewn into armour. */
    private static @Nullable Insulation textileInsulation(TextileKind kind) {
        return switch (kind) {
            case WOOL -> new Insulation(1, 0);
            case WOOL_KNIT -> new Insulation(1.5, 0);
            case PADDING -> new Insulation(1.5, 0.5);
            case TANNED_LEATHER -> new Insulation(1.25, 1.25);
            default -> {
                warnOnce("textile " + kind, "[BTW-ColdSweat] textile {} has no Cold Sweat insulation; skipped", kind);
                yield null;
            }
        };
    }

    /** A meal's warmth: HOT +20 for 2400 ticks, WARM +15 for 1800 ticks. */
    private static @Nullable Warmth warmth(MealTier tier) {
        return switch (tier) {
            case HOT -> new Warmth(20, 2400);
            case WARM -> new Warmth(15, 1800);
            default -> {
                warnOnce("meal " + tier, "[BTW-ColdSweat] meal tier {} has no Cold Sweat temperature; skipped", tier);
                yield null;
            }
        };
    }

    private static void warnOnce(String key, String message, Object... params) {
        if (WARNED.add(key)) LOGGER.warn(message, params);
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < EPSILON;
    }

    // ---- one config load ----

    /** The (FC field, tier) group of heat rows that becomes one block-temperature entry. */
    private record HeatGroup(String fcField, HeatTier tier) {}

    /**
     * One config load: builds and decodes every entry, then adds the accepted ones together, so a failure part-way adds
     * nothing.
     */
    private static final class Load {
        private final LoadRegistriesEvent.Pre event;
        private final RegistryOps<JsonElement> ops;
        private final List<Runnable> accepted = new ArrayList<>();
        private int blockTemps;
        private int armour;
        private int textiles;
        private int foods;
        private int fuels;
        private int fuelItems;
        private int fuelsBelowOneUnit;
        private int rejected;

        Load(LoadRegistriesEvent.Pre event) {
            this.event = event;
            this.ops = RegistryOps.create(JsonOps.INSTANCE, event.getRegistryAccess());
        }

        void run() {
            addBlockTemps();
            addInsulators();
            addFoods();
            addFuels();
            for (Runnable add : accepted) add.run();
            LOGGER.info("[BTW-ColdSweat] added {} block temps, {} insulators ({} armour, {} textiles), {} foods, {} "
                            + "fuels ({} items x hearth+boiler, {} below 1 unit skipped); {} rejected", blockTemps,
                    armour + textiles, armour, textiles, foods, fuels, fuelItems, fuelsBelowOneUnit, rejected);
        }

        /**
         * Heat rows merged per (FC field, tier) into one entry each, so the hibachi's two metadata ranges and the eight
         * odd metas of each lava-receiving block are one entry; then the vanilla-key rows.
         */
        private void addBlockTemps() {
            Map<HeatGroup, BitSet> groups = new LinkedHashMap<>();
            for (BtwThermalTable.HeatSource row : BtwThermalTable.heatSources()) {
                BitSet metas = groups.computeIfAbsent(new HeatGroup(row.fcField(), row.tier()),
                        group -> new BitSet(ColdSweatJson.META_VALUES));
                int lo = Math.max(0, row.metaMin());
                int hi = Math.min(ColdSweatJson.META_VALUES - 1, row.metaMax());
                if (lo <= hi) metas.set(lo, hi + 1);
            }
            for (Map.Entry<HeatGroup, BitSet> entry : groups.entrySet()) {
                HeatGroup group = entry.getKey();
                Calibration c = calibration(group.tier());
                if (c == null) continue;
                ResourceLocation key = FcRefs.blockKey(group.fcField());
                if (key == null) continue;   // FcRefs warned once
                if (entry.getValue().isEmpty()) {
                    warnOnce("metas " + group.fcField(), "[BTW-ColdSweat] heat source {} {} has no metadata inside "
                            + "0-15; skipped", group.tier(), group.fcField());
                    continue;
                }
                JsonObject json = ColdSweatJson.blockTemp(List.of(key.toString()),
                        ColdSweatJson.metaState(entry.getValue()), c.temperature(), UNITS_F, c.range(), c.maxEffect());
                if (offer(ModRegistries.BLOCK_TEMP_DATA, json, "block temp", group.fcField(),
                        data -> droppedBlockTempField(data, c))) {
                    blockTemps++;
                }
            }
            for (BtwThermalTable.VanillaHeatSource row : BtwThermalTable.vanillaHeatSources()) {
                Calibration c = calibration(row.tier());
                if (c == null) continue;
                JsonObject json = ColdSweatJson.blockTemp(List.of(row.selector()),
                        row.requiresLit() ? ColdSweatJson.litState() : null, c.temperature(), UNITS_F, c.range(),
                        c.maxEffect());
                if (offer(ModRegistries.BLOCK_TEMP_DATA, json, "block temp", row.selector(),
                        data -> droppedBlockTempField(data, c))) {
                    blockTemps++;
                }
            }
        }

        /** Clothing as worn insulation (type armor), then textiles as sewing ingredients (type item). */
        private void addInsulators() {
            for (BtwThermalTable.Clothing row : BtwThermalTable.clothing()) {
                Insulation value = armourInsulation(row.set(), row.slot());
                if (value == null) continue;
                ResourceLocation key = FcRefs.itemKey(row.fcField());
                if (key == null) continue;   // FcRefs warned once
                if (offerInsulator("armor", key, value, row.fcField())) armour++;
            }
            for (BtwThermalTable.Textile row : BtwThermalTable.textiles()) {
                Insulation value = textileInsulation(row.kind());
                if (value == null) continue;
                ResourceLocation key = FcRefs.itemKey(row.fcField());
                if (key == null) continue;   // FcRefs warned once
                if (offerInsulator("item", key, value, row.fcField())) textiles++;
            }
        }

        private boolean offerInsulator(String type, ResourceLocation key, Insulation value, String name) {
            JsonObject json = ColdSweatJson.insulator(type, key, value.cold(), value.heat());
            return offer(ModRegistries.INSULATOR_DATA, json, "insulator", name,
                    data -> near(data.getCold(), value.cold()) && near(data.getHeat(), value.heat())
                            ? null : "insulation");
        }

        /** BTW's cauldron meals by FC field, then the ones on fixed vanilla ids (mushroom stew). */
        private void addFoods() {
            for (BtwThermalTable.HotMeal row : BtwThermalTable.hotMeals()) {
                Warmth value = warmth(row.tier());
                if (value == null) continue;
                ResourceLocation key = FcRefs.itemKey(row.fcField());
                if (key == null) continue;   // FcRefs warned once
                if (offerFood(key, value, row.fcField())) foods++;
            }
            for (BtwThermalTable.VanillaHotMeal row : BtwThermalTable.vanillaHotMeals()) {
                Warmth value = warmth(row.tier());
                if (value == null) continue;
                if (offerFood(row.item(), value, row.item().toString())) foods++;
            }
        }

        private boolean offerFood(ResourceLocation key, Warmth value, String name) {
            JsonObject json = ColdSweatJson.food(key, value.temperature(), value.duration());
            // no post-decode check: FoodData's accessors build Map.of(...) with the eater, which throws on null
            return offer(ModRegistries.FOOD_DATA, json, "food", name, data -> null);
        }

        /**
         * Every BTW item fuel (one live walk of FC's item list per load) at FC's ratio to coal: Cold Sweat gives coal 55
         * units, so an item gives round(55 x burn time / coal burn time), in the hearth and in the boiler. An item worth
         * less than one unit is skipped.
         */
        // FCMOD-INTEGRATION(1.20.1): Cold Sweat: FC fuels keep FC's burn ratios in Cold Sweat's hearth and boiler
        private void addFuels() {
            List<BtwThermalTable.Fuel> rows = BtwThermalTable.fuels();
            for (BtwThermalTable.Fuel row : rows) {
                int units = Math.round(COLD_SWEAT_COAL_FUEL * row.fcBurnTime() / BtwThermalTable.FC_COAL_BURN_TIME);
                if (units < 1) {
                    fuelsBelowOneUnit++;
                    continue;
                }
                ResourceLocation key = row.key();
                Item item = row.item();
                if (key == null || item == null) continue;
                fuelItems++;
                for (String device : FUEL_DEVICES) {
                    JsonObject json = ColdSweatJson.fuel(device, key, units);
                    if (offer(ModRegistries.FUEL_DATA, json, device + " fuel", key.toString(),
                            data -> data.fuel(new ItemStack(item)) == units ? null : "fuel")) {
                        fuels++;
                    }
                }
            }
        }

        /**
         * Decodes {@code json} with the registry's codec and queues the entry; false (counted as rejected, one WARN) when
         * the decode reports an error or {@code droppedField} names a field the lenient decoder replaced.
         */
        private <T extends ConfigData> boolean offer(RegistryHolder<T> holder, JsonObject json, String kind,
                                                     String name, Function<T, String> droppedField) {
            DataResult<T> result = holder.codec().parse(ops, json);
            Optional<DataResult.PartialResult<T>> error = result.error();
            if (error.isPresent()) {
                LOGGER.warn("[BTW-ColdSweat] Cold Sweat rejected {} {}: {}", kind, name, error.get().message());
                rejected++;
                return false;
            }
            T value = result.result().orElse(null);
            if (value == null) {
                LOGGER.warn("[BTW-ColdSweat] Cold Sweat rejected {} {}: no value decoded", kind, name);
                rejected++;
                return false;
            }
            String dropped = droppedField.apply(value);
            if (dropped != null) {
                LOGGER.warn("[BTW-ColdSweat] Cold Sweat dropped {} of {} {}", dropped, kind, name);
                rejected++;
                return false;
            }
            accepted.add(() -> event.addRegistryEntry(holder, value));
            return true;
        }
    }

    /** The first block-temperature field the decoder did not keep (range, max_effect, units), or null. */
    private static @Nullable String droppedBlockTempField(BlockTempData data, Calibration c) {
        if (!near(data.range(), c.range())) return "range";
        if (!near(data.maxEffect(), c.maxEffect())) return "max_effect";
        if (!"F".equals(data.units().name())) return "units";
        return null;
    }
}
