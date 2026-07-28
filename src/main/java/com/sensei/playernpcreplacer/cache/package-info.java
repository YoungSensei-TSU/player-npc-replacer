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

/**
 * <b>VENDORED CODE - do not edit by hand.</b>
 * <p>
 * Verbatim copies of RuneLite's own {@code net.runelite:cache} module classes,
 * with <i>only</i> the {@code package} declaration rewritten (and the now-
 * redundant same-package imports dropped). Sourced from the upstream RuneLite
 * repository (github.com/runelite/runelite), {@code
 * cache/src/main/java/net/runelite/cache/}:
 * <ul>
 * <li>{@code io/InputStream.java}</li>
 * <li>{@code definitions/NpcDefinition.java}, {@code definitions/ItemDefinition.java}</li>
 * <li>{@code definitions/loaders/NpcLoader.java}, {@code definitions/loaders/ItemLoader.java}</li>
 * <li>{@code EntityOpsDefinition.java}, {@code definitions/loaders/EntityOpsLoader.java}</li>
 * </ul>
 * Same BSD 2-Clause license as the originals; original copyright headers are
 * retained in each file.
 * <p>
 * <b>Why vendored instead of depended on:</b> this class pair
 * ({@code NpcCacheAnimationLookup}/{@code ItemCacheModelLookup}) was
 * originally developed and proven inside a full RuneLite client checkout,
 * where {@code net.runelite:cache} could not be added as a dependency of
 * {@code runelite-client}'s own build (verified live: removing an attempted
 * dependency line produced 10 "package net.runelite.cache does not exist"
 * errors, since that module isn't otherwise on the client's compile
 * classpath). Vendoring these specific decoder classes - rather than the
 * whole module - solved that with zero build-file changes, and was carried
 * over unchanged into this standalone repo rather than re-litigated, since it
 * demonstrably works and avoids taking on an extra external dependency
 * (and its own version-pinning/transitive-dependency surface) for six small,
 * self-contained files.
 * <p>
 * <b>What these are actually used for:</b> decoding ONLY. The raw definition
 * bytes come from the live client via
 * {@link net.runelite.api.Client#getIndexConfig()} +
 * {@link net.runelite.api.IndexDataBase#loadData}; these classes just parse
 * that {@code byte[]} in memory. Nothing here touches the disk - the cache
 * module's file-reading machinery ({@code Store}, {@code DiskStorage}, etc)
 * is deliberately NOT vendored, because this plugin never reads cache files.
 * They exist because the definition data this plugin needs is genuinely
 * absent from the public API: {@link net.runelite.api.NPCComposition} exposes
 * no animation fields, and {@link net.runelite.api.ItemComposition} exposes
 * no worn/equipped model fields (only {@code getInventoryModel()}).
 * <p>
 * <b>Maintenance caveat:</b> the definition formats change with game updates,
 * and upstream revises these loaders accordingly. If NPC animation lookups or
 * equipped-model lookups start returning wrong/garbage values after a game
 * update, re-copy these files from upstream rather than patching them here.
 * The decode is strictly sequential, so a single mis-handled opcode desyncs
 * the stream and silently yields plausible-looking but wrong ids.
 *
 * @see com.sensei.playernpcreplacer.NpcCacheAnimationLookup
 * @see com.sensei.playernpcreplacer.ItemCacheModelLookup
 */
package com.sensei.playernpcreplacer.cache;
