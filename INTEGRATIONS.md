# FC ↔ Modern MC Integration Protocols

This document defines the contract for reconciling FC gameplay with
post-1.5.2 Minecraft features. Every integration lives here as a
numbered, audited entry. Nothing gets silently modified.

> **FC code is read-only. Never modify it.** Every integration lives in
> port code: the Forge bridge, Modern-Common, or the planned
> FC-extension subproject (Protocol #1).

---

## Protocols (strict)

1. **FC code is read-only. Never modify it.** (maintainer directive, 2026-10-10)
   "FC code" means FlowerChild's BTW sources (`net.minecraft.src.btw.*`
   under `Common/`, `Client/`, `Server/`), the 1.5.2 vanilla sources with
   FC's `FCMOD` edits under `vanilla/`, the frozen compiled 1.5.2/FC
   classes (root `build/classes/java/{main,server}`), FlowerChild's
   original 4.B release files kept in the repo (`Src/`, `SrcServer/` and
   `SrcClient/`, holding the flat sources and the `patch.txt` files that
   `setupVanilla` applies to build `vanilla/`, plus
   `BTWMod4-B0000003.zip`), and FlowerChild's own files such as
   `Readme.txt`. Nobody edits them, ever: no "small fixes", no tagged
   edits. All adaptation happens outside FC code:
   - the **Forge bridge** (`Forge/src/main/java/btw/forge`, mixins on
     1.20.1 classes, data packs);
   - **Modern-Common** (`btw.modern`), the port's own pure-Java stand-ins
     for 1.5.2 vanilla classes. This is port code, not FC code;
   - a **planned FC-extension subproject (name TBD)** that *extends* FC
     code (subclasses, registries, event hooks, replacement classes) and
     *patches* it (bytecode transforms or mixins applied at build or load
     time). Patches never write back to FC sources. The build-time ASM
     transforms in `Forge/build.gradle` (`widenFcHookDescriptors`,
     `openUpEntityAccess`, `extendFcPathingBuildRange`,
     `extendFcTreeBuildRange`) are its precursors and are expected to
     move into it, as are the load-time mixins on FC classes in
     `btw.forge.mixin.fcext` (#018).

   If FC behaviour must change, it changes through that extension/patch
   layer, never by editing FC files. Older port edits to FC code that
   predate this rule are listed in `BRIDGE_SPEC.md` (Key Principles).
   Nothing is added to them.

   **Tag port code, not FC code.** Integration code in the bridge or the
   subproject carries a tag so it stays auditable:
   Java: `// FCMOD-INTEGRATION(1.20.1): <feature>: <one-line why>`
   Mixins: full Javadoc block on the class (Conflict / Pattern / Rebalance / Rejected alternatives).
   The tag name is legacy. Its `FCMOD` prefix echoes FC's own markers in
   `vanilla/`, but `FCMOD-INTEGRATION` marks port code only, never FC
   code. Existing tags keep the name; there is no code rename.

2. **Gate modern affordances through FC infrastructure — don't delete them.**
   If vanilla gives you X for free, attach an FC cost to the channel,
   don't remove the channel.

3. **Prefer data-driven overrides over code changes** (loot tables,
   recipes, tags, gamerules, biome modifiers). Code changes only when
   data can't express the integration.

4. **Prefer Forge events over mixins** when you only need to observe/cancel.
   Mixin only for surgical bytecode changes where no event exists.

5. **Keep both items/blocks dual-sourced.** Never silently replace vanilla X
   with FC Y — let vanilla X still exist and rebalance it so FC's version
   is the natural choice. Preserves compat with other mods.

6. **Version-lock the assumption.** Every integration names `(1.20.1)` in
   its tag. A future port knows exactly what to re-examine.

7. **Test with "what if another mod depends on this"** before shipping.

8. **Integrate, don't disable.** (maintainer stance, 2026-10-10)
   In the maintainer's words: "What I don't want to do is the lazy thing,
   which is just disable stuff; you'd might as well load up BTW on 1.5.2
   if the 'modern port' just gets rid of all the modern features."
   Integrate BTW's features with modern Minecraft in fun and interesting
   ways. Disabling, capping or removing a modern feature is a last
   resort, and the entry must justify it. Every entry must say what BTW
   **adds** to the feature, not only how it is limited. See
   [Integration directions](#integration-directions-2026-10-10).

---

## Integration patterns

Choose one per feature. Integrations that combine patterns must justify
the combination.

| Pattern | Name | When to use |
|---------|------|-------------|
| **A** | Tier-align | Modern item is fine in concept, just belongs at FC's equivalent tier. |
| **B** | Gate | Require FC infrastructure / material to unlock or use. |
| **C** | Rebalance | Keep the feature; adjust numbers so it slots into FC's economy. |
| **D** | FC-extend | BTW variants built on the modern feature's framework, in the extension layer (FC code stays read-only). |
| **E** | Naturalize | Leave vanilla intact, buff FC's version so players prefer FC. |

Under Protocol #8, prefer **D** and **B** (gated by BTW progression). A
**C** entry that only subtracts needs a written justification.

---

## Integration log

### #001 — Mending enchantment

| Field | Value |
|-------|-------|
| **Modern feature** | Mending enchantment (MC 1.9+) |
| **Added** | 2016-02 / MC 1.9 |
| **Conflict** | Infinite durability trivialises FC's anvil / steel / soulforge repair loop |
| **Pattern** | **C** (rebalance, preserve feature) |
| **Implementation** | [`MendingCapMixin`](Forge/src/main/java/btw/forge/mixin/MendingCapMixin.java) |
| **Effect** | Each mending XP orb restores **½** the vanilla durability amount. XP cost is unchanged. |
| **Dual-sourced?** | Yes — the enchantment still appears in loot, trades, and enchanting table RNG. |
| **Data or code?** | Code (mixin). Data-driven override was not possible — Mending's repair amount is hard-coded in `ExperienceOrb.repairPlayerItems`. |
| **Alt rejected** | Gate mending-books behind soulforge. Rejected under Protocol #5 (would remove mending from vanilla enchanting RNG, breaks other mods). |
| **Verified by** | Refmap emits `m_147092_` (repairPlayerItems) + `m_41721_` (setDamageValue) mappings → production build applies correctly. |
| **Net effect on FC loop** | A mending pickaxe now takes 2× as many XP orbs to fully repair, so the anvil / soulforge chain is still the fastest maintenance route for heavy use. Mending remains a quality-of-life boon, not a tool-immortality switch. |
| **Under review (2026-10-10)** | Subtractive (repair halved). Direction: make Mending an earned enchantment through BTW's arcane-scroll / Infernal Enchanter framework (source mob to decide) instead of a weakened one. See [Integration directions](#integration-directions-2026-10-10), Enchanting. |

### #002 — Gamerule defaults

| Field | Value |
|-------|-------|
| **Modern feature** | Post-1.5.2 gamerules: `doInsomnia` (1.13), `doPatrolSpawning` (1.14), `doTraderSpawning` (1.14), `doWardenSpawning` (1.19), `keepInventory` (1.4) |
| **Added** | MC 1.4 – 1.19 |
| **Conflict** | Phantoms double-punish FC's sleep cost; pillager patrols and wandering traders drop loot / sell items that shortcut FC progression; warden is irrelevant to FC design; keepInventory removes FC's load-bearing death penalty |
| **Pattern** | **C** (rebalance via data — set FC-appropriate defaults, preserve user override) |
| **Implementation** | [`WorldGamerulesHandler`](Forge/src/main/java/btw/forge/WorldGamerulesHandler.java) |
| **Effect** | New worlds get `doInsomnia=false`, `doPatrolSpawning=false`, `doTraderSpawning=false`, `doWardenSpawning=false`, `keepInventory=false`. Existing worlds are untouched. Users can override via `/gamerule`. |
| **Dual-sourced?** | Yes — gamerules are vanilla-native and fully reversible |
| **Data or code?** | Event handler (Forge `LevelEvent.CreateSpawnPosition`). No mixin, no bytecode. |
| **Alt rejected** | `ServerAboutToStartEvent` (fires every server start — would override user preferences on restart, violates Protocol #2). |
| **Verified by** | New world → `/gamerule doInsomnia` reports `false`. Existing world → user-set values unchanged. |
| **Net effect on FC loop** | Removes phantom/patrol/trader/warden distractions from FC's early game. Death penalty stays intact by default. |
| **Under review (2026-10-10)** | Subtractive (four features switched off). Direction: replace the off-switches with BTW progression gates (FC already tracks Nether, Wither and End progress); phantoms hunt in the dark, tied to BTW gloom; the warden guards the richest soul source. `keepInventory=false` is not subtractive and stays. See [Integration directions](#integration-directions-2026-10-10), first move 2. |

### #003 — Pattern-E batch (naturalized, no code)

| Field | Value |
|-------|-------|
| **Modern feature** | 21 post-1.5.2 additive features with no FC-design friction. |
| **Added** | MC 1.7 – 1.20 |
| **Conflict** | None that degrade the FC core loop. Each row is either purely cosmetic, a compatible mob variant, or a niche effect FC already tolerates. |
| **Pattern** | **E** (naturalize — allow feature, FC doesn't compete) |
| **Implementation** | None. Features ship vanilla-intact. |
| **Effect** | These rows are reviewed and batch-approved for unchanged behavior. They remain fully present in the game with vanilla mechanics. |
| **Dual-sourced?** | Yes — all items/mechanics remain vanilla-reversible via datapack removal. |
| **Data or code?** | Neither. Documentation entry only. |
| **Alt rejected** | Writing one integration entry per cosmetic row. Rejected: inflates the log with 21 rows that document "no change", drowning real integrations; batching preserves auditability (each row is listed below) while keeping the log scannable. |
| **Verified by** | Static review against Protocol #5 (don't silently replace vanilla). No runtime test — Pattern-E rows are definitionally no-op. |
| **Net effect on FC loop** | Zero. |
| **Batched rows** | Boss bars (1.10); Pufferfish poison teach (1.7); Curse of Vanishing (1.11); Curse of Binding (1.11); Loom (1.14); Cartography table (1.14); Fletching table (1.14); Polar bear (1.10); Husk/stray/drowned (1.10/1.13); Frog/tadpole (1.19); Cat (1.14); Fox (1.14); Panda (1.14); Glowing effect (1.9); Luck / Bad Luck effects (1.9); Levitation effect (1.9); Music discs (1.10+); Banner patterns (1.8); Armor trims (1.20); Hanging signs (1.20); Decorated pots (1.20). |
| **Under review (2026-10-10)** | Contradiction: Wave 2 (W2)'s fix for triage SW5 replaces drowned, husks and strays (plus glow squid) with base FC mobs, which contradicts the *Husk/stray/drowned* row above. Wither skeletons and existing zombie villagers keep their 1.5.2 identities (FC's type-1 wither skeleton and FC's villager zombie); only zombie villagers from new natural, worldgen or spawner spawns become plain FC zombies, matching BTW 4.B, which never spawned villager zombies. Direction: rework the collapse into FC variants that keep the modern identities. Other rows here now have integration directions: Fletching table (BTW arrow bench), Decorated pots (turntable + kiln pottery chain), Cat (scares FC creepers). See [Integration directions](#integration-directions-2026-10-10), Mobs, Workstations and Materials. |

### #004 — Loot-table nerfs (data-driven)

| Field | Value |
|-------|-------|
| **Modern feature** | Post-1.9 loot tables that drop progression-skipping items — bastion chests, ancient city chests, pillager outpost chests, woodland mansion chests, trail-ruins archaeology |
| **Added** | MC 1.9 – 1.20 |
| **Conflict** | Free high-tier loot (ancient debris, diamond gear, enchanted books, iron pickaxes, music discs) bypasses FC's mining/smithing/enchant chains. Players who find a bastion can skip entire tiers of progression |
| **Pattern** | **C** (rebalance via data — keep feature, subtract the progression-skipping items) |
| **Implementation** | Global Loot Modifiers registered via [`BTWLootModifiers`](Forge/src/main/java/btw/forge/data/BTWLootModifiers.java) + [`FCRemoveItemFromLootModifier`](Forge/src/main/java/btw/forge/data/FCRemoveItemFromLootModifier.java). JSON instances emitted by [`FCLootOverrides`](Forge/src/main/java/btw/forge/data/FCLootOverrides.java) during `runData` |
| **Effect** | The following items are stripped from their generating loot tables: ancient debris (all 4 bastion chest tiers); diamond leggings + diamond horse armor (ancient city); iron pickaxe + enchanted books (pillager outpost); diamond chestplate (woodland mansion); Relic music disc (trail-ruins brushing). **10 Global Loot Modifiers total.** |
| **Dual-sourced?** | Yes — vanilla loot tables are untouched; GLMs are an additive layer that server ops can disable via pack priority |
| **Data or code?** | Both — one custom `FCRemoveItemFromLootModifier` codec class (~60 lines) + JSON GLM instances generated via data-gen |
| **Alt rejected** | Full loot-table replacement JSON. Rejected because (a) brittle — vanilla loot tables change between MC patches, we'd have to re-sync; (b) breaks compat with other mods that also modify those tables via GLMs; (c) more JSON per table |
| **Verified by** | `gradlew :Forge:runData` emits 10 JSON files under `src/generated/resources/data/betterthanwolves/loot_modifiers/` plus `global_loot_modifiers.json` index. In-world: open a bastion treasure chest, confirm ancient debris absent from drops |
| **Net effect on FC loop** | Structure exploration stays valuable (players still find emeralds, books, food, situational items), but no structure is a "skip to tier N" shortcut. Netherite progression requires netherite-tier mining (still possible; just not chest loot) |
| **Under review (2026-10-10)** | Subtractive (items stripped, nothing added). Direction: structures and archaeology carry BTW lore items (arcane scrolls, rare seeds) so exploration feeds BTW progression. See [Integration directions](#integration-directions-2026-10-10), Materials. |

### #005 — Stonecutter slab-output rebalance (data-driven)

| Field | Value |
|-------|-------|
| **Modern feature** | Stonecutter (MC 1.14) as a 2× efficiency multiplier over crafting table for slab recipes |
| **Added** | MC 1.14 |
| **Conflict** | Stonecutter turns 1 stone → 2 stone slabs, while the crafting table gives 6 slabs from 3 stone (2 per stone). Net: stonecutter matches crafting-table efficiency *and* lets the player skip all shape-swap intermediate crafts. In FC's economy, where stone is a foundational tier the player earns through mining with a wood/stone pickaxe, the shortcut erodes the early-game mining loop |
| **Pattern** | **C** (rebalance via data — halve stonecutter slab output to 1, matching its output pattern for stairs/walls/etc.) |
| **Implementation** | [`FCRecipeOverrides`](Forge/src/main/java/btw/forge/data/FCRecipeOverrides.java) — 30 stonecutter recipes regenerated at their vanilla resource locations with `count=1` |
| **Effect** | Stonecutter-produced slabs across stone, cobblestone, mossy, smooth, bricks, granite, diorite, andesite, sandstone (regular/smooth/cut/red), prismarine family, nether bricks, end stone bricks, blackstone family, deepslate family, and mud bricks: output count halved from 2 → 1 |
| **Dual-sourced?** | Yes — players can still craft slabs via the 3-stone → 6-slab crafting-table recipe (vanilla, untouched). Stonecutter remains functional, just no longer a strict improvement |
| **Data or code?** | Pure data (generated JSON at `data/minecraft/recipes/*_stonecutting.json` override paths) |
| **Alt rejected** | Remove stonecutter slab recipes entirely. Rejected under Protocol #5 — removes a vanilla affordance rather than rebalancing it; would also break compat with mods that expect stonecutter-to-slab paths |
| **Verified by** | `gradlew :Forge:runData` emits 30 JSON files under `src/generated/resources/data/minecraft/recipes/` (committed; the Forge jar task fails if the generated dir is missing). In-world: load recipe book → stonecutter with 1 stone shows `1 stone_slab` output (was `2`) |
| **Net effect on FC loop** | Early-game stone economy retains its weight. Stonecutter is still the "right tool" for non-slab shapes (stairs, walls, cut variants) since those were already 1:1 |
| **Under review (2026-10-10)** | Subtractive (output halved). Direction: the stonecutter as a mechanically powered stone saw that outputs BTW shapes. See [Integration directions](#integration-directions-2026-10-10), Workstations. |

### #006 — Frost Walker radius cap

| Field | Value |
|-------|-------|
| **Modern feature** | Frost Walker enchantment (MC 1.9+) — freezes water blocks in a radius around the wearer while walking |
| **Added** | MC 1.9 |
| **Conflict** | Vanilla level-1 Frost Walker freezes a 5×5 area (radius 2) under the player, turning any body of water into walkable terrain. FC worldgen tunes water crossings as deliberate traversal obstacles (swim exhaustion, water slowdown, limited ice-breaking). Free water crossings defeat that design |
| **Pattern** | **C** (rebalance, preserve feature) |
| **Implementation** | [`FrostWalkerRadiusMixin`](Forge/src/main/java/btw/forge/mixin/FrostWalkerRadiusMixin.java) — `@ModifyConstant` on the `2` literal inside `onEntityMoved`, reducing base radius from `2 + level` to `0 + level` |
| **Effect** | Level 1 → 1×1 freeze (block under player only); Level 2 → 3×3 freeze (small puddle). Enchantment still rolls in loot and enchanting table |
| **Dual-sourced?** | Yes — enchantment remains in vanilla loot pools and enchanting RNG |
| **Data or code?** | Code (mixin) |
| **Alt rejected** | Remove Frost Walker from treasure-enchant pool. Rejected under Protocol #5 — removes rather than rebalances |
| **Verified by** | Refmap at `Forge/build/tmp/compileJava/betterthanwolves.refmap.json` emits `m_45018_` mapping for `onEntityMoved` → production build applies correctly |
| **Net effect on FC loop** | Water crossings remain deliberate traversal choices. Frost Walker is now a modest slip-protection perk rather than a free-bridge enchant |
| **Under review (2026-10-10)** | Subtractive (radius capped). Direction: Frost Walker comes from a stray arcane scroll, so it is a rare, earned top-tier enchantment rather than a nerfed one. See [Integration directions](#integration-directions-2026-10-10), Enchanting. |

### #007 — Respawn anchor single-use

| Field | Value |
|-------|-------|
| **Modern feature** | Respawn Anchor (MC 1.16+) — Nether block that stores up to 4 glowstone charges, each allowing one respawn at the anchor |
| **Added** | MC 1.16 |
| **Conflict** | FC's HC Spawn (integration `#002`) makes initial respawn location load-bearing for early-game progression. The respawn anchor is a portable Nether-side second spawn, stacking 4 deep — removes death consequence entirely for long Nether stretches |
| **Pattern** | **C** (rebalance, preserve feature) |
| **Implementation** | [`RespawnAnchorChargeMixin`](Forge/src/main/java/btw/forge/mixin/RespawnAnchorChargeMixin.java) — `@ModifyConstant` on the `4` literal inside `canBeCharged(BlockState)`, capping at 1 |
| **Effect** | Respawn anchor accepts exactly one charge. Using it to respawn consumes the charge and requires re-charging. Per `docs/audit/alchemy.md`, each charge costs `fcItemConcentratedHellfire` (gating follow-up, deferred) |
| **Dual-sourced?** | Yes — anchor block and recipe remain vanilla-reachable |
| **Data or code?** | Code (mixin) |
| **Alt rejected** | Delete respawn anchor recipe. Rejected under Protocol #5 — breaks player autonomy; also leaves dangling block in world if players find one in structure loot |
| **Verified by** | Refmap emits `m_55894_` mapping for `canBeCharged` → production build applies correctly |
| **Net effect on FC loop** | Anchor becomes a "one escape route" commitment rather than a routine safety net. HC Spawn stays meaningful |
| **Under review (2026-10-10)** | Subtractive (charges capped at 1). Direction: the anchor becomes the Nether's fixed spawn, charged with soul material instead of glowstone. See [Integration directions](#integration-directions-2026-10-10), Death and hardcore spawn. |

### #008 — Swift Sneak level cap

| Field | Value |
|-------|-------|
| **Modern feature** | Swift Sneak enchantment (MC 1.19+) — legging-slot enchant that reduces the sneak-speed penalty; at level 3 sneak speed approaches full walking speed |
| **Added** | MC 1.19 |
| **Conflict** | FC's cave mining loop treats crouch as a deliberate speed trade-off for ledge safety. Swift Sneak III eliminates the trade entirely, making crouched mining strictly better than upright — collapses a moment-to-moment design decision FC expects the player to make |
| **Pattern** | **C** (rebalance, preserve feature) |
| **Implementation** | [`SwiftSneakLevelMixin`](Forge/src/main/java/btw/forge/mixin/SwiftSneakLevelMixin.java) — `@Inject(HEAD, cancellable)` on `getMaxLevel`, forces return value to `1` |
| **Effect** | Swift Sneak rolls only at level 1 via enchanting table and Forge enchantment RNG. Level 1 still provides a partial mitigation (useful for long mining) without eliminating the trade-off |
| **Dual-sourced?** | Yes — enchant remains available via all vanilla pathways |
| **Data or code?** | Code (mixin) |
| **Alt rejected** | Remove Swift Sneak from ancient-city loot. Rejected under Protocol #5 |
| **Known limitation** | Pre-generated ancient-city loot rolling Swift Sneak III books still functions at level 3 (level is NBT-stored and consulted at consumption). A consumption-time clamp via `EnchantmentHelper#getEnchantmentLevel` remains possible if playtest shows it matters |
| **Verified by** | Refmap emits `m_6586_` mapping for `getMaxLevel` → production build applies correctly |
| **Net effect on FC loop** | Crouch-vs-walk is a meaningful decision in FC mining again. Swift Sneak becomes "modest comfort boost" rather than "crouch-mode upgrade path" |
| **Under review (2026-10-10)** | Subtractive (level capped). Direction: Swift Sneak comes from a warden arcane scroll, a rare, earned top-tier enchantment. See [Integration directions](#integration-directions-2026-10-10), Enchanting. |

### #009 — Totem of Undying soul-flux gate

| Field | Value |
|-------|-------|
| **Modern feature** | Totem of Undying (MC 1.11+) — held totem activates on lethal damage, restoring 1 HP + Regen + Fire Resist + Absorption, consuming the totem |
| **Added** | MC 1.11 |
| **Conflict** | Free death-save short-circuits FC's HC Spawn penalty (integration `#002`). Evokers drop totems at 100% and can be farmed, which turns late-game FC into "accumulate totems, become immortal" |
| **Pattern** | **B** (gate via FC reagent — preserve feature, route through FC's alchemy chain) |
| **Implementation** | [`TotemOfUndyingMixin`](Forge/src/main/java/btw/forge/mixin/TotemOfUndyingMixin.java) — `@Inject(HEAD, cancellable)` on `LivingEntity#checkTotemDeathProtection(DamageSource)` |
| **Effect** | Totem still required in a hand (vanilla behavior), but also requires at least one `fcItemSoulFlux` (item ID 2555) in the player's inventory. On save, one soul flux is consumed alongside the totem. Without soul flux, totem doesn't save. Non-player entities unaffected. Damage sources that bypass invulnerability are passed through to vanilla unchanged (no soul flux consumed on unsavable deaths) |
| **Dual-sourced?** | Yes — totems remain in vanilla loot (evoker drops, woodland-mansion chests), and the vanilla activation mechanic is untouched when soul flux is present |
| **Data or code?** | Code (mixin) |
| **Alt rejected** | Remove totem from evoker drops. Rejected under Protocol #5 — loot-source removal breaks other mods and is heavier than an activation cost |
| **Alt rejected (2)** | `@Redirect` on `shrink(1)` inside vanilla's totem block. Rejected because if we skip the shrink, vanilla still returns `true` and applies save effects, causing the totem to save without being consumed — bug in the wrong direction |
| **Verified by** | Refmap emits `m_21262_` for `checkTotemDeathProtection`. In-game test: lethal damage with totem but no soul flux → player dies; with totem + soul flux → player saved, totem consumed, soul flux decremented |
| **Net effect on FC loop** | Totem saves become endgame commitments rather than routine escape hatches. HC Spawn retains its weight through late game |
| **Model entry (2026-10-10)** | The one true FC-infrastructure gate in the log: the feature stays whole and BTW's soul/alchemy chain gives it a cost. Later entries should follow this shape (Protocol #8). |

### #010 — Crossbow piercing cap

| Field | Value |
|-------|-------|
| **Modern feature** | Crossbow PIERCING enchantment (MC 1.14+) — each level allows projectiles to penetrate one additional entity |
| **Added** | MC 1.14 |
| **Conflict** | FC's ranged-combat armor-bypass mechanism is the `FCItemArrowBroadhead` item (designed-in). Piercing III+ fires through walls of mobs, turning FC spider swarms and zombie hordes into one-shot cleanups, collapsing FC's number-based encounter tuning |
| **Pattern** | **C** (rebalance, preserve feature) |
| **Implementation** | [`CrossbowPiercingMixin`](Forge/src/main/java/btw/forge/mixin/CrossbowPiercingMixin.java) — `@Redirect` on `AbstractArrow#setPierceLevel(byte)` inside `CrossbowItem#shootProjectile` |
| **Effect** | When a crossbow projectile is fired, the piercing level applied to the outgoing arrow is clamped to `min(1, enchantLevel)`. Enchantment still appears in loot and on NBT at any level; only the fire-time effect is capped |
| **Dual-sourced?** | Yes — Piercing stays in enchant pools; crossbows with level-4 piercing still exist in loot |
| **Data or code?** | Code (mixin) |
| **Alt rejected** | Overwrite `PiercingEnchantment.getMaxLevel()` to 1. Rejected because pre-generated loot already has level-3/4 crossbows; level is stored in NBT and consulted at fire time regardless of max-level API. Fire-time clamp is more robust |
| **Verified by** | Refmap emits `m_40894_` for `shootProjectile` (10-arg method matching CrossbowItem's static shoot). In-game: Piercing V crossbow fires → hit first zombie in a line, second zombie unaffected |
| **Net effect on FC loop** | Crossbow remains viable ranged weapon, but mobs-in-a-line no longer cascade-die. Spider/zombie swarms retain their intended threat |
| **Under review (2026-10-10)** | Subtractive (fire-time cap). Direction: Piercing, Multishot and Quick Charge come from pillager arcane scrolls, rare and earned. See [Integration directions](#integration-directions-2026-10-10), Enchanting. |

### #011 — Sweeping Edge suppression

| Field | Value |
|-------|-------|
| **Modern feature** | Sweeping Edge enchantment (MC 1.11+) — sword enchant that inflicts reduced AoE damage on entities near the primary target |
| **Added** | MC 1.11 |
| **Conflict** | FC combat is per-target-hit — each swing costs exhaustion, and damage is reconciled individually. AoE melee breaks that accounting: a single swing can kill multiple mobs without paying the per-mob exhaustion cost. FC's spider swarms and husk clusters are tuned as numerically-threatening encounters, not as loot-bundles |
| **Pattern** | **C** (rebalance, preserve feature) |
| **Implementation** | [`SweepingEdgeMixin`](Forge/src/main/java/btw/forge/mixin/SweepingEdgeMixin.java) — `@Redirect` on `Level#getEntitiesOfClass(Class, AABB)` inside `Player#attack`, returning empty list |
| **Effect** | Sweeping animation, sound, and particle still fire — visible feedback preserved. But the peripheral-entity list used for sweep damage is empty, so no secondary entity takes damage. Sweeping Edge is effectively cosmetic |
| **Dual-sourced?** | Yes — enchant still rolls at all levels, still applies to swords, still displays in item tooltip |
| **Data or code?** | Code (mixin) |
| **Alt rejected** | `@Redirect` on `EnchantmentHelper.getSweepingDamageRatio` returning 0. Rejected because vanilla formula is `sweepMul = 1.0f + ratio × damage`; ratio=0 still yields sweepMul=1.0, so peripheral entities take 1 damage per sweep. Zero-target is cleaner |
| **Alt rejected (2)** | Overwrite `Player#attack`'s sweep block. Rejected — high mixin-conflict risk with mods injecting into attack for stat/damage reasons |
| **Verified by** | Refmap emits `m_5706_` for `attack` + `m_45976_` for `getEntitiesOfClass`. In-game: swing soulforged-steel sword at a sheep inside a 3-sheep clump → only the directly-targeted sheep takes damage |
| **Net effect on FC loop** | Per-target exhaustion cost is enforced. Crowd control via AoE is no longer a free bypass of FC's swing economy |
| **Under review (2026-10-10)** | Subtractive (enchantment made cosmetic). Direction: move Sweeping Edge onto an arcane scroll (source mob to decide) so a working sweep is a rare, earned top-tier enchantment. See [Integration directions](#integration-directions-2026-10-10), Enchanting. |

### #012 — doFireTick governs BTW fire and lava

| Field | Value |
|-------|-------|
| **Modern feature** | `/gamerule doFireTick`. 1.20.1 applies it to fire spread and burn-out (`FireBlock.tick`, FireBlock.java:129-130) and to lava ignition (`LavaFluid.randomTick`, LavaFluid.java:61) |
| **Added** | MC 1.4 (the gamerule); lava obeys it in 1.20.1 |
| **Conflict** | Since the tick-ownership change (`STUB_CATALOGUE.md` 2026-10-14 (as)), FC owns the FIRE scheduled tick and lava's random tick. FC's bodies never read the rule: 1.5.2 `BlockFire.updateTick` did (vanilla/server BlockFire.java:142), but FCMOD replaced it with `FCBlockFire.updateTick`, and 1.5.2 lava never read it. Left alone, `doFireTick false` would stop only unmapped soul fire, and servers that rely on it (spawn protection, build worlds) would get BTW fire spread and lava ignition anyway |
| **Pattern** | **C** (rebalance: keep the gamerule and apply it to BTW's fire as 1.20.1 applies it to its own). Nothing is subtracted by default: the rule is an admin opt-in, and its default (true) is unchanged 4.B. Maintainer decision D1 |
| **Implementation** | [`TickPolicy`](Forge/src/main/java/btw/forge/TickPolicy.java): `HONOUR_DO_FIRE_TICK = true`, `fireTickSuppressed`, `rescheduleSuppressedFire`, applied in `runRandom` / `runScheduled` (reached from [`BlockStateRandomTickMixin`](Forge/src/main/java/btw/forge/mixin/BlockStateRandomTickMixin.java) and [`BlockStateBaseTickMixin`](Forge/src/main/java/btw/forge/mixin/BlockStateBaseTickMixin.java)) and in [`ProxyBlock`](Forge/src/main/java/btw/forge/ProxyBlock.java) `tick` / `randomTick` |
| **Effect** | With `doFireTick false`: a scheduled tick of any FC fire (`btw.modern.BlockFire`: fire 51, stoked fire 251, ...) only reschedules itself at `tickRate + nextInt(10)`, so the fire stays lit without spreading, burning blocks or burning out, as 1.20.1's fire does; FC fire and FC lava random ticks are skipped, so lava starts no fire and does not light or melt what it touches (campfires, BTW snow). With `true`: exactly 4.B |
| **BTW adds** | BTW's own fire (smouldering logs, stoked fire, the hibachi, lava ignition of BTW blocks) answers the same admin switch as vanilla fire |
| **Dual-sourced?** | Yes — the gamerule stays vanilla-native and reversible; unmapped soul fire keeps 1.20.1's own check |
| **Data or code?** | Code: one constant and one check at dispatch time. Never in the random-tick gate, which must not read world state (`BRIDGE_SPEC.md`, Tick ownership) |
| **Alt rejected** | (b) 4.B parity: FC fire and lava ignore the rule. Rejected under Protocol #8: with FC owning the FIRE tick, it would silently switch off a modern admin control for every fire except soul fire |
| **Alt rejected (2)** | (c) Honour it for spread and ignition but keep lava's contact effects (melting BTW snow, lighting campfires). That needs FCBlockLavaStationary's body split in two outside FC code (Protocol #1): more port code for a corner case. Also rejected: reading the rule in the cached random-tick gate, which would drift chunk-section tick counts when the rule changes |
| **Known limitation** | The burning ladder (`FCBlockLadderOnFire`, 1052, whose own updateTick calls `FCBlockFire.CheckForFireSpreadFromLocation`) and the campfire tile entity (`FCTileEntityCampfire`) still spread fire with the rule off; the fire they start obeys it |
| **Verified by** | Compile, and the (as) runtime proofs with `doFireTick true`, where every fire and lava proof matched 4.B. `doFireTick false` has not been run yet. Proof to add: with the rule false, a fire beside wicker stays lit and the wicker survives 600 ticks, and lava beside wicker starts no fire |
| **Net effect on FC loop** | None by default. Admins get one switch for all fire, BTW's included |

### #013 — Modern soils and plant survival

| Field | Value |
|-------|-------|
| **Modern feature** | Soils added after 1.5.2 and the plants 1.20.1 world generation puts on them: ferns, flowers, saplings and mushrooms on podzol, moss, mud, rooted and coarse dirt; grass on moss in dark lush caves; mushrooms on podzol and nylium in full light; cactus and cane on red sand; dead bushes on red sand and terracotta |
| **Added** | MC 1.6 – 1.19 (terracotta 1.6, podzol and red sand 1.7, coarse dirt 1.8, nylium 1.16, moss and rooted dirt 1.17, mud 1.19) |
| **Conflict** | Since the tick-ownership change, FC's plant rules run on vanilla-id plants (random ticks and neighbour changes, through `BlockFlower.checkFlowerChange`), and FC sees these soils as `ModernBlockStandIn`s with no soil hooks, so the plants on them would pop: FC's light rule (light 8 or sky) removed grass on moss in dark caves, and FCBlockMushroom's (light below 13) mushrooms on podzol and nylium. The other way round, 1.20.1's survival executors (BushBlock, CactusBlock, SugarCaneBlock, FarmBlock), which stay 1.20.1-owned, applied 1.20.1 soil rules to FC soils and destroyed cactus on planters, cane on loose dirt and crops on BTW farmland |
| **Pattern** | **D** (FC-extend): modern soils get FC soil behaviour derived from their 1.20.1 tags, and FC soils get FC's rules inside 1.20.1's executors. Maintainer decision D3 |
| **Implementation** | [`ModernBlockStandIn`](Forge/src/main/java/btw/forge/ModernBlockStandIn.java) soil hooks from the live state's tags: `CanWildVegetationGrowOnBlock` / `CanSaplingsGrowOnBlock` on `DIRT`, `CanReedsGrowOnBlock` on `DIRT` or `SAND`, `CanCactusGrowOnBlock` on `SAND`; crops, nether wart and hydration stay FC-only. [`WorldBridge`](Forge/src/main/java/btw/forge/WorldBridge.java)`.GetModernSoilPlantSurvival`, which overrides the Modern-Common `World` hook that `checkFlowerChange` asks first: on a soil with no 1.5.2 identity the live 1.20.1 `canSurvive` decides. [`PlantSoilMixin`](Forge/src/main/java/btw/forge/mixin/PlantSoilMixin.java) on Forge's `Block.canSustainPlant` (`remap = false`): on an FC soil, a 1.5.2 plant gets FC's soil clause (cactus, cane and lily pad have their own), and a modern-only plant gains the FC soils its kind accepts. `TickPolicy` keeps `DEAD_BUSH` off the FC random tick |
| **Effect** | 1.20.1 world content on modern soils survives FC's rules; planters, loose dirt and BTW farmland hold their plants in 1.20.1's survival checks |
| **BTW adds** | BTW's soils work for modern plants too: modern saplings, flowers and crops (beetroot) can be planted and stay on the BTW soils FC's hook for their kind accepts |
| **Dual-sourced?** | Yes — 1.20.1's own rules still decide on modern soils and for modern plants on modern soils |
| **Data or code?** | Code (one mixin and two port hooks) |
| **Alt rejected** | (b) Strict 4.B rules: such plants disappear as they are random-ticked or their neighbours change, deleting 1.20.1 world generation content (Protocol #8). (c) Remap red sand to sand id 12, which would also change red sand's falling and crafting behaviour |
| **Alt rejected (2)** | Mixins on each plant's `canSurvive` (five or more targets, and modded plants that ask `canSustainPlant` would still see 1.20.1 rules on FC soils). Replacing the 1.20.1 answer for modern-only plants as well: Forge types every unlisted plantable PLAINS, so seagrass on sand, gravel or clay, wither roses on netherrack and flowers on vanilla farmland would pop |
| **Known limitation** | Dead bushes on grass blocks or dirt, which are FC soils (1.20.1 places some in swamps, mangrove swamps and giant taigas), are removed on a neighbour change: `FCBlockDeadBush` accepts only sand 12, both in FC's neighbour rule and through PlantSoilMixin (review R7; needs a D3 follow-up). Dead bushes on red sand and terracotta stay. Lily pads on ice are refused, as in 4.B. Waterlogged blocks are not water to FC, so cane and BTW farmland that rely on them fail (review R2) |
| **Verified by** | (as) proof S3: a cactus on planter soil, cane on loose dirt and carrots on BTW farmland, 6/6 survive; run 3: grass (31) on moss in a dark box, 3/3 survive 2400 ticks at randomTickSpeed 300. PlantSoilMixin's descriptor was checked with javap (no refmap entry, `remap = false`). Not runtime-tested: mushrooms on podzol or nylium in full light |
| **Net effect on FC loop** | BTW's plant rules apply in full on 1.5.2 soils; modern biomes keep their vegetation |

### #014 — Fire flammability for modern and BTW blocks

| Field | Value |
|-------|-------|
| **Modern feature** | Flammable blocks added after 1.5.2 (acacia, dark oak, mangrove, cherry and bamboo wood, hay bales, carpets, scaffolding, beehives, ...), the magma block as a fire source, and 1.20.1's fire survival and placement checks (`FireBlock.canSurvive` / `updateShape`, `BaseFireBlock.onPlace`), which read Forge's `getFlammability` / `getFireSpreadSpeed`, backed by FireBlock's own table |
| **Added** | MC 1.7 – 1.20 (magma block 1.10) |
| **Conflict** | Since the tick-ownership change, `FCBlockFire` owns the FIRE tick and reads only FC's tables (`BlockFire.abilityToCatchFire` / `chanceToEncourageFire`, by legacy id), and FC lava ignites only beside a material that can burn. Modern-only blocks read as plain, fireproof stand-ins, so modern wood would stop burning; magma read as a plain stand-in, so fire on magma burned out (FC's eternal fire knows netherrack, End bedrock and `DoesInfiniteBurnToFacing`). The 1.20.1 checks that still run beside FC's fire read FireBlock's table, where every FC-only flammable block (wicker 1082, sidings, mouldings, baskets, hemp) is 0, so fire that FC spread beside them in mid-air was removed at once |
| **Pattern** | **D** (FC-extend): modern blocks enter BTW's fire tables with their 1.20.1 odds, and FC-mapped blocks answer 1.20.1's checks from BTW's tables |
| **Implementation** | [`FireBlockMixin`](Forge/src/main/java/btw/forge/mixin/FireBlockMixin.java): `getBurnOdds` / `getIgniteOdds` (`m_221164_` / `m_221166_`) HEAD answer FC's tables for every FC-mapped block; waterlogged and unmapped states keep 1.20.1's. [`ModernBlockStandIn`](Forge/src/main/java/btw/forge/ModernBlockStandIn.java): a colliding modern-only state with 1.20.1 fire odds (`getFireSpreadSpeed` / `getFlammability`, so modded overrides count) reads as a `btwModernFlammable<n>` stand-in with `SetFireProperties(ignite, burn)` and a burnable rock material; `DoesInfiniteBurnToFacing` answers the live state's `isFireSource` for the up face, as 1.20.1 asks only the block below a fire |
| **Effect** | Modern wood, hay and carpets burn in BTW's fire at their 1.20.1 odds and catch fire from BTW lava; fire on magma (and on datapack additions to the infiniburn tags) burns forever; BTW's flammable blocks keep fire alive beside them. Mapped vanilla blocks without 4.B fire properties (flowers 37 / 38) are no fuel, as in 4.B. Huge mushrooms from 1.20.1 world generation (legacy caps 99 / 100, fireproof in FC) stay fireproof, like 4.B's unconverted caps |
| **BTW adds** | BTW's fire spread (and BTW lava ignition) now reaches every post-1.5.2 flammable block |
| **Dual-sourced?** | Yes — FireBlock's table is untouched for unmapped blocks, and 1.20.1 fire (soul fire) keeps it |
| **Data or code?** | Code (one mixin and stand-in behaviour) |
| **Alt rejected** | Overriding `getFlammability` / `getFireSpreadSpeed` / `isFlammable` on ProxyBlock only: covers FC-only blocks but not the vanilla ids FC maps, which would keep 1.20.1 values FC's own fire never uses. Leaving modern blocks as fireproof stand-ins: disables fire on every post-1.5.2 wood (Protocol #8) |
| **Alt rejected (2)** | A rock-material stand-in carrying only fire properties: FC lava reads `Material.getCanBurn` alone, so modern wood beside lava would stop catching fire. Reading magma as netherrack: it would change every other FC read of magma (hardness, drops, pistons) |
| **Known limitation** | About 13 colliding fire-odds buckets compete for about 9 free legacy ids (159-174, minus mapped ids); later buckets reuse the nearest stand-in of their shape, so their odds are approximate; the ids are per session and never written. Since 2026-10-15 the plant twins (#018) take five of those ids, so four flammable stand-ins remain. Blocks 1.20.1 lava ignites only through `ignitedByLava` (barrel, loom, cartography and smithing tables) read as non-burnable to FC lava (review R9). `IForgeBlock.isFlammable` also accepts any `ignitedByLava` state, so mapped blocks FC calls non-flammable (wooden doors) keep 1.20.1 fire alive in its survival check until FC's own tick removes it |
| **Verified by** | Refmap emits `m_221164_` / `m_221166_`. (as) proofs: F-B fire beside wicker, 0/4 wicker left; F-C planks 0/4; R1-LAVA wicker 0/4; run 3: fire on magma still burning at 600 and 2400 ticks. Not runtime-tested: modern wood beside lava (proof to add: acacia planks beside lava catch fire) |
| **Net effect on FC loop** | Fire behaves as in BTW for every block, old or new |

### #015 — Farmland reverts to loose dirt

| Field | Value |
|-------|-------|
| **Modern feature** | 1.20.1's farmland reversion paths: `FarmBlock.turnToDirt`, called by trampling (`FarmBlock.fallOn`) and by the 1-tick survival check `FarmBlock.updateShape` schedules when a solid block sits above. It places vanilla dirt |
| **Added** | Farmland predates 1.5.2; these executors are 1.20.1's |
| **Conflict** | 4.B reverts farmland to FC loose dirt (1011) on trampling (Server FCBlockFarmlandBase.java:80-89), under a solid block (Common FCBlockFarmlandLegacyBase.java:29-51) and when it dries out (Server FCBlockFarmlandBase.java:270-276). FC's neighbour rule won only when a neighbour update arrived before the queued 1.20.1 tick; shape-only updates and the 1.20.1 trample left vanilla dirt, which a BTW world never produces |
| **Pattern** | **D** (closest; bridge correctness): 1.20.1 keeps deciding when farmland reverts, FC decides what it reverts to |
| **Implementation** | [`FarmBlockMixin`](Forge/src/main/java/btw/forge/mixin/FarmBlockMixin.java): static HEAD inject on `turnToDirt` (`m_269406_`), for `Blocks.FARMLAND` on the server only, places `ProxyRegistry.getModernBlock(1011)`'s default state through 1.20.1's own sequence (`Block.pushEntitiesUp`, `setBlockAndUpdate`, `GameEvent.BLOCK_CHANGE`) |
| **Effect** | Every farmland reversion yields BTW loose dirt; entities, neighbours and sculk sensors see the change as before. Dirt paths and the client keep vanilla dirt |
| **BTW adds** | Trampled and covered farmland feeds BTW's loose-dirt cycle (loose dirt regrows grass the BTW way, `STUB_CATALOGUE.md` (as) proof R1-G1) |
| **Dual-sourced?** | Yes — the 1.20.1 executors and their timing are untouched; dirt paths keep vanilla dirt |
| **Data or code?** | Code (mixin) |
| **Alt rejected** | Routing FarmBlock's scheduled tick to FC: `FCBlockFarmlandBase.updateTick` is the random hydration tick (hydrate, dry one step, revert when dry), so a survival tick under a solid block would run a hydration step and revert only farmland that was already dry, and the trample path would still place vanilla dirt |
| **Alt rejected (2)** | Separate mixins on `fallOn` and `tick`: two targets for one result, where `turnToDirt` is the single place both end |
| **Known limitation** | The trample odds are above 4.B's: 1.20.1 `FarmBlock.fallOn` (threshold 0.5) and FC `onFallenUpon` (0.75) both run. Both now produce loose dirt; the double trample is a separate open finding |
| **Verified by** | Refmap emits `m_269406_`. (as) proof S5: a solid block above farmland gives `betterthanwolves:block_1011`; R1-F1 (FC's own dry reversion): 81 loose dirt, 0 vanilla dirt, 0 farmland |
| **Net effect on FC loop** | Farmland never produces vanilla dirt, as in 4.B |

### #016 — Random-tick ordering with other mods (Serene Seasons)

| Field | Value |
|-------|-------|
| **Modern feature** | Other mods' control of crop growth on Forge 1.20.1: `BlockEvent.CropGrowEvent` (Pre / Post, fired by the 1.20.1 crop `randomTick` bodies through `ForgeHooks.onCropsGrowPre` / `Post`) and mixins that veto `BlockStateBase.randomTick` at HEAD. Serene Seasons 9.1+ uses the mixin (`MixinBlockStateBase` -> `SeasonalCropGrowthHandler`); 9.0.x uses only the event |
| **Added** | Forge (the event); Serene Seasons for 1.20.1 |
| **Conflict** | Since the tick-ownership change, BTW cancels the 1.20.1 random-tick body of FC-owned plants and runs FC's growth instead. (1) Those 1.20.1 bodies are where Forge fires CropGrowEvent, so FC growth fired no event, and event-only mods lost control of FC-mapped crops. (2) Callbacks at one HEAD run in mixin application order, which at equal priority is mod load order; when BTW's callback ran first it cancelled the tick and the seasonal veto never ran for FC wheat, carrots, potatoes, stems, cocoa, wart, cane and cactus |
| **Pattern** | **D** (FC-extend): BTW's growth runs inside Forge's crop-growth hooks, so modern mods that gate growth keep gating it |
| **Implementation** | [`BlockStateRandomTickMixin`](Forge/src/main/java/btw/forge/mixin/BlockStateRandomTickMixin.java): the random-tick dispatch is its own mixin at priority 1100, applied after every default-priority (1000) mixin, so its HEAD callback runs after theirs and a canceller such as Serene Seasons vetoes first. [`TickPolicy`](Forge/src/main/java/btw/forge/TickPolicy.java)`.runRandom` fires `onCropsGrowPre(level, pos, state, true)` before the FC body (DENY skips it) and `onCropsGrowPost` when the state changed and is not air, for the classes whose 1.20.1 body fires them (CropBlock, StemBlock, CactusBlock, SugarCaneBlock, NetherWartBlock, CocoaBlock) |
| **Effect** | Without such mods nothing changes. With them, seasons and other growth gates apply to BTW's crop growth as they did to 1.20.1's, and growth observers see Post. FC's tick is growth and the plant's upkeep in one, so a DENY skips that whole tick |
| **BTW adds** | BTW's slower growth composes with seasons: a mod's veto applies on top of BTW's rates instead of being bypassed |
| **Dual-sourced?** | Yes — the event and other mods' mixins are untouched; BTW only takes part in them |
| **Data or code?** | Code (mixin priority and two event calls) |
| **Alt rejected** | Keeping the random-tick dispatch in `BlockStateBaseTickMixin` at default priority (order left to mod load order), or raising that whole mixin's priority, which would also move its initCache redirect: the cached random-tick gate must not depend on other mods' initCache changes |
| **Alt rejected (2)** | Firing the event alone, which misses the 9.1+ mixin veto; a Serene Seasons-specific compat hook, which needs a soft dependency and helps one mod only |
| **Verified by** | Mixin 0.8.5 ordering checked in the source (`MixinInfo.compareTo`; the PREINJECT pass resolves HEAD before any INJECT); Serene Seasons 9.0.0.46's event-only gating checked with javap; refmap emits `m_222972_`. Not runtime-tested with Serene Seasons installed |
| **Net effect on FC loop** | None in plain BTW; packs with seasonal mods keep their seasons on BTW crops |

### #017 — Bone meal is BTW fertilizer

| Field | Value |
|-------|-------|
| **Modern feature** | Bone meal used on a block: `BoneMealItem.useOn` -> `applyBonemeal` -> `BonemealableBlock.performBonemeal` (instant growth: crops +2..5 ages, saplings to trees, mushrooms to huge mushrooms, grass and flowers on the grass block, nylium on netherrack), else `growWaterPlant` (seagrass, coral), with levelEvent 1505 (particles and sound). Also from the off hand, the vanilla dispenser (Bootstrap's BONE_MEAL behaviour), mods (`BonemealEvent`, `growCrop`) and direct `performBonemeal` callers |
| **Added** | Bone meal predates 1.5.2; these executors, the off hand (1.9) and `BonemealableBlock` are 1.20.1's |
| **Maintainer ruling** | 2026-10-15: "Bone meal behaves exactly as in 4.B: it fertilises the soil via FC, and never grows plants instantly the 1.20.1 way." Settles decision D2, deferred in `STUB_CATALOGUE.md` (as) |
| **Conflict** | In 4.B bone meal never grows a plant. `FCItemDye.onItemUse` applies the clicked block's `AttemptToApplyFertilizerTo` and uses the item exactly when that is true (SrcServer FCItemDye.java:16-30, 71-82); plants relay it to their soil (FCBlockPlants), and fertilized FC farmland and planters double crop growth until the next full stage. Vanilla's growth code is dead in 4.B, and FC emptied the dye dispenser behaviour. The port's Modern-Common `BlockFlower` lacked FCBlockPlants' relay, so FC declined for every 1.5.2 flower-family plant and 1.20.1 took over: at 9c05019 bone meal grew carrots from age 0 to 2 and 0 to 4, a stem to age 4, cocoa, beetroot, berries and saplings, and grew a huge mushroom, while the soil stayed unfertilized. The off hand always went to 1.20.1; FCItemDye ran before block activation (so weeds were not pulled first) and before Forge's `RightClickBlock` and the spectator check |
| **Pattern** | **D** (FC-extend): bone meal keeps its 1.20.1 item, use path and dispenser registry; on every block whose growth BTW governs, its effect is FC's fertilizer rule. Rebalance: none, 4.B parity |
| **Implementation** | [`BoneMealPolicy`](Forge/src/main/java/btw/forge/BoneMealPolicy.java) (routes FC_TARGET / SOIL_RELAY / VANILLA, the `BonemealEvent` listener, the dispenser registration), [`BoneMealItemMixin`](Forge/src/main/java/btw/forge/mixin/BoneMealItemMixin.java) (`useOn` `m_6225_` HEAD), [`BonemealableGateMixin`](Forge/src/main/java/btw/forge/mixin/BonemealableGateMixin.java) (`isValidBonemealTarget` `m_7370_` / `performBonemeal` `m_214148_` HEAD on the 31 1.20.1 classes that declare them), the early return in [`ServerPlayerGameModeMixin`](Forge/src/main/java/btw/forge/mixin/ServerPlayerGameModeMixin.java), [`BoneMealPrediction`](Forge/src/main/java/btw/forge/client/BoneMealPrediction.java) (client swing), Modern-Common [`BlockFlower`](Modern-Common/src/main/java/btw/modern/BlockFlower.java) (FCBlockPlants' relay and weed pull) and [`Item`](Modern-Common/src/main/java/btw/modern/Item.java) (the 1.5.2 block-dispenser default). Hooks: `BRIDGE_SPEC.md`, Bone meal (BoneMealPolicy) |
| **Effect** | On an FC-mapped block, or a post-1.5.2 plant BTW governs (#018), bone meal applies FC fertilizer (a plant relays it to its soil), is used up only when FC took it, and otherwise does nothing and is kept; no particles or sound. Not sneaking, the block is activated first (a weedy plant's weeds are pulled, the bone meal kept), after `RightClickBlock` and the spectator check, as 4.B's `activateBlockOrUseItem` order. Only `minecraft:bone_meal` is fertilizer. The vanilla dispenser ejects bone meal; the FC block dispenser spits out every item without its own block-dispenser use |
| **BTW adds** | Bone meal feeds BTW's fertilizer loop everywhere: from either hand, from both dispensers (an ejected item fertilizes the FC soil it lands on) and for post-1.5.2 crops on FC soil, which read FC's fertilizer through their twins (#018). Claim mods and seasonal vetoes now see bone meal's right-click |
| **Dual-sourced?** | Yes — blocks BTW does not govern keep 1.20.1 bone meal (moss, nylium, pink petals, seagrass, sea pickles, dripleaf, glow lichen, rooted dirt, tall flowers, mangrove leaves, modded plants), and other mods' `BonemealEvent` handling runs first (LOW priority, DEFAULT results only) |
| **Data or code?** | Code: two mixins, an early return, one event listener, one dispenser registration and two Modern-Common ports |
| **Alt rejected** | Cancelling only `applyBonemeal` through a `BonemealEvent` listener: the event cannot stop `growWaterPlant` or levelEvent 1505 (an ALLOW still plays it), and a cancel turns the client's prediction into a PASS with no swing |
| **Alt rejected (2)** | Keeping FCItemDye at HEAD of `useItemOn` (the former path): it ran before activation, `RightClickBlock` and the spectator check, and never covered the off hand. Gating only the eight classes with an FC-mapped block: the post-1.5.2 plants BTW governs would still grow instantly through direct `performBonemeal` callers |
| **Known limitation** | Dropped 1.20.1 white dye still fertilizes FC farmland (and, by code reading, planters): the item bridge hands FC the stack (351, 15), and FC's entity-collision rule checks only id and damage. The early return restores 4.B's order for bone meal only; other FC items still run at HEAD of `useItemOn` (needs its own regression pass). Not runtime-tested: the client swing, the off hand, creative, mod callers |
| **Verified by** | Refmap emits `m_6225_`, `m_7370_`, `m_214148_` and `m_7179_`. Dev-server proof (`STUB_CATALOGUE.md` (at)), 88/92 checks as expected: crops, stems, hemp and flowers on vanilla or FC farmland fertilize the soil (204 / 1088) and stay at age 0; weeds are pulled first and the bone meal kept; saplings, cocoa, mushrooms, the grass block and sand under water are unchanged and the item kept; planter soil is fertilized under a sapling; white dye used by hand does nothing; dropped bone meal fertilizes; the vanilla dispenser ejects (the farmland under it took the item) and the FC block dispenser spits one out; pink petals keep 1.20.1 bone meal. Misses: two test expectations (netherrack converts to the FC block on placement; a stem on a vanilla pumpkin detaches by FC's rule) and the dropped white dye (two checks) |
| **Net effect on FC loop** | Bone meal is 4.B's fertilizer again: it speeds a farm through its soil and never skips growth stages |

### #018 — Post-1.5.2 plants grow by 4.B rules

| Field | Value |
|-------|-------|
| **Modern feature** | Plants added after 1.5.2 and their growth: acacia, dark oak, cherry saplings and the mangrove propagule, azaleas, beetroot, torchflower and pitcher crops, crimson and warped fungi, bamboo, kelp, cave, weeping and twisting vines, sweet berries, chorus; and bee pollination, which grows crops outside the random tick |
| **Added** | MC 1.7 – 1.20 (bees 1.15) |
| **Maintainer ruling** | 2026-10-15: "Post-1.5.2 plants (acacia, dark oak, cherry and mangrove saplings, beetroot, berries, bamboo, kelp, cave vines and so on) grow at 4.B-style speeds and under 4.B-style conditions. Each plant takes the rules that make sense for its lore: the nearest 1.5.2/FC relative's rules, adapted." Settles decision D4, deferred in `STUB_CATALOGUE.md` (as) |
| **Conflict** | With no legacy id these plants kept 1.20.1's rules after the tick-ownership change: saplings grew about 18x faster than FC's, also in the End; beetroot, the sniffer crops, berries, bamboo, kelp, vines, hanging propagules and chorus 1.6x to 20x faster than their 4.B relatives; azaleas and Nether fungi grow only by bone meal, which under #017 would mean never. Bees advanced FC-owned crops and stems a stage per visit, bypassing FC's rules. Modern plants would undercut BTW's slowed farming and forestry |
| **Pattern** | **D** (FC-extend): twins run FC's own plant classes on the modern plants, through load-time mixins keyed on the twin ids; paced plants keep their 1.20.1 bodies at the FC relative's mean pace (FC's numbers, not invented ones); bees: C (one FC roll per visit) |
| **Implementation** | [`TwinRegistry`](Forge/src/main/java/btw/forge/TwinRegistry.java) (families, ids 174 / 169 / 168 / 167 / 166, paces, metadata codec, growth results), [`TickPolicy`](Forge/src/main/java/btw/forge/TickPolicy.java) (owner PACED, `isFcGrowthGoverned`), [`BlockStateBaseTickMixin`](Forge/src/main/java/btw/forge/mixin/BlockStateBaseTickMixin.java) / [`FcPaceAccess`](Forge/src/main/java/btw/forge/FcPaceAccess.java), [`BlockStateRandomTickMixin`](Forge/src/main/java/btw/forge/mixin/BlockStateRandomTickMixin.java), [`FCBlockSaplingTwinMixin`](Forge/src/main/java/btw/forge/mixin/fcext/FCBlockSaplingTwinMixin.java) and [`FCBlockMushroomTwinMixin`](Forge/src/main/java/btw/forge/mixin/fcext/FCBlockMushroomTwinMixin.java) (load-time, on FC classes), [`BeeGrowCropGoalMixin`](Forge/src/main/java/btw/forge/mixin/BeeGrowCropGoalMixin.java) / [`BeeAccessor`](Forge/src/main/java/btw/forge/mixin/BeeAccessor.java), WorldBridge and ProxyRegistry branches, Modern-Common [`ModernTwins`](Modern-Common/src/main/java/btw/modern/ModernTwins.java). Hooks: `BRIDGE_SPEC.md`, Tick ownership, post-1.5.2 plants |
| **Effect** | Twins (FC's own rule, FC owns the random tick): acacia, cherry, dark oak saplings and planted propagules on an FCBlockSapling (survival, light 9, four stages at 1/64, no growth in the End, then the species' own tree; dark oak by FC's 2x2 rule); azaleas the same, into the azalea tree; beetroot on an FCBlockCarrot (0.1, light, hydration, weeds, fertilizer); torchflower and pitcher crops on an FCBlockWheatLegacy (0.05); crimson and warped fungi on an FCBlockMushroom (1 in 50 on their own nylium to the huge fungus, no spread). Paced (the 1.20.1 body on a fraction of random ticks, never in the End): bamboo 3/32, kelp 0.2232, vines 0.625, sweet berries 0.25, hanging propagules 0.05; chorus 1/16 and allowed in the End. A body segment follows its head. Each bee visit to a governed plant is one growth roll. Modern leaves and nylium decay keep 1.20.1 |
| **Lore mapping** | A sapling is a sapling: the species only chooses the tree. Dark oak is FC's only 2x2 case, the jungle rule. An azalea bush is the sapling of the azalea tree. Beetroot is a domesticated root crop (carrot); torchflower and pitcher are ancient seed crops (legacy wheat). Fungi are mushrooms, and nylium is the Nether's mycelium. Bamboo and kelp are giant and aquatic reeds; cave, weeping and twisting vines are vines; sweet berries and hanging propagules ripen on the plant, like cocoa; chorus grows segment by segment like cactus and is the End's own plant, so FC's End ban (the End is barren) does not apply. Modern leaves and nylium decay already at 4.B pace |
| **BTW adds** | BTW's growth conditions (light, hydrated FC soil, weeds, fertilizer, the End ban, the 2x2 rule, mushrooms on mycelium) reach the modern plants. Modern crops are BTW crops to FC: FC animals graze them, FC weeds grow under them, harvesting them untills FC farmland, bone meal fertilizes their soil. Azaleas and Nether fungi grow by themselves, which 1.20.1 never lets them |
| **Dual-sourced?** | Yes — every plant keeps its 1.20.1 block, item, loot, placement, survival and growth result (its own tree, huge fungus, berries); only the timing and conditions are BTW's |
| **Data or code?** | Code |
| **Alt rejected** | One mechanism for all: thinning a plant's 1.20.1 random tick cannot express FC's four-stage sapling distribution, weeds, hydration, fertilizer or the 2x2 rule, so the FC relatives' real objects run where one exists. Port subclasses of the FC classes: FC subclasses in the bridge against the documented layering, or a subproject that does not exist yet; load-time mixins keyed on the twin id patch only the twins |
| **Alt rejected (2)** | Leaving the plants on 1.20.1 (the (as) deferral): undercuts BTW's slowed farming and forestry, the ruling's point. Scaling `randomTickSpeed` per plant: not per state, and every other mechanic of the cell would slow too. A per-cell counter store for FC's exact reed and cactus cadence: a SavedData for a small difference (thinning gives FC's mean). Cancelling the bees' goal for governed plants: bees would stop helping FC farms at all |
| **Known limitation** | Paced plants match FC's mean pace, not its cadence. The twins share the stand-in range 159-174, so four flammable stand-ins remain (#014). An existing world's modern sapling at 1.20.1 STAGE 1 starts at FC stage 0; a lone dark oak sapling waits at stage 3 for ever (1.20.1 has no single dark oak). Blocks that only multiply by bone meal keep 1.20.1 bone meal (#017). Not runtime-tested: an existing-world upgrade, Serene Seasons with twin crops |
| **Verified by** | `[SELF-AUDIT/twin] 6 twin families (11 blocks), 41 twin states, 173 paced states, 0 warnings`; the tick and light audits unchanged. Dev-server proof (`STUB_CATALOGUE.md` (at)), every group within tolerance of its 4.B expectation: acacia, cherry and planted propagules 9/9 present at W1 (1.20.1 would leave about 0.7); azaleas 4/5 and 3/4 grown by W3 (1.20.1 never); dark oak groups by FC's 2x2 rule; beetroot, torchflower and pitcher 0/16 ripe at W1 (1.20.1 about 16); bamboo with 3+ segments 2/16 at W1 (1.20.1 about 16); kelp, vines, berries and hanging propagules at their FC paces; both Nether fungi 16/16 grown at W2 (1.20.1 never); the End cherry never grows and End bamboo never grows, while chorus does. A focused re-check at randomTickSpeed 300 settled two small-sample outliers: bamboo shoots left 5/49 at 1,200 ticks and 1/49 at 2,400 (expected about 3 and 0.2), azaleas 7/36 grown at 2,400 against 13/36 for the FC oak control (both expected about 10.7) |
| **Net effect on FC loop** | Modern plants no longer undercut BTW's slowed farming and forestry; new biomes keep their vegetation, which grows at BTW's pace |

### #019 — Blocks that 4.B removed stay removed

| Field | Value |
|-------|-------|
| **Modern feature** | 1.20.1's survival sources of the ids BTW 4.B removed: crafting and cooking recipes, loot (chests, archaeology, fishing, piglin barter), structures (template and code pieces), the vanilla creative tabs and JEI, string placing tripwire, generated TNT and hopper minecarts, trades |
| **Added** | The ids are 1.5.2 or older (1.5 brought the hopper, comparator, daylight detector, dropper, trapped chest, weighted plates, activator rail and the two minecarts); 1.20.1's data and tabs re-expose them |
| **Maintainer ruling** | 2026-10-15: "Blocks that 4.B explicitly removed stay removed. Examples: the vanilla hopper and other blocks whose recipes FCRecipes removes. Modern-only blocks that 4.B never had are not covered by this ruling." Settles decision D5 (with CT6's share of it) |
| **Conflict** | 4.B stubbed these ids (FCBlockStub, FCItemStub, inert vanilla classes), retired them to legacy-only ids with a BTW successor, or removed their recipes (FCRecipes.RemoveVanillaRecipes: 94 crafting records, 14 furnace inputs; map extending and the two-tool repair). The port never applied the removals (Modern-Common `CraftingManager.RemoveRecipe` returned true and nothing else), and 1.20.1 handed out every id again |
| **Protocol conflict** | Protocol #5 ("Keep both items/blocks dual-sourced. Never silently replace vanilla X with FC Y") and Protocol #8 ("Integrate, don't disable") cut the other way: this entry removes vanilla items and hands out BTW successors in their place. The maintainer's ruling overrides both, for the rows of the `RemovedContent` table only. The table is the entry's whole scope: nothing outside it is removed, and modern-only blocks are excluded by the ruling's own words. Inside the scope the entry still follows #8 where it can: every retired id points to its BTW successor (loot, trades, recipe ingredients), so a recipe a successor can serve is widened rather than stranded |
| **Pattern** | **C** (rebalance by subtraction), justified by the ruling. FC's own precedents, in its order: recipes removed (FCRecipes.RemoveVanillaRecipes), loot filtered (ComponentMineshaftCorridor.FilterChestMinecartContents, WorldGenDungeons.FilterChestContentsForDepth), structure blocks substituted or stripped (StructureComponent's chests, the trap-free pyramids), explicit null creative tabs |
| **Implementation** | [`RemovedContent`](Forge/src/main/java/btw/forge/removed/RemovedContent.java) (the single table: 12 T1, 8 T2, 8 T3 rows), [`FcRecipeRemovals`](Forge/src/main/java/btw/forge/FcRecipeRemovals.java) and [`RetiredIngredientWidening`](Forge/src/main/java/btw/forge/RetiredIngredientWidening.java) (run by [`RecipeBridge`](Forge/src/main/java/btw/forge/RecipeBridge.java) before `replaceRecipes`), Modern-Common [`CraftingManager`](Modern-Common/src/main/java/btw/modern/CraftingManager.java) (records FC's removals), [`FCRemovedContentLootModifier`](Forge/src/main/java/btw/forge/data/FCRemovedContentLootModifier.java) (one condition-free GLM, `betterthanwolves:removed_content`), [`StructureTemplateRemovedMixin`](Forge/src/main/java/btw/forge/mixin/StructureTemplateRemovedMixin.java) (the Forge `processBlockInfos` overload, RETURN, `remap = false`) and [`StructurePieceRemovedMixin`](Forge/src/main/java/btw/forge/mixin/StructurePieceRemovedMixin.java) (`placeBlock` `m_73434_` HEAD), [`RemovedCreativeTabs`](Forge/src/main/java/btw/forge/removed/RemovedCreativeTabs.java), [`RemovedContentEvents`](Forge/src/main/java/btw/forge/removed/RemovedContentEvents.java) (string, minecarts, trades). Hooks: `BRIDGE_SPEC.md`, Stays removed (RemovedContent) |
| **Effect** | T1 stubbed (hopper, comparator, daylight detector, dropper, trapped chest, weighted plates, activator rail, TNT and hopper minecarts, tripwire hook, tripwire): no recipes, loot filtered, hidden from creative and JEI, string no longer places tripwire, structures place a 4.B block instead (trapped chest -> chest, dropper -> dispenser, comparator -> repeater, activator rail -> rail, weighted plates -> stone plate, hopper, daylight detector, tripwire and hooks -> air), generated minecarts become plain minecarts. T2 retired (crafting table, chest, ladder, torch, snow block, clay block, cobweb, the nether brick item): no recipes, hidden from creative, loot and trades give the BTW successor; generated copies stay legacy blocks, which FC converts when broken. T3 recipe-only (wooden tools, stone hoe, snow layer, furnace): no recipes, loot filtered, still in creative, as in 4.B. All 94 of FC's crafting removals (93 matched; the 6-stick fence has no 1.20.1 counterpart), its furnace removals and the two special recipes reach the RecipeManager: 130 recipes removed |
| **BTW adds** | The T1 ids leave their roles to the BTW blocks that replaced them in 4.B (the BTW hopper, block dispenser, detector block and chest), and a T2 id becomes its BTW successor wherever 1.20.1 would hand one out. Non-FC recipes that take a T2 id also take FC's legacy conversion target for it: 13 recipes widened, among them the lantern (nether-coal torches only), the chest boats, the shulker box, terracotta and red nether bricks |
| **Dual-sourced?** | No, by ruling. Removed blocks already in a world or placed by commands are left alone (`/give` and `/setblock` stay op tools, as in 4.B), and modern-only blocks are untouched |
| **Data or code?** | Both: one data GLM whose item list is read from the table at run time, and table-driven code for recipes, structures, tabs, placement, entities and trades |
| **Alt rejected** | A list per hook (recipes, loot, tabs, structures): five lists drift apart; every hook reads the one table. One JSON loot modifier per item: the same drift, in data |
| **Alt rejected (2)** | Making existing removed blocks 4.B-inert or converting them on load: breaks contraptions in existing worlds for little gain. Substituting generated T2 blocks (village chests and torches): FC already converts them when broken, as 4.B does, and a BTW-chest substitute would need the template's loot table carried into FC's chest entity. Waiting for CT6 before hiding T1 and T2: the ruling settles them now |
| **CT6** | Narrowed, not closed. Hiding every T1 and T2 id from the creative tabs (and so from JEI) follows from the ruling and is done; the remaining CT6 rows stay open for the maintainer: music discs other than 13, enchanted books, wheat seeds and legacy wheat, glowing redstone ore, and the BlockStem / BlockNetherStalk / BlockRedstoneTorch stand-ins |
| **Known limitation** | Stranded for later compat work: blast_furnace, smoker and furnace_minecart (they need the furnace) and crossbow (it needs a tripwire hook); they stay craftable from a salvaged furnace or an old hook. A `/give`'d tripwire hook cannot be placed (a placement-routing issue that predates this entry). Generated legacy torches drop the nether-coal torch when broken, as in 4.B. Not runtime-tested: the creative tabs and JEI (client), modded templates |
| **Verified by** | `[REMOVED-AUDIT] stays-removed table: 12 T1, 8 T2, 8 T3 rows; FC stub blocks [...] and items [404, 407, 408] covered; 0 warning(s)`. Refmap emits `m_73434_`; the template mixin has no entry (`remap = false`). Dev-server proof (`STUB_CATALOGUE.md` (at)), 72/73 checks as expected: no removed id in mineshaft, outpost, ruined-portal, fishing, barter, snowy-house, ocean-ruin or bonus-chest loot, and the BTW successor where a T2 id was due; the mansion's trapped chest placed as a chest, the ancient city's comparators as repeaters, no jungle-temple tripwire or hooks; string places nothing; summoned TNT and hopper minecarts become plain minecarts; no recipe of any type produces any of the 27 table items; `setblock` still places T1 blocks. The miss is the `/give`'d tripwire hook |
| **Net effect on FC loop** | 4.B's tech ladder holds against 1.20.1's data: hoppers, comparators, crafting tables, torches and furnaces come only the BTW way |

### Follow-up from #005 implementation

The stonecutter pass reinforced that several planned recipe overrides —
lodestone (needs FC soulforged steel), recovery compass (needs FC
arcane scroll), conduit disable (needs FC crucible replacement in the
same sprint to avoid regression) — are **blocked on stable resource
locations for FC items**. FC's legacy ParseID→index mapping renders
each item as `betterthanwolves:item_<runtime_index>`, which shifts if
the FC config changes ParseIDs. Promoted as a blocker to the deferred
`#006` tag-remediation entry.

---

## Integration directions (2026-10-10)

Recorded from a design discussion on 2026-10-10.
Nothing here is implemented. It applies Protocols #1 and #8. Points marked
*verify* come from memory of BTW 4.B, not from the repo: check them
against the FC source before building on them.

### Critique of the current log

- Protocol #2 says gate, don't delete, but most implemented entries
  subtract: #001 Mending halved, #006 Frost Walker capped, #008 Swift
  Sneak capped, #010 Piercing capped, #011 Sweeping Edge suppressed,
  #007 respawn anchor single-use, #005 stonecutter slabs halved, #004
  loot stripped, #002 phantoms, patrols, traders and wardens switched
  off by gamerule.
- The deferred tables read the same way: "disable or nerf", "limit
  count", "goat horn locked", "reduce range".
- They ask "how do we stop X trivialising BTW?" instead of "what does
  BTW make X mean?".
- Pattern D (FC-extend) is barely used. #009 (the totem costs soul flux)
  is the one true FC-infrastructure gate.
- Wave 2 (W2) of the 2026-10-10 fix effort (triage SW5) turned drowned,
  husks and strays into plain FC zombies and skeletons, and glow squid
  into FC squid. Wither skeletons map to FC's own type-1 wither
  skeleton, and existing zombie villagers stay villager zombies. It
  closed real gaps (these mobs ran 1.20.1 AI with no FC behaviour, and
  they triggered D2's per-tick scroll drops), but it deleted variety and
  contradicts #003, which naturalizes husk/stray/drowned.

### Principles

1. **Every modern material enters a BTW processing chain**: saw,
   millstone, turntable, kiln, crucible, cauldron, filtered hopper.
2. **Gate by BTW world progression, not by gamerules.** FC already
   tracks progress (`FCUtilsWorld.GameProgressHasNetherBeenAccessedServerOnly`,
   `...HasWitherBeenSummonedServerOnly`,
   `...HasEndDimensionBeenAccessedServerOnly`). Lighting a Nether portal
   sets the first flag (`FCBlockPortal`), and lit overworld portals
   possess nearby animals and villagers. `FCUtilsHardcoreSpawn` widens
   the random-respawn radius at each stage (×1.5 Nether, ×2 Wither, ×4
   End) and stops the shared early-game respawn once the Nether flag is
   set. That is the existing precedent for progression gating.
3. **Keep modern mob identities** and give them BTW behaviour, instead
   of collapsing them into base FC mobs.
4. **Give purposeless modern blocks BTW jobs.**

### Ideas by theme

**Death and hardcore spawn**
- BTW's random respawn (`FCUtilsHardcoreSpawn`) makes the recovery
  compass (echo shards, ancient cities), lodestones and maps genuinely
  valuable. Integrate them; don't nerf them.
- Respawn anchor as the Nether's fixed spawn, charged with soul
  material instead of glowstone (replaces #007's cap).
- Phantoms as things that hunt in the dark, tied to BTW gloom
  (`FCDamageSourceCustom.m_DamageSourceGloom`), instead of
  `doInsomnia=false` (#002).

**Souls (BTW's core theme)**
- Sculk is Mojang's own soul/death mechanic: catalysts bloom where mobs
  die. BTW captures souls with soul-sand-filtered hoppers
  (`FCTileEntityHopper`) and urns.
- Sculk as a soul source for urns; catalysts competing with soul
  hoppers; the warden guarding the richest soul source instead of
  `doWardenSpawning=false`. Soul-capture rules: *verify*.
- Soul fire as permanently "stoked" heat under the cauldron and
  crucible: a mid-game convenience earned in soul sand valleys
  (*verify* how stoked fire, `FCBlockFireStoked`, is made and decays).

**Mechanical power and redstone**
- BTW machines emit vibration game events: sculk sensors detect running
  mills, noisy industry in the deep dark summons the warden, and wool
  dampens it (a new job for BTW wool).
- Comparator outputs for FC machines: cauldron cook progress, hopper
  fill, urn souls.
- Animal power: a horse, donkey, camel or goat on a lead turns a BTW
  axle, a tier between the hand crank and the windmill. The goat's ram
  fits FC's existing `OnKickedByCow` hook (`FCEntityCow`,
  `FCEntityCreeper`).
- BTW buoyancy (items float or sink by type, `Item.GetBuoyancy`) plus
  bubble columns gives item elevators and buoyancy sorting, instead of
  "rebalance flow". Exact buoyancy rules: *verify*.
- BTW already has a lightning rod (`FCBlockLightningRod`). Copper rods
  feed the same lightning mechanics; oxidation weakens them and
  honeycomb wax (bees) preserves them.

**Materials**
- Deepslate, tuff and calcite as BTW's deeper stone strata. The seed
  exists: since 2026-10-10 `ModernBlockStandIn` reads deepslate as an
  anonymous solid stand-in (a legacy id allocated at runtime from
  159-174; 160 in the 2026-10-10 dev log); real FC stone behaviour grows
  from there.
- Copper melted in the crucible as an early metal.
- Mud bricks sun-dried like BTW's unfired bricks
  (`FCTileEntityUnfiredBrick` cooks in place, with rain decay).
- Decorated pots shaped on the turntable with sherds and kiln-fired,
  joining BTW's vase/urn pottery chain.
- Archaeology loot carrying BTW lore items (arcane scrolls, rare seeds).
- Cherry, mangrove, bamboo, crimson and warped wood through the saw:
  sidings, mouldings, corners, and bark for tanning.

**Workstations**
- Fletching table = BTW arrow bench (broadheads, composite bow).
- Stonecutter = mechanically powered stone saw that outputs BTW shapes.
- Blast furnace needs bellows or stoked heat.
- Composter feeds BTW's dung/fertilizer chain (*verify* the 4.B chain).
- Chiseled bookshelf powers the Infernal Enchanter by the books it
  holds (today `FCBlockInfernalEnchanter` counts `Block.bookShelf`
  within 8 blocks).

**Enchanting (the biggest win)**
- BTW's arcane scrolls (mob drops, used in the Infernal Enchanter)
  frame every post-1.5.2 enchantment:
  - Frost Walker from strays;
  - Soul Speed from piglins and wither skeletons;
  - Swift Sneak from wardens;
  - Riptide, Loyalty and Channeling from drowned;
  - Multishot, Piercing and Quick Charge from pillagers.
- The caps in #006, #008, #010 and #011 then become rare, earned
  top-tier enchantments instead of nerfs.

**Mobs**
- Modern identities as FC variants: drowned keep tridents; husks'
  hunger feeds BTW hunger penalties; strays keep slowness arrows; glow
  squid as BTW's night head-crab squid (`FCEntitySquid`).
- Modern animals under BTW husbandry (hunger, loose food, breeding
  harness):
  - bees pollinate hemp;
  - sniffers dig up hemp seeds (BTW drops them from grass,
    `FCBlockGrass`);
  - axolotls hunt FC squid;
  - a turtle helmet blocks squid head-crabbing;
  - cats scare FC creepers (FC creeper AI must learn that cats count
    like ocelots).
- Piglins barter BTW Nether goods; BTW nether growth spreads onto
  nylium.

**Teaching** (the "wiki-dependent" CurseForge complaint, triage CTX-3)
- An advancement tree that mirrors BTW's tech ladder, with recipe-book
  unlocks driven by it.

**Ecosystem** (brief)
- Tags (for example Forge ingot tags for soulforged steel), JSON loot
  and recipes, `IItemHandler` capabilities on BTW baskets and hoppers.
  Likely blocked on the same stable FC resource locations as the
  recipe overrides (see Follow-up from #005).

### Extension points the read-only rule requires

FC code cannot change, so integrations need these hooks in the bridge or
the planned FC-extension subproject:

| # | Extension point | Enables |
|---|-----------------|---------|
| a | Registry mapping modern blocks to FC **behaviour** (generalises `ModernBlockStandIn`) | Deepslate strata, chiseled bookshelves, copper rods, soul fire. First instance: the FC plant twins, `TwinRegistry` (#018) |
| b | AI injection so FC mobs recognise modern entities | Cats vs creepers, axolotls vs squid, goats on `OnKickedByCow` |
| c | Machine game events + comparator outputs | Sculk sensing, warden summoning, redstone readouts |
| d | Progression-flag API over FC's `GameProgress*` flags | Replacing the #002 gamerules with progression gates |

### Fidelity layering

- The faithful core stays 1.5.2-exact. Integrations are a separate,
  labelled layer on top.
- A purist server option (core only) is reasonable, but it must not be
  the default.

### Testing

- Forge's GameTest framework is loaded with no tests: the dev logs show
  `Enabled Gametest Namespaces: []`. Use it for in-world BTW regression
  tests of integrations and the faithful core.

### Suggested first moves

1. Adopt "integrate, don't disable" as a protocol (done: Protocol #8).
2. Turn the #002 gamerule switches into progression gates.
3. Move the modern enchantments onto arcane scrolls.
4. Rework the SW5 variant collapse into FC variants.

---

## Adding a new integration — checklist

When you pilot a new feature:

- [ ] Never edit FC code (Protocol #1). Put the change in the Forge
      bridge, Modern-Common or the FC-extension subproject.
- [ ] Say what BTW adds to the feature (Protocol #8). If the entry
      disables, caps or removes anything, justify why integration was
      not possible.
- [ ] Pick an integration pattern (A–E) and justify it in one sentence.
- [ ] Write an alternative you considered and rejected (forces you to
      explore the design space).
- [ ] Prefer Forge event → loot-table override → recipe override →
      mixin, in that order.
- [ ] If mixin, include a full Javadoc block on the class with:
      Feature / Conflict / Pattern / Rebalance / Injection strategy /
      Alt rejected.
- [ ] Tag the new port code (bridge or subproject, never FC code) with
      `// FCMOD-INTEGRATION(1.20.1): <feature>`.
- [ ] Add a new row to the Integration log above.
- [ ] Rebuild and verify refmap (`grep <MixinName>
      Forge/build/tmp/compileJava/betterthanwolves.refmap.json`).
- [ ] Smoke-test in dev run.
- [ ] Smoke-test in built jar (reobfuscation catches things dev misses).

---

## Tracking deferred integrations

Catalogue of post-1.5.2 MC features that touch FC's design surface and are
not yet integrated. Grouped by domain. Each row carries a tentative
pattern (A–E). Items already implemented live in the integration log
above and do **not** appear here. Pattern **E** ("naturalize") rows are
listed for completeness but are batch-approvable with no code at all.
Many rows still read "nerf" or "limit". A **Direction:** note (added
2026-10-10) gives the integrate-don't-disable direction (Protocol #8),
which supersedes the row's tentative note; see
[Integration directions](#integration-directions-2026-10-10). A row with
no Direction note that disables, caps or nerfs a feature is open: it
needs an integrate-don't-disable proposal before promotion.

Promote a row out of this section by writing it up in the integration
log above with the next sequential `#NNN`.

### Combat & weapons (1.9 onward)

| # | Feature | Since | Pattern | Notes |
|---|---------|-------|---------|-------|
| — | Off-hand slot | 1.9 | D | FC inventory has no slot 40; pair with FC torch/shield use |
| — | Attack cooldown bar | 1.9 | C | FC combat assumes instant repeat; cooldown halves DPS vs FC penalty model |
| — | Shields | 1.9 | D | FC variant: requires hide + iron, blocks % based on stamina |
| — | Tipped arrows / lingering potions | 1.9 | C | Lingering only craftable if FC potion stand |
| — | Trident + Riptide/Loyalty/Channeling | 1.13 | B | Gate behind drowned drop in FC nether/wild loot. **Direction:** drowned keep tridents as an FC variant; Riptide/Loyalty/Channeling come from drowned arcane scrolls |
| — | Smithing-template netherite upgrade | 1.20 | A or B | Re-tier netherite under Soulforged, or gate behind soulforge |
| — | Mace + Wind Charge | 1.21 | — | Future port only |

### Inventory, UI & player UX

| # | Feature | Since | Pattern | Notes |
|---|---------|-------|---------|-------|
| — | Recipe book | 1.12 | C | Reveals all FC recipes; only books FC explicitly grants. **Direction:** unlocks driven by the BTW advancement tree |
| — | Advancements (replaces achievements) | 1.12 | D | FC adds its own advancement tree as datapack. **Direction:** the tree mirrors BTW's tech ladder, answering the "wiki-dependent" complaint (triage CTX-3) |
| — | Spectator mode | 1.8 | C | Server gamerule disable in default. **Direction:** open; needs an integrate-don't-disable proposal before promotion (Protocol #8) |
| — | Auto-jump | 1.10 | C | Default off — bypasses FC penalty-jump tuning |
| — | Held-item swap (F key) | 1.9 | C | Rebind to FC torch toggle |

### Movement & survival

| # | Feature | Since | Pattern | Notes |
|---|---------|-------|---------|-------|
| — | Sprint mechanics (revised) | 1.9 | — | Partially bridged in `PlayerBridge`; needs penalty-gate audit |
| — | Elytra | 1.9 | B | Gate behind soulforge fuel + cooldown; bypasses ground travel & HC Spawn |
| — | Powder snow + freezing damage | 1.17 | D | Route through FC `DamageSource`, allow leather-boots immunity |
| — | Scaffolding | 1.14 | C | Limit FC variant to N blocks of vertical traversal |
| — | Dolphin's grace effect | 1.13 | C | Cap duration |
| — | Soul speed enchant | 1.16 | B | Require FC reagent. **Direction:** comes from piglin / wither skeleton arcane scrolls |
| — | Bubble columns (magma/soul sand) | 1.13 | C | Rebalance flow — trivializes underwater mob farms. **Direction:** with BTW buoyancy, item elevators and buoyancy sorting instead of a flow nerf |

### Food & hunger

| # | Feature | Since | Pattern | Notes |
|---|---------|-------|---------|-------|
| — | Honey bottle | 1.15 | C | Low restore, FC alchemy reagent |
| — | Sweet berries / glow berries | 1.14 / 1.17 | C | Low restore, hostile bush damage |
| — | Suspicious stew | 1.14 | C | Lower buff tier |
| — | Composter | 1.14 | D | FC compost-bin variant; bypasses FC's hemp/dung composting chain. **Direction:** feeds BTW's dung/fertilizer chain instead of bypassing it (verify the 4.B chain) |

### Tools, tiers & enchantments

| # | Feature | Since | Pattern | Notes |
|---|---------|-------|---------|-------|
| — | Netherite tier | 1.16 | A | Re-tier under Soulforged; conflicts with FC's tier ceiling |
| — | Smithing templates | 1.20 | B | Require FC ingredient in template |
| — | Anvil rename / repair audit | pre-FC | — | Audit FC anvil overrides for parity with `MendingCapMixin` siblings |
| — | Grindstone disenchanting | 1.14 | C | Tax via FC reagent |
| — | Anvil XP cost cap (40) | 1.9 | C | Raise cap, FC charges differently |

### Blocks & materials

| # | Feature | Since | Pattern | Notes |
|---|---------|-------|---------|-------|
| — | Copper (block + oxidation) | 1.17 | D | FC repurposes for piping/wiring. **Direction:** melted in the crucible as an early metal; copper lightning rods weaken as they oxidize, honeycomb wax preserves them |
| — | Amethyst geodes | 1.17 | C | Slow regen rate to avoid free renewable |
| — | Lightning rod | 1.17 | C | Reduce range — negates FC lightning ignition. **Direction:** feed the same lightning mechanics as BTW's own rod (`FCBlockLightningRod`) instead of cutting range |
| — | Sculk catalyst/shrieker/sensor | 1.19 | C | Sensor wireless redstone shortcuts FC redstone. **Direction:** sculk as a soul source for urns, catalysts competing with soul hoppers; sensors hear running BTW machines, wool dampens them |
| — | Lodestone | 1.16 | B | Require FC nether material — compass-tracking trivializes navigation. **Direction:** integrate, don't nerf: BTW's random respawn makes lodestone navigation genuinely valuable |
| — | Conduit | 1.13 | C | Smaller radius, drains hunger — free Strength III + water breathing |
| — | Beacon (post-1.4 + soul beacon) | 1.4 / 1.16 | C | FC food cost, smaller pyramid base |
| — | Chiseled bookshelves | 1.20 | C | Comparator output signal nerf. **Direction:** powers the Infernal Enchanter by the books it holds, not a signal nerf |
| — | Bamboo wood family | 1.20 | A | Tier-align with FC wood progression. **Direction:** through the saw (sidings, mouldings, corners, bark) |

### Crafting & workstations

| # | Feature | Since | Pattern | Notes |
|---|---------|-------|---------|-------|
| — | Blast furnace | 1.14 | B or D | Recipe set restricted, or FC variant — bypasses bloomery progression. **Direction:** needs bellows or stoked heat. **Stranded (2026-10-15):** its recipe needs the furnace, which stays removed (#019); craftable only from a salvaged furnace until a BTW route exists |
| — | Smoker | 1.14 | C | Slower than vanilla, costs FC fuel. **Stranded (2026-10-15):** its recipe needs the furnace (#019) |
| — | Campfire | 1.14 | C | Limited recipes |
| — | Datapack recipe / FC `CraftingManager` priority | 1.13 | — | Audit needed — could shadow FC recipes |

### Mobs

| # | Feature | Since | Pattern | Notes |
|---|---------|-------|---------|-------|
| — | Horse / donkey / mule | 1.6 | D | FC saddle + breeding rebalance. **Direction:** animal power: on a lead, turns a BTW axle (a tier between hand crank and windmill) |
| — | Llama / trader llama | 1.11 / 1.14 | D | Caravan mount + spit |
| — | Rabbit | 1.8 | C | Food value low |
| — | Aquatic life (turtle, dolphin, fish) | 1.13 | C | Drops slotted into FC food tiers. **Direction:** a turtle helmet blocks squid head-crabbing |
| — | Pillager / vindicator / ravager / evoker | 1.14 / 1.11 | C | Raid mob drops (totem, emerald, raid-specific items) — `#004` covered the outpost chest loot; mob-drop loot still open. **Direction:** pillager arcane scrolls carry Multishot / Piercing / Quick Charge |
| — | Bee | 1.15 | D | FC honey integrates with alchemy. **Direction:** bees pollinate hemp; honeycomb wax preserves copper lightning rods. Pollination of FC-governed plants is one FC growth roll per visit since #018 |
| — | Piglin / hoglin / zoglin / strider / piglin brute | 1.16 | D | Barter table FC-flavored. **Direction:** piglins barter BTW Nether goods; BTW nether growth spreads onto nylium; Soul Speed scrolls |
| — | Goat / axolotl / glow squid | 1.17 | C | Axolotl drops nerf, goat horn locked. **Direction:** goats ram through FC's `OnKickedByCow` hook and can turn an axle; axolotls hunt FC squid; glow squid kept as BTW's night head-crab squid (reverses the SW5 collapse) |
| — | Allay | 1.19 | C | Limit count — auto item-collect trivializes FC sorting. **Direction:** open; needs an integrate-don't-disable proposal before promotion (Protocol #8) |
| — | Camel | 1.20 | D | Mount. **Direction:** animal power on a BTW axle |
| — | Sniffer + torchflower / pitcher plant | 1.20 | C | Low yield. **Direction:** under BTW husbandry, sniffers dig up hemp seeds. Torchflower and pitcher crops grow by 4.B crop rules since #018 |

### World generation

| # | Feature | Since | Pattern | Notes |
|---|---------|-------|---------|-------|
| — | 1.7 biome rewrite | 1.7 | D | FC biome modifier datapack — `BiomeDecorator` only knows old biomes |
| — | Ocean monument / elder guardian | 1.8 | C | Mining fatigue + prismarine balance |
| — | Datapack worldgen (noise router) | 1.18 | D | FC datapack overrides — `WorldType` ignored |
| — | World height extension (-64 → 320) | 1.18 | — | Audit FC pathfinding & block IDs assuming 0–255 |
| — | Cave biomes (lush/dripstone/deep dark) | 1.17 / 1.19 | D | FC ore tweaks per cave biome. **Direction:** deepslate/tuff/calcite as BTW's deeper stone strata (seed: `ModernBlockStandIn`); the warden guards the richest soul source |
| — | Cherry biome | 1.20 | A | Biome + wood tier-align. **Direction:** cherry wood through the saw |
| — | Mangrove biome | 1.19 | A | Biome + wood + mud + propagules. **Direction:** mangrove wood through the saw; mud bricks sun-dried like BTW's unfired bricks |

### Trading & villages

| # | Feature | Since | Pattern | Notes |
|---|---------|-------|---------|-------|
| — | Profession workstation system | 1.14 | D | FC professions on workstations (in progress: `ContainerMerchant`) |
| — | Trade level XP / progression | 1.14 | C | Slow leveling |
| — | Hero of the Village | 1.14 | C | Disable or nerf — free discount + items. **Direction:** open; needs an integrate-don't-disable proposal before promotion (Protocol #8) |
| — | Cured zombie villager discount | 1.4 / 1.14 | C | Cap discount — trivial near-infinite trades |
| — | Iron golem auto-spawn from villagers | 1.14 | C | Require FC trigger — free defenses |

### Death, respawn & sleep

| # | Feature | Since | Pattern | Notes |
|---|---------|-------|---------|-------|
| — | Recovery compass | 1.19 | C | Require FC compass + reagent — trivializes lost-corpse retrieval. **Direction:** integrate, don't nerf: BTW's random respawn makes the compass (echo shards, ancient cities) genuinely valuable |
| — | Hardcore-mode spectator-on-death | 1.3 | — | Audit interaction with FC death override |

### Status effects & potions

| # | Feature | Since | Pattern | Notes |
|---|---------|-------|---------|-------|
| — | Slow Falling | 1.13 | C | Low brewing yield, expensive |

### Audio / visual / cosmetic

| # | Feature | Since | Pattern | Notes |
|---|---------|-------|---------|-------|
| — | New particle types | 1.13+ | — | Partial: numeric ID translation — finish coverage in client bridge |

### Networking / data / commands

| # | Feature | Since | Pattern | Notes |
|---|---------|-------|---------|-------|
| — | Brigadier commands | 1.13 | D | Re-register FC commands as Brigadier nodes — `CommandBase` is old API |
| — | Datapack tag system | 1.13 | — | Audit — FC ID-based checks ignore tags. **Direction:** publish BTW tags (e.g. Forge ingot tags for soulforged steel) |
| — | Resource pack v2/v3/v4 textures | various | — | Partial via `BlockModelBridge` / `FCBakedModel`; finish coverage |
| — | World height in heightmap APIs | 1.18 | — | Audit FC code assuming 0–255 |
| — | IFluidHandler | Forge | D | Partial — wire FC fluid containers (cauldron, bucket, etc.) |
