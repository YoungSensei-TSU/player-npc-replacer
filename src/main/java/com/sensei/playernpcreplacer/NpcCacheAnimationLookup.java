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

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.IndexDataBase;
import com.sensei.playernpcreplacer.cache.NpcDefinition;
import com.sensei.playernpcreplacer.cache.NpcLoader;

/**
 * Looks up an npc's own idle/walk/turn/run animation ids WITHOUT needing to
 * see it spawn - but unlike an earlier version of this class, does so through
 * a genuine live {@link Client} API, not by opening a separate cache file on
 * disk. {@link Client#getIndexConfig()} + {@link IndexDataBase#loadData} is a
 * real, already-used-elsewhere-in-this-codebase pattern
 * ({@code DevToolsPlugin}'s {@code VarInspector} uses the exact same call to
 * read varbit definitions) for fetching a raw archive/file's bytes directly
 * from the client's own already-open connection to the game cache - the same
 * data source {@link net.runelite.api.NPCComposition} itself is built from,
 * just for a field {@code NPCComposition} doesn't expose. This works for any
 * npc id regardless of whether it's ever spawned, since the npc config
 * archive is a static per-id definition table, not tied to live world state.
 * <p>
 * {@link NpcLoader#load(int, byte[])} then decodes those bytes into an
 * {@link NpcDefinition} - a pure in-memory parser that only reads from the
 * {@code byte[]} it's given (via the vendored {@code
 * playernpcreplacer.cache.InputStream}, NOT {@code java.io.InputStream}), so
 * there is no file/disk access anywhere in this class.
 * <p>
 * That decoder is VENDORED into {@code playernpcreplacer.cache} rather than
 * pulled in as a {@code net.runelite:cache} module dependency: this plugin
 * targets the Plugin Hub, which builds against the stock client, so it can't
 * add a dependency to the client's own {@code build.gradle.kts}. The vendored
 * copies are verbatim (same BSD-licensed sources, only the package
 * declaration rewritten) - see that subpackage for the maintenance caveat.
 * <p>
 * {@link Client#getIndexConfig()}/{@link IndexDataBase#loadData} are real
 * {@code Client} API calls, so - like everywhere else this plugin touches the
 * live API - {@link #lookup} must be called on the client thread.
 */
@Slf4j
@Singleton
class NpcCacheAnimationLookup
{
	// Archive id of the npc definition table within the CONFIGS index - the
	// value of the cache module's own ConfigType.NPC, inlined so this plugin
	// doesn't need that enum on the classpath just for one int (see the class
	// doc for why the cache module isn't a dependency here).
	private static final int CONFIG_ARCHIVE_NPC = 9;

	@Inject
	private Client client;

	private final NpcLoader npcLoader = new NpcLoader();

	/**
	 * @return the npc's animation set read live from the client's own cache
	 * connection, or {@code null} if this npc id has no definition (rare -
	 * would mean an invalid/unused id) or the lookup/decode failed.
	 */
	@Nullable
	NpcAnimationSet lookup(int npcId)
	{
		final byte[] data;
		try
		{
			final IndexDataBase configIndex = client.getIndexConfig();
			data = configIndex.loadData(CONFIG_ARCHIVE_NPC, npcId);
		}
		catch (Exception ex)
		{
			// Defensive - it's not documented whether loadData throws or
			// returns null for an id with no definition, so guard against
			// either rather than let one bad id break capture entirely.
			log.warn("Could not read cache data for npc id {}", npcId, ex);
			return null;
		}
		if (data == null)
		{
			return null;
		}

		final NpcDefinition def = npcLoader.load(npcId, data);

		// Field-for-field mapping to Actor's equivalent live getters - see
		// NpcAnimationSet's class doc. rotateLeftAnimation/rotateRightAnimation
		// are the WALKING turn
		// variants here (idle turning has its own distinctly-named
		// idleRotateLeft/RightAnimation fields) - matching Actor's
		// getWalkRotateLeft()/getWalkRotateRight(). The cache format also has
		// run-turn and crawl variants with no equivalent settable field on
		// Actor at all, so those are intentionally not read here.
		return new NpcAnimationSet(
			def.standingAnimation, def.walkingAnimation,
			def.idleRotateLeftAnimation, def.idleRotateRightAnimation,
			def.rotateLeftAnimation, def.rotateRightAnimation,
			def.rotate180Animation, def.runAnimation);
	}
}
