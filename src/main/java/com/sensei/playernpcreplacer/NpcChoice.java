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
 * A single NPC entry - both a search result from {@link NpcIndex} and an entry
 * in the plugin's persisted "active" list. Gson-serialized directly for config
 * storage, so field names/shape matter for save-file compatibility.
 * <p>
 * {@code size}, {@code type}, and {@code bodyType} are metadata pulled from
 * the NPC's composition at index-build time (see {@link NpcIndex}), used for
 * the panel's category filters. There's deliberately no "region" field - NPC
 * compositions carry no location data at all (that only exists on live
 * spawned instances, not the static definition every id in the game has), so
 * it can't be derived here.
 * <p>
 * {@code bodyType} is one of {@link NpcIndex#BODY_HUMAN}/
 * {@link NpcIndex#BODY_BIPEDAL}, or {@code null} if neither heuristic
 * matched - see {@link NpcIndex}'s classification doc for what it's actually
 * based on (name keywords) and its known limitations. Nullable rather than a
 * third "Other" constant since, unlike {@code type} (always exactly one of a
 * fixed set), most NPCs simply aren't bipedal at all and there's no
 * meaningful bucket to put them in.
 */
@Value
public class NpcChoice
{
	int id;
	String name;
	int size;
	String type;
	String bodyType;
}
