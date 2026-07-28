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
 * <b>VENDORED CODE - do not edit by hand.</b> Verbatim copies of RuneLite's
 * {@code net.runelite:cache} module classes (only the {@code package}
 * declaration changed), from {@code cache/src/main/java/net/runelite/cache/}
 * in github.com/runelite/runelite: {@code io/InputStream}, {@code
 * definitions/NpcDefinition}, {@code definitions/ItemDefinition}, {@code
 * definitions/loaders/NpcLoader}, {@code definitions/loaders/ItemLoader},
 * {@code EntityOpsDefinition}, {@code definitions/loaders/EntityOpsLoader}.
 * Same BSD 2-Clause license; original copyright headers retained per-file.
 * <p>
 * Vendored rather than depended on, to avoid an extra external dependency for
 * seven small files. Used for DECODING ONLY: {@link
 * com.sensei.playernpcreplacer.NpcCacheAnimationLookup}/{@link
 * com.sensei.playernpcreplacer.ItemCacheModelLookup} fetch the raw
 * definition bytes live via {@link net.runelite.api.Client#getIndexConfig()}
 * + {@link net.runelite.api.IndexDataBase#loadData}, then hand them to these
 * classes to parse in memory - nothing here touches disk, so the cache
 * module's file-reading machinery ({@code Store}, {@code DiskStorage}) isn't
 * vendored. They exist because {@link net.runelite.api.NPCComposition}
 * exposes no animation fields and {@link net.runelite.api.ItemComposition}
 * exposes no worn/equipped model fields.
 * <p>
 * <b>Maintenance:</b> definition formats change with game updates. If
 * lookups start returning wrong/garbage values, re-copy these files from
 * upstream rather than patch them - the decode is strictly sequential, so one
 * mis-handled opcode desyncs the stream and silently yields plausible-looking
 * wrong ids.
 *
 * @see com.sensei.playernpcreplacer.NpcCacheAnimationLookup
 * @see com.sensei.playernpcreplacer.ItemCacheModelLookup
 */
package com.sensei.playernpcreplacer.cache;
