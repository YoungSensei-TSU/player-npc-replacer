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
 * A persisted (source player name -> replacement) mapping, plus per-entry
 * enabled state for the panel's "Overwritten Players" list. The {@code
 * PlayerNpcReplacerPlugin} equivalent of {@link NpcOverride}, but keyed by
 * player name rather than a stable id - players have no id-based composition
 * the way NPCs do (see the plugin's class doc for why players and NPCs use
 * different underlying mechanisms), so name is the only identifier available.
 * This is a heuristic, not true identity verification: a display name change
 * orphans an existing override, and a different real person who happens to
 * share the same current name would be matched too - the same limitation the
 * session-only tracking this replaced already had.
 * <p>
 * {@code animationsDisabled} exists too, like {@link NpcOverride}'s field of
 * the same name, but for a different reason - player replacement uses the
 * native {@link net.runelite.api.PlayerComposition#setTransformedNpcId(int)}
 * transform, not a {@link net.runelite.api.RuneLiteObject} clone standing in
 * for a hidden entity, so it doesn't have NPCs' borrowed-animation-id/
 * skeleton-mismatch glitch problem - the transformed model already animates
 * correctly by default. This is purely an opt-in "freeze this specific
 * override in a static pose" preference, not a bug workaround, so unlike
 * {@link NpcOverride}'s equivalent there's no size-mismatch-based smart
 * default; brand new player overrides always default to animations enabled.
 * UNVERIFIED mechanism (see {@code PlayerNpcReplacerPlugin#onClientTick}'s
 * player-freeze loop for the full caveat).
 * <p>
 * A "keep the player's real weapon/shield visible on the npc model" toggle was
 * tried and removed for OTHER players (v29 added it, v30 reverted it) -
 * confirmed in-game to do nothing there, because {@code createColorTextureOverride}
 * only recolors what's already rendering in a kit slot, and {@code
 * setTransformedNpcId} leaves nothing rendering in any kit slot to recolor.
 * {@code showEquipment} (v51) is a second attempt, scoped only to the LOCAL
 * player's own self-override, using a different, lower-level mechanism this
 * time: directly rewriting {@link net.runelite.api.PlayerComposition#getEquipmentIds()}
 * (the live backing array, not a recolor of already-rendered output) plus
 * {@code setHash()}, the same primitive {@code DevToolsPlugin}'s real
 * {@code ::wear} console command uses. Defaults {@code false} (hidden) for
 * both brand new self-overrides and any already-persisted entry missing this
 * key, matching the pre-v51 behavior every override already had. See {@code
 * PlayerNpcReplacerPlugin#replace}/{@code #revertPlayer} for how this is
 * actually applied, and its class doc for the full caveat that this is
 * genuinely untested against a live client - only in-game testing can confirm
 * whether the local player's own composition rendering actually differs from
 * a remote player's here.
 */
@Value
public class PlayerOverride
{
	String sourceName;
	NpcChoice replacement;
	boolean enabled;
	boolean animationsDisabled;
	boolean showEquipment;
}
