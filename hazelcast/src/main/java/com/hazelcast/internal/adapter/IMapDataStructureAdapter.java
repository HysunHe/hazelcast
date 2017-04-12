/*
 * Copyright (c) 2008-2017, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.internal.adapter;

import com.hazelcast.core.ICompletableFuture;
import com.hazelcast.core.IMap;
import com.hazelcast.monitor.LocalMapStats;
import com.hazelcast.query.TruePredicate;

import javax.cache.integration.CompletionListener;
import javax.cache.processor.EntryProcessor;
import javax.cache.processor.EntryProcessorException;
import javax.cache.processor.EntryProcessorResult;
import java.util.Map;
import java.util.Set;

public class IMapDataStructureAdapter<K, V> implements DataStructureAdapter<K, V> {

    private final IMap<K, V> map;

    public IMapDataStructureAdapter(IMap<K, V> map) {
        this.map = map;
    }

    @Override
    public V get(K key) {
        return map.get(key);
    }

    @Override
    public ICompletableFuture<V> getAsync(K key) {
        return map.getAsync(key);
    }

    @Override
    public void set(K key, V value) {
        map.set(key, value);
    }

    @Override
    public V put(K key, V value) {
        return map.put(key, value);
    }

    @Override
    public boolean putIfAbsent(K key, V value) {
        return map.putIfAbsent(key, value) == null;
    }

    @Override
    @MethodNotAvailable
    public ICompletableFuture<Boolean> putIfAbsentAsync(K key, V value) {
        throw new MethodNotAvailableException();
    }

    @Override
    public V replace(K key, V newValue) {
        return map.replace(key, newValue);
    }

    @Override
    public boolean replace(K key, V oldValue, V newValue) {
        return map.replace(key, oldValue, newValue);
    }

    @Override
    public void remove(K key) {
        map.remove(key);
    }

    @Override
    public boolean remove(K key, V oldValue) {
        return map.remove(key, oldValue);
    }

    @Override
    public ICompletableFuture<V> removeAsync(K key) {
        return map.removeAsync(key);
    }

    @Override
    @MethodNotAvailable
    public <T> T invoke(K key, EntryProcessor<K, V, T> entryProcessor, Object... arguments) throws EntryProcessorException {
        throw new MethodNotAvailableException();
    }

    @Override
    public Object executeOnKey(K key, com.hazelcast.map.EntryProcessor entryProcessor) {
        return map.executeOnKey(key, entryProcessor);
    }

    @Override
    public Map<K, Object> executeOnKeys(Set<K> keys, com.hazelcast.map.EntryProcessor entryProcessor) {
        return map.executeOnKeys(keys, entryProcessor);
    }

    @Override
    public boolean containsKey(K key) {
        return map.containsKey(key);
    }

    @Override
    public void loadAll(boolean replaceExistingValues) {
        map.loadAll(replaceExistingValues);
    }

    @Override
    public void loadAll(Set<K> keys, boolean replaceExistingValues) {
        map.loadAll(keys, replaceExistingValues);
    }

    @Override
    @MethodNotAvailable
    public void loadAll(Set<? extends K> keys, boolean replaceExistingValues, CompletionListener completionListener) {
        throw new MethodNotAvailableException();
    }

    @Override
    public Map<K, V> getAll(Set<K> keys) {
        return map.getAll(keys);
    }

    @Override
    public void putAll(Map<K, V> map) {
        this.map.putAll(map);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void removeAll() {
        map.removeAll(TruePredicate.INSTANCE);
    }

    @Override
    @MethodNotAvailable
    public void removeAll(final Set<K> keys) {
        throw new MethodNotAvailableException();
    }

    @Override
    @MethodNotAvailable
    public <T> Map<K, EntryProcessorResult<T>> invokeAll(Set<? extends K> keys, EntryProcessor<K, V, T> entryProcessor,
                                                         Object... arguments) {
        throw new MethodNotAvailableException();
    }

    @Override
    public void clear() {
        map.clear();
    }

    @Override
    public LocalMapStats getLocalMapStats() {
        return map.getLocalMapStats();
    }
}
