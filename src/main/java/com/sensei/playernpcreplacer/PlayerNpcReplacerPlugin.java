/*
 * Copyright (c) 2026, Sensei
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.sensei.playernpcreplacer;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Animation;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.KeyCode;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.Renderable;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.Scene;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.PlayerSpawned;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.NpcID;
import com.sensei.playernpcreplacer.cache.ItemDefinition;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.callback.RenderCallback;
import net.runelite.client.callback.RenderCallbackManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;

/**
 * Shift-right-click another player OR an NPC for a "Replace" submenu. If the
 * target is already replaced, "Remove" appears first to revert it. Below that,
 * "Replace-quick" swaps its model for the top entry of the plugin's "active" NPC
 * list (managed from the side panel's searchable list); every other active NPC
 * is listed right below it by name, so any of them can be picked directly. This
 * is flat (one level of submenu, not nested further) - RuneLite has no precedent
 * anywhere of a submenu entry opening another submenu of its own, and an earlier
 * build that nested one crashed the client as soon as it was hovered.
 * <p>
 * Players and NPCs use two genuinely different mechanisms, because the APIs
 * available for each are genuinely different:
 * <p>
 * <b>Players</b> get a local visual override via
 * {@link net.runelite.api.PlayerComposition#setTransformedNpcId(int)} (the same
 * mechanic the client uses natively for cutscene transformations) - a single
 * field set once. It resets naturally whenever the target despawns and respawns
 * (a fresh Player object always starts untransformed), so the persisted (source
 * name -> replacement) mapping in {@link #playerOverrides} is reapplied on every
 * {@link net.runelite.api.events.PlayerSpawned} for a matching name, the same
 * "survives everything, until manually undone" persistence model
 * {@link #npcOverrides} already has for NPCs. Matching is by name only, the
 * only identifier available for a {@code Player} - a display name change
 * orphans the override, and a different real person who happens to share that
 * name would be matched too; this is an accepted heuristic, not true identity
 * verification.
 * <p>
 * <b>Showing the player's real weapon/shield on top of the npc transform</b> was
 * first attempted via {@link net.runelite.api.PlayerComposition#getEquipmentId}
 * + {@link net.runelite.api.PlayerComposition#createColorTextureOverride}
 * (tried in one version, reverted in the next, after an in-game test confirmed
 * it renders nothing) - {@code createColorTextureOverride} only recolors/
 * retextures whatever's ALREADY rendering in a kit slot (per its own javadoc),
 * and {@code setTransformedNpcId} leaves nothing rendering in those slots to
 * recolor in the first place. A second attempt ({@link
 * PlayerOverride#showEquipment}, self-overrides only) uses a lower-level
 * mechanism instead: directly rewriting
 * {@link net.runelite.api.PlayerComposition#getEquipmentIds()} (the live
 * backing array, not a recolor) plus {@code setHash()} - the same primitive
 * {@code DevToolsPlugin}'s real {@code ::wear} console command uses, and
 * genuinely different from the recolor approach above (mutating the raw
 * source data a composition renders FROM, not asking it to redraw something
 * already decided). Whether this actually differs for the local player's own
 * rendering versus a remote player's is unverified against a live client -
 * see {@link #replace}/{@link #revertPlayer} for how it's applied.
 * <p>
 * <b>A transformed player's idle/walk animations, by contrast, ARE fixable</b> -
 * {@link #npcAnimationSets} captures a replacement npc's own real locomotion
 * animation ids (idle, walk, turn-in-place, run), fetched on demand via
 * {@link #getOrLookupAnimationSet} ({@link NpcCacheAnimationLookup} reads
 * them directly from the client's own live cache connection - no static
 * {@code NPCComposition} field exposes this, same gap as {@link NpcIndex}'s
 * body-type heuristic, but unlike that heuristic this one has a real,
 * reliable answer via the live cache index), then {@link #replace} redirects
 * the transformed player's own idle/walk/turn/run
 * animation FIELDS onto those captured ids instead of just clearing them to
 * -1. This is meaningfully different from the abandoned weapon attempt above:
 * that tried to layer two separately-documented, mutually-incompatible
 * mechanisms together; this reuses fields ({@code setIdlePoseAnimation}/
 * {@code setWalkAnimation}/etc.) this plugin already calls successfully
 * (just with -1 instead of a real target id) - the engine reads pose ids FROM
 * these STANDING configuration fields every tick to resolve the CURRENT pose,
 * so setting them once to the replacement's own correct values should
 * redirect all future resolution, without needing {@link #onClientTick}-style
 * continuous re-assertion the way v32's (also unverified) animation-freeze
 * toggle does.
 * <p>
 * The OVERRIDE animation layer ({@code getAnimation()}, distinct from the
 * idle/walk pose layer above) is a separate problem the captured animation
 * set doesn't solve: a real player's mining/woodcutting/combat/emote actions
 * are all driven by that layer, and those are player-rigged animations with
 * no npc-native equivalent to substitute (most npcs, Thurgo included, simply
 * have no "mining" animation at all). Playing one on a differently-shaped npc
 * model is what visibly warps/glitches it. {@link #onClientTick} suppresses
 * this layer (forces it back to -1) for every enabled player override, every
 * tick - but ONLY while animations are enabled (not paused); while paused,
 * this suppression is deliberately skipped, since the user explicitly wants
 * mismatched action animations to play in that state as a "fun little quirk"
 * (their words) - pausing is already an opt-in "this might look a bit off"
 * state, unlike the normal enabled state where avoiding the glitch is the
 * whole point.
 * <p>
 * <b>NPCs</b> have no equivalent settable field at all - {@code NPC} and
 * {@code NPCComposition} are 100% getters, nothing is mutable. So an NPC
 * replacement is a full client-only clone: a {@link RuneLiteObject} built from
 * the replacement's actual model data ({@link Client#loadModel}, merged and
 * recolored per {@link NPCComposition#getModels()}/{@code getColorToReplace()}),
 * with its position, orientation, and animation re-synced every {@link ClientTick}
 * (not every {@link net.runelite.api.events.GameTick} - see {@link #onClientTick}
 * for why) to track the real NPC. The clone's animation isn't just the
 * source's raw current animation id reused verbatim on the replacement's
 * model, either - {@link #remapAnimationForReplacement} retargets it by
 * classifying which of the SOURCE's own idle/walk/run/turn slots it matches,
 * then substituting the REPLACEMENT's own id for that same slot (when both
 * npcs' animation sets are available - see {@link #getOrLookupAnimationSet}),
 * falling back through a short chain of reasonably-similar alternatives (e.g.
 * a missing turn falls back to the replacement's own plain walk, then idle)
 * when the exact slot is missing rather than showing nothing outright - a
 * real, confirmed case: {@code AnimationID} has entries for a generic human's
 * dedicated {@code HUMAN_TURNONSPOT} but no turn/rotate entry at all among
 * General Graardor's {@code GODWARS_BANDOS_*} ones, so overriding a turning
 * human with Graardor has no exact match to bind to - otherwise a
 * differently-shaped replacement (a dwarf standing in for a
 * human-sized shop npc, say) would play the source's human-shaped locomotion
 * animations on its own differently-shaped model, which is what visibly
 * glitches it. This only covers locomotion (no npc-to-npc equivalent exists
 * to retarget an arbitrary combat/skill override animation to), and is
 * deliberately skipped while an override is paused - pausing an npc override
 * (unlike a player override's pause, which specifically allows mismatched
 * action animations through instead - see the class doc note near
 * {@link #onPlayerSpawned}) freezes the model to fully static instead, a more
 * drastic but more certain fix for the same glitch when remapping either
 * can't apply or still isn't enough.
 * <p>
 * The real NPC IS hidden (unlike an earlier version of this class, which left it
 * visible after discovering the wrong hook for this), via
 * {@link RenderCallbackManager#register}/{@link RenderCallback#drawObject}. This
 * matters because it is NOT the same as the older, more commonly-referenced
 * {@code Hooks.registerRenderableDrawListener}/{@code RenderCallback#addEntity} -
 * that hook's own javadoc explicitly says preventing an entity from being added
 * "removes their clickbox" (confirmed in practice: hiding a target that way made
 * it uninteractable, the same well-known limitation EntityHider has). {@code
 * drawObject}'s javadoc carries no such warning, and real-world precedent (the
 * XRay plugin, which hides NPCs behind an outline-only overlay while keeping them
 * clickable, confirmed by testing) shows it genuinely doesn't remove the click
 * target the way {@code addEntity} does - it only suppresses the geometry. So the
 * clone is drawn in the real NPC's place with the real NPC never actually visible,
 * while clicking that location still resolves to the real, unmodified NPC and its
 * real menu actions - the clone itself has no interaction capability at all
 * ({@link RuneLiteObject} is purely decorative), so it can never be a click target
 * regardless. Menu entries targeting the real NPC get their displayed name
 * rewritten (but not their action verbs - Talk-to/Attack/etc. still work exactly
 * as the real, current NPC would). Because
 * the intent here (per the user - restoring an old, removed NPC like Nieve in
 * place of whoever replaced her) is that the illusion survives logging out, the
 * NPC's region unloading, and the NPC moving, until manually undone - the override
 * is keyed by the real NPC's composition id and persisted, and re-applied
 * automatically via {@link #onNpcSpawned} every single time that npc id
 * (re)spawns, for as long as the override exists.
 */
@Slf4j
@PluginDescriptor(
	name = "Player NPC Replacer",
	description = "Shift-right-click a player for an option to replace their model with an NPC's",
	tags = {"cosmetic", "npc", "player", "replace", "transform", "model", "visual", "sensei"},
	enabledByDefault = false
)
public class PlayerNpcReplacerPlugin extends Plugin
{
	private static final String REPLACE = "Replace";
	private static final String REPLACE_SELF = "Replace-self";
	private static final String REMOVE = "Remove";
	private static final String REPLACE_QUICK = "Replace-quick";
	private static final String CONFIG_GROUP = "playernpcreplacer";
	private static final String ACTIVE_NPCS_KEY = "activeNpcs";
	private static final String NPC_OVERRIDES_KEY = "npcOverrides";
	private static final String PLAYER_OVERRIDES_KEY = "playerOverrides";
	private static final String NPC_ANIMATION_SETS_KEY = "npcAnimationSets";
	private static final Type ACTIVE_NPCS_TYPE = new TypeToken<List<NpcChoice>>()
	{
	}.getType();
	private static final Type NPC_OVERRIDES_TYPE = new TypeToken<Map<Integer, NpcOverride>>()
	{
	}.getType();
	private static final Type PLAYER_OVERRIDES_TYPE = new TypeToken<Map<String, PlayerOverride>>()
	{
	}.getType();
	private static final Type NPC_ANIMATION_SETS_TYPE = new TypeToken<Map<Integer, NpcAnimationSet>>()
	{
	}.getType();
	private static final int MAX_ACTIVE_NPCS = 15;
	private static final NpcChoice FALLBACK_NPC = new NpcChoice(NpcID.MAN, "Man", 1, NpcIndex.TYPE_OTHER, NpcIndex.BODY_HUMAN);
	// Generic, universal human unarmed-combat animations - real, verified
	// AnimationID constants (not derived/guessed). Used by
	// findActionAnimationSubstitute as its middle tier, for any replacement
	// that has no npc-specific candidate of its own: without it, such a
	// replacement just stands there during combat AND skilling.
	// Deliberately NOT restricted by npc body type - an earlier version was,
	// on a since-disproven crash theory, and it silently blocked most npcs
	// (the body-type classifier is a name-keyword heuristic that returns
	// null for anything unmatched). See findActionAnimationSubstitute.
	private static final int[] GENERIC_ATTACK_ANIMATIONS = {AnimationID.HUMAN_UNARMEDPUNCH, AnimationID.HUMAN_UNARMEDKICK};
	private static final int[] GENERIC_DEFEND_ANIMATIONS = {AnimationID.HUMAN_UNARMEDBLOCK, AnimationID.HUMAN_UNARMED_DEF};
	private static final int[] GENERIC_DEATH_ANIMATIONS = {AnimationID.HUMAN_DEATH, AnimationID.HUMAN_DEATH_BACKWARDS};
	// The value PlayerComposition#getTransformedNpcId() reads as "not transformed".
	// Not documented on the interface and no revert precedent exists anywhere in
	// this codebase to confirm it from; -1 matches the "unset" convention used by
	// every other similar field on this API (setIdlePoseAnimation(-1) etc.) and
	// NPC ids are never negative, but this specific value hasn't been verified
	// against a live client - worth confirming Remove actually restores appearance.
	private static final int NO_TRANSFORM = -1;

	@Inject
	private Client client;

	@Inject
	private ConfigManager configManager;

	@Inject
	private Gson gson;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ClientThread clientThread;

	@Inject
	private RenderCallbackManager renderCallbackManager;

	@Inject
	private PlayerNpcReplacerConfig config;

	@Inject
	private NpcCacheAnimationLookup npcCacheAnimationLookup;

	@Inject
	private ItemCacheModelLookup itemCacheModelLookup;

	@Inject
	private AnimationNameIndex animationNameIndex;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private PlayerNpcReplacerHighlightOverlay highlightOverlay;

	private PlayerNpcReplacerPanel panel;
	private NavigationButton navButton;

	// Ordered most-recently-selected first; index 0 is the "quick replace" default.
	// This is the list a settings-panel edit and an in-game menu click both mutate.
	private final List<NpcChoice> activeNpcs = new ArrayList<>();

	// PERSISTED: source npc composition id -> chosen replacement (+ display name
	// and per-entry highlight preference). This is the thing that survives
	// logout/region-unload/respawn - re-applied every time a matching npc id
	// spawns (see onNpcSpawned), not tied to any live object.
	private final Map<Integer, NpcOverride> npcOverrides = new HashMap<>();

	// PERSISTED: source player name -> chosen replacement (+ display name and
	// enabled state). The player equivalent of npcOverrides above - re-applied
	// every time a matching player name spawns (see onPlayerSpawned), not tied
	// to any live object. Replaces the old session-only overriddenPlayerNames
	// tracking this plugin used to have.
	private final Map<String, PlayerOverride> playerOverrides = new HashMap<>();

	// PERSISTED: npc composition id -> its own idle/walk/turn/run animation
	// ids (see NpcAnimationSet's class doc - no static NPCComposition field
	// exposes this, so it's read via NpcCacheAnimationLookup, a live Client
	// API call). Populated on demand by getOrLookupAnimationSet the first
	// time any code actually needs a given npc id's set, not pre-emptively -
	// used by replace() when transforming a player into an npc id (in place
	// of just clearing the player's own pose fields) and by
	// remapAnimationForReplacement (npc-to-npc clone animation retargeting).
	private final Map<Integer, NpcAnimationSet> npcAnimationSets = new HashMap<>();

	// SESSION-ONLY, not persisted (cheap to rebuild - pure reflection, no live
	// lookup involved): replacement npc id -> memoized, ALREADY client.loadAnimation-
	// validated result of AnimationNameIndex#findNearbyActionAnimations for
	// that npc's own walk/idle anchor, so findActionAnimationSubstitute
	// doesn't re-scan ~14,400 AnimationID constants (or repeatedly re-validate
	// loadability) every single client tick. An empty list is itself a valid,
	// cached "checked, found nothing usable" result - no separate
	// negative-cache tracking needed.
	private final Map<Integer, List<Integer>> actionAnimationCandidates = new HashMap<>();

	// SESSION-ONLY, not persisted: player name -> their own real (equipment-
	// driven) animation field values, captured the first time replace() is
	// called for them since their last revert. Lets revertPlayer restore the
	// player's actual correct idle/walk/turn/run ids (e.g. 808/819/etc. for
	// unarmed, but whatever their real weapon-appropriate set is) instead of
	// -1, which left a reverted player's pose unresolved - frozen/glitched -
	// until their next natural equipment change. See replace()/revertPlayer().
	private final Map<String, NpcAnimationSet> capturedPlayerAnimations = new HashMap<>();

	// SESSION-ONLY, not persisted: player name -> their own real equipment ids
	// (a clone of PlayerComposition#getEquipmentIds()), captured the first time
	// replace() is called for them since their last revert - same
	// computeIfAbsent-guarded capture-before-overwrite pattern as
	// capturedPlayerAnimations above, and for the same reason: revertPlayer must
	// be able to restore the real values, since a "hide equipment" self-override
	// (see PlayerOverride#showEquipment) actively rewrites this live, shared
	// array while the override is active.
	private final Map<String, int[]> capturedPlayerEquipmentIds = new HashMap<>();

	// SESSION-ONLY, not persisted: player name -> the last action-animation
	// substitute id onClientTick's player-override loop actually applied via
	// setAnimation() (see findActionAnimationSubstitute).
	//
	// Its ESSENTIAL job is telling "an animation the engine set" apart from
	// "the substitute WE set last tick" - that loop is the only place in this
	// plugin that both reads and writes the same live getAnimation() field
	// (the clone paths read an Actor but write a separate RuneLiteObject, so
	// they can't have this problem). Reading our own output back and treating
	// it as a fresh real animation feeds it into the next substitute pick,
	// and since that pick is Math.floorMod(rawAnimationId, pool.size()), a
	// changed input id yields a different pool entry every tick - a permanent
	// animation-swapping cycle that also reset the frame each time, so
	// nothing ever finished playing. Confirmed live by the user (Graardor,
	// mining). See the loop's own "our own substitute" branch for the full
	// worked example.
	private final Map<String, Integer> lastPlayerActionSubstituteId = new HashMap<>();

	// SESSION-ONLY, not persisted: overridden actor -> the game tick number
	// (see Client#getTickCount()) until which a just-received hitsplat should
	// bias findActionAnimationSubstitute toward a defend/block-flavored
	// candidate instead of picking blindly (see resolveActionContext/
	// onHitsplatApplied). Only ever populated for actors that are actually a
	// Player with a current entry in playerOverrides (self included, since a
	// self-override lives in that same map) - bounded, not every hitsplat in
	// the game gets tracked. Keyed by the live Actor reference itself, same
	// accepted "only meaningfully checked within the next tick or two, never
	// held long-term" pattern activeClones already uses for NPC keys - a
	// pooled-slot reuse landing on the exact wrong actor within that same
	// narrow window is an accepted, negligible edge case, not one this plugin
	// guards against elsewhere either.
	private final Map<Actor, Integer> recentlyHitUntilTick = new HashMap<>();

	// SESSION-ONLY, not persisted: the local player's own display name, cached
	// from onPlayerSpawned (client-thread) so the Swing EDT (panel cell
	// renderers) can cheaply check "is this override row me" without touching
	// Client directly off the client thread. Null until the local player has
	// spawned at least once this session.
	@Nullable
	private volatile String localPlayerName;

	// SESSION-ONLY: real, currently-spawned NPC -> its active clone. An NPC object
	// is a live view over an array-backed pool the client reuses every tick (same
	// caveat as Player), so this is only ever touched within a single spawn-to-
	// despawn lifetime; onNpcDespawned removes the entry the moment that ends.
	private final Map<NPC, NpcClone> activeClones = new HashMap<>();

	// SESSION-ONLY: the local player's own PlayerOverride#showEquipment
	// self-clone, if one is currently active. Nullable rather than a map -
	// there's exactly one local player. See SelfClone's class doc.
	@Nullable
	private SelfClone selfClone;

	// Built models are cached by replacement npc id (not per-instance) so
	// replacing several simultaneous NPCs with the same choice, or re-spawning
	// after a region reload, doesn't repeatedly rebuild the same model.
	private final Map<Integer, Model> modelCache = new HashMap<>();

	// Suppresses the real NPC's geometry (see the class doc for why this specific
	// hook, and not Hooks.registerRenderableDrawListener, is what keeps it
	// clickable). Mirrors the exact instanceof-unwrap pattern the real-world XRay
	// plugin uses - NPCs come through drawObject wrapped as a GameObject whose
	// getRenderable() is the actual NPC, not as a bare NPC/TileObject.
	private final RenderCallback renderCallback = new RenderCallback()
	{
		@Override
		public boolean drawObject(Scene scene, TileObject object)
		{
			if (object instanceof GameObject)
			{
				final Renderable renderable = ((GameObject) object).getRenderable();
				if (renderable instanceof NPC && activeClones.containsKey(renderable))
				{
					return false;
				}
			}
			return true;
		}

		// Hides the real local player's own geometry while a self-clone stands
		// in for them - unlike NPCs (drawObject above), a Player never comes
		// through drawObject wrapped as a TileObject, so drawObject's
		// clickbox-preserving trick doesn't apply here at all; addEntity is the
		// only hook that covers players (its own javadoc explicitly lists
		// "players, npcs, projectiles, spotanims" as what it covers). Losing
		// the local player's own clickbox while self-cloned (other players
		// right-clicking you) is an accepted tradeoff, not a concern this
		// plugin's NPC-hiding code had to solve around.
		@Override
		public boolean addEntity(Renderable renderable, boolean ui)
		{
			// Requires the clone to be REGISTERED, not merely to exist. If it
			// isn't in the scene it draws nothing, and hiding the player on
			// top of that leaves the user genuinely invisible rather than
			// replaced - confirmed live after teleporting, where a scene
			// rebuild can drop the object's registration without the plugin
			// being told. Tying the hide to "something is actually being
			// drawn in your place" makes that failure mode structurally
			// impossible: worst case you briefly look like yourself.
			if (selfClone != null && selfClone.getObject().isActive()
				&& renderable == client.getLocalPlayer())
			{
				return false;
			}
			return true;
		}
	};

	@Provides
	PlayerNpcReplacerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(PlayerNpcReplacerConfig.class);
	}

	@Override
	protected void startUp()
	{
		activeNpcs.clear();
		// ConfigManager's generic getConfiguration(group, key, Type) overload only
		// has special-cased handling for Set<...> among parameterized types (see its
		// source) - a List falls through every case and, on the write side, falls
		// through to Object.toString() instead of JSON, and on the read side gets
		// returned as a plain String, throwing a ClassCastException at this call
		// site's compiler-inserted checkcast. So this (de)serializes via Gson
		// directly against the raw String config overloads instead, matching the
		// pattern used elsewhere in the codebase (GrandExchangePlugin etc.) for the
		// same reason. Wrapped in try/catch to self-heal past any already-corrupted
		// value stored by an earlier, broken build of this plugin.
		final String json = configManager.getConfiguration(CONFIG_GROUP, ACTIVE_NPCS_KEY);
		if (json != null)
		{
			try
			{
				final List<NpcChoice> saved = gson.fromJson(json, ACTIVE_NPCS_TYPE);
				if (saved != null)
				{
					activeNpcs.addAll(saved);
				}
			}
			catch (Exception ex)
			{
				log.warn("Discarding unreadable {}.{} config value", CONFIG_GROUP, ACTIVE_NPCS_KEY, ex);
			}
		}

		npcOverrides.clear();
		final String npcJson = configManager.getConfiguration(CONFIG_GROUP, NPC_OVERRIDES_KEY);
		if (npcJson != null)
		{
			try
			{
				final Map<Integer, NpcOverride> saved = gson.fromJson(npcJson, NPC_OVERRIDES_TYPE);
				if (saved != null)
				{
					// Gson doesn't error on a shape mismatch, it just leaves missing
					// fields null - so entries stored under the old (pre-NpcOverride)
					// shape deserialize "successfully" here with sourceName/replacement
					// both null, only to NPE later (e.g. sorting by sourceName in
					// getNpcOverrides). Drop anything that didn't come through intact
					// rather than let corrupted/legacy entries crash the plugin.
					for (Map.Entry<Integer, NpcOverride> e : saved.entrySet())
					{
						final NpcOverride override = e.getValue();
						if (override != null && override.getSourceName() != null && override.getReplacement() != null)
						{
							npcOverrides.put(e.getKey(), override);
						}
						else
						{
							log.warn("Discarding malformed {}.{} entry for npc id {}", CONFIG_GROUP, NPC_OVERRIDES_KEY, e.getKey());
						}
					}
				}
			}
			catch (Exception ex)
			{
				log.warn("Discarding unreadable {}.{} config value", CONFIG_GROUP, NPC_OVERRIDES_KEY, ex);
			}
		}

		playerOverrides.clear();
		final String playerJson = configManager.getConfiguration(CONFIG_GROUP, PLAYER_OVERRIDES_KEY);
		if (playerJson != null)
		{
			try
			{
				final Map<String, PlayerOverride> saved = gson.fromJson(playerJson, PLAYER_OVERRIDES_TYPE);
				if (saved != null)
				{
					// Same defensive validation as npcOverrides above - a shape
					// mismatch deserializes "successfully" with null fields rather
					// than throwing, so drop anything that didn't come through
					// intact rather than let a corrupted entry crash the plugin later.
					for (Map.Entry<String, PlayerOverride> e : saved.entrySet())
					{
						final PlayerOverride override = e.getValue();
						if (override != null && override.getSourceName() != null && override.getReplacement() != null)
						{
							playerOverrides.put(e.getKey(), override);
						}
						else
						{
							log.warn("Discarding malformed {}.{} entry for player {}", CONFIG_GROUP, PLAYER_OVERRIDES_KEY, e.getKey());
						}
					}
				}
			}
			catch (Exception ex)
			{
				log.warn("Discarding unreadable {}.{} config value", CONFIG_GROUP, PLAYER_OVERRIDES_KEY, ex);
			}
		}

		npcAnimationSets.clear();
		final String animJson = configManager.getConfiguration(CONFIG_GROUP, NPC_ANIMATION_SETS_KEY);
		if (animJson != null)
		{
			try
			{
				final Map<Integer, NpcAnimationSet> saved = gson.fromJson(animJson, NPC_ANIMATION_SETS_TYPE);
				if (saved != null)
				{
					// NpcAnimationSet has no reference-type fields to be null after
					// a shape mismatch (all 8 fields are primitive ints), so there's
					// nothing to validate the way the Override types above need -
					// a corrupted/legacy entry just deserializes with default 0s,
					// which is a harmless (if slightly wrong-looking) animation id
					// rather than something that can NPE downstream.
					npcAnimationSets.putAll(saved);
				}
			}
			catch (Exception ex)
			{
				log.warn("Discarding unreadable {}.{} config value", CONFIG_GROUP, NPC_ANIMATION_SETS_KEY, ex);
			}
		}

		panel = injector.getInstance(PlayerNpcReplacerPanel.class);
		navButton = NavigationButton.builder()
			.tooltip("Player NPC Replacer")
			.icon(buildIcon())
			.priority(10)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		clientThread.invokeLater(() -> renderCallbackManager.register(renderCallback));
		overlayManager.add(highlightOverlay);
	}

	@Override
	protected void shutDown()
	{
		// navButton is only assigned after the panel is successfully constructed
		// in startUp() - if that threw (e.g. the panel's own constructor failing),
		// shutDown() still runs during cleanup and would NPE here on a null button
		// otherwise, masking whatever the real startup error was.
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
		}

		overlayManager.remove(highlightOverlay);

		clientThread.invoke(() -> renderCallbackManager.unregister(renderCallback));

		for (NpcClone clone : activeClones.values())
		{
			clone.getObject().setActive(false);
		}
		activeClones.clear();
		// Unlike a plain setTransformedNpcId player override (a property of the
		// real, still-interactable player entity, nothing synthetic to tear
		// down), selfClone IS a RuneLiteObject this plugin owns, same as every
		// entry in activeClones was.
		teardownSelfClone();
		modelCache.clear();
		// npcOverrides/playerOverrides intentionally kept in memory (though
		// harmless either way, since startUp reloads them from config) - the
		// persisted mappings are what need to survive, and they already do via
		// config regardless.
	}

	/** Read-only snapshot for the panel to render. */
	List<NpcChoice> getActiveNpcs()
	{
		return Collections.unmodifiableList(activeNpcs);
	}

	/** Adds (or promotes, if already present) an NPC to the front of the active list. */
	void selectNpc(NpcChoice choice)
	{
		activeNpcs.removeIf(c -> c.getId() == choice.getId());
		activeNpcs.add(0, choice);
		while (activeNpcs.size() > MAX_ACTIVE_NPCS)
		{
			activeNpcs.remove(activeNpcs.size() - 1);
		}
		persistActiveNpcs();
		ensureNpcAnimationSetCaptured(choice.getId());
	}

	/**
	 * Bulk version of {@link #selectNpc} for the panel's "add random NPCs"
	 * tool - adds every choice to the front (same de-dupe-then-promote
	 * behavior, applied per choice, in list order so the LAST entry in
	 * {@code choices} ends up frontmost) and evicts down to {@link
	 * #MAX_ACTIVE_NPCS} once at the end, rather than after every individual
	 * add. Single persist/refresh-worthy pass instead of N separate ones -
	 * still calls {@link #ensureNpcAnimationSetCaptured} per choice
	 * afterward, same as {@link #selectNpc} does for a single one.
	 */
	void selectNpcs(List<NpcChoice> choices)
	{
		for (NpcChoice choice : choices)
		{
			activeNpcs.removeIf(c -> c.getId() == choice.getId());
			activeNpcs.add(0, choice);
		}
		while (activeNpcs.size() > MAX_ACTIVE_NPCS)
		{
			activeNpcs.remove(activeNpcs.size() - 1);
		}
		persistActiveNpcs();
		for (NpcChoice choice : choices)
		{
			ensureNpcAnimationSetCaptured(choice.getId());
		}
	}

	/** Clears the entire active list - the panel's "replace with N random" tool calls this before {@link #selectNpcs} to start from empty. */
	void clearActiveNpcs()
	{
		activeNpcs.clear();
		persistActiveNpcs();
	}

	void removeNpc(NpcChoice choice)
	{
		activeNpcs.removeIf(c -> c.getId() == choice.getId());
		persistActiveNpcs();
	}

	/** Swaps an entry with the one directly above it (index - 1), if possible. */
	void moveUp(NpcChoice choice)
	{
		final int i = indexOf(choice);
		if (i > 0)
		{
			Collections.swap(activeNpcs, i, i - 1);
			persistActiveNpcs();
		}
	}

	/** Swaps an entry with the one directly below it (index + 1), if possible. */
	void moveDown(NpcChoice choice)
	{
		final int i = indexOf(choice);
		if (i >= 0 && i < activeNpcs.size() - 1)
		{
			Collections.swap(activeNpcs, i, i + 1);
			persistActiveNpcs();
		}
	}

	private int indexOf(NpcChoice choice)
	{
		for (int i = 0; i < activeNpcs.size(); i++)
		{
			if (activeNpcs.get(i).getId() == choice.getId())
			{
				return i;
			}
		}
		return -1;
	}

	private void persistActiveNpcs()
	{
		configManager.setConfiguration(CONFIG_GROUP, ACTIVE_NPCS_KEY, gson.toJson(activeNpcs));
	}

	private void persistNpcOverrides()
	{
		configManager.setConfiguration(CONFIG_GROUP, NPC_OVERRIDES_KEY, gson.toJson(npcOverrides));
	}

	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		if (!client.isKeyPressed(KeyCode.KC_SHIFT))
		{
			return;
		}

		final Player localPlayer = client.getLocalPlayer();

		// Collect each distinct other player targeted by any entry in this menu
		// (there's no single-fire "Examine"-style option for players to key off,
		// unlike objects/NPCs, so entries have to be scanned and deduped by hand).
		// The existing entry's target string is reused so our option gets the same
		// coloured name formatting as the game's own player options.
		final Map<Player, String> targets = new LinkedHashMap<>();
		for (MenuEntry entry : event.getMenuEntries())
		{
			final Player player = entry.getPlayer();
			if (player == null || player == localPlayer)
			{
				continue;
			}

			targets.putIfAbsent(player, entry.getTarget());
		}

		// Same dedup approach for NPCs targeted by any entry in this menu.
		final Map<NPC, String> npcTargets = new LinkedHashMap<>();
		for (MenuEntry entry : event.getMenuEntries())
		{
			final NPC npc = entry.getNpc();
			if (npc != null)
			{
				npcTargets.putIfAbsent(npc, entry.getTarget());
			}
		}

		// There's no menu-entry equivalent of entry.getPlayer()/getNpc() for
		// "the player themselves" to key off, since a player can't be
		// right-clicked (there's no menu entry targeting them at all) - so
		// self-replace is offered whenever this is a genuine game-world click
		// instead, signalled by the native "Walk here" entry every ground-tile
		// right-click menu carries (MenuAction.WALK). This coexists fine with
		// the player-/npc-target loops below - shift-right-clicking near an
		// overridable player or npc still gets "Walk here" bundled into the
		// same menu, so "Replace-self" simply appears alongside "Replace" in
		// that case too, rather than instead of it.
		boolean canReplaceSelf = false;
		for (MenuEntry entry : event.getMenuEntries())
		{
			if (entry.getType() == MenuAction.WALK)
			{
				canReplaceSelf = true;
				break;
			}
		}
		final String localPlayerNameForSelf = localPlayer == null ? null : localPlayer.getName();
		if (localPlayerNameForSelf == null)
		{
			canReplaceSelf = false;
		}

		if (targets.isEmpty() && npcTargets.isEmpty() && !canReplaceSelf)
		{
			return;
		}

		int idx = -1;

		if (canReplaceSelf)
		{
			final MenuEntry parent = client.getMenu().createMenuEntry(idx--)
				.setOption(REPLACE_SELF)
				.setType(MenuAction.RUNELITE);
			final Menu replaceMenu = parent.createSubMenu();

			if (playerOverrides.containsKey(localPlayerNameForSelf))
			{
				replaceMenu.createMenuEntry(0)
					.setOption(REMOVE)
					.setType(MenuAction.RUNELITE)
					.onClick(click -> removePlayerOverride(localPlayerNameForSelf));
			}

			replaceMenu.createMenuEntry(0)
				.setOption(REPLACE_QUICK)
				.setType(MenuAction.RUNELITE)
				.onClick(click -> applySelfOverrideFromMenu(defaultNpc()));

			if (activeNpcs.isEmpty())
			{
				replaceMenu.createMenuEntry(0)
					.setOption("No NPCs selected - open the settings panel")
					.setType(MenuAction.RUNELITE)
					.onClick(click -> {});
			}
			else
			{
				for (NpcChoice choice : activeNpcs)
				{
					replaceMenu.createMenuEntry(0)
						.setOption(choice.getName())
						.setType(MenuAction.RUNELITE)
						.onClick(click -> applySelfOverrideFromMenu(choice));
				}
			}
		}

		for (Map.Entry<Player, String> e : targets.entrySet())
		{
			final Player player = e.getKey();
			final WorldView playerWorldView = player.getWorldView();
			if (playerWorldView == null)
			{
				continue;
			}

			// Capture a stable slot reference (world view + index + expected name)
			// rather than the Player object itself. Between opening this menu and
			// actually clicking an option, the target can walk out of render range
			// and despawn - its Player object becomes stale, and the underlying
			// slot can even be reused by a different entity. Calling a mutating
			// method like setTransformedNpcId on a stale/reused Player is not just
			// a wrong-target bug, it can crash the client. onClick always re-resolves
			// a fresh Player from the slot and verifies identity before touching it.
			final int worldViewId = playerWorldView.getId();
			final int playerIndex = player.getId();
			final String playerName = player.getName();

			final MenuEntry parent = client.getMenu().createMenuEntry(idx--)
				.setOption(REPLACE)
				.setTarget(e.getValue())
				.setType(MenuAction.RUNELITE);
			final Menu replaceMenu = parent.createSubMenu();

			// Unlike the old session-only tracking, playerOverrides is the single
			// source of truth and is never stale - it's re-applied on every
			// PlayerSpawned for a matching name (see onPlayerSpawned), the same
			// reasoning npcOverrides already relies on below (no extra live
			// composition-state check needed).
			if (playerOverrides.containsKey(playerName))
			{
				replaceMenu.createMenuEntry(0)
					.setOption(REMOVE)
					.setType(MenuAction.RUNELITE)
					.onClick(click -> removePlayerOverride(playerName));
			}

			replaceMenu.createMenuEntry(0)
				.setOption(REPLACE_QUICK)
				.setType(MenuAction.RUNELITE)
				.onClick(click -> applyPlayerOverride(playerName, worldViewId, playerIndex, defaultNpc()));

			if (activeNpcs.isEmpty())
			{
				replaceMenu.createMenuEntry(0)
					.setOption("No NPCs selected - open the settings panel")
					.setType(MenuAction.RUNELITE)
					.onClick(click -> {});
			}
			else
			{
				for (NpcChoice choice : activeNpcs)
				{
					replaceMenu.createMenuEntry(0)
						.setOption(choice.getName())
						.setType(MenuAction.RUNELITE)
						.onClick(click -> applyPlayerOverride(playerName, worldViewId, playerIndex, choice));
				}
			}
		}

		for (Map.Entry<NPC, String> e : npcTargets.entrySet())
		{
			final NPC npc = e.getKey();
			final WorldView npcWorldView = npc.getWorldView();
			if (npcWorldView == null)
			{
				continue;
			}

			// Same slot-based re-resolve approach as players, and for the same
			// reason (a captured NPC reference can go stale between menu-open and
			// click - here that's even more likely, since replaced NPCs are exactly
			// the ones intended to eventually walk off / despawn / reload).
			final int worldViewId = npcWorldView.getId();
			final int npcIndex = npc.getIndex();
			final int sourceNpcId = npc.getId();
			final String sourceNpcName = npc.getName();

			final MenuEntry parent = client.getMenu().createMenuEntry(idx--)
				.setOption(REPLACE)
				.setTarget(e.getValue())
				.setType(MenuAction.RUNELITE);
			final Menu replaceMenu = parent.createSubMenu();

			// Unlike players, this doesn't need a live "still showing it" check -
			// npcOverrides is the single source of truth for "is this npc id
			// currently overridden", and it's never stale (only WE ever populate or
			// clear it, and it applies uniformly to every spawn of that npc id).
			if (npcOverrides.containsKey(sourceNpcId))
			{
				replaceMenu.createMenuEntry(0)
					.setOption(REMOVE)
					.setType(MenuAction.RUNELITE)
					.onClick(click -> removeNpcOverrideById(sourceNpcId));
			}

			replaceMenu.createMenuEntry(0)
				.setOption(REPLACE_QUICK)
				.setType(MenuAction.RUNELITE)
				.onClick(click -> applyNpcOverride(sourceNpcId, sourceNpcName, worldViewId, npcIndex, defaultNpc()));

			if (activeNpcs.isEmpty())
			{
				replaceMenu.createMenuEntry(0)
					.setOption("No NPCs selected - open the settings panel")
					.setType(MenuAction.RUNELITE)
					.onClick(click -> {});
			}
			else
			{
				for (NpcChoice choice : activeNpcs)
				{
					replaceMenu.createMenuEntry(0)
						.setOption(choice.getName())
						.setType(MenuAction.RUNELITE)
						.onClick(click -> applyNpcOverride(sourceNpcId, sourceNpcName, worldViewId, npcIndex, choice));
				}
			}
		}
	}

	private NpcChoice defaultNpc()
	{
		return activeNpcs.isEmpty() ? FALLBACK_NPC : activeNpcs.get(0);
	}

	/**
	 * Persists the override (so it re-applies on every future spawn of a player
	 * with this name, forever, until removed - see {@link #onPlayerSpawned}) and,
	 * if that player happens to still be present right now, applies the
	 * transform immediately too. Always (re)enables the override, mirroring
	 * {@link #applyNpcOverride}'s reasoning exactly - picking Replace from the
	 * in-game menu is an explicit "make this happen now" action. Re-resolves the
	 * player from their world view slot (rather than trusting a captured
	 * reference) and only proceeds if someone is still there AND it's still the
	 * same player by name - guards against the target having despawned or their
	 * slot having been reused by someone else since the menu opened (see the
	 * class doc's stale-Actor-reference warning).
	 * <p>
	 * The animations-disabled and show-equipment preferences carry over from
	 * any existing override on this player name ("cosmetic preference, no
	 * reason to reset on re-apply", same as {@link
	 * NpcOverride#isAnimationsDisabled()} in {@link #applyNpcOverride}) - but
	 * ONLY when the new replacement is still player-shaped (see {@link
	 * NpcIndex#isPlayerShaped}). Both settings are inherently player-oriented:
	 * "paused" means borrowing the PLAYER's own animations, and show-equipment
	 * layers PLAYER-rigged item models on top. Carrying either onto a
	 * non-humanoid or larger-than-1x1 replacement reliably produces the
	 * glitchy visuals they were only ever meant to be an opt-in for, so those
	 * reset to the safe defaults instead. An override should look right
	 * immediately on being applied, without the user first having to discover
	 * and undo a setting inherited from some unrelated earlier npc.
	 */
	private void applyPlayerOverride(String playerName, int worldViewId, int playerIndex, NpcChoice replacement)
	{
		final PlayerOverride existing = playerOverrides.get(playerName);
		final boolean playerShaped = NpcIndex.isPlayerShaped(replacement);
		final boolean animationsDisabled = playerShaped && existing != null && existing.isAnimationsDisabled();
		final boolean showEquipment = playerShaped && existing != null && existing.isShowEquipment();
		playerOverrides.put(playerName, new PlayerOverride(playerName, replacement, true, animationsDisabled, showEquipment));
		persistPlayerOverrides();
		pushPlayerOverridesRefresh();

		final WorldView worldView = client.getWorldView(worldViewId);
		if (worldView == null)
		{
			return;
		}

		final Player player = worldView.players().byIndex(playerIndex);
		if (player == null || !Objects.equals(player.getName(), playerName))
		{
			return;
		}

		replace(player, replacement);
	}

	/**
	 * Enables or disables a specific player override without deleting the
	 * mapping, mirroring {@link #setOverrideEnabled}'s NPC equivalent exactly
	 * (including being dispatched via {@link #clientThread} and refreshing from
	 * inside the queued task, for the same EDT-vs-client-thread reasons - a
	 * {@code Player}'s composition is client-thread-owned state just like
	 * {@link #activeClones} is). Takes effect immediately: scans every
	 * currently-present player for a name match and applies or reverts the
	 * transform right now, rather than waiting for their next spawn.
	 */
	void setPlayerOverrideEnabled(String playerName, boolean enabled)
	{
		clientThread.invoke(() ->
		{
			final PlayerOverride existing = playerOverrides.get(playerName);
			if (existing == null)
			{
				return;
			}
			playerOverrides.put(playerName, new PlayerOverride(playerName, existing.getReplacement(), enabled, existing.isAnimationsDisabled(), existing.isShowEquipment()));
			persistPlayerOverrides();

			final Player player = findPresentPlayer(playerName);
			if (player != null)
			{
				if (enabled)
				{
					replace(player, existing.getReplacement());
				}
				else
				{
					revertPlayer(player);
				}
			}
			pushPlayerOverridesRefresh();
		});
	}

	/**
	 * Enables or disables freezing this override's animations, mirroring
	 * {@link #setAnimationsEnabled}'s NPC equivalent structurally (dispatched
	 * via {@link #clientThread}, refreshes from inside the queued task). Unlike
	 * that NPC method, no immediate forced action is needed here: the freeze
	 * itself happens continuously in {@link #onClientTick} for every currently
	 * enabled-and-animations-disabled override, so toggling this flag off just
	 * means that per-tick loop stops targeting this player starting next tick -
	 * the native engine resumes its own pose resolution on its own, with
	 * nothing for this plugin to explicitly "restore".
	 */
	void setPlayerAnimationsEnabled(String playerName, boolean enabled)
	{
		clientThread.invoke(() ->
		{
			final PlayerOverride existing = playerOverrides.get(playerName);
			if (existing == null)
			{
				return;
			}
			playerOverrides.put(playerName, new PlayerOverride(playerName, existing.getReplacement(), existing.isEnabled(), !enabled, existing.isShowEquipment()));
			persistPlayerOverrides();
			// Same forced-resync reasoning as replace()'s: un-pausing mid-action
			// would otherwise read back whatever substitute was left in the
			// animation field, match it, and take the "leave it alone" branch
			// instead of recomputing for the now-unpaused state.
			lastPlayerActionSubstituteId.remove(playerName);
			pushPlayerOverridesRefresh();
		});
	}

	/**
	 * Toggles {@link PlayerOverride#showEquipment} for an existing override and
	 * immediately re-applies via {@link #replace}, mirroring {@link
	 * #setUseOriginalAnimations}'s "force an immediate resync" structure rather
	 * than {@link #setPlayerAnimationsEnabled}'s "let the next natural tick
	 * catch up" one - unlike the animation freeze loop, there's no per-tick
	 * code that separately re-checks this flag, so without an explicit
	 * re-{@code replace} call here the equipment array would stay in whatever
	 * state it was left in until the player's next unrelated spawn. The panel
	 * only exposes this control for the local player's own row (see {@link
	 * #isLocalPlayerOverride}), but the method itself doesn't special-case who
	 * it's called for - same reasoning as {@link PlayerOverride#showEquipment}'s
	 * doc for why the underlying mechanism isn't self-only.
	 */
	void setPlayerShowEquipment(String playerName, boolean show)
	{
		clientThread.invoke(() ->
		{
			final PlayerOverride existing = playerOverrides.get(playerName);
			if (existing == null)
			{
				return;
			}
			playerOverrides.put(playerName, new PlayerOverride(playerName, existing.getReplacement(), existing.isEnabled(), existing.isAnimationsDisabled(), show));
			persistPlayerOverrides();

			final Player player = findPresentPlayer(playerName);
			if (player != null)
			{
				replace(player, existing.getReplacement());
			}
			pushPlayerOverridesRefresh();
		});
	}

	/**
	 * Un-persists the override entirely and reverts the player back to their
	 * real appearance if they're currently present, mirroring
	 * {@link #removeNpcOverrideById}'s NPC equivalent (including the
	 * {@link #clientThread} dispatch and pushing the refresh from inside the
	 * queued task). Works equally whether triggered from the in-game menu or the
	 * panel's list, since it looks the player up by name rather than requiring a
	 * live slot reference.
	 */
	void removePlayerOverride(String playerName)
	{
		clientThread.invoke(() ->
		{
			playerOverrides.remove(playerName);
			persistPlayerOverrides();

			final Player player = findPresentPlayer(playerName);
			if (player != null)
			{
				revertPlayer(player);
			}
			pushPlayerOverridesRefresh();
		});
	}

	/**
	 * Scans the top-level world view for a currently-present player with this
	 * exact name. Player names are unique among real players at any given
	 * moment, so the first match is the only match.
	 */
	@Nullable
	private Player findPresentPlayer(String playerName)
	{
		final WorldView worldView = client.getTopLevelWorldView();
		if (worldView == null)
		{
			return null;
		}
		for (Player player : worldView.players())
		{
			if (player != null && Objects.equals(player.getName(), playerName))
			{
				return player;
			}
		}
		return null;
	}

	/**
	 * If this npc id's own real locomotion animation ids are available (see
	 * {@link #getOrLookupAnimationSet}), redirect the player's own
	 * idle/walk/turn/run animation FIELDS onto them - not just the single
	 * current-frame override cleared below, but the actual standing
	 * configuration the engine resolves a pose FROM every tick - so the
	 * transformed player idles/walks using the replacement npc's own correct
	 * animations instead of continuing to resolve from the player's own
	 * (potentially mismatched) equipment-derived ones. Falls back to the
	 * original -1 clear on the rare id with no cache definition available at
	 * all.
	 * <p>
	 * Captures the player's own current field values into
	 * {@link #capturedPlayerAnimations} first, but only if nothing's captured
	 * for them yet ({@code computeIfAbsent}) - this can run again on an
	 * already-transformed player (e.g. picking a different replacement while
	 * an override is already active, or a bulk re-apply), and at that point
	 * the live fields are the PREVIOUS replacement's, not the player's real
	 * ones, so only the very first capture since their last {@link
	 * #revertPlayer} is trustworthy. On a genuine fresh spawn this is exactly
	 * right too: the client repopulates a player's pose fields from their real
	 * equipment before {@link #onPlayerSpawned} fires, so "whatever's there
	 * right now" is correct in that case as well.
	 * <p>
	 * Also captures the player's real {@link PlayerComposition#getEquipmentIds()}
	 * into {@link #capturedPlayerEquipmentIds} unconditionally, the same
	 * computeIfAbsent-guarded way, regardless of whether {@code showEquipment}
	 * is actually set - both the show and hide paths below actively rewrite
	 * this live, shared array, so a trustworthy original must always be on hand
	 * for {@link #revertPlayer} to restore, even on an override that currently
	 * has equipment hidden.
	 * <p>
	 * For the LOCAL player specifically, when {@link PlayerOverride#showEquipment}
	 * is set, none of the above runs at all - this routes entirely to {@link
	 * #applySelfClone} instead, since {@code setTransformedNpcId} was confirmed
	 * in-game to render no equipment no matter what's written into the
	 * composition's equipment array (see {@link #buildSelfCloneModel}'s doc for
	 * the actual mechanism that replaces it). {@link #revertTransformOnly} runs
	 * first in that branch so a player previously shown via the plain transform
	 * (before toggling "show equipment" on) doesn't stay transformed underneath
	 * the clone.
	 */
	private void replace(Player player, NpcChoice choice)
	{
		final String name = player.getName();
		final PlayerOverride override = playerOverrides.get(name);
		final boolean showEquipment = override != null && override.isShowEquipment();

		if (showEquipment && player == client.getLocalPlayer())
		{
			revertTransformOnly(player);
			applySelfClone(choice);
			return;
		}

		// Only tear the self-clone down when THIS call is about the local
		// player (i.e. they're genuinely switching from clone-mode to the
		// plain transform). This used to be unconditional, which meant every
		// replace() for ANY OTHER player destroyed the local player's own
		// equipment clone - and onPlayerSpawned calls replace() for every
		// overridden player each time they walk into render range, so with
		// bulk overrides applied it fired constantly. Combined with the
		// per-tick transform re-assert, the local player would then be
		// re-transformed the plain way, which is exactly the confirmed-live
		// symptom: equipment shows briefly, then "reverts back to without
		// having the equipment".
		if (player == client.getLocalPlayer())
		{
			teardownSelfClone();
		}

		capturedPlayerAnimations.computeIfAbsent(name, n -> new NpcAnimationSet(
			player.getIdlePoseAnimation(), player.getWalkAnimation(),
			player.getIdleRotateLeft(), player.getIdleRotateRight(),
			player.getWalkRotateLeft(), player.getWalkRotateRight(),
			player.getWalkRotate180(), player.getRunAnimation()));
		final PlayerComposition composition = player.getPlayerComposition();
		final int[] capturedEquipmentIds = capturedPlayerEquipmentIds.computeIfAbsent(
			name, n -> composition.getEquipmentIds().clone());

		composition.setTransformedNpcId(choice.getId());

		final NpcAnimationSet animationSet = getOrLookupAnimationSet(choice.getId());
		if (animationSet != null)
		{
			player.setIdlePoseAnimation(animationSet.getIdlePoseAnimation());
			player.setWalkAnimation(animationSet.getWalkAnimation());
			player.setIdleRotateLeft(animationSet.getIdleRotateLeft());
			player.setIdleRotateRight(animationSet.getIdleRotateRight());
			player.setWalkRotateLeft(animationSet.getWalkRotateLeft());
			player.setWalkRotateRight(animationSet.getWalkRotateRight());
			player.setWalkRotate180(animationSet.getWalkRotate180());
			player.setRunAnimation(animationSet.getRunAnimation());
		}
		else
		{
			// Player-specific animations don't exist on the NPC model; clearing
			// pose animations avoids a glitched pose until the next natural
			// animation change.
			player.setIdlePoseAnimation(-1);
		}
		player.setPoseAnimation(-1);

		final int[] equipmentIds = composition.getEquipmentIds();
		if (showEquipment)
		{
			// Only reachable for a non-local player (see the branch above) -
			// kept for symmetry/no-crash rather than any expectation it does
			// anything visible, since this exact array-rewrite was the
			// approach already confirmed not to work for the local player
			// either, before the self-clone path replaced it.
			System.arraycopy(capturedEquipmentIds, 0, equipmentIds, 0, capturedEquipmentIds.length);
		}
		else
		{
			Arrays.fill(equipmentIds, 0);
		}
		composition.setHash();

		// Force onClientTick to recompute this player's action-layer
		// substitute on its next pass, exactly like applyClone/applySelfClone
		// do via NpcClone/SelfClone#setLastAnimationId(Integer.MIN_VALUE) -
		// the plain player path is the one that never had this, and needed it
		// for the same reason.
		//
		// Without it, applying an override to a player who is ALREADY
		// mid-action leaves them on a stale animation: the tick loop reads
		// getAnimation(), sees the substitute a PREVIOUS override already
		// wrote there, matches it against this same map, and takes the
		// "that's our own substitute, leave it completely alone" branch - so
		// it never switches to the new replacement's animation at all, and
		// the old npc's animation keeps playing until the engine happens to
		// set a fresh one on its own.
		lastPlayerActionSubstituteId.remove(name);
	}

	/**
	 * The {@code setTransformedNpcId}-path half of {@link #revertPlayer} -
	 * restores whatever {@link #replace}'s transform branch actually touched
	 * (the transform itself, pose/animation fields, equipment array) back to
	 * the player's real captured values. Guarded on {@link
	 * #capturedPlayerAnimations} actually having an entry for this player - a
	 * player who was ONLY ever shown via {@link #applySelfClone} never had any
	 * of this touched in the first place, so running it unconditionally would
	 * wrongly force their real, untouched animation fields to -1 (the same
	 * frozen-pose bug this capture/restore approach exists to avoid
	 * elsewhere in this class).
	 */
	private void revertTransformOnly(Player player)
	{
		final String name = player.getName();
		if (!capturedPlayerAnimations.containsKey(name))
		{
			return;
		}

		final PlayerComposition composition = player.getPlayerComposition();
		composition.setTransformedNpcId(NO_TRANSFORM);

		// Clears any action-animation substitute onClientTick's player-override
		// loop might have left playing (see findActionAnimationSubstitute) -
		// otherwise the just-reverted, real player could keep visibly playing
		// a borrowed npc animation until the native engine happens to
		// overwrite it on its own.
		if (lastPlayerActionSubstituteId.remove(name) != null)
		{
			player.setAnimation(-1);
		}

		final int[] capturedEquipmentIds = capturedPlayerEquipmentIds.remove(name);
		if (capturedEquipmentIds != null)
		{
			System.arraycopy(capturedEquipmentIds, 0, composition.getEquipmentIds(), 0, capturedEquipmentIds.length);
			composition.setHash();
		}

		final NpcAnimationSet original = capturedPlayerAnimations.remove(name);
		player.setIdlePoseAnimation(original.getIdlePoseAnimation());
		player.setWalkAnimation(original.getWalkAnimation());
		player.setIdleRotateLeft(original.getIdleRotateLeft());
		player.setIdleRotateRight(original.getIdleRotateRight());
		player.setWalkRotateLeft(original.getWalkRotateLeft());
		player.setWalkRotateRight(original.getWalkRotateRight());
		player.setWalkRotate180(original.getWalkRotate180());
		player.setRunAnimation(original.getRunAnimation());
		player.setPoseAnimation(-1);
	}

	/**
	 * Reverts a player back to their real appearance, regardless of which
	 * mechanism was actually showing them as an npc - the plain transform path
	 * ({@link #revertTransformOnly}) or, for the local player with {@link
	 * PlayerOverride#showEquipment} set, the self-clone path ({@link
	 * #teardownSelfClone}). Safe to call unconditionally on any player
	 * regardless of which (if either) was actually active - both halves no-op
	 * on their own if they have nothing to undo.
	 */
	private void revertPlayer(Player player)
	{
		if (player == client.getLocalPlayer())
		{
			teardownSelfClone();
		}
		revertTransformOnly(player);
	}

	/**
	 * Creates (or re-targets, if one already exists) the self-clone {@link
	 * RuneLiteObject} standing in for the local player, built via {@link
	 * #buildSelfCloneModel}. Mirrors {@link #applyClone}'s create-once/
	 * re-set-every-call structure, but for the single local-player slot
	 * ({@link #selfClone}) rather than a per-npc map. Leaves any existing
	 * clone as-is (rather than tearing it down) if the model can't be built
	 * right now (e.g. the replacement npc id or an equipped item's definition
	 * can't be resolved) - a transient lookup failure shouldn't blank out an
	 * already-working clone.
	 * <p>
	 * KNOWN LIMITATION: the built model is a one-shot snapshot of whatever the
	 * player had equipped at the moment this actually runs (apply, toggle,
	 * respawn) - unlike {@link #onClientTick}'s position/orientation/animation
	 * sync, changing gear WHILE already self-cloned does not live-update the
	 * clone's model, since there's no equipment-change event this plugin
	 * subscribes to. Re-toggling {@link PlayerOverride#showEquipment} off then
	 * on (or relogging) picks up the new gear.
	 */
	private void applySelfClone(NpcChoice choice)
	{
		final Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null)
		{
			return;
		}
		final Model model = buildSelfCloneModel(choice.getId());
		if (model == null)
		{
			return;
		}

		if (selfClone == null)
		{
			final RuneLiteObject object = client.createRuneLiteObject();
			object.setShouldLoop(true);
			selfClone = new SelfClone(object, choice);
		}
		else
		{
			selfClone.setReplacement(choice);
		}
		selfClone.getObject().setModel(model);
		selfClone.getObject().setLocation(localPlayer.getLocalLocation(), localPlayer.getWorldLocation().getPlane());
		selfClone.getObject().setOrientation(localPlayer.getCurrentOrientation());
		selfClone.getObject().setActive(true);
		// Force onClientTick to re-sync (and thus set) the animation on its
		// next pass, same reasoning as applyClone's equivalent line.
		selfClone.setLastAnimationId(Integer.MIN_VALUE);
	}

	/** Deactivates and clears {@link #selfClone}, if one is currently active. */
	private void teardownSelfClone()
	{
		if (selfClone != null)
		{
			selfClone.getObject().setActive(false);
			selfClone = null;
		}
	}

	/** Read-only sorted snapshot for the panel to render. */
	List<PlayerOverride> getPlayerOverrides()
	{
		final List<PlayerOverride> list = new ArrayList<>(playerOverrides.values());
		list.sort(Comparator.comparing(PlayerOverride::getSourceName, String.CASE_INSENSITIVE_ORDER));
		return list;
	}

	private void persistPlayerOverrides()
	{
		configManager.setConfiguration(CONFIG_GROUP, PLAYER_OVERRIDES_KEY, gson.toJson(playerOverrides));
	}

	private void persistNpcAnimationSets()
	{
		configManager.setConfiguration(CONFIG_GROUP, NPC_ANIMATION_SETS_KEY, gson.toJson(npcAnimationSets));
	}

	/**
	 * Eagerly warms {@link #npcAnimationSets} for {@code npcId} the moment it
	 * actually becomes relevant (see {@link #selectNpc}) - purely so the log
	 * line (and thus the actual captured numbers) shows up right when the
	 * user picks something, rather than only later, silently, the first time
	 * it's actually used. Not strictly required for correctness - anything
	 * that actually NEEDS an animation set calls {@link #getOrLookupAnimationSet}
	 * directly and fetches it on the spot regardless of whether this ran
	 * first. Dispatched via {@link #clientThread} since the underlying lookup
	 * is a real {@code Client} API call - this method may be reached from the
	 * Swing EDT (the panel's search results click).
	 */
	private void ensureNpcAnimationSetCaptured(int npcId)
	{
		clientThread.invoke(() -> getOrLookupAnimationSet(npcId));
	}

	/**
	 * The single, central way ANY code in this plugin acquires an npc's
	 * animation set - a fast in-memory {@link #npcAnimationSets} read if
	 * already known, otherwise a fresh, on-demand {@link NpcCacheAnimationLookup}
	 * call (a real live {@code Client} API - see its class doc), which is
	 * cheap and reliable enough to just call whenever needed rather than
	 * depending on some earlier "capture" step having already succeeded.
	 * This deliberately does NOT permanently remember a failed lookup (no
	 * negative caching) - every call where the id is still missing from the
	 * map tries again fresh, so a transient failure (or an id that only had
	 * no definition available at the time for some reason) naturally
	 * self-heals on the next call, rather than being stuck unresolved for
	 * the rest of the session the way a one-shot "try once, give up forever"
	 * capture model would be. On success, persists so future sessions don't
	 * need to look it up again either.
	 * <p>
	 * Must be called from the client thread (same as every other place this
	 * plugin touches the live {@code Client} API) - both call sites
	 * ({@link #onClientTick} and {@link #replace}) already run there.
	 *
	 * @return the animation set, or {@code null} if this npc id genuinely has
	 * no definition available (rare - an invalid/unused id).
	 */
	@Nullable
	private NpcAnimationSet getOrLookupAnimationSet(int npcId)
	{
		final NpcAnimationSet cached = npcAnimationSets.get(npcId);
		if (cached != null)
		{
			return cached;
		}

		final NpcAnimationSet fromCache = npcCacheAnimationLookup.lookup(npcId);
		if (fromCache != null)
		{
			npcAnimationSets.put(npcId, fromCache);
			persistNpcAnimationSets();
			// debug, not info - this is developer/diagnostic visibility into
			// the actual looked-up numbers, not something an end user needs
			// in their log (RuneLite runs at INFO in production; see
			// AGENTS.md). Fires once per distinct npc id (cached after), not
			// per frame/tick, but still not a one-time/infrequent event in
			// the sense that would justify info - a user applying many
			// different overrides in a session hits this repeatedly.
			log.debug("Captured animation set for npc id {} from the live cache index: {}", npcId, fromCache);
		}
		return fromCache;
	}

	/**
	 * Retargets {@code rawAnimationId} (whatever the source npc is currently
	 * playing) from the source's own animation ids onto the replacement's -
	 * e.g. "the source's walk" becomes "the replacement's walk", rather than
	 * literally reusing the source's walk animation id on a replacement model
	 * it was never designed for (a human's walk id played on a dwarf's model,
	 * say). Classifies {@code rawAnimationId} by checking which of the
	 * source's own captured idle/walk/run/turn slots it matches, then returns
	 * the replacement's captured id for that SAME slot.
	 * <p>
	 * Falls back to returning {@code rawAnimationId} unchanged whenever a
	 * remap isn't possible at all: either npc's animation set hasn't been
	 * captured yet, or the id doesn't match any of the source's locomotion
	 * slots at all (most commonly a combat/skill override animation - there's
	 * no npc-to-npc equivalent to retarget those to, so they pass through
	 * as-is, same accepted tradeoff as before this method existed, just now
	 * narrowed to only the cases with no known correct answer).
	 * <p>
	 * If the matched slot IS known but the replacement's own value for it is
	 * -1 (no distinct animation for that exact slot - confirmed to genuinely
	 * happen: {@code AnimationID} has no turn/rotate entry at all for
	 * General Graardor's {@code GODWARS_BANDOS_*} animations, unlike a
	 * generic human's dedicated {@code HUMAN_TURNONSPOT}, so a source
	 * npc turning while overridden by Graardor has nothing exact to bind to),
	 * this walks a short fallback chain of reasonably-similar alternatives
	 * from the SAME replacement set rather than immediately giving up to a
	 * gap: a missing turn falls back to the replacement's own plain walk,
	 * then its own idle; a missing run falls back to its own walk. Only once
	 * every fallback in the chain is ALSO -1 does this finally return -1 -
	 * showing nothing is still safer than playing a definitely-wrong id, but
	 * only as the last resort, not the first.
	 */
	private int remapAnimationForReplacement(int sourceId, int replacementId, int rawAnimationId, Actor sourceActor)
	{
		if (rawAnimationId == -1)
		{
			return -1;
		}

		final NpcAnimationSet sourceSet = getOrLookupAnimationSet(sourceId);
		final NpcAnimationSet replacementSet = getOrLookupAnimationSet(replacementId);
		if (sourceSet == null || replacementSet == null)
		{
			return rawAnimationId;
		}
		return remapAnimationForReplacement(sourceSet, replacementSet, replacementId, rawAnimationId, resolveActionContext(sourceActor));
	}

	/**
	 * The actual slot-matching/fallback-chain logic {@link
	 * #remapAnimationForReplacement(int, int, int, Actor, String)} runs once
	 * it has both {@link NpcAnimationSet}s in hand - split out so {@link
	 * #onClientTick}'s self-clone animation sync can reuse the exact same
	 * remap logic with a SOURCE set that isn't looked up by npc id at all:
	 * {@link #capturedPlayerAnimations}' entry for the local player IS
	 * already a valid "source animation set" in the same shape (idle/walk/
	 * turn/run ids), just captured from a real player's own fields (see
	 * {@link #replace}'s doc) instead of {@link NpcCacheAnimationLookup}.
	 * {@code replacementId} is threaded through separately from {@code
	 * replacementSet} purely for {@link #findActionAnimationSubstitute} - the
	 * LOCOMOTION slot matches below only ever need the set, not the id.
	 * {@code context} (see {@link #resolveActionContext}) is likewise only
	 * used by that same fallback.
	 */
	private int remapAnimationForReplacement(NpcAnimationSet sourceSet, NpcAnimationSet replacementSet, int replacementId, int rawAnimationId, ActionContext context)
	{
		if (rawAnimationId == -1)
		{
			return -1;
		}
		if (rawAnimationId == sourceSet.getIdlePoseAnimation())
		{
			return replacementSet.getIdlePoseAnimation();
		}
		if (rawAnimationId == sourceSet.getWalkAnimation())
		{
			return replacementSet.getWalkAnimation();
		}
		if (rawAnimationId == sourceSet.getRunAnimation())
		{
			return firstAvailable(replacementSet.getRunAnimation(), replacementSet.getWalkAnimation());
		}
		if (rawAnimationId == sourceSet.getIdleRotateLeft())
		{
			return firstAvailable(replacementSet.getIdleRotateLeft(), replacementSet.getIdleRotateRight(),
				replacementSet.getIdlePoseAnimation());
		}
		if (rawAnimationId == sourceSet.getIdleRotateRight())
		{
			return firstAvailable(replacementSet.getIdleRotateRight(), replacementSet.getIdleRotateLeft(),
				replacementSet.getIdlePoseAnimation());
		}
		if (rawAnimationId == sourceSet.getWalkRotateLeft())
		{
			return firstAvailable(replacementSet.getWalkRotateLeft(), replacementSet.getWalkRotateRight(),
				replacementSet.getWalkAnimation());
		}
		if (rawAnimationId == sourceSet.getWalkRotateRight())
		{
			return firstAvailable(replacementSet.getWalkRotateRight(), replacementSet.getWalkRotateLeft(),
				replacementSet.getWalkAnimation());
		}
		if (rawAnimationId == sourceSet.getWalkRotate180())
		{
			// Falls back to the IN-PLACE turn variants and then idle - NOT to
			// walk. Despite living alongside the walk-rotate slots, a 180 is
			// a turn performed while STATIONARY ("turn around on the spot"),
			// so a walk cycle is never an appropriate stand-in: it renders as
			// the actor shuffling on the spot. Confirmed live by the user -
			// "when my overwritten npcs do a 180, they often do some odd
			// shuffle step instead of turning smoothly" - and it showed up
			// specifically on npc overrides because a replacement with no
			// rotate180 of its own (common; General Graardor has no turn
			// entry at all) hit this fallback every time. Players never
			// showed it because replace() binds their real turn SLOTS and
			// lets the native engine resolve which to play.
			//
			// The two idleRotate branches above already fall back to idle for
			// exactly this reason; walkRotateLeft/Right legitimately keep
			// their walk fallback, since those genuinely occur while moving.
			return firstAvailable(replacementSet.getWalkRotate180(),
				replacementSet.getIdleRotateLeft(), replacementSet.getIdleRotateRight(),
				replacementSet.getIdlePoseAnimation());
		}
		// No locomotion slot matched at all - this is an override/action-layer
		// animation (skilling, combat, emotes) with no npc-native equivalent to
		// retarget to. Try a best-effort substitute (see its doc) rather than
		// either suppressing to nothing or letting the source's own
		// player/npc-rigged raw id play verbatim on a differently-shaped model.
		final int substitute = findActionAnimationSubstitute(replacementId, rawAnimationId, context);
		return substitute != -1 ? substitute : rawAnimationId;
	}

	/**
	 * Best-effort substitute for an override/action-layer animation with no
	 * locomotion-slot match (see {@link #remapAnimationForReplacement}'s final
	 * fallback) - only reached when the relevant override is currently
	 * Playing/not-paused, since that's the only context either caller invokes
	 * {@code remapAnimationForReplacement} from at all.
	 * <p>
	 * THREE tiers, tried in order:
	 * <ol>
	 * <li>{@link #actionAnimationCandidates} (memoized per-npc-id results
	 * from {@link AnimationNameIndex#findNearbyActionAnimations}, anchored
	 * on the replacement's own known walk/idle id). Often finds nothing for
	 * npcs whose combat animations aren't named near their own walk id -
	 * confirmed, in practice, to be the common case for generic humans,
	 * unlike a unique boss like General Graardor. Death animations are
	 * gated BOTH ways within this tier: only an {@link ActionContext#DEATH}
	 * actor can receive one, and it can receive nothing else.</li>
	 * <li>{@link #GENERIC_ATTACK_ANIMATIONS}/{@link #GENERIC_DEFEND_ANIMATIONS}/
	 * {@link #GENERIC_DEATH_ANIMATIONS} (real, universal human ids), applied
	 * for ANY replacement in ANY context - {@link ActionContext#DEATH} gets
	 * the death set, {@link ActionContext#DEFEND} the block-flavored one, and
	 * {@link ActionContext#ATTACK} and {@link ActionContext#NONE} (which is
	 * what skilling resolves to) both get the attack-flavored one. This is
	 * what stops a replacement with no npc-specific candidates from simply
	 * standing still. See this tier's own inline comment for why an earlier
	 * body-type gate here was removed.</li>
	 * <li>A STRICT idle-first pick (walk, then run, only if idle itself is
	 * -1) from the replacement's own already-known locomotion set - every
	 * animation this method is asked to substitute for represents a
	 * STATIONARY action, so walk/run are never actually appropriate, only
	 * ever last-resort filler (a walk/run pick visibly "walks in place" -
	 * confirmed live, by the user, as a real regression when an earlier
	 * version of this tier picked among all three by modulo instead).</li>
	 * </ol>
	 * Returns -1 only if genuinely nothing from any tier is available.
	 * <p>
	 * Every candidate from tier 1 is validated via {@link #isLoadableAnimation}
	 * before ever being used or cached - a named {@code AnimationID} constant
	 * is not, by itself, proof the id still corresponds to real, non-empty
	 * sequence data (content gets renamed/removed/restructured over updates,
	 * and the generated constants file can retain stale/vestigial entries).
	 * This alone was NOT sufficient to stop a real, confirmed-live crash
	 * (continuous native "ArrayIndexOutOfBoundsException ... exception
	 * drawing game entity" errors traced back to this substitution system,
	 * persisting even with this check in place) - the actual root cause is
	 * tier 2's body-type gate below, added afterward once that became clear.
	 */
	private int findActionAnimationSubstitute(int replacementId, int rawAnimationId, ActionContext context)
	{
		final NpcAnimationSet replacementSet = getOrLookupAnimationSet(replacementId);
		if (replacementSet == null)
		{
			return -1;
		}

		final List<Integer> nearby = actionAnimationCandidates.computeIfAbsent(replacementId, id ->
		{
			final int anchor = replacementSet.getWalkAnimation() != -1
				? replacementSet.getWalkAnimation()
				: replacementSet.getIdlePoseAnimation();
			final Set<Integer> excludeIds = new HashSet<>(Arrays.asList(
				replacementSet.getIdlePoseAnimation(), replacementSet.getWalkAnimation(),
				replacementSet.getIdleRotateLeft(), replacementSet.getIdleRotateRight(),
				replacementSet.getWalkRotateLeft(), replacementSet.getWalkRotateRight(),
				replacementSet.getWalkRotate180(), replacementSet.getRunAnimation()));
			final List<Integer> found = animationNameIndex.findNearbyActionAnimations(anchor, excludeIds);
			return validateLoadable(found);
		});
		if (context == ActionContext.DEATH)
		{
			// Death is hard-gated both ways: a dying actor gets ONLY a death
			// animation from this tier (never an attack/block, which would
			// look absurd), and a living one can never receive one (see the
			// withoutDeathAnimations call below). Falls through to the
			// generic death ids, and ultimately to idle, if this replacement
			// has no death animation of its own.
			final List<Integer> deaths = animationNameIndex.filterDeathAnimations(nearby);
			if (!deaths.isEmpty())
			{
				return deaths.get(Math.floorMod(rawAnimationId, deaths.size()));
			}
		}
		else
		{
			// Strip death animations for any living actor before flavoring -
			// they're in the candidate pool only so the branch above can find
			// them, and must never be picked as a generic stand-in for
			// skilling or combat.
			final List<Integer> living = animationNameIndex.withoutDeathAnimations(nearby);
			if (!living.isEmpty())
			{
				// Three levels of preference, all within the npc's OWN family.
				// Falling to the next level is always better than dropping out
				// of this tier entirely, because everything here was authored
				// for this exact skeleton - whereas the generic tier below is a
				// player-rig animation on whatever this npc happens to be.
				//   1. context-flavored (attacking -> an attack animation)
				//   2. anything that looks like a combat/action animation
				//   3. ANY surviving same-family animation at all
				// Level 3 is what stops an npc whose animations simply aren't
				// named in a way this code recognises from being handed a human
				// kick - confirmed live by the user (Grimy Lizard). The
				// blocklist in AnimationNameIndex is what keeps level 3 safe.
				List<Integer> pool = context == ActionContext.ATTACK ? animationNameIndex.filterAttackAnimations(living)
					: context == ActionContext.DEFEND ? animationNameIndex.filterDefendAnimations(living)
					: Collections.emptyList();
				if (pool.isEmpty())
				{
					pool = animationNameIndex.filterActionAnimations(living);
				}
				if (pool.isEmpty())
				{
					pool = living;
				}
				return pool.get(Math.floorMod(rawAnimationId, pool.size()));
			}
		}

		// Generic player unarmed-combat animations, applied to ANY replacement
		// and in ANY context (not just combat).
		//
		// An earlier version gated this on the replacement's bodyType being
		// BODY_HUMAN/BODY_BIPEDAL, on a theory that applying a player-rig
		// animation to a non-human npc skeleton was what caused the native
		// "ArrayIndexOutOfBoundsException ... exception drawing game entity"
		// crashes. That theory was WRONG - the real causes were found later
		// and fixed properly: a stale animation FRAME index (see
		// setAnimationFrame(0) in onClientTick's player-override loop) and a
		// read/write feedback loop on getAnimation(). With those fixed, the
		// gate had no remaining justification.
		//
		// It was also actively harmful, because NpcIndex#classifyBody is a
		// NAME-KEYWORD heuristic that returns null for anything unmatched -
		// so most named npcs ("Thurgo", "Hans", etc) are unclassified even
		// when obviously human, and the gate silently blocked them, leaving
		// them standing still. Confirmed live by the user: "basically all my
		// humans are failing to play animations and just stand still".
		//
		// Deliberately NOT restricted to ATTACK/DEFEND either. Skilling
		// resolves to ActionContext.NONE, so gating this tier on combat
		// context meant a skilling npc could never reach it and always fell
		// through to plain idle - which defeats the original point of this
		// whole feature: "if it doesnt have specifc animations for skilling
		// actions, like chopping wood, fishing, mining, etc. Then instead
		// lets look for one to replace it, like an attack animation" (user's
		// own words). DEATH gets the generic death ids; DEFEND gets
		// block/defend-flavored ones; ATTACK and NONE both get
		// attack-flavored ones.
		final int[] generic = context == ActionContext.DEATH ? GENERIC_DEATH_ANIMATIONS
			: context == ActionContext.DEFEND ? GENERIC_DEFEND_ANIMATIONS
			: GENERIC_ATTACK_ANIMATIONS;
		final int genericId = firstLoadable(generic, rawAnimationId);
		if (genericId != -1)
		{
			return genericId;
		}

		if (replacementSet.getIdlePoseAnimation() != -1)
		{
			return replacementSet.getIdlePoseAnimation();
		}
		if (replacementSet.getWalkAnimation() != -1)
		{
			return replacementSet.getWalkAnimation();
		}
		return replacementSet.getRunAnimation();
	}

	/**
	 * @return the subset of {@code ids} that {@link #isLoadableAnimation}
	 * confirms actually resolve to real sequence data - see {@link
	 * #findActionAnimationSubstitute}'s doc.
	 */
	private List<Integer> validateLoadable(List<Integer> ids)
	{
		final List<Integer> result = new ArrayList<>();
		for (int id : ids)
		{
			if (isLoadableAnimation(id))
			{
				result.add(id);
			}
		}
		return result;
	}

	/**
	 * @return whether {@code id} resolves to real, non-empty animation data.
	 * {@link Client#loadAnimation} is NOT documented {@code @Nullable} (unlike
	 * {@link Client#loadModel}, right above it in the same interface, which
	 * explicitly is) - meaning it may well return a non-null but effectively
	 * EMPTY {@link net.runelite.api.Animation} for an id with no real backing
	 * data, rather than null, making a bare {@code != null} check potentially
	 * a no-op. {@link net.runelite.api.Animation#getNumFrames()} ("how many
	 * distinct frames this animation has") is the more meaningful signal - a
	 * genuinely nonexistent/vestigial id should resolve to zero frames even
	 * if the returned object itself isn't null. Checks both, since neither
	 * alone is confirmed sufficient without a live client to verify against.
	 */
	private boolean isLoadableAnimation(int id)
	{
		final Animation animation = client.loadAnimation(id);
		return animation != null && animation.getNumFrames() > 0;
	}

	/**
	 * Picks from {@code candidates} deterministically (same {@code
	 * Math.floorMod(rawAnimationId, ...)} approach as the nearby tier), but
	 * only among entries {@link #isLoadableAnimation} confirms, trying each
	 * remaining candidate in turn if the first pick doesn't validate - for a
	 * short, fixed, well-known array like {@link #GENERIC_ATTACK_ANIMATIONS}
	 * this is cheap enough to check on demand rather than memoize. Returns
	 * -1 if none of {@code candidates} validate.
	 */
	private int firstLoadable(int[] candidates, int rawAnimationId)
	{
		final int start = Math.floorMod(rawAnimationId, candidates.length);
		for (int i = 0; i < candidates.length; i++)
		{
			final int id = candidates[(start + i) % candidates.length];
			if (isLoadableAnimation(id))
			{
				return id;
			}
		}
		return -1;
	}

	/** What {@code actor} appears to be doing right now, for {@link #findActionAnimationSubstitute}'s flavor preference. */
	private enum ActionContext
	{
		DEATH, ATTACK, DEFEND, NONE
	}

	/**
	 * Resolves {@code actor}'s current {@link ActionContext} - {@link
	 * ActionContext#DEFEND} takes priority over {@link ActionContext#ATTACK}
	 * when both would apply (e.g. trading hits with a target: mid-swing AND
	 * just hit in the same window) on the theory that a hitsplat is a more
	 * specific, deliberate signal than merely having a target selected.
	 * {@link ActionContext#ATTACK} is inferred from {@link
	 * Actor#getInteracting()} being non-null (a target is selected) - not
	 * scoped further to "and an action animation is currently playing",
	 * since the caller already only reaches this when one is (see both call
	 * sites). {@link ActionContext#DEFEND} comes from {@link
	 * #recentlyHitUntilTick}, populated by {@link #onHitsplatApplied}.
	 */
	private ActionContext resolveActionContext(Actor actor)
	{
		// Highest priority by a wide margin: a dying actor is also, always,
		// a just-hit one (and often mid-attack too), so this has to be tested
		// before either of those or it would never win.
		if (actor.isDead())
		{
			return ActionContext.DEATH;
		}
		final Integer hitUntilTick = recentlyHitUntilTick.get(actor);
		if (hitUntilTick != null && client.getTickCount() <= hitUntilTick)
		{
			return ActionContext.DEFEND;
		}
		if (actor.getInteracting() != null)
		{
			return ActionContext.ATTACK;
		}
		return ActionContext.NONE;
	}

	/** @return the first non--1 id in {@code ids}, or -1 if every one of them is -1. */
	private static int firstAvailable(int... ids)
	{
		for (int id : ids)
		{
			if (id != -1)
			{
				return id;
			}
		}
		return -1;
	}

	/** Same EDT-safety reasoning as {@link #pushOverridesRefresh}, for the player list. */
	private void pushPlayerOverridesRefresh()
	{
		SwingUtilities.invokeLater(panel::refreshPlayerOverridesList);
	}

	/**
	 * Re-applies a persisted player override whenever a matching player name
	 * (re)spawns - the player equivalent of {@link #onNpcSpawned}. This is what
	 * makes the override survive despawn/respawn/logout-and-back, since a fresh
	 * {@code Player} object always starts untransformed on its own (see the
	 * class doc). Also refreshes {@link #localPlayerName} whenever the spawned
	 * player is the local player - the only reasonably frequent, already-on-
	 * the-client-thread hook available to keep that cache current for the panel.
	 */
	@Subscribe
	public void onPlayerSpawned(PlayerSpawned event)
	{
		final Player player = event.getPlayer();
		final String name = player.getName();
		if (name == null)
		{
			return;
		}

		if (player == client.getLocalPlayer())
		{
			localPlayerName = name;
		}

		final PlayerOverride override = playerOverrides.get(name);
		if (override != null && override.isEnabled())
		{
			replace(player, override.getReplacement());
		}
	}

	/**
	 * Tears down {@link #selfClone} on logout/world-hop - unlike a real NPC
	 * (which fires {@link NpcDespawned} on logout, cleaning up {@link
	 * #activeClones} for free via {@link #onNpcDespawned}), there's no
	 * equivalent despawn event for the local player, so without this the
	 * self-clone {@link RuneLiteObject} would keep {@code setActive(true)} and
	 * be left floating at its last known location after logging out - it has
	 * no other owner to tear it down. Re-created automatically on the next
	 * login/world via {@link #onPlayerSpawned} if the override is still
	 * enabled with equipment shown, same self-healing respawn behavior every
	 * other override already has.
	 */
	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		final GameState state = event.getGameState();
		if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING || state == GameState.LOADING)
		{
			// LOADING (teleport / region change) is included deliberately.
			// A RuneLiteObject's registration does NOT reliably survive a
			// scene rebuild, and the object can't detect that itself:
			// isActive() only reports client.isRuneLiteObjectRegistered(),
			// and RuneLiteObject#setLocation only re-registers when the
			// WORLD VIEW changes - neither covers a same-world region
			// reload. So the clone could end up registered-but-not-drawn
			// while addEntity was still hiding the real player, making the
			// user invisible on arrival (confirmed live).
			//
			// Rather than guess at the client's scene-teardown semantics,
			// drop the clone here and let onClientTick's self-heal rebuild
			// it once the new scene is up. Being briefly un-cloned is safe:
			// addEntity stops hiding the player the moment selfClone is
			// null, so the worst case is looking like yourself for a tick,
			// never invisible.
			teardownSelfClone();
		}
	}

	/**
	 * Records that {@code event.getActor()} was just hit, for {@link
	 * #resolveActionContext} to bias action-animation substitution toward a
	 * defend/block-flavored candidate for the next tick or two - see {@link
	 * #recentlyHitUntilTick}'s doc for why this is scoped to only currently-
	 * overridden players (self included). Fires regardless of whether the
	 * hitsplat was actually rendered (per its own javadoc), which is fine
	 * here - a miss still triggers a real defensive/block reaction animation
	 * on the real player, same as a hit does.
	 */
	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		final Actor actor = event.getActor();
		if (!(actor instanceof Player))
		{
			return;
		}
		final String name = ((Player) actor).getName();
		if (name == null || !playerOverrides.containsKey(name))
		{
			return;
		}
		recentlyHitUntilTick.put(actor, client.getTickCount() + 1);
	}

	/**
	 * Cheap, EDT-safe check for whether {@code override} belongs to the local
	 * player - just a cached-name comparison, no {@code Client} touch, so the
	 * panel's cell renderer can call this directly instead of needing {@link
	 * #clientThread}. Used to scope the "show equipment" control to the one
	 * row it's actually meaningful for (see {@link PlayerOverride#showEquipment}'s
	 * doc for why this isn't offered for other players' overrides).
	 */
	boolean isLocalPlayerOverride(PlayerOverride override)
	{
		return localPlayerName != null && localPlayerName.equals(override.getSourceName());
	}

	/**
	 * Every other real player within {@code radius} tiles of the local player
	 * (Chebyshev/tile distance, via {@link WorldPoint#distanceTo} - also
	 * naturally excludes anyone on a different plane, since that method returns
	 * {@link Integer#MAX_VALUE} across planes). Used by the panel's bulk player
	 * tools. Excludes the local player and anyone with a null name (matching the
	 * same exclusions {@link #onMenuOpened}'s single-target path already applies).
	 */
	List<Player> findPlayersInRadius(int radius)
	{
		final Player local = client.getLocalPlayer();
		final WorldView worldView = client.getTopLevelWorldView();
		if (local == null || worldView == null)
		{
			return Collections.emptyList();
		}

		final WorldPoint center = local.getWorldLocation();
		final List<Player> result = new ArrayList<>();
		for (Player player : worldView.players())
		{
			if (player == null || player == local || player.getName() == null)
			{
				continue;
			}
			if (center.distanceTo(player.getWorldLocation()) <= radius)
			{
				result.add(player);
			}
		}
		return result;
	}

	/**
	 * Applies {@code choice} to every other player within {@code radius} tiles
	 * of the local player - the panel's "apply chosen NPC to players in radius"
	 * bulk tool. Persists each affected player exactly like a normal single
	 * {@link #applyPlayerOverride} would (so bulk-applied overrides survive
	 * respawn/logout the same way individually-applied ones do), just looped
	 * over every match instead of one target. {@code animationsDisabled} is
	 * set explicitly from the panel's animations-state selector for every
	 * affected player (both brand new overrides AND ones already present, if
	 * not skipped) - unlike the single-target {@link #applyPlayerOverride},
	 * which carries the preference over from any existing override, a bulk
	 * action is explicitly choosing a setting to apply consistently to
	 * everyone it touches, not silently inheriting whatever each player
	 * happened to have before. Dispatched via {@link #clientThread} for the
	 * same reason as every other panel-reachable method touching
	 * {@code Player}/composition state (see {@link #setOverrideEnabled}'s doc
	 * for the full EDT-vs-client-thread reasoning) - refreshes once at the
	 * end, not per-player, to avoid hammering the EDT with a refresh per
	 * affected player.
	 */
	void bulkApplyToPlayersInRadius(int radius, NpcChoice choice, boolean skipExisting, boolean animationsDisabled)
	{
		clientThread.invoke(() ->
		{
			for (Player player : findPlayersInRadius(radius))
			{
				final String name = player.getName();
				if (skipExisting && playerOverrides.containsKey(name))
				{
					continue;
				}
				// showEquipment is always false here - findPlayersInRadius never
				// includes the local player, and that toggle only ever does
				// anything for a self-override (see PlayerOverride#showEquipment).
				playerOverrides.put(name, new PlayerOverride(name, choice, true, animationsDisabled, false));
				replace(player, choice);
			}
			persistPlayerOverrides();
			pushPlayerOverridesRefresh();
		});
	}

	/**
	 * Like {@link #bulkApplyToPlayersInRadius}, but each matched player
	 * independently gets an INDEPENDENTLY random pick from {@link #activeNpcs}
	 * (a fresh random draw per player, not one draw applied to everyone) - the
	 * panel's "randomize players in radius" bulk tool. No-ops (does nothing,
	 * doesn't even dispatch to the client thread) if the active list is empty,
	 * since there'd be nothing to randomly choose from. Same
	 * {@code animationsDisabled} reasoning as {@link #bulkApplyToPlayersInRadius} -
	 * set explicitly from the panel's selector for every affected player.
	 */
	void bulkRandomizePlayersInRadius(int radius, boolean skipExisting, boolean animationsDisabled)
	{
		if (activeNpcs.isEmpty())
		{
			return;
		}
		clientThread.invoke(() ->
		{
			final Random random = new Random();
			for (Player player : findPlayersInRadius(radius))
			{
				final String name = player.getName();
				if (skipExisting && playerOverrides.containsKey(name))
				{
					continue;
				}
				final NpcChoice choice = activeNpcs.get(random.nextInt(activeNpcs.size()));
				playerOverrides.put(name, new PlayerOverride(name, choice, true, animationsDisabled, false));
				replace(player, choice);
			}
			persistPlayerOverrides();
			pushPlayerOverridesRefresh();
		});
	}

	/**
	 * Un-persists and reverts every player within {@code radius} tiles of the
	 * local player that currently HAS an override - the panel's "clear players
	 * in radius" bulk tool. Players in radius with no override are silently
	 * skipped (nothing to clear), so this is safe to run indiscriminately over
	 * a crowd without needing to know in advance who's actually overridden.
	 */
	void bulkClearPlayersInRadius(int radius)
	{
		clientThread.invoke(() ->
		{
			for (Player player : findPlayersInRadius(radius))
			{
				if (playerOverrides.remove(player.getName()) != null)
				{
					revertPlayer(player);
				}
			}
			persistPlayerOverrides();
			pushPlayerOverridesRefresh();
		});
	}

	/**
	 * The inverse of {@link #bulkClearPlayersInRadius}: un-persists and
	 * reverts every player override this plugin knows about EXCEPT for
	 * players currently within {@code radius} tiles of the local player -
	 * the panel's "clear players NOT in radius" bulk tool, for cleaning up
	 * everyone far away while keeping whoever's actually nearby right now.
	 * "Not in radius" includes players who aren't even currently
	 * present/spawned at all (not just ones who are visible but far away) -
	 * anyone not confirmed to be in radius right now gets cleared, same as
	 * {@link #bulkClearAllPlayerOverrides} not requiring presence either,
	 * just scoped down to exclude the radius instead of excluding nothing.
	 */
	void bulkClearPlayersOutsideRadius(int radius)
	{
		clientThread.invoke(() ->
		{
			final Set<String> namesInRadius = new HashSet<>();
			for (Player player : findPlayersInRadius(radius))
			{
				namesInRadius.add(player.getName());
			}

			playerOverrides.entrySet().removeIf(entry ->
			{
				if (namesInRadius.contains(entry.getKey()))
				{
					return false;
				}
				final Player player = findPresentPlayer(entry.getKey());
				if (player != null)
				{
					revertPlayer(player);
				}
				return true;
			});
			persistPlayerOverrides();
			pushPlayerOverridesRefresh();
		});
	}

	/**
	 * Un-persists and reverts EVERY player override this plugin currently
	 * knows about, regardless of location - the panel's "clear all" bulk tool.
	 * Unlike the radius-scoped tools, this isn't limited to players currently
	 * nearby/present, so the panel confirms with the user before calling this
	 * (see {@code PlayerNpcReplacerPanel}'s bulk tools section).
	 */
	void bulkClearAllPlayerOverrides()
	{
		clientThread.invoke(() ->
		{
			for (String name : playerOverrides.keySet())
			{
				final Player player = findPresentPlayer(name);
				if (player != null)
				{
					revertPlayer(player);
				}
			}
			playerOverrides.clear();
			persistPlayerOverrides();
			pushPlayerOverridesRefresh();
		});
	}

	/**
	 * Applies {@code choice} to the LOCAL player - the panel's "apply to
	 * yourself" bulk tool, the one bulk-tools entry point that isn't
	 * radius-scoped (there's exactly one local player, not a crowd to filter).
	 * Persists into the same {@link #playerOverrides} map every other player
	 * override lives in, keyed by the local player's own name - re-applies on
	 * future spawns via {@link #onPlayerSpawned} and shows up in "Overwritten
	 * Players" identically to any other entry, entirely for free, since
	 * nothing else in this plugin's persistence/apply/revert machinery treats
	 * "self" as a special case. {@code showEquipment} isn't carried over from
	 * any existing self-override the way {@link #applyPlayerOverride} carries
	 * {@code animationsDisabled} - the panel's checkbox is an explicit choice
	 * made right before clicking Apply, same as the bulk radius tools'
	 * explicit {@code animationsDisabled} parameter.
	 */
	void applySelfOverride(NpcChoice choice, boolean showEquipment)
	{
		clientThread.invoke(() ->
		{
			final Player localPlayer = client.getLocalPlayer();
			if (localPlayer == null || localPlayer.getName() == null)
			{
				return;
			}
			final String name = localPlayer.getName();
			final PlayerOverride existing = playerOverrides.get(name);
			// See NpcIndex#isPlayerShaped: both of these settings only make
			// visual sense on a roughly player-shaped replacement, so a
			// non-humanoid or larger-than-1x1 one resets them rather than
			// inheriting whatever was set for a previous, different npc.
			// This deliberately also overrides the panel's own "show my
			// equipment" checkbox for such a replacement - equipment models
			// are authored for the player rig and have nothing sane to attach
			// to on e.g. a dragon.
			final boolean playerShaped = NpcIndex.isPlayerShaped(choice);
			final boolean animationsDisabled = playerShaped && existing != null && existing.isAnimationsDisabled();
			playerOverrides.put(name, new PlayerOverride(name, choice, true, animationsDisabled, playerShaped && showEquipment));
			persistPlayerOverrides();
			replace(localPlayer, choice);
			pushPlayerOverridesRefresh();
		});
	}

	/**
	 * The in-game "Replace-self" menu's equivalent of {@link #applySelfOverride} -
	 * unlike the panel's bulk tool (an explicit checkbox right before clicking
	 * Apply), there's no UI to ask "show equipment?" from a menu click, so this
	 * carries the preference over from any existing self-override instead, the
	 * same "cosmetic preference, no reset on reapply" pattern {@link
	 * #applyPlayerOverride}/{@link #applyNpcOverride} already use for their own
	 * per-entry preferences. Defaults to hidden ({@code false}) for a brand new
	 * self-override, matching every other override's default.
	 */
	private void applySelfOverrideFromMenu(NpcChoice choice)
	{
		final Player localPlayer = client.getLocalPlayer();
		final String name = localPlayer == null ? null : localPlayer.getName();
		final PlayerOverride existing = name == null ? null : playerOverrides.get(name);
		final boolean showEquipment = existing != null && existing.isShowEquipment();
		applySelfOverride(choice, showEquipment);
	}

	/**
	 * Persists the override (so it re-applies on every future spawn of this npc
	 * id, forever, until removed) and, if that npc happens to still be the exact
	 * same live instance right now, applies the clone immediately too. Always
	 * (re)enables the override, even if it was previously disabled from the
	 * panel - picking Replace from the in-game menu is an explicit "make this
	 * happen now" action, so it would be confusing for nothing to visibly change
	 * because a stale disabled flag from before silently carried over. The
	 * animations-disabled preference is handled the opposite way - carried over
	 * unchanged from any existing override on this source npc, since "does this
	 * replacement's animation look glitchy" is a cosmetic assessment with no
	 * "must reset to make the change visible" urgency (unlike the enabled flag
	 * above), matching how the highlight preference used to carry over here
	 * before v17 redefined its meaning. For a BRAND NEW override (no existing
	 * entry to carry a preference from), animations default on regardless of
	 * size - the old default-off-on-size-mismatch heuristic (a cheap but crude
	 * proxy for "will this look glitchy") predates
	 * {@link #remapAnimationForReplacement}, which now retargets the
	 * replacement's own idle/walk/turn/run ids onto the source's animation
	 * slots (with a fallback chain for gaps) instead of just borrowing the
	 * source's raw ids verbatim - the actual thing that heuristic was guarding
	 * against. The use-original-animations preference carries over the same way as
	 * animationsDisabled (unchanged from existing, false for brand new) - also
	 * a cosmetic per-source-npc preference with no reset-on-reapply urgency.
	 * Pushes a refresh to the panel's "Overwritten NPCs" list - this is
	 * triggered from the
	 * in-game menu, a completely different code path than the panel's own
	 * clicks, so without this the panel would only catch up next time it's
	 * switched to (see {@link PlayerNpcReplacerPanel#onActivate}).
	 */
	private void applyNpcOverride(int sourceNpcId, String sourceNpcName, int worldViewId, int npcIndex, NpcChoice replacement)
	{
		// NPC.getName() can be null for some npcs; getNpcOverrides() sorts by this
		// field, so it must never be stored null (see startUp()'s load-time comment
		// for what happens when a null sneaks in).
		final String safeName = sourceNpcName != null ? sourceNpcName : "NPC #" + sourceNpcId;
		final NpcOverride existing = npcOverrides.get(sourceNpcId);
		final boolean animationsDisabled = existing != null && existing.isAnimationsDisabled();
		final boolean useOriginalAnimations = existing != null && existing.isUseOriginalAnimations();
		npcOverrides.put(sourceNpcId, new NpcOverride(sourceNpcId, safeName, replacement, true, animationsDisabled, useOriginalAnimations));
		persistNpcOverrides();
		pushOverridesRefresh();

		final NPC npc = resolveNpc(worldViewId, npcIndex, sourceNpcId);
		if (npc != null)
		{
			applyClone(npc, replacement);
		}
	}

	/**
	 * Panel UI updates must happen on the Swing EDT, not the client thread this
	 * runs on (menu-click callbacks, NpcSpawned/NpcDespawned handlers). Safe to
	 * call unconditionally - panel is assigned early in startUp(), before any
	 * event that could trigger this.
	 */
	private void pushOverridesRefresh()
	{
		SwingUtilities.invokeLater(panel::refreshOverridesList);
	}

	/** Read-only snapshot for the panel, sorted alphabetically by source name. */
	List<NpcOverride> getNpcOverrides()
	{
		final List<NpcOverride> list = new ArrayList<>(npcOverrides.values());
		list.sort(Comparator.comparing(NpcOverride::getSourceName, String.CASE_INSENSITIVE_ORDER));
		return list;
	}

	/**
	 * Enables or disables a specific override WITHOUT deleting the mapping - the
	 * source npc still remembers which replacement it's set to, so re-enabling
	 * reapplies the same one without having to search/pick it again. Takes effect
	 * immediately: disabling tears down any currently-active clone(s) for this npc
	 * id right now (not waiting for it to despawn), and enabling scans every
	 * currently-spawned instance of this npc id and (re)applies the clone to each
	 * one right now (not waiting for the next spawn/respawn). Dispatched via
	 * {@link #clientThread} because this is reachable from the panel's checkbox
	 * click, which runs on the Swing EDT - {@link #activeClones} and the
	 * {@link RuneLiteObject}/{@link WorldView} APIs it touches are otherwise only
	 * ever touched from the client thread (every {@code onClientTick}, every
	 * {@link #renderCallback} frame), so mutating them directly from the EDT is an
	 * unsynchronized cross-thread race - the change can fail to become visible to
	 * the client thread at all, which is what made the panel's controls
	 * unreliable while the in-game menu (whose clicks already run on the client
	 * thread) worked fine. Pushes the panel refresh from INSIDE the queued task,
	 * not right after queuing it - {@code clientThread.invoke} runs
	 * asynchronously when called from the EDT (it queues for the client thread's
	 * next pass rather than blocking), so refreshing immediately after queuing
	 * would read {@link #npcOverrides} before this task actually ran, showing
	 * stale state until the following click happened to catch it up.
	 */
	void setOverrideEnabled(int sourceNpcId, boolean enabled)
	{
		clientThread.invoke(() ->
		{
			final NpcOverride existing = npcOverrides.get(sourceNpcId);
			if (existing == null)
			{
				return;
			}
			npcOverrides.put(sourceNpcId, new NpcOverride(existing.getSourceId(), existing.getSourceName(),
				existing.getReplacement(), enabled, existing.isAnimationsDisabled(), existing.isUseOriginalAnimations()));
			persistNpcOverrides();

			if (enabled)
			{
				final WorldView worldView = client.getTopLevelWorldView();
				if (worldView != null)
				{
					for (NPC npc : worldView.npcs())
					{
						if (npc != null && npc.getId() == sourceNpcId)
						{
							applyClone(npc, existing.getReplacement());
						}
					}
				}
			}
			else
			{
				deactivateClonesFor(sourceNpcId);
			}
			pushOverridesRefresh();
		});
	}

	/**
	 * Enables or disables animations for a specific override - an escape hatch
	 * for replacements whose animations don't fit the source npc's skeleton and
	 * look glitchy, letting the user fall back to a clean static model instead
	 * without having to give up the replacement entirely. Unlike
	 * {@link #setOverrideEnabled}, this never touches {@link #npcOverrides}'
	 * membership or {@link #activeClones}' membership - the clone stays exactly
	 * as active as it already was, only whether it animates changes. Takes
	 * effect immediately: forces every currently-active clone for this npc id to
	 * re-sync its animation on the very next {@link #onClientTick} pass (by
	 * resetting {@link NpcClone#getLastAnimationId()} past its change-detection
	 * check), the same "force a resync" trick {@link #applyClone} already uses -
	 * without this, a clone mid-walk-cycle would keep showing that same
	 * animation until its id happened to naturally change on its own, which
	 * could take a while and wouldn't feel like an immediate toggle. Dispatched
	 * via {@link #clientThread} and refreshes from inside the queued task, for
	 * the same reasons as {@link #setOverrideEnabled}.
	 */
	void setAnimationsEnabled(int sourceNpcId, boolean enabled)
	{
		clientThread.invoke(() ->
		{
			final NpcOverride existing = npcOverrides.get(sourceNpcId);
			if (existing == null)
			{
				return;
			}
			npcOverrides.put(sourceNpcId, new NpcOverride(existing.getSourceId(), existing.getSourceName(),
				existing.getReplacement(), existing.isEnabled(), !enabled, existing.isUseOriginalAnimations()));
			persistNpcOverrides();

			for (Map.Entry<NPC, NpcClone> entry : activeClones.entrySet())
			{
				if (entry.getKey().getId() == sourceNpcId)
				{
					entry.getValue().setLastAnimationId(Integer.MIN_VALUE);
				}
			}
			pushOverridesRefresh();
		});
	}

	/**
	 * Toggles whether this override plays the REPLACEMENT's own retargeted
	 * animations (the default - see {@link #remapAnimationForReplacement}) or
	 * the SOURCE's raw ones verbatim, skipping remapping entirely - for cases
	 * where the user judges the source's own animation actually looks
	 * better/more correct on this specific replacement than the "correctly"
	 * retargeted one does. Same structure as {@link #setAnimationsEnabled}
	 * (dispatched via {@link #clientThread}, forces an immediate resync by
	 * resetting {@link NpcClone#getLastAnimationId()}, refreshes from inside
	 * the queued task) - this is a sibling preference to that one, not related
	 * to it beyond sharing the same "which animation shows" concern.
	 */
	void setUseOriginalAnimations(int sourceNpcId, boolean useOriginal)
	{
		clientThread.invoke(() ->
		{
			final NpcOverride existing = npcOverrides.get(sourceNpcId);
			if (existing == null)
			{
				return;
			}
			npcOverrides.put(sourceNpcId, new NpcOverride(existing.getSourceId(), existing.getSourceName(),
				existing.getReplacement(), existing.isEnabled(), existing.isAnimationsDisabled(), useOriginal));
			persistNpcOverrides();

			for (Map.Entry<NPC, NpcClone> entry : activeClones.entrySet())
			{
				if (entry.getKey().getId() == sourceNpcId)
				{
					entry.getValue().setLastAnimationId(Integer.MIN_VALUE);
				}
			}
			pushOverridesRefresh();
		});
	}

	/**
	 * Whether this specific npc id currently has an override that's both mapped
	 * AND enabled - i.e. actually showing its replacement right now. A disabled
	 * override (mapping kept, but paused) is NOT "overridden" by this definition:
	 * its npc is currently rendering as its real, unmodified self, so it should be
	 * treated exactly like any other ordinary npc everywhere this is checked
	 * (e.g. the highlight overlay's "overwritten only" mode).
	 */
	boolean isOverridden(int npcId)
	{
		final NpcOverride override = npcOverrides.get(npcId);
		return override != null && override.isEnabled();
	}

	/**
	 * Un-persists the override entirely (so future spawns of this npc id render
	 * normally again, and the panel's list forgets it existed) and deactivates any
	 * currently-active clone for it. Scans {@link #activeClones} directly by npc
	 * id rather than requiring a specific live slot reference, so this works
	 * equally whether triggered from the in-game menu (which has one) or the
	 * panel's override list (which doesn't). Deactivation is immediate - it
	 * doesn't wait for the real npc to despawn - and since that same npc is also
	 * removed from {@link #activeClones} here, {@link #renderCallback} stops
	 * suppressing its draw on the very next frame, so the real model reappears
	 * right away too. Also pushes a panel refresh - necessary whether triggered
	 * from the panel's own ✕ (whose click handler no longer refreshes itself,
	 * precisely to avoid racing this method's own async refresh - see below) or
	 * from the in-game "Remove" option.
	 * Dispatched via {@link #clientThread} for the same reason as
	 * {@link #setOverrideEnabled} - reachable from the panel's ✕ click (Swing
	 * EDT), but {@link #activeClones} is otherwise client-thread-only state, so
	 * mutating it off-thread is an unsynchronized race that can leave the client
	 * thread never observing the removal (the model doesn't reappear). The
	 * refresh is pushed from INSIDE the queued task for the same reason as
	 * {@link #setOverrideEnabled} - queuing is asynchronous from the EDT, so
	 * refreshing right after queuing would read stale state.
	 */
	void removeNpcOverrideById(int sourceNpcId)
	{
		clientThread.invoke(() ->
		{
			npcOverrides.remove(sourceNpcId);
			persistNpcOverrides();
			deactivateClonesFor(sourceNpcId);
			pushOverridesRefresh();
		});
	}

	/**
	 * Deactivates and forgets any currently-active clone(s) standing in for a
	 * given source npc id. Shared by {@link #removeNpcOverrideById} (which also
	 * un-persists the mapping) and {@link #setOverrideEnabled} (which keeps it).
	 */
	private void deactivateClonesFor(int sourceNpcId)
	{
		activeClones.entrySet().removeIf(entry ->
		{
			if (entry.getKey().getId() == sourceNpcId)
			{
				entry.getValue().getObject().setActive(false);
				return true;
			}
			return false;
		});
	}

	/**
	 * Re-resolves an NPC from its world view slot and verifies it's still the
	 * same npc id before returning it - same stale-Actor-reference guard as
	 * {@link #applyPlayerOverride}, applied to NPCs (see the class doc for why
	 * this matters even more here: a replaced NPC is exactly the kind that's
	 * expected to eventually despawn/move/reload).
	 */
	@Nullable
	private NPC resolveNpc(int worldViewId, int npcIndex, int expectedId)
	{
		final WorldView worldView = client.getWorldView(worldViewId);
		if (worldView == null)
		{
			return null;
		}

		final NPC npc = worldView.npcs().byIndex(npcIndex);
		if (npc == null || npc.getId() != expectedId)
		{
			return null;
		}

		return npc;
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		final NPC npc = event.getNpc();

		final NpcOverride override = npcOverrides.get(npc.getId());
		// A mapping existing isn't enough - it must also be enabled. This is what
		// makes a disabled override actually stay disabled across a respawn/region
		// reload, rather than silently reapplying every time (the bug this was
		// added to fix: this check used to be missing entirely, so the panel's
		// checkbox only ever affected the hover-highlight outline, never whether
		// the clone itself got (re)created).
		if (override != null && override.isEnabled())
		{
			applyClone(npc, override.getReplacement());
		}
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		final NpcClone clone = activeClones.remove(event.getNpc());
		if (clone != null)
		{
			clone.getObject().setActive(false);
		}
	}

	/**
	 * Re-syncs every active clone's position/orientation/animation onto its real
	 * (hidden) npc. Deliberately runs on {@link ClientTick} (posted every ~20ms
	 * client cycle) rather than {@link net.runelite.api.events.GameTick} (only
	 * once per ~600ms game tick, which is what this originally used) - a walking
	 * npc's tile position only changes once per game tick, but the client
	 * animates the actual on-screen movement smoothly BETWEEN ticks every client
	 * cycle by continuously interpolating {@link NPC#getLocalLocation()}/
	 * {@link NPC#getCurrentOrientation()} toward the destination tile/facing -
	 * that's how the real (hidden) npc looks like it's smoothly walking rather
	 * than snapping tile-to-tile. Sampling those same already-interpolating
	 * values only once per game tick meant the clone only ever saw the
	 * post-interpolation "arrived" position, then held it for the rest of that
	 * tick - producing exactly the laggy, teleport-y motion this was written to
	 * fix. Sampling on every client tick instead means the clone continuously
	 * re-reads the same mid-interpolation values the real npc is being rendered
	 * with, so it tracks it just as smoothly.
	 * <p>
	 * The same distinction applies per-VALUE, not just per-tick: rotation must
	 * be read from {@link NPC#getCurrentOrientation()} (the actual, gradually
	 * interpolating facing) and NOT {@link NPC#getOrientation()} (the TARGET
	 * facing the engine is rotating toward). Reading the target made clones
	 * snap instantly to their final facing and never visibly turn at all -
	 * see the inline comment at that call.
	 */
	@Subscribe
	public void onClientTick(ClientTick event)
	{
		// Track each active clone onto its real (hidden) npc's current position,
		// facing, and animation. This - not the one-time creation - is what makes
		// a replaced NPC that walks around actually look like it's walking around.
		for (Map.Entry<NPC, NpcClone> entry : activeClones.entrySet())
		{
			final NPC realNpc = entry.getKey();
			final NpcClone clone = entry.getValue();
			final RuneLiteObject object = clone.getObject();

			object.setLocation(realNpc.getLocalLocation(), realNpc.getWorldLocation().getPlane());
			// getCurrentOrientation(), NOT getOrientation(). getOrientation()
			// is the TARGET facing (where the actor wants to end up), which
			// the engine then rotates the actor smoothly TOWARD over several
			// client ticks via getCurrentOrientation(). Syncing the clone to
			// the target made it snap instantly to the final facing, so it
			// never visibly turned at all - confirmed live by the user: "they
			// just immediately face the orientation of the tile they are
			// trying to reach". Any turn animation then played on an already-
			// turned model, which is what read as an odd shuffle rather than
			// a turn. Exactly the same class of mistake as sampling position
			// on GameTick instead of ClientTick (see this method's doc): the
			// value was right, the SOURCE of smoothness was not.
			object.setOrientation(realNpc.getCurrentOrientation());

			// getAnimation() is an OVERRIDE animation (attacks, skilling, emotes) -
			// it's -1 almost all the time, which is why the clone previously sat
			// frozen in a bind pose outside of combat/skilling: null was constantly
			// (re)applied whenever no override was active. getPoseAnimation() is
			// what the engine resolves to the correct idle/turn/walk animation
			// every tick ("usually an idle animation, or one of the walking ones" -
			// Actor#getPoseAnimation) - confirmed via the DevTools NPC overlay to
			// hold correct live values (idle vs. walk ids) even on this exact real
			// npc while its draw is suppressed, so an earlier theory that hiding it
			// broke pose-animation resolution was wrong; reverted the movement-
			// tracking workaround that theory led to, back to reading this directly.
			final NpcOverride override = npcOverrides.get(realNpc.getId());
			final int animationId;
			if (override != null && override.isAnimationsDisabled())
			{
				// Escape hatch for replacements whose animations don't fit the
				// source npc's skeleton and look glitchy - force a fully static
				// model instead, including suppressing override (combat/skilling)
				// animations, not just idle/walk. This is the paused state the
				// remapping below is deliberately NOT applied to - remapping is
				// a "keep playing, but try to match up" feature for the normal
				// (not paused) case; pausing already has its own, more drastic
				// fallback (freeze entirely) for exactly this glitch.
				animationId = -1;
			}
			else
			{
				final int overrideAnimation = realNpc.getAnimation();
				final int rawAnimationId = overrideAnimation != -1 ? overrideAnimation : realNpc.getPoseAnimation();
				// useOriginalAnimations opts OUT of remapping entirely - the user
				// has explicitly judged the source's own raw animation looks
				// better/more correct on this replacement than the "correctly"
				// retargeted one does, so skip straight to using it verbatim.
				animationId = override != null && !override.isUseOriginalAnimations()
					? remapAnimationForReplacement(realNpc.getId(), override.getReplacement().getId(), rawAnimationId, realNpc)
					: rawAnimationId;
			}
			if (animationId != clone.getLastAnimationId())
			{
				object.setAnimation(animationId == -1 ? null : client.loadAnimation(animationId));
				clone.setLastAnimationId(animationId);
			}
		}

		// Track the self-clone (if active) onto the local player, the same way
		// the loop above tracks each npc clone onto its real (hidden) npc -
		// architecturally this IS an npc clone, just standing in for the local
		// player specifically instead of a real NPC. The "source" animation
		// set for remapping is built fresh from the local player's own live
		// idle/walk/turn/run fields every tick rather than read from {@link
		// #capturedPlayerAnimations} - that map is only ever populated by
		// replace()'s OTHER (setTransformedNpcId) branch, which the self-clone
		// path deliberately never runs, so there'd be nothing captured there to
		// read. Reading the real fields live is simpler anyway: this path never
		// overwrites them, so they're always already correct, with no
		// capture/restore bookkeeping needed at all.
		// SELF-HEAL: keep selfClone's existence matched to what the persisted
		// override actually says it should be, every tick, rather than relying
		// on every possible create/destroy trigger being wired up correctly.
		// This is what recovers the clone after a teleport (onGameStateChanged
		// drops it on LOADING so it gets rebuilt against the NEW scene), and
		// it equally covers a failed initial build, a login where no
		// PlayerSpawned arrived for the local player, or any future teardown
		// path that forgets to rebuild. An invariant re-checked continuously
		// is far more robust here than an exhaustive list of triggers - the
		// same "continuously re-assert" principle the npc clones and the
		// transform re-assert already rely on.
		{
			final Player localPlayer = client.getLocalPlayer();
			final String localName = localPlayer == null ? null : localPlayer.getName();
			final PlayerOverride selfOverride = localName == null ? null : playerOverrides.get(localName);
			final boolean wantSelfClone = selfOverride != null
				&& selfOverride.isEnabled()
				&& selfOverride.isShowEquipment();

			if (wantSelfClone && selfClone == null)
			{
				// applySelfClone no-ops if the model can't be built yet (e.g.
				// mid-load), leaving selfClone null so this simply retries on
				// the next tick.
				applySelfClone(selfOverride.getReplacement());
			}
			else if (!wantSelfClone && selfClone != null && localPlayer != null)
			{
				// Guarded on a non-null localPlayer so a momentary null during
				// loading isn't mistaken for "the override was turned off".
				teardownSelfClone();
			}
		}

		if (selfClone != null)
		{
			final Player localPlayer = client.getLocalPlayer();
			if (localPlayer == null)
			{
				// Just skip this tick - do NOT tear the clone down. An earlier
				// version did, which permanently destroyed the clone (only
				// onPlayerSpawned could rebuild it) any time getLocalPlayer()
				// went briefly null, e.g. mid-teleport while the new region
				// loads - confirmed live by the user as "the model disappears
				// when I teleport". A genuinely gone local player (logout,
				// world hop) is already handled by onGameStateChanged.
			}
			else
			{
				final RuneLiteObject object = selfClone.getObject();
				// Re-assert active state as well as position: a scene/region
				// reload (teleporting) can drop the object out of the scene,
				// and setLocation alone won't bring it back.
				if (!object.isActive())
				{
					object.setActive(true);
				}
				object.setLocation(localPlayer.getLocalLocation(), localPlayer.getWorldLocation().getPlane());
				// Same target-vs-current distinction as the npc clone loop
				// above - see its comment.
				object.setOrientation(localPlayer.getCurrentOrientation());

				final PlayerOverride override = playerOverrides.get(localPlayer.getName());
				final boolean paused = override != null && override.isAnimationsDisabled();
				final int overrideAnimation = localPlayer.getAnimation();
				final int rawAnimationId = overrideAnimation != -1 ? overrideAnimation : localPlayer.getPoseAnimation();
				final ActionContext context = paused ? ActionContext.NONE : resolveActionContext(localPlayer);
				final int animationId;
				if (paused || animationNameIndex.isEmoteAnimation(rawAnimationId))
				{
					// Paused: play the player's own real (unremapped) animation
					// on the npc-shaped clone instead of freezing to static -
					// the same "show the mismatched original as an accepted
					// quirk" behavior every other paused override in this
					// plugin already has (NPC overrides' useOriginalAnimations,
					// the plain setTransformedNpcId path's paused behavior).
					// Static/frozen (the old behavior here) was reported as a
					// bug, not the intended "quirk" state - pausing was never
					// meant to mean "show nothing" for this path either.
					//
					// Emotes take the same unremapped path, for a different
					// reason: an emote is deliberately performed by the user
					// and should actually be seen, so it plays as-is on the
					// clone rather than being substituted away (same rule the
					// plain player-override loop below applies).
					animationId = rawAnimationId;
				}
				else
				{
					final NpcAnimationSet sourceSet = new NpcAnimationSet(
						localPlayer.getIdlePoseAnimation(), localPlayer.getWalkAnimation(),
						localPlayer.getIdleRotateLeft(), localPlayer.getIdleRotateRight(),
						localPlayer.getWalkRotateLeft(), localPlayer.getWalkRotateRight(),
						localPlayer.getWalkRotate180(), localPlayer.getRunAnimation());
					final int replacementId = selfClone.getReplacement().getId();
					final NpcAnimationSet replacementSet = getOrLookupAnimationSet(replacementId);
					animationId = replacementSet != null
						? remapAnimationForReplacement(sourceSet, replacementSet, replacementId, rawAnimationId, context)
						: rawAnimationId;
				}
				if (animationId != selfClone.getLastAnimationId())
				{
					object.setAnimation(animationId == -1 ? null : client.loadAnimation(animationId));
					selfClone.setLastAnimationId(animationId);
				}
			}
		}

		// For every ENABLED-and-NOT-paused player override, retarget the
		// OVERRIDE animation layer (getAnimation()/setAnimation(), "A") every
		// tick instead of leaving it alone - this is what a mining/
		// woodcutting/combat/emote action plays on, and it's a PLAYER-rigged
		// animation (designed for the player's own skeleton) being forced
		// onto a differently-shaped npc model, which is what was warping/
		// glitching the model out when the user started mining while
		// transformed. findActionAnimationSubstitute (best-effort, see its
		// doc) is tried first; only once IT also finds nothing does this fall
		// back to suppressing to -1.
		//
		// An earlier attempt routed the substitute's tier-3 (locomotion)
		// fallback to the POSE layer ("P") instead, on a theory that a
		// looping animation in A was what crashed the renderer. That was
		// wrong on both counts and is reverted: the real cause was a stale
		// animation FRAME index (see the setAnimationFrame(0) call and its
		// comment in the loop body), and the split itself introduced a
		// visible jitter of its own - it cleared A to -1, so the NEXT tick
		// read getAnimation() == -1, took the "no action" branch, and stopped
		// asserting the forced pose entirely, letting the engine resolve its
		// own pose until the action re-fired. That oscillation is exactly the
		// stutter it was supposed to fix. P also has no frame-reset API at
		// all (Actor exposes setAnimationFrame but no setPoseFrame), so the
		// actual fix isn't even expressible on that layer.
		//
		// BUT when animations are paused (isAnimationsDisabled()), none of this
		// runs at all - the user explicitly wants mismatched REAL action
		// animations to play in that state ("a fun little quirk", their words -
		// it doesn't always look bad, and pausing is already an opt-in "I know
		// this might look a bit off" state, unlike the normal enabled state
		// where avoiding the glitch is the point). So: enabled+not paused ->
		// retarget/suppress (avoid the glitch); paused -> allow the real one
		// through untouched (the fun-quirk case).
		//
		// If paused AND we have no captured NpcAnimationSet for this npc id,
		// additionally freeze the pose layer too (idle/walk) - static, since
		// there's nothing better to fall back on. When we DO have a captured
		// set, replace() has already bound the player's STANDING
		// idlePoseAnimation/walkAnimation/etc. fields to that npc's own real
		// locomotion animations (see replace()'s doc) - forcing
		// setPoseAnimation(-1) here would override that already-correct
		// binding with a blank static freeze for no reason, when the captured
		// animations are exactly the kind of thing "pause" is meant to fall
		// back to instead of nothing.
		// UNVERIFIED (no way to launch the client in this environment):
		// unlike activeClones above (a RuneLiteObject this plugin fully owns),
		// a transformed Player is a real Actor whose pose the native engine
		// ALSO continuously recomputes every tick on its own - the only way to
		// fight that (when we DO need to freeze) is to keep re-asserting -1
		// every single client tick, the same "continuously re-assert" pattern
		// already proven for activeClones' position/orientation sync above,
		// just applied to a real Actor's own animation fields instead of a
		// synthetic object's.
		for (PlayerOverride override : playerOverrides.values())
		{
			if (!override.isEnabled())
			{
				continue;
			}
			final Player player = findPresentPlayer(override.getSourceName());
			if (player == null)
			{
				continue;
			}
			if (selfClone != null && player == client.getLocalPlayer())
			{
				// Handled entirely by the self-clone block above instead - the
				// real local player is untouched/hidden in this mode (see
				// replace()'s doc), so none of this suppress-or-freeze logic
				// (which exists to stop a REAL transformed Actor's own fields
				// from glitching) applies; touching them here would just be
				// pointless mutation of a live Actor's own animation state.
				continue;
			}

			// Re-assert the npc transform itself every tick if it's drifted.
			// setTransformedNpcId used to be applied ONCE (in replace(), from
			// event handlers only) and never re-checked - so anything that
			// made the client rebuild the player's PlayerComposition silently
			// dropped the override, and nothing restored it until that
			// player's next spawn. Confirmed live by the user: the model
			// reverting to their real appearance on taking a hit and on
			// teleporting, while the override was still enabled.
			//
			// This is the same "continuously re-assert" approach the npc
			// clones above already rely on, and for the same stated reason -
			// a real Actor's state is owned by the native engine, which
			// recomputes it on its own schedule, so a one-shot write is never
			// durable. Guarded on a get() first so a steady state costs one
			// comparison and no write.
			final PlayerComposition composition = player.getPlayerComposition();
			final int wantedNpcId = override.getReplacement().getId();
			if (composition != null && composition.getTransformedNpcId() != wantedNpcId)
			{
				composition.setTransformedNpcId(wantedNpcId);

				// Whatever rebuilt the composition almost certainly also
				// recomputed this Actor's STANDING pose fields from the
				// player's real equipment (that's where the engine normally
				// derives them), which would leave the correct npc model
				// moving with the PLAYER's locomotion animations - the exact
				// mismatch replace() binds these to avoid. Re-bind them here
				// rather than only restoring the model half.
				//
				// Deliberately inside the drift branch: in steady state this
				// costs nothing, and it must NOT run every tick regardless -
				// these are the same fields revertTransformOnly restores from
				// capturedPlayerAnimations, and that capture (taken once, on
				// first replace) is unaffected either way.
				final NpcAnimationSet animationSet = getOrLookupAnimationSet(wantedNpcId);
				if (animationSet != null)
				{
					player.setIdlePoseAnimation(animationSet.getIdlePoseAnimation());
					player.setWalkAnimation(animationSet.getWalkAnimation());
					player.setIdleRotateLeft(animationSet.getIdleRotateLeft());
					player.setIdleRotateRight(animationSet.getIdleRotateRight());
					player.setWalkRotateLeft(animationSet.getWalkRotateLeft());
					player.setWalkRotateRight(animationSet.getWalkRotateRight());
					player.setWalkRotate180(animationSet.getWalkRotate180());
					player.setRunAnimation(animationSet.getRunAnimation());
				}
			}

			if (!override.isAnimationsDisabled())
			{
				final String name = override.getSourceName();
				// This loop both READS and WRITES the same field, so the very
				// first thing it has to do is work out whether what it's
				// reading is a genuine engine-set animation or just its OWN
				// substitute from a previous tick being read back.
				final int currentAnimation = player.getAnimation();
				final Integer ourSubstitute = lastPlayerActionSubstituteId.get(name);

				if (currentAnimation == -1)
				{
					// Nothing playing - the real action (and whatever we put
					// in its place) has finished.
					lastPlayerActionSubstituteId.remove(name);
				}
				else if (animationNameIndex.isEmoteAnimation(currentAnimation))
				{
					// Emotes play through completely untouched. Substitution
					// exists to hide the glitchiness of PLAYER-rigged
					// skilling/combat animations on an npc model - but an
					// emote is something the user deliberately chose and
					// wants to actually see happen, so overriding it with an
					// npc animation defeats the point of performing it.
					// Clearing our tracking here also means that once the
					// emote ends, the next real action is treated as a fresh
					// engine-set animation and substituted normally.
					lastPlayerActionSubstituteId.remove(name);
				}
				else if (ourSubstitute != null && currentAnimation == ourSubstitute)
				{
					// We're looking at our OWN substitute, still playing.
					// Leave it completely alone: don't recompute, don't
					// rewrite, don't touch the frame.
					//
					// This branch is the fix for a confirmed-live bug where
					// the animation "swapped between two different animations
					// very rapidly and never fully played" while skilling.
					// Without it, our own output was being fed straight back
					// in as the selector for the next pick - and because that
					// pick is Math.floorMod(rawAnimationId, pool.size()), a
					// different input id yields a DIFFERENT pool entry, so
					// the substitute changed every single tick in a fixed
					// cycle, resetting the frame to 0 each time (hence
					// "never fully plays"). Worked example, Graardor mining,
					// 4-entry pool {READY 7017, ATTACK 7018, DEFEND 7019,
					// RANGED 7021}: real 8349 -> idx 1 -> 7018; next tick
					// reads 7018 -> idx 2 -> 7019; then 7019 -> idx 3 ->
					// 7021; then 7021 -> idx 1 -> 7018 - a permanent 3-cycle.
					// Attacking only escaped this by luck: its keyword-
					// filtered pool is small enough that the cycle lands on
					// a fixed point instead.
				}
				else
				{
					// A genuinely new animation the ENGINE set (a fresh
					// mining swing, a new attack, etc) - this is the only
					// case that should ever compute and apply a substitute.
					final int substitute = findActionAnimationSubstitute(override.getReplacement().getId(),
						currentAnimation, resolveActionContext(player));
					player.setAnimation(substitute);
					if (substitute != -1)
					{
						// An Actor's animation FRAME counter is a separate
						// field from its animation ID and does NOT reset when
						// the id changes - so swapping mid-play from a longer
						// animation (say frame 11 of a 30-frame mining
						// animation) to a shorter substitute leaves the
						// counter past the new animation's frame array, and
						// the native renderer indexes out of bounds on the
						// next draw: the confirmed-live "Index 11 out of
						// bounds for length 11" (frame 11 in an 11-frame
						// animation - valid 0..10, one past the end).
						// DevToolsPlugin's ::anim command uses this same
						// setAnimation + setAnimationFrame(0) pairing.
						//
						// Safe to reset unconditionally here precisely
						// BECAUSE of the branch above: this only runs on a
						// genuinely new engine-set animation, never on our
						// own substitute being read back, so it restarts the
						// animation exactly once per real action rather than
						// every tick.
						//
						// Both clone paths (NpcClone/SelfClone) are immune to
						// the frame issue by construction, which is why only
						// real-Actor player overrides ever crashed:
						// RuneLiteObject#setAnimation builds a brand new
						// AnimationController per call, so their frame always
						// starts at 0 for free.
						player.setAnimationFrame(0);
						lastPlayerActionSubstituteId.put(name, substitute);
					}
					else
					{
						lastPlayerActionSubstituteId.remove(name);
					}
				}
			}
			else if (!npcAnimationSets.containsKey(override.getReplacement().getId()))
			{
				player.setPoseAnimation(-1);
			}
		}
	}

	/**
	 * Renames every menu entry targeting an overridden NPC - Talk-to, Attack,
	 * Examine, all of it - to show the replacement's name instead of the real
	 * one, without touching the option/action itself. The real NPC's actual
	 * actions keep working exactly as they do now; only the displayed name in
	 * front of them changes. Substituting the plain name text directly inside the
	 * existing (already correctly coloured/formatted) target string, rather than
	 * stripping and rebuilding it, is what keeps this looking native.
	 */
	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		final MenuEntry entry = event.getMenuEntry();
		final NPC npc = entry.getNpc();
		if (npc == null)
		{
			return;
		}

		final NpcOverride override = npcOverrides.get(npc.getId());
		if (override == null)
		{
			return;
		}

		final String realName = npc.getName();
		if (realName == null)
		{
			return;
		}

		entry.setTarget(entry.getTarget().replace(realName, override.getReplacement().getName()));
	}

	/**
	 * Creates (or re-targets, if one already exists for this exact live npc
	 * instance) the {@link RuneLiteObject} clone standing in for {@code realNpc}.
	 * Once this exists in {@link #activeClones}, {@link #renderCallback} hides the
	 * real npc's own geometry and {@link #onClientTick} keeps the clone in sync.
	 */
	private void applyClone(NPC realNpc, NpcChoice replacement)
	{
		final Model model = modelCache.computeIfAbsent(replacement.getId(), this::buildModel);
		if (model == null)
		{
			log.warn("Could not build a model for npc id {} ({})", replacement.getId(), replacement.getName());
			return;
		}

		NpcClone clone = activeClones.get(realNpc);
		if (clone == null)
		{
			final RuneLiteObject object = client.createRuneLiteObject();
			object.setShouldLoop(true);
			clone = new NpcClone(object, replacement);
			activeClones.put(realNpc, clone);
		}
		else
		{
			clone.setReplacement(replacement);
		}

		clone.getObject().setModel(model);
		clone.getObject().setLocation(realNpc.getLocalLocation(), realNpc.getWorldLocation().getPlane());
		clone.getObject().setOrientation(realNpc.getCurrentOrientation());
		clone.getObject().setActive(true);
		// Force onClientTick to re-sync (and thus set) the animation on its next
		// pass, even if the real npc's current animation id happens to match
		// whatever this clone last had from a previous replacement.
		clone.setLastAnimationId(Integer.MIN_VALUE);
	}

	/**
	 * Builds the composite {@link Model} for an npc id from its composition's raw
	 * part model ids, recoloured per the composition's own colour-substitution
	 * arrays and merged if it's a multi-part model. Returns null if the
	 * composition or its models can't be resolved.
	 * <p>
	 * Merges at the {@link ModelData} level, THEN lights ONCE at the end
	 * ({@code recolor -> merge -> light}) - not the reverse (light each part
	 * individually via {@link Client#loadModel(int, short[], short[])}, then
	 * merge the already-lit {@link Model}s together), which is what an earlier
	 * version of this did. That ordering matches how the client itself natively
	 * assembles a multi-part npc's model, and is the same pipeline
	 * {@code Lootbeam}'s "modern" style uses for the same
	 * recolor-then-merge-then-light reason. Suspected (not proven - no way to
	 * compare the two side by side in this environment) to be why animations
	 * weren't visibly playing on multi-part replacement models: merging models
	 * that were each independently finalized/lit in isolation may not correctly
	 * preserve whatever per-part skeletal/vertex-group data
	 * {@link Client#applyTransformations} needs to deform the combined mesh.
	 */
	@Nullable
	private Model buildModel(int npcId)
	{
		final List<ModelData> parts = new ArrayList<>();
		if (!collectNpcModelParts(npcId, parts))
		{
			return null;
		}

		final ModelData merged = parts.size() == 1 ? parts.get(0) : client.mergeModels(parts.toArray(new ModelData[0]));
		return merged.light();
	}

	/**
	 * Loads and recolors {@code npcId}'s own raw composition part models into
	 * {@code out}, WITHOUT merging or lighting them - the shared first step of
	 * both {@link #buildModel} (npc-only) and {@link #buildSelfCloneModel}
	 * (npc parts + the local player's own equipped items layered on top), so
	 * both stay in sync using exactly the same recolor logic. Returns {@code
	 * false} (and leaves {@code out} unspecified) if the composition or its
	 * models can't be resolved.
	 */
	private boolean collectNpcModelParts(int npcId, List<ModelData> out)
	{
		final NPCComposition composition = client.getNpcDefinition(npcId);
		if (composition == null)
		{
			return false;
		}

		final int[] modelIds = composition.getModels();
		if (modelIds == null || modelIds.length == 0)
		{
			return false;
		}

		final short[] colorToReplace = composition.getColorToReplace();
		final short[] colorToReplaceWith = composition.getColorToReplaceWith();
		final boolean hasRecolor = colorToReplace != null && colorToReplaceWith != null;

		for (int modelId : modelIds)
		{
			ModelData part = client.loadModelData(modelId);
			if (part == null)
			{
				return false;
			}
			if (hasRecolor)
			{
				part = part.cloneColors();
				for (int c = 0; c < colorToReplace.length && c < colorToReplaceWith.length; c++)
				{
					part = part.recolor(colorToReplace[c], colorToReplaceWith[c]);
				}
			}
			out.add(part);
		}
		return true;
	}

	/**
	 * Builds a {@code npcId}-shaped model with the LOCAL player's own
	 * currently-equipped items layered on top, for {@link PlayerOverride#showEquipment}'s
	 * self-clone path (see {@link #applySelfOverride}/{@link #onClientTick}'s
	 * self-clone section) - the actual attempt at "show my own equipment" that
	 * replaces the earlier {@code setTransformedNpcId}-only approach, which was
	 * confirmed in-game to render no equipment at all.
	 * <p>
	 * Starts from exactly {@link #collectNpcModelParts}'s output (the npc's own
	 * recolored parts, unmerged), then appends one recolored {@link ModelData}
	 * part per equipped item's real worn-model ids ({@link
	 * com.sensei.playernpcreplacer.cache.ItemDefinition#maleModel0}/{@code 1}/{@code 2}
	 * or the {@code female*} equivalents, chosen by {@link
	 * PlayerComposition#getGender()} - fetched via {@link #itemCacheModelLookup},
	 * the item equivalent of {@link #npcCacheAnimationLookup}), recolored per
	 * that item's own {@code colorFind}/{@code colorReplace} the same way an
	 * npc's own parts are. Only real equipped items are read from {@link
	 * PlayerComposition#getEquipmentIds()} - entries below {@link
	 * PlayerComposition#ITEM_OFFSET} are body-kit ids (skin/hair/etc, not real
	 * items), which are deliberately skipped since the npc's own body already
	 * replaces the player's body; only the {@code >=ITEM_OFFSET} entries (real
	 * worn gear) get layered on.
	 * <p>
	 * <b>Alignment is unverified and likely only coherent on Human/Bipedal npcs.</b>
	 * Every equipped item's worn model is authored assuming the standard player
	 * skeleton/rig - that's the only thing Jagex ever needed it to align with.
	 * This merges those parts onto an ARBITRARY npc's own model with no anchor/
	 * bone-alignment correction at all (the same flat {@code mergeModels} call
	 * {@link #buildModel} already uses for an npc's own same-rig parts, which
	 * works there only because Jagex authored those parts to already share one
	 * coordinate space). For a human-rig npc (already a real category this
	 * plugin tracks - see {@code NpcIndex#BODY_HUMAN}/{@code BODY_BIPEDAL}) the
	 * equipment may well line up correctly, since it shares the same base rig
	 * convention as the player model; for a structurally different npc (a
	 * quadruped, a boss with an unrelated skeleton, an inanimate-object-shaped
	 * npc) there is no reason to expect the equipment to land anywhere sensible.
	 * This can only actually be confirmed by testing in a live client.
	 */
	@Nullable
	private Model buildSelfCloneModel(int npcId)
	{
		final Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null)
		{
			return null;
		}

		final List<ModelData> parts = new ArrayList<>();
		if (!collectNpcModelParts(npcId, parts))
		{
			return null;
		}

		final PlayerComposition composition = localPlayer.getPlayerComposition();
		final boolean female = composition.getGender() == 1;
		// No cap on how many parts get merged. An earlier version capped this
		// at 11, on the belief that a confirmed-live "Index 11 out of bounds
		// for length 11" crash was client.mergeModels overrunning an internal
		// buffer. That attribution was WRONG - the same error was later
		// root-caused to a stale Actor animation FRAME index (frame 11 in an
		// 11-frame animation; see setAnimationFrame(0) in onClientTick's
		// player-override loop), and mergeModels documents no size limit at
		// all. Meanwhile the cap was silently discarding real equipment: the
		// npc's own parts are collected first, so everything truncated was
		// worn gear, and a fully-equipped player easily exceeds 11 parts
		// (each item contributes up to 3 via maleModel0/1/2). That made
		// "show equipment" look broken - confirmed live by the user.
		for (int equipmentId : composition.getEquipmentIds())
		{
			if (equipmentId < PlayerComposition.ITEM_OFFSET)
			{
				continue;
			}
			collectItemModelParts(equipmentId - PlayerComposition.ITEM_OFFSET, female, parts);
		}

		final ModelData merged = parts.size() == 1 ? parts.get(0) : client.mergeModels(parts.toArray(new ModelData[0]));
		return merged.light();
	}

	/**
	 * Loads and recolors one equipped item's real worn-model parts into
	 * {@code out} - see {@link #buildSelfCloneModel}'s doc for the full
	 * rationale/caveats. Silently contributes nothing for an item with no
	 * model in the requested gender's slots (e.g. a ring, or an item with no
	 * gender-appropriate model at all) - unlike {@link #collectNpcModelParts},
	 * a single unresolvable item shouldn't abort the whole clone, since most
	 * equipment slots (rings, some capes) genuinely have nothing to contribute.
	 */
	private void collectItemModelParts(int itemId, boolean female, List<ModelData> out)
	{
		final ItemDefinition item = itemCacheModelLookup.lookup(itemId);
		if (item == null)
		{
			return;
		}

		final int[] modelIds = female
			? new int[]{item.femaleModel0, item.femaleModel1, item.femaleModel2}
			: new int[]{item.maleModel0, item.maleModel1, item.maleModel2};
		final short[] colorFind = item.colorFind;
		final short[] colorReplace = item.colorReplace;
		final boolean hasRecolor = colorFind != null && colorReplace != null;

		for (int modelId : modelIds)
		{
			if (modelId == -1)
			{
				continue;
			}
			ModelData part = client.loadModelData(modelId);
			if (part == null)
			{
				continue;
			}
			if (hasRecolor)
			{
				part = part.cloneColors();
				for (int c = 0; c < colorFind.length && c < colorReplace.length; c++)
				{
					part = part.recolor(colorFind[c], colorReplace[c]);
				}
			}
			out.add(part);
		}
	}

	private static BufferedImage buildIcon()
	{
		final BufferedImage icon = new BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = icon.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(0, 0, 0, 0));
		g.fillRect(0, 0, 24, 24);
		g.setColor(new Color(230, 190, 120));
		g.fillOval(7, 2, 10, 10);
		g.setColor(new Color(80, 130, 200));
		g.fillRoundRect(3, 12, 18, 11, 6, 6);
		g.dispose();
		return icon;
	}
}
