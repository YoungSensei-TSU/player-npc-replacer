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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Finds plausible combat/action animations for an npc by searching the id
 * space NEAR one of its own already-known, always-present locomotion
 * animation ids (typically its walk id). Needs no {@code Client} lookup per
 * entry, since the id-to-name data this class searches ({@link #ensureBuilt})
 * is fully known at compile time - so unlike {@code NpcIndex}, this never
 * needs the client thread at all.
 * <p>
 * {@code id=NAME} pairs come from the bundled {@value #RESOURCE_NAME}
 * resource (see {@link #ensureBuilt} for how/why) rather than from {@code
 * net.runelite.api.gameval.AnimationID} directly - reflecting over that
 * class's fields was the original approach, but reflection is forbidden for
 * Plugin Hub submissions (see AGENTS.md). The resource is a generated,
 * point-in-time snapshot of that same file, so see {@link #ensureBuilt}'s doc
 * for the maintenance implication.
 * <p>
 * <b>Deliberately NOT name-matched against the npc's own display name</b> - an
 * earlier version of this class tried that and was corrected: an npc's
 * animations don't need to reference its name at all, since the caller
 * already knows exactly which npc they belong to (it looked them up via the
 * npc id in the first place). Confirmed name-matching was actively the wrong
 * approach via a real counterexample: General Graardor's combat animations
 * are {@code GODWARS_BANDOS_ATTACK}/{@code READY}/etc, nothing containing
 * "GRAARDOR". What DOES reliably relate an npc's animations to each other is
 * id proximity: Jagex allocates a piece of content's full animation set as a
 * contiguous block when it ships - Graardor's own {@code GODWARS_BANDOS_WALK
 * = 7016} sits immediately next to {@code READY = 7017}, {@code ATTACK =
 * 7018}, {@code DEFEND = 7019}, {@code DEATH = 7020}, {@code RANGED = 7021},
 * {@code PROJ = 7022}, {@code SPOT = 7023} - a real, confirmed, contiguous
 * block anchored on the ONE animation id every npc is guaranteed to actually
 * have on record ({@link com.sensei.playernpcreplacer.cache.NpcDefinition#walkingAnimation}/
 * {@code standingAnimation}, already read by {@link NpcCacheAnimationLookup}
 * for every npc override anyway - no extra data needed to get an anchor).
 * <p>
 * <b>Proximity alone is NOT enough</b> - confirmed the hard way (a live-client
 * test): Jagex ships an entire CONTENT RELEASE as one contiguous id block, not
 * one npc's animations in isolation. Immediately after Graardor's own
 * WALK(7016)-SPOT(7023) block sit {@code GODWARS_WATERFALL}/{@code
 * WATERFALL_SLOW} (7024-7025, an unrelated environmental effect) and then
 * {@code GODWARS_CENTAUR_*} (7026+, a completely different God Wars Dungeon
 * monster's own animations) - a plain "any named, non-excluded id in the
 * window" search picked {@code GODWARS_WATERFALL_SLOW} for Graardor's own
 * mining substitute, which is wrong in exactly the way this whole feature
 * exists to avoid. Fixed by additionally requiring the candidate's constant
 * name to share the SAME PREFIX as the anchor's own constant name (derived by
 * stripping the anchor name's last {@code _SEGMENT}, e.g. {@code
 * GODWARS_BANDOS_WALK} -> {@code GODWARS_BANDOS}) - proximity finds the right
 * neighborhood, the shared prefix confirms the candidate is actually part of
 * THIS npc's own named animation family, not a neighboring npc's or an
 * environmental effect that merely shipped in the same batch. If the anchor
 * itself has no named constant at all (common - only a fraction of the id
 * space is named), there's no prefix to derive and this returns nothing
 * rather than guess.
 * <p>
 * <b>Still a heuristic, not a guarantee</b> - id-block allocation isn't
 * universal (a later-added animation can land far outside its npc's original
 * block; {@code GODWARS_BANDOS_ATTACK_LOOP = 8765} is ~1700 ids from the rest
 * of Graardor's block, and K'ril Tsutsaroth's own combat kit sits ~2880 ids
 * from his walk id - both now within {@link #WINDOW_AFTER}, see its own doc
 * for why that's set as high as it is and how far it's safe to go). Expect
 * this to find a useful, CORRECTLY-scoped block for npcs whose full combat
 * kit shipped together under one consistent naming prefix, and nothing for
 * npcs where either condition doesn't hold - a miss is the safe failure
 * mode; a wrong-but-named neighbor is the one this fix specifically closes
 * off.
 * <p>
 * {@link #filterAttackAnimations}/{@link #filterDefendAnimations} narrow an
 * already-found candidate list further by keyword, so {@code
 * PlayerNpcReplacerPlugin#findActionAnimationSubstitute} can prefer an
 * attack-flavored pick while the actor is actively attacking something, or a
 * defend-flavored one right after it's been hit, instead of picking blindly
 * among everything {@link #findNearbyActionAnimations} found.
 * <p>
 * <b>Every id this class hands back is validated with {@code
 * client.loadAnimation(id) != null} by the caller before ever being applied</b> -
 * a named {@code AnimationID} constant is not, by itself, proof that its id
 * still corresponds to real, currently-loadable sequence data (content gets
 * renamed/removed/restructured over time; the generated constants file can
 * lag or retain vestigial entries). See {@code
 * PlayerNpcReplacerPlugin#findActionAnimationSubstitute}'s doc for why that
 * validation step was added.
 */
@Slf4j
@Singleton
class AnimationNameIndex
{
	// How far past/before the anchor id to search. WINDOW_AFTER=2900 (not a
	// small number) is deliberate and evidence-based, not a guess - confirmed
	// live by the user: K'ril Tsutsaroth's own anchor is GODWARS_ZAMORAK_WALK
	// = 4070, but his actual DEFEND/ATTACK/DEATH/MAGIC_ATTACK ids sit at
	// 6947-6950, a +2877-2880 gap far outside what an earlier, much smaller
	// window (60) ever covered - explaining a report of "K'ril has real
	// animations but we fall back to human kick" that turned out to be a
	// window-size problem, not a keyword-exclusion one. This class's own
	// doc already predicted the shape of this failure (citing
	// GODWARS_BANDOS_ATTACK_LOOP = 8765, +1749 from Graardor's own walk id,
	// as "would be missed by a window search centered on the walk id") but
	// had accepted it as a limitation rather than fixed it; K'ril's case
	// showed the gap can be even larger, making that no longer acceptable.
	//
	// Safe to widen this far ONLY because of the mandatory same-prefix check
	// below - it, not window size, is what keeps results scoped to the right
	// npc. Confirmed the boundary carefully before picking 2900: immediately
	// after K'ril's own last real entry (GODWARS_ZAMORAK_MAGIC_ATTACK_SPOTANIM
	// = 6951) comes GODWARS_ZAMORAK_BDYGRD_RANGED = 7077 - a DIFFERENT npc (a
	// ranged Zamorak bodyguard, not K'ril) that happens to share the same
	// top-level "GODWARS_ZAMORAK_" prefix despite being an unrelated entity,
	// and whose RANGED animation would be wrongly attributed to K'ril if the
	// window reached that far (RANGED is a positive ACTION_KEYWORDS match,
	// not something EXCLUDED_KEYWORDS would catch). 2900 lands at 6970 -
	// comfortably past K'ril's real block, comfortably short of 7077.
	private static final int WINDOW_BEFORE = 10;
	private static final int WINDOW_AFTER = 2900;

	// Used to prefer a semantically-appropriate candidate (see
	// PlayerNpcReplacerPlugin#findActionAnimationSubstitute's ActionContext
	// parameter) when the actor is known to currently be attacking or
	// defending, rather than picking blindly among every non-excluded nearby
	// candidate. RANGED/CAST/MELEE count as attack-flavored (they're still an
	// outgoing offensive action - MELEE is a standalone attack kind in its
	// own right, e.g. Grimy Lizard's actual attack is named GRIMY_LIZARD_MELEE);
	// BLOCK/PARRY count as defend-flavored alongside the more common DEFEND.
	private static final String[] ATTACK_KEYWORDS = {
		"ATTACK", "SWING", "STAB", "SLASH", "CRUSH", "SPECIAL", "CAST", "RANGED", "MELEE"
	};
	private static final String[] DEFEND_KEYWORDS = {
		"DEFEND", "BLOCK", "PARRY"
	};
	// Death animations are a valid candidate KIND (they're real body
	// animations belonging to the family), but are only ever appropriate for
	// an actor that is actually dying - so unlike attack/defend, which are
	// merely PREFERRED in their context, these are hard-gated: the caller
	// includes them only when ActionContext.DEATH applies, and strips them
	// from the pool entirely otherwise. Playing a death animation on a living
	// actor mid-skill would be worse than any of the mismatches this
	// substitution exists to fix.
	private static final String[] DEATH_KEYWORDS = {
		"DEATH", "DIE"
	};

	// PREFERENCE list, NOT a filter. A candidate matching one of these is
	// ranked ahead of the rest of the npc's family, but a candidate matching
	// none of them is still perfectly usable - see filterActionAnimations and
	// the caller's three-level preference in
	// PlayerNpcReplacerPlugin#findActionAnimationSubstitute.
	//
	// This WAS a hard allowlist briefly, and that was wrong. It was introduced
	// because Delrith (a demon, so anchored on DEMON_WALK = 63 with family
	// prefix DEMON_) picked up DEMON_PORTAL = 70 and glitched - a real
	// problem, but the fix belonged in EXCLUDED_KEYWORDS, not here. As a hard
	// filter it immediately broke a different npc: a Grimy Lizard's only
	// attack animation is GRIMY_LIZARD_MELEE, "MELEE" wasn't listed, so tier 1
	// came back empty and the lizard was handed a generic HUMAN kick instead.
	//
	// That's the asymmetry that decides the design: failing to RANK an
	// animation costs almost nothing (some other same-family animation plays),
	// while failing to RETURN one costs a fall-through to a foreign
	// player-rig animation on a non-human model. So this list stays advisory
	// and EXCLUDED_KEYWORDS carries the only hard rejections.
	//
	// Keywords chosen from the real distribution of name suffixes across
	// AnimationID, not guessed. Verified against the families with known data:
	//   DEMON_*          -> ranks ATTACK(64), BLOCK(65), READY(66),
	//                       CASTING(69); PORTAL/PORTALEND rejected outright by
	//                       EXCLUDED_KEYWORDS, DEATH(67)/DEATH_GREATER(68)
	//                       context-gated.
	//   GODWARS_BANDOS_* -> ranks READY(7017), ATTACK(7018), DEFEND(7019),
	//                       RANGED(7021); PROJ/SPOT rejected, DEATH(7020)
	//                       context-gated.
	//   GRIMY_LIZARD_*   -> ranks MELEE(11045). WALK(11043) is the anchor and
	//                       READY(11044) is that npc's own idle, so both
	//                       arrive pre-excluded via excludeIds.
	//   HORROR_CRAB_*    -> ranks ATTACK(1312), DEFEND(1313);
	//                       HIDE(1314)/HIDE_READY(1315)/REVEAL(1316) rejected
	//                       outright as burrow-state sequences.
	//
	// READY is listed as a combat-stance idle, which reads fine as a generic
	// "busy doing something" pose. DEATH is listed so a dying actor can rank
	// its own death animation, but is hard-gated on ActionContext.DEATH by
	// the caller (see DEATH_KEYWORDS). MELEE is a standalone attack kind, not
	// merely a suffix on "..._ATTACK_MELEE" - the Grimy Lizard case above.
	// MAGIC and FIRE were deliberately NOT listed despite comparable
	// frequency: sampling showed MAGIC is mostly quest/effect animations
	// (HANDSAND_SAND_MAGIC, BONE_SACRIFICE_MAGIC) and FIRE mostly object
	// animations (MCANNON_*_FIRE), and CAST already covers real magic attacks.
	private static final String[] ACTION_KEYWORDS = {
		"ATTACK", "SWING", "STAB", "SLASH", "CRUSH", "SPECIAL", "CAST", "RANGED",
		"MELEE", "DEFEND", "BLOCK", "PARRY", "READY", "DEATH", "DIE"
	};

	// The ONLY hard filter on same-family candidates. Everything in the npc's
	// own family that survives this is usable - ACTION_KEYWORDS above merely
	// PREFERS the obviously-combat ones, it no longer excludes the rest.
	//
	// That split is deliberate: a same-family animation was authored for that
	// npc's own skeleton, so even a semantically odd one is a far better
	// stand-in than a foreign player-rig animation. Only categories that are
	// genuinely not usable body animations belong here:
	//   - locomotion (WALK/IDLE/STAND/TURN/ROTATE/RUN/CRAWL), already handled
	//     through NpcAnimationSet's own dedicated slots;
	//   - graphic effects rather than body poses (SPOT covers SPOTANIM, PROJ,
	//     IMPACT);
	//   - spawn/transition sequences, which assume surrounding state the
	//     model isn't in - PORTAL is the confirmed-live case (Delrith picking
	//     DEMON_PORTAL and glitching), and SPAWN (also matching DESPAWN/
	//     RESPAWN), SUMMON and TRAVEL are the same category, all confirmed
	//     frequent in AnimationID's actual name distribution.
	// DEATH is deliberately absent - it's a legitimate animation needing
	// context gating, not banning (see DEATH_KEYWORDS).
	//
	// Matched per-SEGMENT via matches(), not as raw substrings, so a term
	// can't silently eat an unrelated word that merely contains it - see that
	// method. DESPAWN/RESPAWN are listed separately because segment matching
	// is prefix-based: neither of them STARTS with "SPAWN".
	private static final String[] EXCLUDED_KEYWORDS = {
		"WALK", "IDLE", "STAND", "TURN", "ROTATE", "RUN", "CRAWL",
		"SPOT", "PROJ", "IMPACT", "PORTAL", "SPAWN", "DESPAWN", "RESPAWN",
		"SUMMON", "TRAVEL",
		// Positional/state sequences: the model is deliberately placed
		// somewhere it doesn't normally sit (underground, asleep, mid-emerge),
		// so these only look right inside their own sequence and render as
		// clipping/teleporting when borrowed as a generic action. Confirmed
		// live by the user: a King Sandcrab (HORROR_CRAB_*) picking
		// HIDE_READY(1315) and clipping underground in its burrowed pose.
		// All are recurring categories in AnimationID, not one-offs - BURROW
		// ~30 names, EMERGE ~27, SLEEP ~27, WAKE ~15, DIG ~14, REVEAL ~13,
		// RISE ~12, HIDE ~8.
		//
		// Note HIDE also removes HORROR_CRAB_HIDE_READY, whose READY segment
		// would otherwise get it RANKED as a legitimate action - the hard
		// filter runs before ranking, which is exactly why that ordering
		// matters.
		"HIDE", "REVEAL", "BURROW", "EMERGE", "RISE", "SLEEP", "WAKE", "DIG",
		// Quest/narrative interaction verbs: a scripted, ROLE-SPECIFIC action
		// a quest npc performs as part of a cutscene or dialogue sequence, not
		// a generic body pose - confirmed live by the user: Mother (the giant
		// troll boss from My Arm's Big Adventure) shares her MYARM_TROLL_*
		// family with Dad, the farming-tutorial troll from earlier in that
		// same quest, so her family is full of entries like CRY, EATING,
		// PICKING_HERBS, PLANTING_SEEDS, POURING_BUCKET, RECEIVING_VILE,
		// BREAKING_RAKE, POKING_FARMING_PATCH and RAKING - none of which are
		// remotely combat/skilling-appropriate, but none of which were
		// excluded either, so a Mother override could end up miming farm
		// chores instead of fighting.
		//
		// Unlike the positional/state-sequence category above, this one has
		// no single unifying concept - it's inherently a grab-bag of
		// arbitrary quest verbs, so treat future oddities of this SAME kind
		// (an npc override occasionally performing an obviously
		// narrative/cutscene action) as more entries to add here, not a sign
		// this approach doesn't work. Verified each of these terms is a real,
		// recurring segment elsewhere in AnimationID (not a one-off unique to
		// this quest) and sampled its OTHER uses to confirm none collide with
		// a legitimate combat animation: EATING (SITTING_EATING, racoon/sick-
		// folk eating), PICKING (GARDEN_PICKING), BREAKING (object/seal/
		// boulder breaking, never a weapon break), GIVE (dialogue item
		// exchanges - GIVE_BEER, GIVES_CLOTHES, GIVE_COMPOST), VILE (a quest
		// item container), CRY (crying emotes/dialogue).
		"CRY", "EATING", "JUMPING", "PICKING", "PLANTING", "POURING",
		"RECEIVING", "BREAKING", "RAKING", "POKING", "GIVE", "VILE",
		// Dialogue-PORTRAIT animations, not world-model body poses at all -
		// the same "wrong layer entirely" category as SPOT/SPOTANIM, and a
		// real recurring one (~64 names). Spotted in Mother's own family
		// (MY2ARM_TROLL_CHATHEAD_TALK_BASIC/_MUTE/_CRY/_TALK_SAD) once the
		// prefix fix made her full family reachable.
		"CHATHEAD"
	};

	// Only one keyword, but kept in the same form so isEmoteAnimation can use
	// the shared per-segment matcher rather than a bare contains().
	private static final String[] EMOTE_KEYWORDS = {"EMOTE"};

	// The locomotion words an anchor animation can be named for, used ONLY by
	// deriveFamilyPrefix to find where to cut - see its doc. Intentionally a
	// separate list from EXCLUDED_KEYWORDS' locomotion entries even though
	// they currently overlap: these two answer different questions ("where
	// does this npc's name end?" vs "is this candidate unusable?"), and
	// EXCLUDED_KEYWORDS gets terms added to it for unrelated reasons that
	// must not start silently moving the prefix cut point.
	private static final String[] LOCOMOTION_SEGMENTS = {
		"WALK", "IDLE", "STAND", "RUN", "CRAWL", "ROTATE", "TURN"
	};

	// Resolved relative to this class, so it must live alongside the compiled
	// class file - see build.gradle's resources handling and
	// src/main/resources/com/sensei/playernpcreplacer/animation-names.txt.
	private static final String RESOURCE_NAME = "animation-names.txt";

	private volatile Map<Integer, String> byId;

	/**
	 * Parses the bundled {@value #RESOURCE_NAME} resource - one {@code
	 * id=NAME} pair per line - generated once from {@code
	 * net.runelite.api.gameval.AnimationID}'s own source (every {@code public
	 * static final int} field, with its name), the same set of ids this class
	 * previously obtained by reflecting over that class directly. A pure
	 * substring-per-line parse, no dependency on {@code AnimationID} at all.
	 * <p>
	 * <b>MAINTENANCE:</b> this is a point-in-time snapshot, not a live
	 * lookup - it will not automatically pick up animation ids Jagex adds in
	 * future game updates. If npc-specific action animations stop being found
	 * for newly-added content, regenerate the resource from the current
	 * {@code AnimationID.java} (one line per constant, formatted {@code
	 * id=CONSTANT_NAME}, sorted by id) and replace this file. This has the
	 * same "regenerate from source, don't hand-patch" maintenance shape as
	 * the vendored {@code cache} subpackage - see its {@code package-info}.
	 */
	private synchronized void ensureBuilt()
	{
		if (byId != null)
		{
			return;
		}

		final Map<Integer, String> map = new HashMap<>();
		try (InputStream in = AnimationNameIndex.class.getResourceAsStream(RESOURCE_NAME))
		{
			if (in == null)
			{
				log.warn("Missing bundled resource {} - npc-specific action animations will not be found; "
					+ "falling back to generic ones for every replacement", RESOURCE_NAME);
			}
			else
			{
				try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
				{
					String line;
					while ((line = reader.readLine()) != null)
					{
						final int eq = line.indexOf('=');
						if (eq <= 0)
						{
							continue;
						}
						try
						{
							final int id = Integer.parseInt(line.substring(0, eq));
							// The generator never emits a duplicate id (verified
							// when the resource was built - every AnimationID
							// constant is a distinct value), but putIfAbsent
							// keeps a hand-edited or manually-regenerated file
							// safe either way.
							map.putIfAbsent(id, line.substring(eq + 1));
						}
						catch (NumberFormatException ignored)
						{
							// Malformed line - skip rather than fail the whole load.
						}
					}
				}
			}
		}
		catch (IOException ex)
		{
			log.warn("Failed to read bundled resource {} - npc-specific action animations will not be found",
				RESOURCE_NAME, ex);
		}
		byId = map;
	}

	/**
	 * @param anchorId one of the npc's own known-good animation ids (its walk
	 * or idle id - see the class doc for why this is what "finds its
	 * animations" actually anchors on) - a value {@code <= 0}, or one with no
	 * named {@code AnimationID} constant at all (so no prefix can be derived
	 * from it), returns empty immediately.
	 * @param excludeIds the npc's own already-known locomotion ids (idle,
	 * walk, turn/rotate variants, run) - never returned, even if one happens
	 * to fall inside the search window, since those are already handled by
	 * {@link NpcAnimationSet}'s own dedicated slot-matching.
	 * @return every id within the search window of {@code anchorId} whose
	 * constant name shares the anchor's own prefix (see the class doc),
	 * contains none of {@link #EXCLUDED_KEYWORDS}, excluding {@code
	 * excludeIds}. Deliberately NOT filtered to {@link #ACTION_KEYWORDS} -
	 * everything in the npc's own family is returned, and the caller applies
	 * those keywords only as a ranking preference. Often empty, see the class
	 * doc.
	 */
	List<Integer> findNearbyActionAnimations(int anchorId, Set<Integer> excludeIds)
	{
		if (anchorId <= 0)
		{
			return Collections.emptyList();
		}
		ensureBuilt();

		final String anchorName = byId.get(anchorId);
		if (anchorName == null)
		{
			// No named constant for the anchor at all - nothing to derive a
			// shared-family prefix from, so we can't confirm ANY candidate
			// actually belongs to this npc's own animation family. Don't guess.
			return Collections.emptyList();
		}
		final String prefix = deriveFamilyPrefix(anchorName);
		if (prefix == null)
		{
			return Collections.emptyList();
		}

		final List<Integer> result = new ArrayList<>();
		for (int id = anchorId - WINDOW_BEFORE; id <= anchorId + WINDOW_AFTER; id++)
		{
			if (id == anchorId || excludeIds.contains(id))
			{
				continue;
			}
			final String constantName = byId.get(id);
			if (constantName == null || !constantName.startsWith(prefix))
			{
				continue;
			}
			if (matches(constantName, EXCLUDED_KEYWORDS))
			{
				continue;
			}
			result.add(id);
		}
		return result;
	}

	/**
	 * Derives the shared "this npc's own animation family" prefix from the
	 * anchor's constant name, by cutting at the LOCOMOTION segment the anchor
	 * is named for and discarding it plus everything after it.
	 * <p>
	 * This deliberately does NOT just strip the last {@code _SEGMENT}, which
	 * is what it used to do. That worked only because every npc verified up to
	 * that point happened to have an anchor ending in exactly one locomotion
	 * word ({@code GODWARS_BANDOS_WALK} -> {@code GODWARS_BANDOS_}), and broke
	 * silently the moment one didn't. Confirmed live by the user: Mother's
	 * anchor is {@code MY2ARM_TROLL_WALKING_2X2} (a size qualifier AFTER the
	 * locomotion word), which yielded {@code MY2ARM_TROLL_WALKING_} - so the
	 * only candidates matching that prefix were her other {@code WALKING_*}
	 * variants, every one of which {@link #EXCLUDED_KEYWORDS} then correctly
	 * removed as locomotion, emptying the tier entirely and dropping her to
	 * the generic human kick. Her real {@code MY2ARM_TROLL_ATTACK_MELEE}/
	 * {@code ATTACK_RANGED} were never even considered, since they don't start
	 * with {@code MY2ARM_TROLL_WALKING_}.
	 * <p>
	 * Not a one-off: ~586 {@code AnimationID} constants carry a qualifier
	 * after their locomotion word ({@code TROLL_WALK_SHIELD}, {@code
	 * HUMAN_WALK_F}, {@code GNOME_WALK_WITHBALL}...), so any npc anchored on
	 * one of those was silently getting an over-narrow family.
	 * <p>
	 * Scans right-to-left for the locomotion segment on purpose, taking the
	 * LAST match rather than the first: a name whose npc-identifying part
	 * happens to itself begin with a locomotion word (a hypothetical {@code
	 * SOMETHING_STANDARD_WALK} - {@code STANDARD} starts with {@code STAND})
	 * would otherwise cut far too early and produce a dangerously broad
	 * prefix, sweeping in unrelated npcs. Taking the last match keeps the cut
	 * at the real locomotion word.
	 *
	 * @return the family prefix INCLUDING its trailing {@code '_'}, or {@code
	 * null} if none could be derived (no locomotion segment found and no
	 * strippable trailing segment, or the cut would leave nothing before it -
	 * both meaning there's no way to confirm family membership, so the caller
	 * returns no candidates rather than guessing).
	 */
	@Nullable
	private static String deriveFamilyPrefix(String anchorName)
	{
		final String[] segments = anchorName.split("_");

		// Index 0 is never a valid cut point - there'd be no npc-identifying
		// part left in front of it to match a family on.
		for (int i = segments.length - 1; i >= 1; i--)
		{
			if (matches(segments[i], LOCOMOTION_SEGMENTS))
			{
				final StringBuilder prefix = new StringBuilder();
				for (int j = 0; j < i; j++)
				{
					prefix.append(segments[j]).append('_');
				}
				return prefix.toString();
			}
		}

		// No locomotion segment found - the anchor is named for something
		// else entirely (an idle with an unusual name, say). Fall back to the
		// original "strip the last segment" behavior, which is still a
		// reasonable guess at a family prefix and is what every npc verified
		// before this method existed was already relying on.
		final int lastSegment = anchorName.lastIndexOf('_');
		return lastSegment <= 0 ? null : anchorName.substring(0, lastSegment + 1);
	}

	/**
	 * @return the subset of {@code ids} whose constant name contains at least
	 * one of {@link #ATTACK_KEYWORDS} - used to prefer an attack-flavored
	 * candidate when the actor being resolved is currently attacking
	 * something (see {@code PlayerNpcReplacerPlugin#resolveActionContext}).
	 * May be empty even when {@code ids} isn't - e.g. Graardor's own nearby
	 * set has {@code ATTACK}/{@code RANGED} but also {@code READY}, which
	 * isn't attack-flavored and would be excluded here.
	 */
	List<Integer> filterAttackAnimations(List<Integer> ids)
	{
		return filterByKeywords(ids, ATTACK_KEYWORDS);
	}

	/** @return the subset of {@code ids} whose constant name contains at least one of {@link #DEFEND_KEYWORDS}. */
	List<Integer> filterDefendAnimations(List<Integer> ids)
	{
		return filterByKeywords(ids, DEFEND_KEYWORDS);
	}

	/** @return the subset of {@code ids} that ARE death animations - only appropriate for an actually-dying actor. */
	List<Integer> filterDeathAnimations(List<Integer> ids)
	{
		return filterByKeywords(ids, DEATH_KEYWORDS);
	}

	/**
	 * @return the subset of {@code ids} that positively look like combat/action
	 * animations ({@link #ACTION_KEYWORDS}). A PREFERENCE, not a requirement -
	 * the caller falls back to the unfiltered same-family pool when this comes
	 * back empty, because any animation belonging to the npc's own family
	 * beats a foreign player-rig one.
	 */
	List<Integer> filterActionAnimations(List<Integer> ids)
	{
		return filterByKeywords(ids, ACTION_KEYWORDS);
	}

	/**
	 * @return the same preference as {@link #filterActionAnimations}, but
	 * with anything matching {@link #DEFEND_KEYWORDS} excluded - for a
	 * context where a defend/block/hit-reaction animation would look wrong
	 * even as a loose "something action-like" pick, not just a context where
	 * it's merely not preferred. Used for {@code ActionContext#NONE}
	 * (skilling): confirmed live by the user that mining sometimes picked a
	 * DEFEND-flavored animation from a family with no dedicated attack match,
	 * which reads as "getting hit" while chopping/mining/fishing - {@link
	 * #ACTION_KEYWORDS} treats DEFEND/BLOCK/PARRY as generically
	 * combat-flavored (useful when the actor genuinely IS attacking or
	 * defending and level 1's stricter filter came up empty), but that's
	 * exactly wrong for an actor doing neither.
	 */
	List<Integer> filterActionAnimationsExcludingDefend(List<Integer> ids)
	{
		final List<Integer> result = filterActionAnimations(ids);
		result.removeAll(filterDefendAnimations(ids));
		return result;
	}

	/**
	 * @return {@code ids} with every death animation removed - the default
	 * for any living actor, so a death animation can never be picked as a
	 * generic stand-in for skilling/combat.
	 */
	List<Integer> withoutDeathAnimations(List<Integer> ids)
	{
		final List<Integer> result = new ArrayList<>();
		for (int id : ids)
		{
			if (!isDeathAnimation(id))
			{
				result.add(id);
			}
		}
		return result;
	}

	private boolean isDeathAnimation(int id)
	{
		final String constantName = byId.get(id);
		return constantName != null && matches(constantName, DEATH_KEYWORDS);
	}

	/**
	 * The single keyword-matching rule used by EVERY keyword list in this
	 * class. Splits the constant name on {@code _} and tests whether any
	 * resulting SEGMENT starts with the keyword - deliberately not a plain
	 * {@code contains} over the whole name.
	 * <p>
	 * A raw substring test silently eats valid names whenever one keyword
	 * happens to appear inside an unrelated word: {@code "END"} would match
	 * {@code GODWARS_BANDOS_DEFEND} and exclude every defend animation in the
	 * game. Matching per-segment makes that structurally impossible, because
	 * {@code DEFEND} is its own segment and does not START with {@code END}.
	 * <p>
	 * {@code startsWith} rather than equality on the segment, because several
	 * keywords are genuinely meant to cover suffixed variants and those are
	 * real, present names: {@code CAST} must still match {@code CASTING},
	 * {@code SPOT} must match {@code SPOTANIM}, {@code PORTAL} must match
	 * {@code PORTALEND}, and {@code WALK}/{@code IDLE} must match the
	 * numbered {@code WALK01}/{@code IDLE01} forms. What it will NOT do is
	 * match a keyword buried mid-word, which is the entire failure mode being
	 * removed. Prefix-only variants still need listing explicitly - hence
	 * {@code DESPAWN}/{@code RESPAWN} alongside {@code SPAWN}, since neither
	 * starts with it.
	 */
	private static boolean matches(String constantName, String[] keywords)
	{
		for (String segment : constantName.split("_"))
		{
			for (String keyword : keywords)
			{
				if (segment.startsWith(keyword))
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * @return whether {@code id}'s {@code AnimationID} constant name marks it
	 * as an emote - used to pass a player's emote straight through instead of
	 * substituting an npc animation over it (see {@code
	 * PlayerNpcReplacerPlugin#onClientTick}). An emote is something the user
	 * deliberately triggered and expects to actually see, unlike the
	 * skilling/combat animations substitution exists to paper over.
	 * <p>
	 * Matches "contains EMOTE" rather than "starts with EMOTE_", because
	 * plenty of genuine player emotes are named otherwise - clue-scroll emote
	 * clues ({@code TRAIL_BOW_EMOTE}, {@code TRAIL_YAWN_EMOTE}...), quest
	 * emotes ({@code TROLLROMANCE_EMOTE_*}), and holiday/item emotes ({@code
	 * RABBIT_EMOTE}, {@code ZOMBIE_WALK_EMOTE}, {@code HUMAN_EMOTE_CRABDANCE}).
	 * The looser match does also catch a few non-player ids (pet emotes like
	 * {@code COWBOSS_PET_EMOTE}, and {@code *_SPOTANIM} graphic ids), but
	 * those are harmless here: this is only ever asked about an id the engine
	 * actually set on a PLAYER's animation field, which those never are.
	 */
	boolean isEmoteAnimation(int id)
	{
		if (id == -1)
		{
			return false;
		}
		ensureBuilt();
		final String constantName = byId.get(id);
		return constantName != null && matches(constantName, EMOTE_KEYWORDS);
	}

	private List<Integer> filterByKeywords(List<Integer> ids, String[] keywords)
	{
		ensureBuilt();
		final List<Integer> result = new ArrayList<>();
		for (int id : ids)
		{
			final String constantName = byId.get(id);
			if (constantName != null && matches(constantName, keywords))
			{
				result.add(id);
			}
		}
		return result;
	}
}
