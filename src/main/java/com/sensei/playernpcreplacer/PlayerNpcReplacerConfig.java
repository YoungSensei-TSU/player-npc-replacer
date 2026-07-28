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

import java.awt.Color;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

/**
 * Visible, standard settings-screen config (unlike the plugin's other persisted
 * state - activeNpcs/npcOverrides - which are managed entirely from the side
 * panel and stored as hidden raw keys under this same group; a real checkbox
 * belongs in the normal Configure panel, not the custom search UI).
 */
@ConfigGroup(PlayerNpcReplacerConfig.GROUP)
public interface PlayerNpcReplacerConfig extends Config
{
	String GROUP = "playernpcreplacer";

	@ConfigItem(
		keyName = "highlightNpcsOnHover",
		name = "Highlight NPCs on hover",
		description = "Outline an NPC when you hover it - the same idea as the Interact Highlight plugin, "
			+ "built in so you don't need a separate plugin just for this.",
		position = 0
	)
	default boolean highlightNpcsOnHover()
	{
		return true;
	}

	@ConfigItem(
		keyName = "highlightOverwrittenOnly",
		name = "Highlight overwritten NPCs only",
		description = "Only outline NPCs that currently have an active Replace override - ordinary NPCs are never "
			+ "highlighted. Each overwritten NPC can still be individually excluded from the 'Overwritten NPCs' "
			+ "list in the side panel.",
		position = 1
	)
	default boolean highlightOverwrittenOnly()
	{
		return false;
	}

	@Alpha
	@ConfigItem(
		keyName = "npcHighlightColor",
		name = "Highlight colour",
		description = "The colour of the hover outline.",
		position = 2
	)
	default Color npcHighlightColor()
	{
		return new Color(0x90FFFF00, true);
	}

	@ConfigItem(
		keyName = "npcHighlightBorderWidth",
		name = "Border width",
		description = "Width of the outlined border.",
		position = 3
	)
	default int npcHighlightBorderWidth()
	{
		return 2;
	}

	@ConfigItem(
		keyName = "npcHighlightFeather",
		name = "Outline feather",
		description = "Specify between 0-4 how much of the model outline should be faded.",
		position = 4
	)
	@Range(max = 4)
	default int npcHighlightFeather()
	{
		return 2;
	}
}
