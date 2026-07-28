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

import lombok.Getter;
import lombok.Setter;
import net.runelite.api.RuneLiteObject;

/**
 * Session-only runtime state for the LOCAL player's own {@link
 * PlayerOverride#showEquipment} self-clone - the {@link
 * net.runelite.api.RuneLiteObject}-based alternative to {@code
 * setTransformedNpcId} used only when the user wants their own real equipped
 * items layered onto the replacement npc's shape (see {@code
 * PlayerNpcReplacerPlugin#buildSelfCloneModel}'s doc for why this needs a
 * genuinely different mechanism). There is only ever at most one of these -
 * unlike {@code activeClones} (keyed per real NPC), there's exactly one local
 * player - so the plugin holds a single nullable field rather than a map.
 */
@Getter
class SelfClone
{
	private final RuneLiteObject object;

	@Setter
	private NpcChoice replacement;

	// Same "can never equal a real animation id" sentinel NpcClone uses, so the
	// first tick after creation always triggers setAnimation once.
	@Setter
	private int lastAnimationId = Integer.MIN_VALUE;

	SelfClone(RuneLiteObject object, NpcChoice replacement)
	{
		this.object = object;
		this.replacement = replacement;
	}
}
