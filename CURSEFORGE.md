<!-- MAINTAINER: do not publish this page while a "TODO (maintainer)" under Links is still open.
     Once the BTW wiki link is filled in, the second Installation tip may point players to it. -->

# Better Than Wolves — Forge 1.20.1 Port

A faithful port of **Better Than Wolves** (originally by FlowerChild for Minecraft 1.5.2) to **Forge 1.20.1**.

This is not a rewrite. The port runs the original FC game code verbatim on top of a compatibility bridge that translates modern Minecraft's APIs back into the APIs FC was written against. Every block, item, entity, recipe, and tile-entity behaviour comes straight from the original BTW 4.B0000003 sources — the same progression, the same difficulty curve, the same unforgiving hunger/penalty system FlowerChild designed.

---

## What Better Than Wolves Is

BTW is a total-conversion survival mod that throws out Minecraft's ease-of-life shortcuts and rebuilds the game around a multi-stage **mechanical-power tech tree**. Early game is hard. Resources are scarce. Tools break. Food matters. Sleep costs you. The payoff is one of the deepest hand-built tech progressions ever shipped in a Minecraft mod — windmills, waterwheels, axles, gearboxes, mill stones, saws, anvils, cauldrons, crucibles, kilns, soulforges, and the soul-steel endgame.

If you've never played BTW before, **do not expect vanilla Minecraft + extras**. Expect a different, harsher, more rewarding game.

---

## Core Content Included

**Progression & Tech**
- Full mechanical-power network: windmills, waterwheels, horizontal/vertical axles, gearboxes, hand cranks
- Mill stone, saw, anvil, chopping block, turntable, pottery wheel
- Hibachi + stoked fire, kiln, cauldron, stoked cauldron, crucible, stoked crucible
- Soulforge and the steel progression
- Bellows, detector blocks, pulleys, rope, cords

**Farming & Food**
- Hemp cultivation, flax-style fiber processing
- Animal husbandry loop with FC's health/nutrition rules
- Cooking, baking, knitting, dying
- Stoking, roasting, canning

**Combat & Survival**
- FC's pre-1.9 combat model retained
- Composite bow, battleaxe, warhammer, mattock, steel tools
- Gloom, hunger, fat, and health penalty system (four separate debuff tracks)
- FC's food scale (0–60) driving penalty gates

**World & Mobs**
- All FC blocks, plants, ores, and worldgen-facing content
- FC's custom animal behaviours and drop tables
- Hardcore spawn, costly sleep, FC's death penalties

**Integration**
- **JEI** support (optional). BTW 4.B itself had no recipe viewer; with JEI installed you can browse:
  - recipe categories for the cauldron, stoked cauldron, crucible, stoked crucible, mill stone, soulforge (4x4) and campfire spit (the plain crucible list is empty, as in 4.B);
  - FC's special crafting recipes (log chopping with an axe, knitting, baiting a fishing rod) in the crafting category;
  - information pages for the processes that have no recipe list: kiln, saw, turntable, hopper filters and souls, hibachi and stoked fire, cauldron/crucible fire rules, and campfire cooking.

---

## Requirements

- **Minecraft 1.20.1**
- **Forge 47.3.0** or newer
- **Java 17**
- **JEI 15.3.0.8+** (optional, recommended)

---

## Installation

1. Install Forge 1.20.1.
2. Drop the BTW jar into your `mods/` folder.
3. (Optional) Drop JEI 15.x into the same folder.
4. Launch and create a new world.

> **Tip:** BTW assumes a fresh world. Do not apply it to an existing save.

> **Tip:** BTW 4.B teaches almost nothing in game, by design. Install JEI for the recipes and for notes on the processes that have no recipe list (kiln, saw, turntable, hopper, fire).

---

## Compatibility Notes

This port runs the original FC code through a runtime bridge. Other Forge mods generally work alongside it, but a few caveats apply:

- **World generation mods** that replace Minecraft biomes wholesale may hide FC ores or shift FC worldgen.
- **Food/hunger mods** will conflict — BTW replaces the vanilla food system with its own penalty model.
- **Combat overhauls** (that add attack cooldown tuning, dodge mechanics, etc.) may not mesh with FC's pre-1.9 combat.
- **Tool-durability overhauls** (mending changes, unbreakable tool mods) break FC's repair economy on purpose.
- **Early-progression mods** (copper/brass tech trees, quest mods, mining mods) typically don't need to conflict but were not explicitly tested.
- **Multiplayer:** run the same version of this port on the server and on every client. A client whose BTW network protocol differs from the server's is refused when it joins.

If you are bundling BTW in a modpack, keep its progression in mind — many "quality of life" mods trivialise FC's intended difficulty curve.

---

## Known Differences from BTW 4.B

The port aims to play exactly like BTW 4.B0000003, but it is not there yet. If something works differently from BTW 4.B, it is most likely a port bug: please report it (see **Links**) with your `latest.log`.

- Minecraft 1.20.1 blocks, items, mobs and biomes that did not exist in 1.5.2 are still in the game:
  - BTW's mobs and machines treat the newer blocks as plain solid blocks; seagrass and kelp count as water.
  - Some 1.20.1 mob types are replaced by BTW mobs: drowned, husks and zombie villagers spawn as BTW zombies, strays and wither skeletons as BTW skeletons, and glow squids as BTW squids. Because drowned spawn in water, BTW zombies also appear in rivers and oceans.
- Knitting, wicker weaving and bone carving never finish: holding right-click makes no progress, so you cannot weave wicker, knit wool or carve a bone fishing hook yet. The knitting recipe itself works and shows in JEI.
- BTW mobs do not trigger pressure plates or tripwire, do not travel through nether or end portals, and are not slowed by soul sand. Blocks being moved by a piston are solid to them.
- Some vanilla 1.20.1 crafting recipes that BTW 4.B removed may still be craftable.
- BTW's Hardcore Buoy option (`fcEnableHardcoreBuoy`) has no effect yet: dropped items float as in Minecraft 1.20.1.
- The Obstructed player-names option (`fcEnableHardcorePlayerNames=2`) hides only players' name tags behind walls, not named mobs' tags. The default Hardcore option (no player name tags) works.
- The vanilla bow fires Minecraft's own arrows, from anywhere in your inventory; 4.B fired BTW arrows from the hotbar only. BTW's arrow handling applies to rotten arrows and the composite bow.
- Food stacks to 64 in your inventory, as in Minecraft 1.20.1. 4.B capped food stacks at 16, and BTW's own machines still do.
- Some items that 1.20.1 split into separate items come back as their 1.5.2 equivalent when BTW stores or changes them:
  - black, brown, blue and white dye become ink sac, cocoa beans, lapis lazuli and bone meal;
  - smooth sandstone becomes cut sandstone;
  - stone slabs become smooth stone slabs.
- BTW's own items can only be used from the main hand; 1.5.2 had no off hand.
- JEI support and the port's one-time join note are additions; 4.B had neither.
- Port bugs and their fixes are listed per release in `CHANGELOG.md` in the source repository: https://github.com/ThatSoulyGuy/BetterThanWolves

---

## Known Limitations (Alpha)

This is an ongoing port. The following are in progress:

- Some visual polish (cracked pottery overlay, specific animated textures)
- A handful of FC tile-entity renderers
- A few mob animations and effects: squid tentacles, the cow's kick, villager and witch particles, the composite bow's draw animation
- Network sync timing on some multi-player edge cases
- Full compatibility with every modded dimension is not guaranteed

Bug reports with log files are welcome. "I tried to use X and it didn't drop Y" reports **with a `latest.log`** are the most useful.

---

## Credits

- **FlowerChild** — original author of Better Than Wolves for Minecraft 1.5.2. This port contains his code verbatim. All gameplay design credit belongs to him.
- **Mojang** — Minecraft.
- **MinecraftForge team** — the Forge 1.20.1 modloader.
- **mezz** — JEI.
- **SpongePowered** — Mixin.

---

## License

The port bridge code (the `btw.forge.*` and `btw.modern.*` compatibility layer) is released under its own terms. The BTW content itself remains the property of its original author and is redistributed only to the extent permitted by the original BTW distribution license. If you are the original author and wish redistribution terms to change, please contact the port maintainer.

---

## Links

- **Issue tracker:** https://github.com/ThatSoulyGuy/BetterThanWolves/issues
- **Original BTW:** *TODO (maintainer): add the link to FlowerChild's original Better Than Wolves release. Left blank on purpose rather than guessing a URL.*
- **BTW wiki:** *TODO (maintainer): add the link to the BTW 4.x wiki players should use. Left blank on purpose rather than guessing a URL.*
