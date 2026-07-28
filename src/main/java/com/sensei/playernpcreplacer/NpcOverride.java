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

import com.google.gson.annotations.SerializedName;
import lombok.Value;

/**
 * A persisted (source npc id -> replacement) mapping, plus per-entry state for
 * the panel's "Overwritten NPCs" list. {@code sourceName} is captured at the
 * moment the override is applied (from the live NPC, since a stale/removed
 * source id alone isn't human-readable) purely for display - matching isn't
 * done by name anywhere, only by {@code sourceId}. Gson-serialized directly for
 * config storage, so field names/shape matter for save-file compatibility.
 * <p>
 * {@code enabled} controls whether the override is actually applied right now -
 * when false, the source npc shows its real, unmodified appearance (the clone is
 * torn down / never (re)created on spawn), but the mapping itself is kept, so
 * re-enabling reapplies the same replacement without having to search/pick it
 * again. The Java field is named {@code enabled} for clarity, but keeps the
 * original {@code highlightEnabled} JSON key via {@link SerializedName} - this
 * field predates and originally only gated the hover-highlight outline before
 * being expanded to gate the override itself, and renaming the JSON key too
 * would silently lose already-saved values on load (every previously-saved
 * entry defaulted this to true, so a botched migration would look like every
 * override just stopped applying).
 * <p>
 * {@code animationsDisabled} lets a specific override fall back to a fully
 * static model (no animation at all, including combat/skilling overrides) -
 * an escape hatch for replacements whose animations don't fit the source npc's
 * skeleton and end up looking glitchy, so the user can at least keep a clean
 * static model instead. Deliberately stored inverted (disabled, not enabled) -
 * this field didn't exist before this was added, so every already-persisted
 * override's JSON is missing this key entirely, and Gson defaults a missing
 * {@code boolean} to {@code false} on load. Storing it as "disabled" means that
 * default (false) correctly means "not disabled", i.e. animations still play,
 * which matches every pre-existing override's actual prior behavior - had this
 * been named/stored as "enabled" instead, every already-saved override would
 * have silently defaulted to animations OFF the moment this shipped (the same
 * class of regression {@code @SerializedName} above was added to avoid).
 * <p>
 * {@code useOriginalAnimations} lets the user choose which npc's animations
 * actually play on the clone, when it's not paused: the REPLACEMENT's own
 * (retargeted via {@code PlayerNpcReplacerPlugin#remapAnimationForReplacement} -
 * the default, {@code false}) or the SOURCE's raw ones played verbatim
 * (skipping remapping entirely - the pre-remapping behavior, useful when the
 * user judges the source's own animation actually looks better/more correct
 * on this specific replacement than the "correctly" retargeted one would).
 * Defaults to {@code false} (use the replacement's own) both for new
 * overrides and already-persisted ones missing this key, matching the
 * current/default behavior - same reasoning as {@code animationsDisabled}
 * above, just without needing the same "store inverted" trick since {@code
 * false} already happens to be the wanted default here.
 */
@Value
public class NpcOverride
{
	int sourceId;
	String sourceName;
	NpcChoice replacement;
	@SerializedName("highlightEnabled")
	boolean enabled;
	boolean animationsDisabled;
	boolean useOriginalAnimations;
}
