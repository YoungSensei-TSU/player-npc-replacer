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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.NPCComposition;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.util.Text;

/**
 * Builds and caches the master alphabetical list of every named NPC in the game,
 * for the search panel. There's no built-in "all npcs" API, so this scans every
 * numeric id from 0 to {@link #MAX_NPC_ID} and resolves each one's display name
 * via {@link Client#getNpcDefinition(int)}, which must happen on the client
 * thread. An earlier version of this class instead reflected over every {@code
 * public static final int} field of {@code net.runelite.api.gameval.NpcID} to
 * enumerate ids without needing a numeric bound at all - reflection is
 * forbidden for Plugin Hub submissions (see AGENTS.md), so this plain numeric
 * scan replaces it. The two are otherwise equivalent: {@code NpcID}'s fields
 * are themselves just the same contiguous-ish id range with names attached,
 * and this class only ever needed the ids, never the names. See {@link
 * #MAX_NPC_ID}'s own doc for the resulting maintenance requirement. Many ids
 * share a display name (regional/graphical variants of the same NPC, e.g. many
 * "Man" ids) or resolve to blank/"null" (unnamed/invisible markers) - both are
 * collapsed/filtered out, so the final list is one entry per distinct name.
 * <p>
 * Some NPC names in the cache contain literal {@code <col=...>} formatting tags
 * (the game applies these in its own UI); those are stripped via
 * {@link Text#removeTags} before display, since Swing doesn't understand them.
 * <p>
 * Each entry also carries a size (tile footprint, from {@code getSize()}) and a
 * type, heuristically classified from the composition's combat level and menu
 * actions (Attack / Combat / Shop / Thieving / Talk-to / Other) - both real
 * fields on the composition. {@link #TYPE_ATTACK} is the narrower subset of
 * {@link #TYPE_COMBAT} (has an actual Attack option, not merely a combat
 * level), broken out because those npcs tend to ship much richer animation
 * sets and so make better replacements; filtering on {@code TYPE_COMBAT}
 * still returns both (see {@link #matchesType}). There is deliberately no
 * "region" category: NPC compositions carry no location data at all (only
 * live spawned instances have a position), so it can't be derived from this
 * data source.
 * <p>
 * Also carries a body type (Human / Bipedal / neither), for finding npcs whose
 * shape is likely to actually fit a replacement well (see
 * {@code PlayerNpcReplacerPlugin}'s known animation-mismatch caveats for
 * differently-shaped replacements). Unlike {@code type} and {@code size}, there
 * is NO real composition field for this at all - {@link NPCComposition} exposes
 * nothing about race/skeleton/body shape - so this is a best-effort NAME-KEYWORD
 * heuristic (see {@link #classifyBody}), with a real, known limitation: it only
 * catches npcs whose name contains a recognizable descriptive word ("Guard",
 * "Goblin", etc.) - it will miss proper-named humanoid npcs entirely (e.g. a
 * unique slayer master's own name), which is exactly the kind of npc this
 * plugin's users most often want to find. Typing a name directly in the search
 * box always works regardless of this classification; it's a browse/discovery
 * aid on top of that, not a replacement for it.
 * <p>
 * Size is also used as a hard signal: an npc larger than a single tile can
 * never be classified {@link #BODY_HUMAN}, even if its name matches a human
 * keyword (real humans are always 1x1) - it falls back to
 * {@link #BODY_BIPEDAL} if it also matches a bipedal-ish keyword, otherwise
 * unclassified. {@link #BODY_NON_HUMAN} is NOT a third stored classification -
 * it's a derived filter (any size &gt; 1, OR anything already stored as
 * {@link #BODY_BIPEDAL}) evaluated at search time by {@link #matchesBodyType},
 * so a keyword-matched non-human 1x1 npc (a goblin, say) is still found under
 * "Non-human" rather than being missed just because it's the same size as a
 * real human.
 * <p>
 * Building touches ~16k cache entries, so it's done once, lazily, off the UI path
 * that needs it (see {@link #ensureBuilt}), not at plugin startup.
 */
@Singleton
class NpcIndex
{
	// Narrower than TYPE_COMBAT: an npc that actually has an "Attack" option,
	// rather than merely a combat level. These are the ones worth surfacing
	// separately for this plugin's purposes - a genuinely attackable npc
	// almost always ships a far richer animation set (attack/defend/death/
	// ranged variants) than a shopkeeper or quest npc does, so they make much
	// better replacements. Selecting TYPE_COMBAT still includes these too
	// (see matchesType) - only TYPE_ATTACK narrows to them exclusively.
	static final String TYPE_ATTACK = "Attack";
	static final String TYPE_COMBAT = "Combat";
	static final String TYPE_SHOP = "Shop";
	static final String TYPE_THIEVING = "Thieving";
	static final String TYPE_TALK = "Talk-to";
	static final String TYPE_OTHER = "Other";

	static final String BODY_HUMAN = "Human";
	static final String BODY_BIPEDAL = "Bipedal";
	// Not a stored classification (see class doc) - only ever appears as a
	// filter value, never as an NpcChoice#getBodyType() result.
	static final String BODY_NON_HUMAN = "Non-human";

	// Best-effort name-keyword classification - see the class doc's caveats.
	// HUMAN_WORDS are checked first and are the narrower/stronger match;
	// BIPEDAL_WORDS are other two-legged humanoid creatures that clearly
	// aren't specifically human. Neither list is exhaustive.
	private static final Set<String> HUMAN_WORDS = new HashSet<>(Arrays.asList(
		"man", "woman", "boy", "girl", "child", "human", "citizen", "villager",
		"guard", "knight", "wizard", "witch", "warrior", "thief", "rogue",
		"banker", "farmer", "monk", "nun", "priest", "soldier", "archer",
		"mage", "pirate", "sailor", "chef", "baker", "shopkeeper",
		"apprentice", "student", "novice", "master", "king", "queen",
		"prince", "princess", "lord", "lady", "brother", "sister", "father",
		"mother", "assassin", "bandit", "barbarian", "berserker", "cultist",
		"druid", "duellist", "fisherman", "gardener", "guide", "healer",
		"hunter", "jester", "juggler", "lumberjack", "merchant", "miner",
		"musician", "ninja", "officer", "paladin", "peasant", "pilgrim",
		"recruiter", "sage", "scholar", "scribe", "sentinel", "servant",
		"slayer", "smith", "spy", "squire", "tanner", "trader", "thug",
		"tribesman", "vagrant", "watchman", "worker", "zealot"
	));
	private static final Set<String> BIPEDAL_WORDS = new HashSet<>(Arrays.asList(
		"goblin", "hobgoblin", "zombie", "skeleton", "vampyre", "dwarf",
		"elf", "gnome", "ogre", "troll", "orc", "imp", "demon", "cyclops",
		"giant", "ghoul", "mummy", "werewolf", "minotaur", "centaur",
		"draugr", "lizardman", "tzhaar", "brute", "golem"
	));

	// Upper bound for the numeric id scan build() does - see the class doc for
	// why this replaced reflecting over NpcID's fields. The highest real
	// NpcID constant as of this writing is 16293 (POH_MAGGOT_KING_PET); this
	// is set well above that for headroom against future game updates adding
	// more, since (unlike the reflection this replaced) there's no way to
	// discover the current true max at runtime - it has to be a maintained
	// constant.
	//
	// MAINTENANCE: if newly-added npcs stop appearing in search after a game
	// update, that's the symptom of this bound going stale - bump it. Check
	// the current max by opening net.runelite.api.gameval.NpcID (from the
	// net.runelite:client dependency's sources jar, or
	// https://github.com/runelite/runelite/blob/master/runelite-api/src/main/java/net/runelite/api/gameval/NpcID.java)
	// and reading the last constant's value.
	private static final int MAX_NPC_ID = 20000;

	private final Client client;
	private final ClientThread clientThread;

	private volatile List<NpcChoice> all;

	@Inject
	NpcIndex(Client client, ClientThread clientThread)
	{
		this.client = client;
		this.clientThread = clientThread;
	}

	/**
	 * Builds the index if it hasn't been already, then runs {@code onReady} on the
	 * Swing EDT. If already built, {@code onReady} runs synchronously/immediately.
	 */
	void ensureBuilt(Runnable onReady)
	{
		if (all != null)
		{
			onReady.run();
			return;
		}

		clientThread.invoke(() ->
		{
			build();
			javax.swing.SwingUtilities.invokeLater(onReady);
		});
	}

	boolean isBuilt()
	{
		return all != null;
	}

	int size()
	{
		return all == null ? 0 : all.size();
	}

	Set<Integer> allSizes()
	{
		if (all == null)
		{
			return Collections.emptySet();
		}
		return all.stream().map(NpcChoice::getSize).collect(Collectors.toCollection(TreeSet::new));
	}

	Set<String> allTypes()
	{
		if (all == null)
		{
			return Collections.emptySet();
		}
		return all.stream().map(NpcChoice::getType).collect(Collectors.toCollection(TreeSet::new));
	}

	/**
	 * @param query non-null, may be blank for "no text filter"
	 * @param sizeFilter null = any size
	 * @param typeFilter null = any type
	 * @param bodyTypeFilter null = any body type. {@link #BODY_BIPEDAL} matches
	 * BOTH {@link #BODY_BIPEDAL} and {@link #BODY_HUMAN} entries (a human IS
	 * bipedal) - only {@link #BODY_HUMAN} is an exact match, since it's the
	 * narrower category.
	 */
	List<NpcChoice> search(String query, Integer sizeFilter, String typeFilter, String bodyTypeFilter, int limit)
	{
		if (all == null)
		{
			return Collections.emptyList();
		}

		final String q = query.trim().toLowerCase();

		return all.stream()
			.filter(c -> q.isEmpty() || c.getName().toLowerCase().contains(q))
			.filter(c -> sizeFilter == null || c.getSize() == sizeFilter)
			.filter(c -> typeFilter == null || matchesType(c, typeFilter))
			.filter(c -> bodyTypeFilter == null || matchesBodyType(c, bodyTypeFilter))
			.limit(limit)
			.collect(Collectors.toList());
	}

	private void build()
	{
		// Keyed by lowercase name to dedupe the many ids that share a display name;
		// first id encountered for a name wins (any of them renders the same NPC).
		final TreeMap<String, NpcChoice> byName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

		for (int id = 0; id <= MAX_NPC_ID; id++)
		{
			try
			{
				final NPCComposition comp = client.getNpcDefinition(id);
				if (comp == null)
				{
					continue;
				}

				String name = comp.getName();
				if (name == null)
				{
					continue;
				}
				name = Text.removeTags(name).trim();
				if (name.isEmpty() || name.equalsIgnoreCase("null"))
				{
					continue;
				}

				byName.putIfAbsent(name, new NpcChoice(id, name, comp.getSize(), classify(comp), classifyBody(name, comp.getSize())));
			}
			catch (Exception ignored)
			{
				// A handful of ids may fail to resolve; skip and keep building.
			}
		}

		final List<NpcChoice> list = new ArrayList<>(byName.values());
		list.sort(Comparator.comparing(NpcChoice::getName, String.CASE_INSENSITIVE_ORDER));
		this.all = list;
	}

	private static String classify(NPCComposition comp)
	{
		final String[] actions = comp.getActions();
		final List<String> lower = actions == null
			? java.util.Collections.emptyList()
			: Arrays.stream(actions)
				.filter(java.util.Objects::nonNull)
				.map(String::toLowerCase)
				.collect(Collectors.toList());

		// Checked BEFORE the combat-level test, since almost every attackable
		// npc also has a combat level - testing level first would mean this
		// never fired at all. TYPE_COMBAT consequently now means "has a combat
		// level but is NOT attackable" as a stored value, but selecting it as
		// a FILTER still returns both (see matchesType), so nothing a user
		// could previously find under "Combat" got lost.
		if (lower.stream().anyMatch(a -> a.contains("attack")))
		{
			return TYPE_ATTACK;
		}
		if (comp.getCombatLevel() > 0)
		{
			return TYPE_COMBAT;
		}
		if (actions == null)
		{
			return TYPE_OTHER;
		}

		if (lower.stream().anyMatch(a -> a.contains("trade") || a.contains("shop")))
		{
			return TYPE_SHOP;
		}
		if (lower.stream().anyMatch(a -> a.contains("pickpocket")))
		{
			return TYPE_THIEVING;
		}
		if (lower.stream().anyMatch(a -> a.contains("talk-to")))
		{
			return TYPE_TALK;
		}
		return TYPE_OTHER;
	}

	/**
	 * Best-effort guess at whether an npc is specifically human (narrower) or a
	 * more general two-legged humanoid creature (broader), purely from its
	 * name - see the class doc for why (no real composition field exists for
	 * this) and its known limitations (proper-named npcs won't match either
	 * list at all). Plain substring matching, same style as {@link #classify};
	 * not word-boundary-aware, so it can occasionally over-match (e.g. a name
	 * that happens to contain "orc" as a substring of an unrelated word) -
	 * accepted as a reasonable tradeoff for a browse/discovery aid, not
	 * treated as authoritative.
	 * <p>
	 * {@code size > 1} is a hard override on the {@link #BODY_HUMAN} result
	 * specifically (real humans are always 1x1) - it does NOT suppress
	 * {@link #BODY_BIPEDAL} (an ogre or troll is still bipedal-shaped despite
	 * being larger than a tile), and it plays no part in this method
	 * classifying something {@link #BODY_NON_HUMAN} at all, since that's a
	 * derived filter value handled entirely in {@link #matchesBodyType}, never
	 * something this method returns.
	 */
	private static String classifyBody(String name, int size)
	{
		final String lower = name.toLowerCase();
		final boolean humanWord = HUMAN_WORDS.stream().anyMatch(lower::contains);
		final boolean bipedalWord = BIPEDAL_WORDS.stream().anyMatch(lower::contains);

		if (humanWord && size <= 1)
		{
			return BODY_HUMAN;
		}
		if (humanWord || bipedalWord)
		{
			return BODY_BIPEDAL;
		}
		return null;
	}

	/**
	 * Whether {@code choice} is close enough to a real player's shape that
	 * player-oriented override settings (borrowing the player's own animations
	 * via "pause", and layering the player's equipment on top) stand a chance
	 * of looking right on it.
	 * <p>
	 * Deliberately CONSERVATIVE - it must be confidently player-shaped, not
	 * merely un-disproven. Requires both a 1x1 footprint (a real player is
	 * always 1x1, so anything bigger definitely isn't player-shaped, and
	 * {@code getSize()} is a genuine {@code NPCComposition} field rather than
	 * a heuristic) AND an explicit {@link #BODY_HUMAN} classification.
	 * <p>
	 * Treating unclassified ({@code null}) as NOT player-shaped is the whole
	 * point: {@link #classifyBody} is a name-keyword heuristic that returns
	 * null for most npcs, so "not disproven" would wave through nearly
	 * everything. The failure modes are asymmetric - being too strict just
	 * resets a cosmetic toggle the user can turn back on, while being too
	 * loose produces the glitchy visuals these settings exist to avoid. Note
	 * this correctly rejects e.g. Thurgo, who is a dwarf and genuinely not
	 * player-shaped despite being 1x1.
	 */
	static boolean isPlayerShaped(NpcChoice choice)
	{
		return choice.getSize() <= 1 && BODY_HUMAN.equals(choice.getBodyType());
	}

	/**
	 * {@link #TYPE_COMBAT} matches {@link #TYPE_ATTACK} entries too - an
	 * attackable npc is still a combat npc, {@code TYPE_ATTACK} is just the
	 * narrower "actually has an Attack option" subset of it. Same
	 * broader-includes-narrower relationship {@link #matchesBodyType} uses for
	 * {@link #BODY_BIPEDAL}/{@link #BODY_HUMAN}, and for the same reason: it
	 * means adding the narrower category can't take anything away from the
	 * broader one that used to be findable under it. Every other type is an
	 * exact match.
	 */
	private static boolean matchesType(NpcChoice choice, String filter)
	{
		if (filter.equals(TYPE_COMBAT))
		{
			return TYPE_COMBAT.equals(choice.getType()) || TYPE_ATTACK.equals(choice.getType());
		}
		return filter.equals(choice.getType());
	}

	/**
	 * {@link #BODY_BIPEDAL} matches {@link #BODY_HUMAN} entries too (a human is
	 * bipedal). {@link #BODY_NON_HUMAN} isn't a stored value at all (see the
	 * class doc) - it matches anything sized bigger than a single tile
	 * ("assume anything over 1x1 is non-human"), OR anything already stored as
	 * {@link #BODY_BIPEDAL} (a keyword-matched non-human creature, regardless
	 * of its size - this is what keeps a 1x1 goblin from being missed just
	 * because it's the same size as a real human).
	 */
	private static boolean matchesBodyType(NpcChoice choice, String filter)
	{
		if (filter.equals(BODY_NON_HUMAN))
		{
			return choice.getSize() > 1 || BODY_BIPEDAL.equals(choice.getBodyType());
		}

		final String body = choice.getBodyType();
		if (body == null)
		{
			return false;
		}
		if (filter.equals(BODY_BIPEDAL))
		{
			return body.equals(BODY_BIPEDAL) || body.equals(BODY_HUMAN);
		}
		return body.equals(filter);
	}
}
