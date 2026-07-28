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

import lombok.Value;

/**
 * A snapshot of one npc id's own native locomotion animation ids - idle, walk,
 * and the various in-place/while-walking turn and run variants
 * {@link net.runelite.api.Actor} exposes. Fetched on demand, whenever any code
 * actually needs a given npc id's set (see
 * {@code PlayerNpcReplacerPlugin#getOrLookupAnimationSet}), via
 * {@link NpcCacheAnimationLookup} - a genuine live {@code Client} API call
 * (see its own class doc), not derived from its id via anything
 * {@code NPCComposition} exposes directly (same gap documented on
 * {@link NpcIndex}: {@code NPCComposition} exposes nothing about
 * animation/skeleton data on its own).
 * <p>
 * Applied to a transformed {@link net.runelite.api.Player} in place of just
 * clearing their own pose fields to -1 - the point is to redirect the
 * player's STANDING pose-resolution configuration (what {@code getAnimation()}
 * would forward to a real npc, distinct from the CURRENT per-tick resolved
 * value) onto the replacement npc's own real ids, so an npc-transformed
 * player idles/walks using that npc's own correct animations instead of
 * continuing to resolve from the player's own (mismatched, if the model
 * doesn't fit) equipment-derived ones.
 */
@Value
public class NpcAnimationSet
{
	int idlePoseAnimation;
	int walkAnimation;
	int idleRotateLeft;
	int idleRotateRight;
	int walkRotateLeft;
	int walkRotateRight;
	int walkRotate180;
	int runAnimation;
}
