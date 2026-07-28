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
import com.sensei.playernpcreplacer.cache.ItemDefinition;
import com.sensei.playernpcreplacer.cache.ItemLoader;

/**
 * Looks up an item's real WORN/EQUIPPED model ids (not its flat inventory
 * icon), the exact piece of data {@link net.runelite.api.ItemComposition} was
 * found to not expose (it only has {@code getInventoryModel()} - see {@code
 * PlayerNpcReplacerPlugin}'s class doc). Same technique as {@link
 * NpcCacheAnimationLookup} (see its class doc for the full rationale): {@link
 * Client#getIndexConfig()} + {@link IndexDataBase#loadData} reads the item's
 * raw definition archive bytes directly from the client's own already-open
 * cache connection (archive id 10, the item equivalent of the npc table's 9),
 * then {@link ItemLoader#load(int, byte[])} - the same VENDORED decoder
 * arrangement {@link NpcCacheAnimationLookup} uses, see its class doc -
 * decodes them into an {@link ItemDefinition}, a pure in-memory parser with
 * no file/disk access.
 * <p>
 * {@link ItemDefinition#maleModel0}/{@code maleModel1}/{@code maleModel2}
 * (and the {@code female*} equivalents) are the item's actual worn-appearance
 * model part ids - built assuming the standard player skeleton/rig, since
 * that's the only thing Jagex ever needed them to align with. Merging them
 * onto an arbitrary NPC's own model (see {@code
 * PlayerNpcReplacerPlugin#buildSelfCloneModel}) has no such guarantee - see
 * that method's doc for the caveat.
 */
@Slf4j
@Singleton
class ItemCacheModelLookup
{
	// Archive id of the item definition table within the CONFIGS index - the
	// value of the cache module's own ConfigType.ITEM, inlined for the same
	// reason as NpcCacheAnimationLookup's npc equivalent.
	private static final int CONFIG_ARCHIVE_ITEM = 10;

	@Inject
	private Client client;

	private final ItemLoader itemLoader = new ItemLoader();

	/**
	 * @return the item's definition read live from the client's own cache
	 * connection, or {@code null} if this item id has no definition or the
	 * lookup/decode failed.
	 */
	@Nullable
	ItemDefinition lookup(int itemId)
	{
		final byte[] data;
		try
		{
			final IndexDataBase configIndex = client.getIndexConfig();
			data = configIndex.loadData(CONFIG_ARCHIVE_ITEM, itemId);
		}
		catch (Exception ex)
		{
			log.warn("Could not read cache data for item id {}", itemId, ex);
			return null;
		}
		if (data == null)
		{
			return null;
		}

		return itemLoader.load(itemId, data);
	}
}
