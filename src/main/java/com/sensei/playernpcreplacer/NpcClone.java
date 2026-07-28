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
 * Session-only runtime state for one currently-spawned real NPC that's being
 * visually stood in for by a {@link RuneLiteObject} clone. Not persisted - the
 * persisted thing is the (source npc id -> replacement) mapping in
 * {@code PlayerNpcReplacerPlugin.npcOverrides}; this is just the live bookkeeping
 * needed to keep an active clone's position/animation in sync while its real NPC
 * is actually on screen.
 */
@Getter
class NpcClone
{
	private final RuneLiteObject object;

	@Setter
	private NpcChoice replacement;

	// Sentinel that can never equal a real animation id (-1 = idle, 0+ = real ids),
	// so the very first tick after creation always triggers setAnimation once.
	@Setter
	private int lastAnimationId = Integer.MIN_VALUE;

	NpcClone(RuneLiteObject object, NpcChoice replacement)
	{
		this.object = object;
		this.replacement = replacement;
	}
}
