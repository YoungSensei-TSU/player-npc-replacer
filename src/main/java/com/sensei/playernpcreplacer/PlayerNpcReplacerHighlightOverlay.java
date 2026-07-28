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

import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.Point;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;

/**
 * A scoped-down version of the core {@code Interact Highlight} plugin: outlines
 * whichever NPC the cursor is currently hovering (via the same "what's the
 * hovered menu entry" logic that plugin uses), so users don't need to separately
 * install it just to see what they're about to shift-right-click Replace. Unlike
 * Interact Highlight, this deliberately only covers NPC hover - no objects,
 * items, players, or persistent "on interact" highlighting - since that's the
 * one thing actually relevant to using this plugin.
 * <p>
 * One gate applies on top of the main
 * {@link PlayerNpcReplacerConfig#highlightNpcsOnHover()} switch:
 * {@link PlayerNpcReplacerConfig#highlightOverwrittenOnly()} makes ordinary
 * (non-replaced) NPCs ineligible for highlighting at all. There's no separate
 * per-entry opt-out anymore - an NPC whose override is disabled from the
 * panel's "Overwritten NPCs" list is, by definition
 * ({@link PlayerNpcReplacerPlugin#isOverridden}), currently rendering as its
 * real unmodified self, so it's already indistinguishable from an ordinary
 * NPC here.
 */
class PlayerNpcReplacerHighlightOverlay extends Overlay
{
	private final Client client;
	private final PlayerNpcReplacerPlugin plugin;
	private final PlayerNpcReplacerConfig config;
	private final ModelOutlineRenderer modelOutlineRenderer;

	@Inject
	private PlayerNpcReplacerHighlightOverlay(Client client, PlayerNpcReplacerPlugin plugin,
		PlayerNpcReplacerConfig config, ModelOutlineRenderer modelOutlineRenderer)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		this.modelOutlineRenderer = modelOutlineRenderer;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.highlightNpcsOnHover())
		{
			return null;
		}

		final MenuEntry[] menuEntries = client.getMenuEntries();
		if (menuEntries.length == 0)
		{
			return null;
		}

		// If the right-click menu is open, use whichever row the mouse is over;
		// otherwise the top of the stack is the entry a left-click would trigger.
		final MenuEntry entry = client.isMenuOpen() ? hoveredMenuEntry(menuEntries) : menuEntries[menuEntries.length - 1];
		final NPC npc = entry.getNpc();
		if (npc == null)
		{
			return null;
		}

		// "Overwritten only" mode makes ordinary (and disabled-override) NPCs
		// ineligible for highlighting entirely.
		if (config.highlightOverwrittenOnly() && !plugin.isOverridden(npc.getId()))
		{
			return null;
		}

		modelOutlineRenderer.drawOutline(npc, config.npcHighlightBorderWidth(), config.npcHighlightColor(),
			config.npcHighlightFeather());

		return null;
	}

	/**
	 * Same menu-row hit-testing as {@code InteractHighlightOverlay} - the menu's
	 * own layout constants (19px "Choose Option" header, 15px per row) aren't
	 * exposed anywhere else to derive this from.
	 */
	private MenuEntry hoveredMenuEntry(MenuEntry[] menuEntries)
	{
		final int menuX = client.getMenuX();
		final int menuY = client.getMenuY();
		final int menuWidth = client.getMenuWidth();
		final Point mousePosition = client.getMouseCanvasPosition();

		int dy = mousePosition.getY() - menuY;
		dy -= 19; // Height of "Choose Option" header
		if (dy < 0)
		{
			return menuEntries[menuEntries.length - 1];
		}

		int idx = dy / 15; // Height of each menu option row
		idx = menuEntries.length - 1 - idx;

		if (mousePosition.getX() > menuX && mousePosition.getX() < menuX + menuWidth
			&& idx >= 0 && idx < menuEntries.length)
		{
			return menuEntries[idx];
		}
		return menuEntries[menuEntries.length - 1];
	}
}
