/*
 * Copyright (c) 2017 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.services.concierge.enforcement;

import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.eclipse.ditto.model.base.headers.WithDittoHeaders;
import org.eclipse.ditto.model.enforcers.Enforcer;
import org.eclipse.ditto.services.utils.cache.Cache;
import org.eclipse.ditto.services.utils.cache.EntityIdWithResourceType;
import org.eclipse.ditto.services.utils.cache.entry.Entry;
import org.eclipse.ditto.signals.commands.base.exceptions.GatewayInternalErrorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Retrieves an enforcer by using an ID Cache and Enforcer Cache.
 */
public final class EnforcerRetriever {

    private static final Logger LOGGER = LoggerFactory.getLogger(EnforcerRetriever.class);

    private final Cache<EntityIdWithResourceType, Entry<EntityIdWithResourceType>> idCache;
    private final Function<String, Cache<EntityIdWithResourceType, Entry<Enforcer>>> enforcerCacheFunction;

    /**
     * Constructor.
     *
     * @param idCache the ID Cache.
     * @param enforcerCache the Enforcer Cache.
     */
    public EnforcerRetriever(
            final Cache<EntityIdWithResourceType, Entry<EntityIdWithResourceType>> idCache,
            final Cache<EntityIdWithResourceType, Entry<Enforcer>> enforcerCache) {
        this(idCache, resourceType -> requireNonNull(enforcerCache));
    }

    /**
     * Constructor.
     *
     * @param idCache the ID Cache.
     * @param enforcerCacheFunction a function to determine a Enforcer Cache for a resource type.
     */
    private EnforcerRetriever(
            final Cache<EntityIdWithResourceType, Entry<EntityIdWithResourceType>> idCache,
            final Function<String, Cache<EntityIdWithResourceType, Entry<Enforcer>>> enforcerCacheFunction) {
        this.idCache = requireNonNull(idCache);
        this.enforcerCacheFunction = requireNonNull(enforcerCacheFunction);
    }

    /**
     * Constructor.
     *
     * @param idCache the ID Cache.
     * @param enforcerCaches the Enforcer Caches per resource type.
     */
    public EnforcerRetriever(
            final Cache<EntityIdWithResourceType, Entry<EntityIdWithResourceType>> idCache,
            final Map<String, Cache<EntityIdWithResourceType, Entry<Enforcer>>> enforcerCaches) {
        this(idCache, requireNonNull(enforcerCaches)::get);
    }

    /**
     * By an entity cache key, look up the enforcer cache key and the enforcer itself.
     *
     * @param entityKey cache key of an entity.
     * @param handler handler of cache lookup results.
     * @return future after retrieved cache entries are given to the consumer.
     */
    public CompletionStage<Contextual<WithDittoHeaders>> retrieve(final EntityIdWithResourceType entityKey,
            final BiFunction<Entry<EntityIdWithResourceType>, Entry<Enforcer>, CompletionStage<Contextual<WithDittoHeaders>>> handler) {
        return idCache.get(entityKey).thenCompose(enforcerKeyEntryOptional -> {
            if (!enforcerKeyEntryOptional.isPresent()) {
                // may happen due to namespace blocking
                LOGGER.info("Did not get id-cache value for entityKey <{}>.", entityKey);
                return handler.apply(Entry.nonexistent(), Entry.nonexistent());
            } else {
                final Entry<EntityIdWithResourceType> enforcerKeyEntry = enforcerKeyEntryOptional.get();
                if (enforcerKeyEntry.exists()) {
                    final EntityIdWithResourceType enforcerKey = enforcerKeyEntry.getValueOrThrow();
                    final String resourceType = enforcerKey.getResourceType();
                    final Cache<EntityIdWithResourceType, Entry<Enforcer>> enforcerCache = enforcerCacheFunction.apply(resourceType);
                    if (enforcerCache == null) {
                        LOGGER.error("No enforcerCache for resource type: <{}>", resourceType);
                        throw GatewayInternalErrorException.newBuilder()
                                .build();
                    }
                    return retrieveByEnforcerKey(enforcerKey, enforcerEntry ->
                            handler.apply(enforcerKeyEntry, enforcerEntry));
                } else {
                    return handler.apply(enforcerKeyEntry, Entry.nonexistent());
                }
            }
        });
    }

    /**
     * Lookup the enforcer by its own key (as opposed to the key of an entity it governs).
     *
     * @param enforcerKey key of the enforcer.
     * @param handler what to do with the enforcer.
     */
    public CompletionStage<Contextual<WithDittoHeaders>> retrieveByEnforcerKey(final EntityIdWithResourceType enforcerKey,
            final Function<Entry<Enforcer>, CompletionStage<Contextual<WithDittoHeaders>>> handler) {
        final String resourceType = enforcerKey.getResourceType();
        final Cache<EntityIdWithResourceType, Entry<Enforcer>> enforcerCache =
                enforcerCacheFunction.apply(resourceType);
        if (enforcerCache == null) {
            throw new IllegalStateException("No enforcerCache for resource type: " + resourceType);
        }
        return enforcerCache.get(enforcerKey)
                .thenCompose(enforcerEntryOptional -> {
                    if (!enforcerEntryOptional.isPresent()) {
                        // may happen due to namespace blocking
                        LOGGER.info("Did not get enforcer-cache value for entityKey <{}>.", enforcerKey);
                        return handler.apply(Entry.nonexistent());
                    } else {
                        final Entry<Enforcer> enforcerEntry = enforcerEntryOptional.get();
                        return handler.apply(enforcerEntry);
                    }
                });

    }

}
