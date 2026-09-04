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

/**
 * How the "Special operations" auto-override feature picks a replacement for a
 * player who renders with no override of their own - see {@code
 * PlayerNpcReplacerPlugin#maybeAutoApplyOverride}. Every mode draws from the
 * same Active NPCs list the rest of the plugin uses, so an empty Active list
 * means nothing is auto-applied regardless of mode.
 * <p>
 * Persisted by {@link #name()} rather than ordinal, so reordering or inserting
 * constants here can't silently repoint a user's saved setting at a different
 * mode. An unrecognized stored value falls back to {@link #OFF}.
 */
enum AutoOverrideMode
{
	/** No automatic overrides - players are only ever overridden manually. */
	OFF("Off"),

	/** Each eligible player gets an independently-chosen random Active NPC. */
	RANDOM("Random"),

	/**
	 * Eligible players are assigned Active NPCs in list order, advancing one
	 * entry per assignment and wrapping at the end. The position is
	 * session-only (deliberately not persisted): the Active list can be
	 * reordered or resized between sessions, which would make a restored
	 * index point somewhere arbitrary rather than "where it left off".
	 */
	ORDERED("Ordered");

	private final String displayName;

	AutoOverrideMode(String displayName)
	{
		this.displayName = displayName;
	}

	/** Shown directly in the panel's mode dropdown. */
	@Override
	public String toString()
	{
		return displayName;
	}

	/** @return the mode stored under {@code name}, or {@link #OFF} if it's absent/unrecognized. */
	static AutoOverrideMode fromName(String name)
	{
		if (name != null)
		{
			for (AutoOverrideMode mode : values())
			{
				if (mode.name().equals(name))
				{
					return mode;
				}
			}
		}
		return OFF;
	}
}
