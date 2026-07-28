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

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JViewport;
import javax.swing.ListCellRenderer;
import javax.swing.ListModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.ToolTipManager;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.IconTextField;

/**
 * The plugin's side panel. A collapsible "Overwritten NPCs" section (collapsed
 * by default, since it's a secondary management view) sits above everything
 * else: it lists every currently-persisted NPC replacement with a per-entry
 * hover-highlight checkbox and a remove button, so a replacement doesn't have to
 * be manually re-found in-game just to toggle its highlight or undo it. Below
 * that, "Active NPCs" (the list used in-game) is shown next, since that's the
 * thing you actually check/adjust most. Both it and the browse/
 * search section below are backed by a {@link JList} rather than one Swing
 * component per row - not just for the browse list's scale (the master NPC list
 * can run into the thousands), but because JList cells stretch to the list's full
 * width automatically and have a single well-defined row height, which is what
 * a manual JPanel-per-row layout kept getting wrong (rows narrower than the
 * panel, inconsistent gaps between them).
 * <p>
 * JList cell renderers are non-interactive "rubber stamps" - clicks never reach
 * components drawn inside one. So the active list's per-row ▲ ▼ ✕ controls are
 * drawn by {@link ActiveNpcCellRenderer} at fixed-width zones at the LEFT edge
 * of each row (in front of the name, so they're never pushed out of view by a
 * long name), and a single {@link MouseListener} on the list itself works out
 * which zone was clicked from the x-coordinate (see {@link #onActiveListClicked}).
 * <p>
 * Both lists also allow horizontal scrolling for names too long to fit the
 * panel width. Plain {@link JList} won't do this on its own - for a vertical
 * layout orientation it reports {@code getScrollableTracksViewportWidth() ==
 * true}, which force-shrinks every cell to the viewport width and silently
 * clips (rather than scrolls) anything wider - which is exactly what was
 * clipping the ▲▼✕ controls off-window before they were moved to the front.
 * {@link HScrollableJList} overrides that to only track the viewport width when
 * content actually fits, so a horizontal scrollbar appears otherwise.
 * <p>
 * Right below the browse/search section's own results list is a small
 * "Randomize Active NPCs" tool - deliberately reuses the type/body/size
 * filter combos already there (not the free-text search box, which has no
 * clear meaning for a quantity/randomize action) as the candidate pool, so
 * setting a filter first (e.g. body = Human) scopes the randomization the
 * same way it scopes the visible search results. {@code addRandomButton}
 * adds N distinct random picks on top of the current Active NPCs list (via
 * {@code PlayerNpcReplacerPlugin#selectNpcs}, same de-dupe-then-promote and
 * {@code MAX_ACTIVE_NPCS} eviction {@code selectNpc} already has for a single
 * pick); {@code replaceRandomButton} clears the list first ({@code
 * clearActiveNpcs}) for a clean N-npc set instead of adding on top.
 * <p>
 * Below the browse/search section is a "Bulk player tools" area - deliberately
 * players-only (not NPCs, which have no natural "radius around me" concept the
 * way nearby players do). Lets the user apply a chosen NPC or a random one
 * (drawn per-player from Active NPCs) to every other player within a
 * configurable tile radius, or clear overrides in-radius/everywhere. These
 * call straight into {@code PlayerNpcReplacerPlugin}'s bulk methods, which
 * reuse the exact same persistence path a single in-game Replace does, so
 * bulk-applied overrides behave identically afterward (survive respawn,
 * show up in "Overwritten Players", etc.) - there's no separate "bulk" state.
 * The animations-state selector (Playing/Paused) is applied explicitly to
 * every player a bulk apply/randomize affects, not carried over per-player
 * the way the single-target panel controls do - a bulk action is choosing a
 * setting to apply consistently, not inheriting whatever each player already
 * had. The same section also has one non-radius-scoped tool: "Apply to
 * yourself", which transforms the LOCAL player (reusing {@code bulkNpcCombo}'s
 * selection) rather than any nearby player - the only bulk tool with exactly
 * one possible target. Its "show my own equipment" checkbox sets {@link
 * PlayerOverride#showEquipment}, which the "Overwritten Players" list also
 * exposes per-row (E/H, only for the local player's own row - see {@link
 * OverridePlayerCellRenderer}).
 */
class PlayerNpcReplacerPanel extends PluginPanel
{
	private static final int MAX_RESULTS = 500;
	private static final String ANY_TYPE = "All types";
	private static final String ANY_SIZE = "All sizes";
	private static final String ANY_BODY = "All body types";
	private static final String ANIMATIONS_PLAYING = "Animations: Playing";
	private static final String ANIMATIONS_PAUSED = "Animations: Paused";
	private static final int ROW_HEIGHT = 26;
	private static final int CONTROL_ZONE_WIDTH = 20;

	private final PlayerNpcReplacerPlugin plugin;
	private final NpcIndex npcIndex;

	private final DefaultListModel<NpcOverride> overridesModel = new DefaultListModel<>();

	// Anonymous subclass (rather than a plain HScrollableJList<>) so hovering
	// each control zone shows what it does - same x-coordinate zone math as
	// onOverridesListClicked, kept in sync with it deliberately (both derive
	// from the same CONTROL_ZONE_WIDTH constant).
	private final JList<NpcOverride> overridesList = new HScrollableJList<NpcOverride>(overridesModel)
	{
		@Override
		public String getToolTipText(MouseEvent event)
		{
			final int i = locationToIndex(event.getPoint());
			if (i < 0 || i >= overridesModel.size())
			{
				return null;
			}
			final int x = event.getX();
			if (x < CONTROL_ZONE_WIDTH)
			{
				return "Enable/disable this override";
			}
			else if (x < 2 * CONTROL_ZONE_WIDTH)
			{
				return "Enable/disable animations for this override (turn off if they look glitchy)";
			}
			else if (x < 3 * CONTROL_ZONE_WIDTH)
			{
				return "Choose which npc's animations play: the replacement's own (R, default) "
					+ "or the original source npc's, unmatched (O)";
			}
			else if (x < 4 * CONTROL_ZONE_WIDTH)
			{
				return "Remove this override";
			}
			return null;
		}
	};
	private final JLabel overridesEmptyLabel = new JLabel("None yet - shift-right-click an NPC in-game and pick Replace.");
	private final JLabel overridesArrow = new JLabel();
	private final JLabel overridesHeaderText = new JLabel();
	private final JPanel overridesContent = new JPanel();
	private boolean overridesExpanded = false;

	private final DefaultListModel<PlayerOverride> playerOverridesModel = new DefaultListModel<>();

	// Same anonymous-subclass tooltip approach as overridesList - three zones,
	// same as the NPC list (checkbox, animations toggle, remove).
	private final JList<PlayerOverride> playerOverridesList = new HScrollableJList<PlayerOverride>(playerOverridesModel)
	{
		@Override
		public String getToolTipText(MouseEvent event)
		{
			final int i = locationToIndex(event.getPoint());
			if (i < 0 || i >= playerOverridesModel.size())
			{
				return null;
			}
			final int x = event.getX();
			if (x < CONTROL_ZONE_WIDTH)
			{
				return "Enable/disable this override";
			}
			else if (x < 2 * CONTROL_ZONE_WIDTH)
			{
				return "Pause this override's idle/walk animations (falls back to the npc's own captured "
					+ "animations if any were captured, else a static pose). Also lets mismatched "
					+ "action animations (mining, combat, etc.) play as a fun quirk instead of being "
					+ "suppressed, since paused is already an \"this might look a bit off\" state.";
			}
			else if (x < 3 * CONTROL_ZONE_WIDTH)
			{
				final PlayerOverride override = playerOverridesModel.get(i);
				return plugin.isLocalPlayerOverride(override)
					? "Show (E) or hide (H) your own real equipment on top of the npc model - experimental"
					: "Only available for your own overwritten appearance";
			}
			else if (x < 4 * CONTROL_ZONE_WIDTH)
			{
				return "Remove this override";
			}
			return null;
		}
	};
	private final JLabel playerOverridesEmptyLabel = new JLabel("None yet - shift-right-click a player in-game and pick Replace.");
	private final JLabel playerOverridesArrow = new JLabel();
	private final JLabel playerOverridesHeaderText = new JLabel();
	private final JPanel playerOverridesContent = new JPanel();
	private boolean playerOverridesExpanded = false;

	private final DefaultListModel<NpcChoice> activeModel = new DefaultListModel<>();
	private final JList<NpcChoice> activeList = new HScrollableJList<>(activeModel);
	private final JLabel activeEmptyLabel = new JLabel("None yet - search below and pick an NPC.");
	private final JLabel statusLabel = new JLabel();
	private final IconTextField searchBar = new IconTextField();
	private final JComboBox<String> typeFilter = new JComboBox<>();
	private final JComboBox<String> sizeFilter = new JComboBox<>();
	private final JComboBox<String> bodyFilter = new JComboBox<>();
	private final DefaultListModel<NpcChoice> resultsModel = new DefaultListModel<>();
	private final JList<NpcChoice> resultsList = new HScrollableJList<>(resultsModel);

	// Randomize/bulk-add Active NPCs - uses the type/body/size filters above
	// (not the free-text search) as the candidate pool, so "N random human
	// npcs" works by setting the body filter first. MAX_ACTIVE_NPCS itself is
	// plugin-private; 15 here is just this spinner's own upper bound, kept in
	// sync with it by convention rather than a shared constant.
	private final JSpinner randomQuantitySpinner = new JSpinner(new SpinnerNumberModel(5, 1, 15, 1));
	private final JButton addRandomButton = new JButton("Add random NPCs to Active list");
	private final JButton replaceRandomButton = new JButton("Replace Active list with random NPCs");

	// Bulk player tools - deliberately player-only (see PlayerNpcReplacerPlugin's
	// bulk methods), sits below the browse/search section.
	private final JSpinner radiusSpinner = new JSpinner(new SpinnerNumberModel(5, 1, 50, 1));
	private final JComboBox<NpcChoice> bulkNpcCombo = new JComboBox<>();
	private final JComboBox<String> bulkAnimationsStateCombo = new JComboBox<>(new String[]{ANIMATIONS_PLAYING, ANIMATIONS_PAUSED});
	private final JCheckBox skipExistingCheckbox = new JCheckBox("Skip players that already have an override");
	private final JButton bulkApplyButton = new JButton("Apply to players in radius");
	private final JButton bulkRandomizeButton = new JButton("Randomize players in radius (from Active NPCs)");
	private final JButton bulkClearRadiusButton = new JButton("Clear players in radius");
	private final JButton bulkClearOutsideRadiusButton = new JButton("Clear players NOT in radius");
	private final JButton bulkClearAllButton = new JButton("Clear ALL player overrides");

	// Apply to yourself - the one bulk-tools entry point that isn't radius-
	// scoped (there's exactly one local player). Reuses bulkNpcCombo's
	// selection rather than a second NPC picker.
	private final JCheckBox selfShowEquipmentCheckbox = new JCheckBox("Show my own equipment (experimental)");
	private final JButton selfApplyButton = new JButton("Apply to yourself (uses NPC selected above)");

	private boolean indexBuilt = false;

	@Inject
	PlayerNpcReplacerPanel(PlayerNpcReplacerPlugin plugin, NpcIndex npcIndex)
	{
		this.plugin = plugin;
		this.npcIndex = npcIndex;

		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));

		overridesList.setBackground(ColorScheme.DARK_GRAY_COLOR);
		overridesList.setFixedCellHeight(ROW_HEIGHT);
		overridesList.setCellRenderer(new OverrideCellRenderer());
		// Overriding getToolTipText(MouseEvent) directly (rather than a static
		// setToolTipText(...)) needs this explicit registration - setToolTipText
		// normally does it implicitly, but that's not called here.
		ToolTipManager.sharedInstance().registerComponent(overridesList);
		overridesList.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				onOverridesListClicked(e);
			}
		});

		overridesEmptyLabel.setForeground(Color.GRAY);
		overridesEmptyLabel.setFont(FontManager.getRunescapeSmallFont());
		overridesEmptyLabel.setBorder(new EmptyBorder(6, 4, 6, 4));
		overridesEmptyLabel.setAlignmentX(LEFT_ALIGNMENT);

		final JScrollPane overridesScroll = new JScrollPane(overridesList);
		overridesScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
		overridesScroll.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH, 140));
		overridesScroll.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 140));
		overridesScroll.setAlignmentX(LEFT_ALIGNMENT);

		overridesContent.setLayout(new BoxLayout(overridesContent, BoxLayout.Y_AXIS));
		overridesContent.setBackground(ColorScheme.DARK_GRAY_COLOR);
		overridesContent.setAlignmentX(LEFT_ALIGNMENT);
		overridesContent.add(overridesEmptyLabel);
		overridesContent.add(overridesScroll);

		overridesArrow.setForeground(Color.WHITE);
		overridesArrow.setFont(FontManager.getRunescapeBoldFont().deriveFont(10f));
		overridesArrow.setBorder(new EmptyBorder(0, 0, 0, 4));

		overridesHeaderText.setForeground(Color.WHITE);
		overridesHeaderText.setFont(FontManager.getRunescapeBoldFont().deriveFont(12f));

		final JPanel overridesHeaderLabels = new JPanel(new BorderLayout());
		overridesHeaderLabels.setOpaque(false);
		overridesHeaderLabels.add(overridesArrow, BorderLayout.WEST);
		overridesHeaderLabels.add(overridesHeaderText, BorderLayout.CENTER);

		final JPanel overridesHeaderRow = new JPanel(new BorderLayout());
		overridesHeaderRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		overridesHeaderRow.setBorder(new EmptyBorder(8, 0, 4, 0));
		overridesHeaderRow.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 22));
		overridesHeaderRow.setAlignmentX(LEFT_ALIGNMENT);
		overridesHeaderRow.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		overridesHeaderRow.add(overridesHeaderLabels, BorderLayout.WEST);
		overridesHeaderRow.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				setOverridesExpanded(!overridesExpanded);
			}
		});

		playerOverridesList.setBackground(ColorScheme.DARK_GRAY_COLOR);
		playerOverridesList.setFixedCellHeight(ROW_HEIGHT);
		playerOverridesList.setCellRenderer(new OverridePlayerCellRenderer(plugin));
		ToolTipManager.sharedInstance().registerComponent(playerOverridesList);
		playerOverridesList.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				onPlayerOverridesListClicked(e);
			}
		});

		playerOverridesEmptyLabel.setForeground(Color.GRAY);
		playerOverridesEmptyLabel.setFont(FontManager.getRunescapeSmallFont());
		playerOverridesEmptyLabel.setBorder(new EmptyBorder(6, 4, 6, 4));
		playerOverridesEmptyLabel.setAlignmentX(LEFT_ALIGNMENT);

		final JScrollPane playerOverridesScroll = new JScrollPane(playerOverridesList);
		playerOverridesScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
		playerOverridesScroll.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH, 140));
		playerOverridesScroll.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 140));
		playerOverridesScroll.setAlignmentX(LEFT_ALIGNMENT);

		playerOverridesContent.setLayout(new BoxLayout(playerOverridesContent, BoxLayout.Y_AXIS));
		playerOverridesContent.setBackground(ColorScheme.DARK_GRAY_COLOR);
		playerOverridesContent.setAlignmentX(LEFT_ALIGNMENT);
		playerOverridesContent.add(playerOverridesEmptyLabel);
		playerOverridesContent.add(playerOverridesScroll);

		playerOverridesArrow.setForeground(Color.WHITE);
		playerOverridesArrow.setFont(FontManager.getRunescapeBoldFont().deriveFont(10f));
		playerOverridesArrow.setBorder(new EmptyBorder(0, 0, 0, 4));

		playerOverridesHeaderText.setForeground(Color.WHITE);
		playerOverridesHeaderText.setFont(FontManager.getRunescapeBoldFont().deriveFont(12f));

		final JPanel playerOverridesHeaderLabels = new JPanel(new BorderLayout());
		playerOverridesHeaderLabels.setOpaque(false);
		playerOverridesHeaderLabels.add(playerOverridesArrow, BorderLayout.WEST);
		playerOverridesHeaderLabels.add(playerOverridesHeaderText, BorderLayout.CENTER);

		final JPanel playerOverridesHeaderRow = new JPanel(new BorderLayout());
		playerOverridesHeaderRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		playerOverridesHeaderRow.setBorder(new EmptyBorder(8, 0, 4, 0));
		playerOverridesHeaderRow.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 22));
		playerOverridesHeaderRow.setAlignmentX(LEFT_ALIGNMENT);
		playerOverridesHeaderRow.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		playerOverridesHeaderRow.add(playerOverridesHeaderLabels, BorderLayout.WEST);
		playerOverridesHeaderRow.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				setPlayerOverridesExpanded(!playerOverridesExpanded);
			}
		});

		activeList.setBackground(ColorScheme.DARK_GRAY_COLOR);
		activeList.setFixedCellHeight(ROW_HEIGHT);
		activeList.setCellRenderer(new ActiveNpcCellRenderer());
		activeList.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				onActiveListClicked(e);
			}
		});

		activeEmptyLabel.setForeground(Color.GRAY);
		activeEmptyLabel.setFont(FontManager.getRunescapeSmallFont());
		activeEmptyLabel.setBorder(new EmptyBorder(6, 4, 6, 4));

		final JScrollPane activeScroll = new JScrollPane(activeList);
		activeScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
		activeScroll.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH, 180));
		activeScroll.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 180));
		activeScroll.setAlignmentX(LEFT_ALIGNMENT);

		searchBar.setIcon(IconTextField.Icon.SEARCH);
		searchBar.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH, 30));
		searchBar.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 30));
		searchBar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		searchBar.setHoverBackgroundColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
		searchBar.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				updateResults();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				updateResults();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				updateResults();
			}
		});
		searchBar.addClearListener(this::updateResults);

		typeFilter.addItem(ANY_TYPE);
		typeFilter.addActionListener(e -> updateResults());
		sizeFilter.addItem(ANY_SIZE);
		sizeFilter.addActionListener(e -> updateResults());
		bodyFilter.addItem(ANY_BODY);
		bodyFilter.addItem(NpcIndex.BODY_HUMAN);
		bodyFilter.addItem(NpcIndex.BODY_BIPEDAL);
		bodyFilter.addItem(NpcIndex.BODY_NON_HUMAN);
		bodyFilter.addActionListener(e -> updateResults());

		// GridLayout, not BorderLayout - three combos now, and BorderLayout only
		// has one slot per compass direction (CENTER/EAST was enough for two).
		final JPanel filters = new JPanel(new GridLayout(1, 3, 4, 0));
		filters.setBackground(ColorScheme.DARK_GRAY_COLOR);
		filters.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 26));
		filters.setAlignmentX(LEFT_ALIGNMENT);
		filters.add(typeFilter);
		filters.add(bodyFilter);
		filters.add(sizeFilter);

		statusLabel.setForeground(Color.LIGHT_GRAY);
		statusLabel.setFont(FontManager.getRunescapeSmallFont());
		statusLabel.setHorizontalAlignment(SwingConstants.CENTER);
		statusLabel.setAlignmentX(LEFT_ALIGNMENT);
		statusLabel.setText("Loading NPC list...");

		resultsList.setBackground(ColorScheme.DARK_GRAY_COLOR);
		resultsList.setFixedCellHeight(ROW_HEIGHT);
		resultsList.setCellRenderer(new NpcCellRenderer());
		resultsList.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				final int i = resultsList.locationToIndex(e.getPoint());
				if (i < 0 || i >= resultsModel.size())
				{
					return;
				}
				plugin.selectNpc(resultsModel.get(i));
				refreshActiveList();
			}
		});
		final JScrollPane resultsScroll = new JScrollPane(resultsList);
		resultsScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
		resultsScroll.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH, 320));
		resultsScroll.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 320));
		resultsScroll.setAlignmentX(LEFT_ALIGNMENT);

		final JLabel randomHeader = section("Randomize Active NPCs (uses filters above)");
		randomHeader.setAlignmentX(LEFT_ALIGNMENT);

		randomQuantitySpinner.setMaximumSize(new Dimension(60, 24));
		final JPanel randomQuantityRow = new JPanel(new BorderLayout(4, 0));
		randomQuantityRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		randomQuantityRow.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 26));
		randomQuantityRow.setAlignmentX(LEFT_ALIGNMENT);
		final JLabel randomQuantityLabel = new JLabel("Quantity:");
		randomQuantityLabel.setForeground(Color.WHITE);
		randomQuantityLabel.setFont(FontManager.getRunescapeSmallFont());
		randomQuantityRow.add(randomQuantityLabel, BorderLayout.WEST);
		randomQuantityRow.add(randomQuantitySpinner, BorderLayout.EAST);

		addRandomButton.setAlignmentX(LEFT_ALIGNMENT);
		addRandomButton.addActionListener(e -> randomizeActiveNpcs(false));

		replaceRandomButton.setAlignmentX(LEFT_ALIGNMENT);
		replaceRandomButton.addActionListener(e -> randomizeActiveNpcs(true));

		final JLabel bulkToolsHeader = section("Bulk player tools (players only, not npcs)");
		bulkToolsHeader.setAlignmentX(LEFT_ALIGNMENT);

		final JLabel bulkToolsDescription = new JLabel("Affects other players within range of you.");
		bulkToolsDescription.setForeground(Color.GRAY);
		bulkToolsDescription.setFont(FontManager.getRunescapeSmallFont());
		bulkToolsDescription.setAlignmentX(LEFT_ALIGNMENT);

		radiusSpinner.setMaximumSize(new Dimension(60, 24));
		final JPanel radiusRow = new JPanel(new BorderLayout(4, 0));
		radiusRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		radiusRow.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 26));
		radiusRow.setAlignmentX(LEFT_ALIGNMENT);
		final JLabel radiusLabel = new JLabel("Radius (tiles):");
		radiusLabel.setForeground(Color.WHITE);
		radiusLabel.setFont(FontManager.getRunescapeSmallFont());
		radiusRow.add(radiusLabel, BorderLayout.WEST);
		radiusRow.add(radiusSpinner, BorderLayout.EAST);

		bulkNpcCombo.setRenderer((list, value, index, isSelected, cellHasFocus) ->
			new JLabel(value == null ? "" : value.getName()));
		bulkNpcCombo.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 26));
		bulkNpcCombo.setAlignmentX(LEFT_ALIGNMENT);

		bulkAnimationsStateCombo.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 26));
		bulkAnimationsStateCombo.setAlignmentX(LEFT_ALIGNMENT);

		bulkApplyButton.setAlignmentX(LEFT_ALIGNMENT);
		bulkApplyButton.addActionListener(e ->
		{
			final NpcChoice choice = (NpcChoice) bulkNpcCombo.getSelectedItem();
			if (choice != null)
			{
				plugin.bulkApplyToPlayersInRadius(getRadius(), choice, skipExistingCheckbox.isSelected(), isBulkAnimationsPaused());
			}
		});

		bulkRandomizeButton.setAlignmentX(LEFT_ALIGNMENT);
		bulkRandomizeButton.addActionListener(e ->
			plugin.bulkRandomizePlayersInRadius(getRadius(), skipExistingCheckbox.isSelected(), isBulkAnimationsPaused()));

		skipExistingCheckbox.setForeground(Color.LIGHT_GRAY);
		skipExistingCheckbox.setFont(FontManager.getRunescapeSmallFont());
		skipExistingCheckbox.setOpaque(false);
		skipExistingCheckbox.setAlignmentX(LEFT_ALIGNMENT);

		selfShowEquipmentCheckbox.setForeground(Color.LIGHT_GRAY);
		selfShowEquipmentCheckbox.setFont(FontManager.getRunescapeSmallFont());
		selfShowEquipmentCheckbox.setOpaque(false);
		selfShowEquipmentCheckbox.setAlignmentX(LEFT_ALIGNMENT);

		selfApplyButton.setAlignmentX(LEFT_ALIGNMENT);
		selfApplyButton.addActionListener(e ->
		{
			final NpcChoice choice = (NpcChoice) bulkNpcCombo.getSelectedItem();
			if (choice != null)
			{
				plugin.applySelfOverride(choice, selfShowEquipmentCheckbox.isSelected());
			}
		});

		bulkClearRadiusButton.setAlignmentX(LEFT_ALIGNMENT);
		bulkClearRadiusButton.addActionListener(e -> plugin.bulkClearPlayersInRadius(getRadius()));

		bulkClearOutsideRadiusButton.setAlignmentX(LEFT_ALIGNMENT);
		bulkClearOutsideRadiusButton.addActionListener(e -> plugin.bulkClearPlayersOutsideRadius(getRadius()));

		bulkClearAllButton.setForeground(new Color(220, 90, 90));
		bulkClearAllButton.setAlignmentX(LEFT_ALIGNMENT);
		bulkClearAllButton.addActionListener(e ->
		{
			final int count = plugin.getPlayerOverrides().size();
			final int result = JOptionPane.showConfirmDialog(this,
				"Remove all " + count + " persisted player override(s), wherever they are? This cannot be undone.",
				"Clear all player overrides", JOptionPane.YES_NO_OPTION);
			if (result == JOptionPane.YES_OPTION)
			{
				plugin.bulkClearAllPlayerOverrides();
			}
		});

		final JLabel selfToolsHeader = new JLabel("Apply to yourself (not radius-scoped):");
		selfToolsHeader.setForeground(Color.GRAY);
		selfToolsHeader.setFont(FontManager.getRunescapeSmallFont());
		selfToolsHeader.setAlignmentX(LEFT_ALIGNMENT);
		selfToolsHeader.setBorder(new EmptyBorder(6, 0, 0, 0));

		final JLabel activeHeader = section("Active NPCs (top = Replace-quick default; all shown in the in-game Replace menu)");
		final JLabel browseHeader = section("Browse / search all NPCs");
		activeHeader.setAlignmentX(LEFT_ALIGNMENT);
		browseHeader.setAlignmentX(LEFT_ALIGNMENT);
		searchBar.setAlignmentX(LEFT_ALIGNMENT);
		activeEmptyLabel.setAlignmentX(LEFT_ALIGNMENT);

		add(overridesHeaderRow);
		add(overridesContent);
		add(Box.createVerticalStrut(4));
		add(playerOverridesHeaderRow);
		add(playerOverridesContent);
		add(Box.createVerticalStrut(4));
		add(activeHeader);
		add(activeEmptyLabel);
		add(activeScroll);
		add(Box.createVerticalStrut(10));
		add(browseHeader);
		add(searchBar);
		add(Box.createVerticalStrut(4));
		add(filters);
		add(statusLabel);
		add(resultsScroll);
		add(randomHeader);
		add(randomQuantityRow);
		add(Box.createVerticalStrut(4));
		add(addRandomButton);
		add(replaceRandomButton);
		add(bulkToolsHeader);
		add(bulkToolsDescription);
		add(Box.createVerticalStrut(4));
		add(radiusRow);
		add(Box.createVerticalStrut(4));
		add(bulkNpcCombo);
		add(bulkAnimationsStateCombo);
		add(bulkApplyButton);
		add(bulkRandomizeButton);
		add(skipExistingCheckbox);
		add(selfToolsHeader);
		add(selfShowEquipmentCheckbox);
		add(selfApplyButton);
		add(Box.createVerticalStrut(4));
		add(bulkClearRadiusButton);
		add(bulkClearOutsideRadiusButton);
		add(Box.createVerticalStrut(4));
		add(bulkClearAllButton);

		refreshActiveList();
		refreshOverridesList();
		refreshPlayerOverridesList();
		setOverridesExpanded(false);
		setPlayerOverridesExpanded(false);
	}

	@Override
	public void onActivate()
	{
		// All three lists are also mutated from the in-game right-click menu
		// (applying/removing an override, which the panel has no other way to
		// learn about), so re-sync from the plugin's live state every time this
		// panel becomes visible again, not just in response to the panel's own
		// clicks.
		refreshOverridesList();
		refreshPlayerOverridesList();
		refreshActiveList();

		if (indexBuilt)
		{
			return;
		}

		npcIndex.ensureBuilt(() ->
		{
			indexBuilt = true;
			statusLabel.setText(npcIndex.size() + " NPCs - type to search");

			for (String type : npcIndex.allTypes())
			{
				typeFilter.addItem(type);
			}
			for (Integer size : npcIndex.allSizes())
			{
				sizeFilter.addItem(size + "x" + size);
			}

			updateResults();
		});
	}

	private static JLabel section(String text)
	{
		JLabel label = new JLabel(text);
		label.setForeground(Color.WHITE);
		label.setFont(FontManager.getRunescapeBoldFont().deriveFont(12f));
		label.setBorder(new EmptyBorder(8, 0, 4, 0));
		return label;
	}

	private void updateResults()
	{
		resultsModel.clear();

		if (!indexBuilt)
		{
			return;
		}

		final List<NpcChoice> results = npcIndex.search(searchBar.getText(),
			selectedSizeFilter(), selectedTypeFilter(), selectedBodyFilter(), MAX_RESULTS);
		for (NpcChoice choice : results)
		{
			resultsModel.addElement(choice);
		}
	}

	/**
	 * Shared type/size/body filter parsing - used by both {@link
	 * #updateResults} (the visible search results) and {@link
	 * #randomizeActiveNpcs} (the random-npc candidate pool), so "randomize
	 * within the current filters" always means exactly the same filters the
	 * browse list itself is showing.
	 */
	private String selectedTypeFilter()
	{
		final String selected = (String) typeFilter.getSelectedItem();
		return selected == null || selected.equals(ANY_TYPE) ? null : selected;
	}

	private Integer selectedSizeFilter()
	{
		final String selected = (String) sizeFilter.getSelectedItem();
		return selected == null || selected.equals(ANY_SIZE) ? null : Integer.parseInt(selected.substring(0, selected.indexOf('x')));
	}

	private String selectedBodyFilter()
	{
		final String selected = (String) bodyFilter.getSelectedItem();
		return selected == null || selected.equals(ANY_BODY) ? null : selected;
	}

	/**
	 * Picks a random, distinct subset (up to {@link #randomQuantitySpinner}'s
	 * value) of the CURRENT type/body/size-filtered npc pool (ignoring the
	 * free-text search box - a quantity/randomize tool has no use for a name
	 * query) and adds them to the Active NPCs list via {@link
	 * PlayerNpcReplacerPlugin#selectNpcs}, optionally clearing the list
	 * first. No-ops quietly if the index isn't built yet or the filtered
	 * pool is empty (e.g. an overly narrow filter combination).
	 */
	private void randomizeActiveNpcs(boolean replace)
	{
		if (!indexBuilt)
		{
			return;
		}

		final List<NpcChoice> pool = new ArrayList<>(
			npcIndex.search("", selectedSizeFilter(), selectedTypeFilter(), selectedBodyFilter(), npcIndex.size()));
		if (pool.isEmpty())
		{
			return;
		}
		Collections.shuffle(pool);

		final int quantity = Math.min((Integer) randomQuantitySpinner.getValue(), pool.size());
		final List<NpcChoice> picked = pool.subList(0, quantity);

		if (replace)
		{
			plugin.clearActiveNpcs();
		}
		plugin.selectNpcs(picked);
		refreshActiveList();
	}

	void refreshActiveList()
	{
		final List<NpcChoice> active = plugin.getActiveNpcs();

		activeModel.clear();
		for (NpcChoice choice : active)
		{
			activeModel.addElement(choice);
		}

		activeEmptyLabel.setVisible(active.isEmpty());
		activeList.setVisible(!active.isEmpty());

		refreshBulkNpcCombo(active);
	}

	/**
	 * Keeps {@link #bulkNpcCombo}'s options in sync with the Active NPCs list -
	 * that's the pool the bulk "apply chosen NPC" tool picks from, same as the
	 * in-game Replace submenu. Preserves the current selection across a refresh
	 * if it's still present (e.g. after a size change elsewhere didn't affect
	 * this particular choice), falling back to no selection otherwise rather
	 * than silently jumping to a different NPC.
	 */
	private void refreshBulkNpcCombo(List<NpcChoice> active)
	{
		final NpcChoice previouslySelected = (NpcChoice) bulkNpcCombo.getSelectedItem();
		bulkNpcCombo.removeAllItems();
		for (NpcChoice choice : active)
		{
			bulkNpcCombo.addItem(choice);
		}
		if (previouslySelected != null && active.contains(previouslySelected))
		{
			bulkNpcCombo.setSelectedItem(previouslySelected);
		}
	}

	private int getRadius()
	{
		return (Integer) radiusSpinner.getValue();
	}

	private boolean isBulkAnimationsPaused()
	{
		return ANIMATIONS_PAUSED.equals(bulkAnimationsStateCombo.getSelectedItem());
	}

	/**
	 * Works out which of the row's zones (▲ up / ▼ down / ✕ remove / name-select)
	 * was clicked from the x position, using the same {@link #CONTROL_ZONE_WIDTH}
	 * the renderer draws those zones at. The zones sit at the row's LEFT edge (in
	 * front of the name) - matching {@link ActiveNpcCellRenderer} - so a click's
	 * x is relative to the row's own content start, not the visible viewport, and
	 * this still works correctly when the list has been scrolled horizontally.
	 */
	private void onActiveListClicked(MouseEvent e)
	{
		final int i = activeList.locationToIndex(e.getPoint());
		if (i < 0 || i >= activeModel.size())
		{
			return;
		}
		final NpcChoice choice = activeModel.get(i);

		final int x = e.getX();

		if (x < CONTROL_ZONE_WIDTH)
		{
			plugin.moveUp(choice);
		}
		else if (x < 2 * CONTROL_ZONE_WIDTH)
		{
			plugin.moveDown(choice);
		}
		else if (x < 3 * CONTROL_ZONE_WIDTH)
		{
			plugin.removeNpc(choice);
		}
		else
		{
			plugin.selectNpc(choice);
		}
		refreshActiveList();
	}

	private void setOverridesExpanded(boolean expanded)
	{
		overridesExpanded = expanded;
		overridesArrow.setText(expanded ? "▼" : "▶");
		overridesContent.setVisible(expanded);
		revalidate();
		repaint();
	}

	void refreshOverridesList()
	{
		final List<NpcOverride> overrides = plugin.getNpcOverrides();

		overridesModel.clear();
		for (NpcOverride override : overrides)
		{
			overridesModel.addElement(override);
		}

		overridesEmptyLabel.setVisible(overrides.isEmpty());
		overridesList.setVisible(!overrides.isEmpty());
		overridesHeaderText.setText("Overwritten NPCs (" + overrides.size() + ")");
	}

	/**
	 * Three zones at the row's left edge - checkbox (toggle the override on/off),
	 * then a walking-figure glyph (toggle animations on/off for this override),
	 * then R/O (choose replacement's own vs. original source's animations),
	 * then ✕ (remove) - same left-anchored, x-coordinate hit-testing approach as
	 * {@link #onActiveListClicked}, for the same reason (keeps controls visible
	 * regardless of how long the "Source -> Replacement" name pair is).
	 * <p>
	 * Deliberately does NOT refresh the list itself after calling into the
	 * plugin - {@link PlayerNpcReplacerPlugin#setOverrideEnabled},
	 * {@link PlayerNpcReplacerPlugin#setAnimationsEnabled},
	 * {@link PlayerNpcReplacerPlugin#setUseOriginalAnimations}, and
	 * {@link PlayerNpcReplacerPlugin#removeNpcOverrideById} all dispatch their
	 * actual work onto the client thread asynchronously, so refreshing right here
	 * would read {@link #overridesModel}'s backing data before that queued work
	 * had run, showing stale state until the next click happened to catch it up.
	 * All four plugin methods push their own refresh once the mutation actually
	 * completes.
	 */
	private void onOverridesListClicked(MouseEvent e)
	{
		final int i = overridesList.locationToIndex(e.getPoint());
		if (i < 0 || i >= overridesModel.size())
		{
			return;
		}
		final NpcOverride override = overridesModel.get(i);

		final int x = e.getX();
		if (x < CONTROL_ZONE_WIDTH)
		{
			plugin.setOverrideEnabled(override.getSourceId(), !override.isEnabled());
		}
		else if (x < 2 * CONTROL_ZONE_WIDTH)
		{
			plugin.setAnimationsEnabled(override.getSourceId(), override.isAnimationsDisabled());
		}
		else if (x < 3 * CONTROL_ZONE_WIDTH)
		{
			plugin.setUseOriginalAnimations(override.getSourceId(), !override.isUseOriginalAnimations());
		}
		else if (x < 4 * CONTROL_ZONE_WIDTH)
		{
			plugin.removeNpcOverrideById(override.getSourceId());
		}
	}

	private void setPlayerOverridesExpanded(boolean expanded)
	{
		playerOverridesExpanded = expanded;
		playerOverridesArrow.setText(expanded ? "▼" : "▶");
		playerOverridesContent.setVisible(expanded);
		revalidate();
		repaint();
	}

	void refreshPlayerOverridesList()
	{
		final List<PlayerOverride> overrides = plugin.getPlayerOverrides();

		playerOverridesModel.clear();
		for (PlayerOverride override : overrides)
		{
			playerOverridesModel.addElement(override);
		}

		playerOverridesEmptyLabel.setVisible(overrides.isEmpty());
		playerOverridesList.setVisible(!overrides.isEmpty());
		playerOverridesHeaderText.setText("Overwritten Players (" + overrides.size() + ")");
	}

	/**
	 * Four zones at the row's left edge - checkbox (toggle the override
	 * on/off), then ▶/⏸ (toggle animations for this override), then E/H (show
	 * or hide real equipment - only wired for the local player's own row,
	 * see {@link PlayerOverride#showEquipment}'s doc for why), then ✕ (remove).
	 * Same "don't refresh here, the plugin methods push their own once their
	 * async client-thread work actually completes" reasoning as
	 * {@link #onOverridesListClicked}.
	 */
	private void onPlayerOverridesListClicked(MouseEvent e)
	{
		final int i = playerOverridesList.locationToIndex(e.getPoint());
		if (i < 0 || i >= playerOverridesModel.size())
		{
			return;
		}
		final PlayerOverride override = playerOverridesModel.get(i);

		final int x = e.getX();
		if (x < CONTROL_ZONE_WIDTH)
		{
			plugin.setPlayerOverrideEnabled(override.getSourceName(), !override.isEnabled());
		}
		else if (x < 2 * CONTROL_ZONE_WIDTH)
		{
			plugin.setPlayerAnimationsEnabled(override.getSourceName(), override.isAnimationsDisabled());
		}
		else if (x < 3 * CONTROL_ZONE_WIDTH)
		{
			if (plugin.isLocalPlayerOverride(override))
			{
				plugin.setPlayerShowEquipment(override.getSourceName(), !override.isShowEquipment());
			}
		}
		else if (x < 4 * CONTROL_ZONE_WIDTH)
		{
			plugin.removePlayerOverride(override.getSourceName());
		}
	}

	private static String describe(NpcChoice choice)
	{
		final String body = choice.getBodyType();
		return choice.getName() + "  (" + choice.getSize() + "x" + choice.getSize() + ", " + choice.getType()
			+ (body == null ? "" : ", " + body) + ")";
	}

	private static class NpcCellRenderer extends JLabel implements ListCellRenderer<NpcChoice>
	{
		NpcCellRenderer()
		{
			setOpaque(true);
			setFont(FontManager.getRunescapeSmallFont());
			setBorder(new EmptyBorder(0, 6, 0, 6));
		}

		@Override
		public Component getListCellRendererComponent(JList<? extends NpcChoice> list, NpcChoice value, int index,
			boolean isSelected, boolean cellHasFocus)
		{
			setText(describe(value));
			setBackground(ColorScheme.DARK_GRAY_COLOR);
			setForeground(Color.WHITE);
			return this;
		}
	}

	/**
	 * Draws three fixed-width control zones (▲ ▼ ✕, each {@link #CONTROL_ZONE_WIDTH}
	 * wide) at the LEFT, then "★ Name (SxS, Type)". The controls go in front of
	 * the name - not after it - so a long name pushing the row wider than the
	 * panel (now scrollable, see the class doc) can never carry them out of view;
	 * they're always the first thing visible. This is a "rubber stamp" - it never
	 * receives clicks itself; {@link #onActiveListClicked} interprets clicks on
	 * the real JList using the same zone widths.
	 */
	private static class ActiveNpcCellRenderer extends JPanel implements ListCellRenderer<NpcChoice>
	{
		private final JLabel nameLabel = new JLabel();
		private final JLabel upLabel = new JLabel("▲", SwingConstants.CENTER);
		private final JLabel downLabel = new JLabel("▼", SwingConstants.CENTER);
		private final JLabel removeLabel = new JLabel("✕", SwingConstants.CENTER);

		ActiveNpcCellRenderer()
		{
			setLayout(new BorderLayout());
			setOpaque(true);

			nameLabel.setFont(FontManager.getRunescapeSmallFont());
			nameLabel.setBorder(new EmptyBorder(0, 6, 0, 0));

			final JPanel controls = new JPanel(new GridLayout(1, 3));
			controls.setOpaque(false);
			for (JLabel label : new JLabel[]{upLabel, downLabel, removeLabel})
			{
				label.setFont(FontManager.getRunescapeSmallFont());
				label.setPreferredSize(new Dimension(CONTROL_ZONE_WIDTH, ROW_HEIGHT));
				controls.add(label);
			}

			add(controls, BorderLayout.WEST);
			add(nameLabel, BorderLayout.CENTER);
		}

		@Override
		public Component getListCellRendererComponent(JList<? extends NpcChoice> list, NpcChoice value, int index,
			boolean isSelected, boolean cellHasFocus)
		{
			final boolean isDefault = index == 0;
			nameLabel.setText((isDefault ? "★ " : "") + describe(value));
			nameLabel.setForeground(isDefault ? Color.ORANGE : Color.WHITE);

			final int size = list.getModel().getSize();
			upLabel.setForeground(index > 0 ? Color.LIGHT_GRAY : ColorScheme.DARK_GRAY_COLOR);
			downLabel.setForeground(index < size - 1 ? Color.LIGHT_GRAY : ColorScheme.DARK_GRAY_COLOR);
			removeLabel.setForeground(Color.GRAY);

			setBackground(ColorScheme.DARK_GRAY_COLOR);
			return this;
		}
	}

	/**
	 * Four fixed-width control zones (checkbox, walking-figure animations
	 * toggle, R/O animation-source toggle, ✕) at the LEFT, then
	 * "Source → Replacement". Same "rubber stamp" / left-anchored-controls
	 * reasoning as {@link ActiveNpcCellRenderer}; {@link #onOverridesListClicked}
	 * interprets clicks on the real JList using the same zone widths. The
	 * animations glyph uses ▶/⏸ (play/pause) rather than a literal pictograph
	 * "stick figure" character - RuneLite's bitmap game font is only confirmed
	 * to support plain dingbat-style glyphs like the ones already used
	 * elsewhere in this panel (☑ ☐ ✕ ▲ ▼ ★ ▶), not full pictorial emoji, and ▶
	 * specifically is already proven to render correctly in this exact font
	 * (used for the collapsible section's expand arrow) - play/pause also maps
	 * onto "animating vs. static" at least as clearly as a person icon would.
	 * The R/O toggle uses plain letters for the same font-safety reason, and
	 * to avoid the ambiguity a single-letter "O" for both "Original" and
	 * "Overwritten" would create - R/O (Replacement/Original) is unambiguous
	 * and matches this codebase's own source/replacement terminology.
	 */
	private static class OverrideCellRenderer extends JPanel implements ListCellRenderer<NpcOverride>
	{
		private final JLabel nameLabel = new JLabel();
		private final JLabel checkLabel = new JLabel("", SwingConstants.CENTER);
		private final JLabel animLabel = new JLabel("", SwingConstants.CENTER);
		private final JLabel animSourceLabel = new JLabel("", SwingConstants.CENTER);
		private final JLabel removeLabel = new JLabel("✕", SwingConstants.CENTER);

		OverrideCellRenderer()
		{
			setLayout(new BorderLayout());
			setOpaque(true);

			nameLabel.setFont(FontManager.getRunescapeSmallFont());
			nameLabel.setBorder(new EmptyBorder(0, 6, 0, 0));

			final JPanel controls = new JPanel(new GridLayout(1, 4));
			controls.setOpaque(false);
			for (JLabel label : new JLabel[]{checkLabel, animLabel, animSourceLabel, removeLabel})
			{
				label.setFont(FontManager.getRunescapeSmallFont());
				label.setPreferredSize(new Dimension(CONTROL_ZONE_WIDTH, ROW_HEIGHT));
				controls.add(label);
			}

			add(controls, BorderLayout.WEST);
			add(nameLabel, BorderLayout.CENTER);
		}

		@Override
		public Component getListCellRendererComponent(JList<? extends NpcOverride> list, NpcOverride value, int index,
			boolean isSelected, boolean cellHasFocus)
		{
			nameLabel.setText(value.getSourceName() + " → " + value.getReplacement().getName());
			nameLabel.setForeground(Color.WHITE);

			checkLabel.setText(value.isEnabled() ? "☑" : "☐");
			checkLabel.setForeground(value.isEnabled() ? new Color(80, 220, 80) : Color.GRAY);

			final boolean animationsEnabled = !value.isAnimationsDisabled();
			animLabel.setText(animationsEnabled ? "▶" : "⏸");
			animLabel.setForeground(animationsEnabled ? new Color(80, 220, 80) : Color.GRAY);

			animSourceLabel.setText(value.isUseOriginalAnimations() ? "O" : "R");
			animSourceLabel.setForeground(value.isUseOriginalAnimations() ? Color.ORANGE : new Color(80, 220, 80));

			removeLabel.setForeground(Color.GRAY);

			setBackground(ColorScheme.DARK_GRAY_COLOR);
			return this;
		}
	}

	/**
	 * Four fixed-width control zones (checkbox, ▶/⏸, E/H, ✕) at the LEFT, then
	 * "Source → Replacement" - the {@link PlayerOverride} equivalent of
	 * {@link OverrideCellRenderer}. {@link #onPlayerOverridesListClicked}
	 * interprets clicks on the real JList using the same zone widths. The E/H
	 * (equipment shown/hidden) zone only lights up and responds to clicks for
	 * the local player's own row - drawn dim/inert ("-") for every other
	 * override, since {@link PlayerOverride#showEquipment} only ever does
	 * anything for a self-override (see its doc). Same plain-letter,
	 * font-safety reasoning as the NPC list's R/O toggle.
	 */
	private static class OverridePlayerCellRenderer extends JPanel implements ListCellRenderer<PlayerOverride>
	{
		private final PlayerNpcReplacerPlugin plugin;
		private final JLabel nameLabel = new JLabel();
		private final JLabel checkLabel = new JLabel("", SwingConstants.CENTER);
		private final JLabel animLabel = new JLabel("", SwingConstants.CENTER);
		private final JLabel equipmentLabel = new JLabel("", SwingConstants.CENTER);
		private final JLabel removeLabel = new JLabel("✕", SwingConstants.CENTER);

		OverridePlayerCellRenderer(PlayerNpcReplacerPlugin plugin)
		{
			this.plugin = plugin;

			setLayout(new BorderLayout());
			setOpaque(true);

			nameLabel.setFont(FontManager.getRunescapeSmallFont());
			nameLabel.setBorder(new EmptyBorder(0, 6, 0, 0));

			final JPanel controls = new JPanel(new GridLayout(1, 4));
			controls.setOpaque(false);
			for (JLabel label : new JLabel[]{checkLabel, animLabel, equipmentLabel, removeLabel})
			{
				label.setFont(FontManager.getRunescapeSmallFont());
				label.setPreferredSize(new Dimension(CONTROL_ZONE_WIDTH, ROW_HEIGHT));
				controls.add(label);
			}

			add(controls, BorderLayout.WEST);
			add(nameLabel, BorderLayout.CENTER);
		}

		@Override
		public Component getListCellRendererComponent(JList<? extends PlayerOverride> list, PlayerOverride value, int index,
			boolean isSelected, boolean cellHasFocus)
		{
			nameLabel.setText(value.getSourceName() + " → " + value.getReplacement().getName());
			nameLabel.setForeground(Color.WHITE);

			checkLabel.setText(value.isEnabled() ? "☑" : "☐");
			checkLabel.setForeground(value.isEnabled() ? new Color(80, 220, 80) : Color.GRAY);

			final boolean animationsEnabled = !value.isAnimationsDisabled();
			animLabel.setText(animationsEnabled ? "▶" : "⏸");
			animLabel.setForeground(animationsEnabled ? new Color(80, 220, 80) : Color.GRAY);

			if (plugin.isLocalPlayerOverride(value))
			{
				equipmentLabel.setText(value.isShowEquipment() ? "E" : "H");
				equipmentLabel.setForeground(value.isShowEquipment() ? new Color(80, 220, 80) : Color.ORANGE);
			}
			else
			{
				equipmentLabel.setText("-");
				equipmentLabel.setForeground(ColorScheme.DARK_GRAY_COLOR);
			}

			removeLabel.setForeground(Color.GRAY);

			setBackground(ColorScheme.DARK_GRAY_COLOR);
			return this;
		}
	}

	/**
	 * A {@link JList} that actually supports horizontal scrolling. Plain JList,
	 * laid out vertically, reports {@code getScrollableTracksViewportWidth()} as
	 * {@code true} unconditionally - meaning the surrounding {@link JScrollPane}
	 * always force-shrinks it to the viewport's width, silently clipping any cell
	 * content wider than that instead of showing a horizontal scrollbar. This
	 * only tracks (matches) the viewport width when the list's own natural
	 * preferred width already fits within it; otherwise it reports its true,
	 * wider preferred width so the scroll pane grows a horizontal scrollbar.
	 */
	private static class HScrollableJList<T> extends JList<T>
	{
		HScrollableJList(ListModel<T> model)
		{
			super(model);
		}

		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			final Component parent = getParent();
			if (parent instanceof JViewport)
			{
				return getPreferredSize().width < parent.getWidth();
			}
			return super.getScrollableTracksViewportWidth();
		}
	}
}
