/*
 * Copyright (c) 2008-2015, Hazelcast, Inc. All Rights Reserved.
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

package com.hazelcast.client.spi.impl;

import com.hazelcast.client.impl.HazelcastClientInstanceImpl;
import com.hazelcast.client.spi.ClientClusterService;
import com.hazelcast.client.spi.ClientExecutionService;
import com.hazelcast.client.spi.ClientPartitionService;
import com.hazelcast.core.HazelcastInstanceNotActiveException;
import com.hazelcast.core.Member;
import com.hazelcast.core.Partition;
import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.Logger;
import com.hazelcast.nio.Address;
import com.hazelcast.nio.Connection;
import com.hazelcast.nio.serialization.Data;
import com.hazelcast.partition.NoDataMemberInClusterException;
import com.hazelcast.partition.client.GetPartitionsRequest;
import com.hazelcast.partition.client.PartitionsResponse;
import com.hazelcast.util.EmptyStatement;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.hazelcast.cluster.memberselector.MemberSelectors.DATA_MEMBER_SELECTOR;
import static com.hazelcast.util.HashUtil.hashToIndex;

/**
 * The {@link ClientPartitionService} implementation.
 */
public final class ClientPartitionServiceImpl implements ClientPartitionService {

    private static final ILogger LOGGER = Logger.getLogger(ClientPartitionService.class);
    private static final long PERIOD = 10;
    private static final long INITIAL_DELAY = 10;
    private static final int PARTITION_WAIT_TIME = 1000;

    private final HazelcastClientInstanceImpl client;

    private final ConcurrentHashMap<Integer, Address> partitions = new ConcurrentHashMap<Integer, Address>(271, 0.75f, 1);

    private final AtomicBoolean updating = new AtomicBoolean(false);

    private volatile int partitionCount;

    public ClientPartitionServiceImpl(HazelcastClientInstanceImpl client) {
        this.client = client;
    }

    public void start() {
        ClientExecutionService clientExecutionService = client.getClientExecutionService();
        clientExecutionService.scheduleWithFixedDelay(new RefreshTask(), INITIAL_DELAY, PERIOD, TimeUnit.SECONDS);
    }

    public void refreshPartitions() {
        ClientExecutionServiceImpl executionService = (ClientExecutionServiceImpl) client.getClientExecutionService();
        try {
            executionService.executeInternal(new RefreshTask());
        } catch (RejectedExecutionException ignored) {
            EmptyStatement.ignore(ignored);
        }
    }

    private void getPartitionsBlocking() {
        while (!getPartitions() && client.getConnectionManager().isAlive()) {
            if (isClusterFormedByOnlyLiteMembers()) {
                throw new NoDataMemberInClusterException(
                        "Partitions can't be assigned since all nodes in the cluster are lite members");
            }

            try {
                Thread.sleep(PARTITION_WAIT_TIME);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private boolean isClusterFormedByOnlyLiteMembers() {
        final ClientClusterService clusterService = client.getClientClusterService();
        return clusterService.getMembers(DATA_MEMBER_SELECTOR).isEmpty();
    }

    private boolean getPartitions() {
        ClientClusterService clusterService = client.getClientClusterService();
        Address ownerAddress = clusterService.getOwnerConnectionAddress();
        if (ownerAddress == null) {
            return false;
        }
        Connection connection = client.getConnectionManager().getConnection(ownerAddress);
        PartitionsResponse response = getPartitionsFrom(connection);
        if (response != null) {
            processPartitionResponse(response);
            return true;
        }
        return false;
    }

    private PartitionsResponse getPartitionsFrom(Connection connection) {
        if (connection == null) {
            return null;
        }
        try {
            final GetPartitionsRequest request = new GetPartitionsRequest();
            Future<PartitionsResponse> future = new ClientInvocation(client, request, connection).invokeUrgent();
            return client.getSerializationService().toObject(future.get());
        } catch (Exception e) {
            if (client.getLifecycleService().isRunning()) {
                LOGGER.warning("Error while fetching cluster partition table!", e);
            }
        }
        return null;
    }

    private void processPartitionResponse(PartitionsResponse response) {
        Address[] members = response.getMembers();
        int[] ownerIndexes = response.getOwnerIndexes();
        if (partitionCount == 0) {
            partitionCount = ownerIndexes.length;
        }
        for (int partitionId = 0; partitionId < partitionCount; partitionId++) {
            final int ownerIndex = ownerIndexes[partitionId];
            if (ownerIndex > -1) {
                partitions.put(partitionId, members[ownerIndex]);
            }
        }
    }

    public void stop() {
        partitions.clear();
    }

    @Override
    public Address getPartitionOwner(int partitionId) {
        Address address = partitions.get(partitionId);
        if (address == null) {
            getPartitionsBlocking();
        }
        return partitions.get(partitionId);
    }

    @Override
    public int getPartitionId(Data key) {
        final int pc = getPartitionCount();
        if (pc <= 0) {
            return 0;
        }
        int hash = key.getPartitionHash();
        return hashToIndex(hash, pc);
    }

    @Override
    public int getPartitionId(Object key) {
        final Data data = client.getSerializationService().toData(key);
        return getPartitionId(data);
    }

    @Override
    public int getPartitionCount() {
        if (partitionCount == 0) {
            getPartitionsBlocking();
        }
        return partitionCount;
    }

    @Override
    public Partition getPartition(int partitionId) {
        return new PartitionImpl(partitionId);
    }

    private final class PartitionImpl implements Partition {

        private final int partitionId;

        private PartitionImpl(int partitionId) {
            this.partitionId = partitionId;
        }

        public int getPartitionId() {
            return partitionId;
        }

        public Member getOwner() {
            Address owner = getPartitionOwner(partitionId);
            if (owner != null) {
                return client.getClientClusterService().getMember(owner);
            }
            return null;
        }

        @Override
        public String toString() {
            return "PartitionImpl{partitionId=" + partitionId + '}';
        }
    }

    private class RefreshTask implements Runnable {

        @Override
        public void run() {
            if (!updating.compareAndSet(false, true)) {
                return;
            }

            try {
                getPartitions();
            } catch (HazelcastInstanceNotActiveException ignored) {
                EmptyStatement.ignore(ignored);
            } finally {
                updating.set(false);
            }
        }
    }
}
