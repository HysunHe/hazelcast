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

import com.hazelcast.client.AuthenticationException;
import com.hazelcast.client.HazelcastClientNotActiveException;
import com.hazelcast.client.config.ClientProperties;
import com.hazelcast.client.connection.nio.ClientConnection;
import com.hazelcast.client.impl.HazelcastClientInstanceImpl;
import com.hazelcast.client.impl.client.ClientRequest;
import com.hazelcast.client.impl.client.RetryableRequest;
import com.hazelcast.client.spi.ClientExecutionService;
import com.hazelcast.client.spi.ClientInvocationService;
import com.hazelcast.client.spi.EventHandler;
import com.hazelcast.core.ExecutionCallback;
import com.hazelcast.core.HazelcastInstanceNotActiveException;
import com.hazelcast.core.HazelcastOverloadException;
import com.hazelcast.core.ICompletableFuture;
import com.hazelcast.core.LifecycleService;
import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.Logger;
import com.hazelcast.nio.Address;
import com.hazelcast.nio.Connection;
import com.hazelcast.spi.exception.RetryableHazelcastException;

import java.io.IOException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static com.hazelcast.client.config.ClientProperty.HEARTBEAT_INTERVAL;
import static com.hazelcast.client.config.ClientProperty.INVOCATION_TIMEOUT_SECONDS;

public class ClientInvocation implements Runnable {

    public static final long RETRY_WAIT_TIME_IN_SECONDS = 1;
    private static final int UNASSIGNED_PARTITION = -1;
    private static final ILogger LOGGER = Logger.getLogger(ClientInvocation.class);

    private final LifecycleService lifecycleService;
    private final ClientInvocationService invocationService;
    private final ClientExecutionService executionService;
    private final ClientRequest request;
    private final EventHandler handler;

    private final ClientInvocationFuture clientInvocationFuture;
    private final int heartBeatInterval;
    private final Address address;
    private final int partitionId;
    private final Connection connection;
    private boolean urgent;
    private long retryTimeoutPointInMillis;
    private volatile ClientConnection sendConnection;

    private ClientInvocation(HazelcastClientInstanceImpl client, EventHandler handler,
                             ClientRequest request, int partitionId, Address address,
                             Connection connection) {
        this.lifecycleService = client.getLifecycleService();
        this.invocationService = client.getInvocationService();
        this.executionService = client.getClientExecutionService();
        this.handler = handler;
        this.request = request;
        this.partitionId = partitionId;
        this.address = address;
        this.connection = connection;

        ClientProperties clientProperties = client.getClientProperties();
        long waitTime = clientProperties.getMillis(INVOCATION_TIMEOUT_SECONDS);
        long waitTimeResolved = waitTime > 0 ? waitTime : Integer.parseInt(INVOCATION_TIMEOUT_SECONDS.getDefaultValue());
        retryTimeoutPointInMillis = System.currentTimeMillis() + waitTimeResolved;

        this.clientInvocationFuture = new ClientInvocationFuture(this, client, request);

        int interval = clientProperties.getInteger(HEARTBEAT_INTERVAL);
        this.heartBeatInterval = interval > 0 ? interval : Integer.parseInt(HEARTBEAT_INTERVAL.getDefaultValue());
    }

    public ClientInvocation(HazelcastClientInstanceImpl client, EventHandler handler, ClientRequest request) {
        this(client, handler, request, UNASSIGNED_PARTITION, null, null);
    }

    public ClientInvocation(HazelcastClientInstanceImpl client, EventHandler handler,
                            ClientRequest request, int partitionId) {
        this(client, handler, request, partitionId, null, null);
    }

    public ClientInvocation(HazelcastClientInstanceImpl client, EventHandler handler,
                            ClientRequest request, Address address) {
        this(client, handler, request, UNASSIGNED_PARTITION, address, null);
    }

    public ClientInvocation(HazelcastClientInstanceImpl client, EventHandler handler,
                            ClientRequest request, Connection connection) {
        this(client, handler, request, UNASSIGNED_PARTITION, null, connection);
    }

    public ClientInvocation(HazelcastClientInstanceImpl client, ClientRequest request) {
        this(client, null, request);
    }

    public ClientInvocation(HazelcastClientInstanceImpl client, ClientRequest request,
                            int partitionId) {
        this(client, null, request, partitionId);
    }

    public ClientInvocation(HazelcastClientInstanceImpl client, ClientRequest request,
                            Address address) {
        this(client, null, request, address);
    }

    public ClientInvocation(HazelcastClientInstanceImpl client, ClientRequest request,
                            Connection connection) {
        this(client, null, request, connection);
    }

    public int getPartitionId() {
        return partitionId;
    }

    public ClientRequest getRequest() {
        return request;
    }

    public EventHandler getEventHandler() {
        return handler;
    }

    public ClientInvocationFuture invoke() {
        if (request == null) {
            throw new IllegalStateException("Request can not be null");
        }

        try {
            invokeOnSelection();
        } catch (Exception e) {
            if (e instanceof HazelcastOverloadException) {
                throw (HazelcastOverloadException) e;
            }
            notify(e);
        }

        return clientInvocationFuture;
    }

    public ClientInvocationFuture invokeUrgent() {
        urgent = true;
        return invoke();
    }

    private void invokeOnSelection() throws IOException {
        if (isBindToSingleConnection()) {
            invocationService.invokeOnConnection(this, (ClientConnection) connection);
        } else if (partitionId != -1) {
            invocationService.invokeOnPartitionOwner(this, partitionId);
        } else if (address != null) {
            invocationService.invokeOnTarget(this, address);
        } else {
            invocationService.invokeOnRandomTarget(this);
        }
    }

    @Override
    public void run() {
        try {
            invoke();
        } catch (Throwable e) {
            clientInvocationFuture.setResponse(e);
        }
    }

    public void notify(Object response) {
        if (response == null) {
            throw new IllegalArgumentException("response can't be null");
        }

        if (!(response instanceof Exception)) {
            clientInvocationFuture.setResponse(response);
            return;
        }
        notifyException((Exception) response);
    }

    private void notifyException(Exception exception) {
        if (!lifecycleService.isRunning()) {
            clientInvocationFuture.setResponse(new HazelcastClientNotActiveException(exception.getMessage()));
            return;
        }

        if (isRetryable(exception)) {
            if (handleRetry()) {
                return;
            }
        }
        if (exception instanceof RetryableHazelcastException) {
            if (request instanceof RetryableRequest || invocationService.isRedoOperation()) {
                if (handleRetry()) {
                    return;
                }
            }
        }
        clientInvocationFuture.setResponse(exception);
    }

    private boolean handleRetry() {
        if (isBindToSingleConnection()) {
            return false;
        }
        if (System.currentTimeMillis() > retryTimeoutPointInMillis) {
            return false;
        }

        try {
            rescheduleInvocation();
        } catch (RejectedExecutionException e) {
            if (LOGGER.isFinestEnabled()) {
                LOGGER.finest("Retry could not be scheduled ", e);
            }
            notifyException(e);
        }
        return true;
    }

    private void rescheduleInvocation() {
        executionService.schedule(new Runnable() {
            @Override
            public void run() {
                try {
                    ICompletableFuture<?> future = ((ClientExecutionServiceImpl) executionService)
                            .submitInternal(ClientInvocation.this);
                    future.andThen(new ExecutionCallback() {
                        @Override
                        public void onResponse(Object response) {
                        }

                        @Override
                        public void onFailure(Throwable t) {
                            if (LOGGER.isFinestEnabled()) {
                                LOGGER.finest("Failure during retry ", t);
                            }
                            clientInvocationFuture.setResponse(t);
                        }
                    });
                } catch (RejectedExecutionException e) {
                    if (LOGGER.isFinestEnabled()) {
                        LOGGER.finest("Could not reschedule invocation.", e);
                    }
                    notifyException(e);
                }
            }
        }, RETRY_WAIT_TIME_IN_SECONDS, TimeUnit.SECONDS);
    }

    private boolean isBindToSingleConnection() {
        return connection != null;
    }

    boolean isConnectionHealthy(long elapsed) {
        if (elapsed >= heartBeatInterval) {
            if (sendConnection != null) {
                return sendConnection.isHeartBeating();
            } else {
                return true;
            }
        }
        return true;
    }

    public int getHeartBeatInterval() {
        return heartBeatInterval;
    }

    public boolean isUrgent() {
        return urgent;
    }

    public void setSendConnection(ClientConnection connection) {
        this.sendConnection = connection;
    }

    public ClientConnection getSendConnectionOrWait() throws InterruptedException {
        while (sendConnection == null && !clientInvocationFuture.isDone()) {
            Thread.sleep(TimeUnit.SECONDS.toMillis(RETRY_WAIT_TIME_IN_SECONDS));
        }
        return sendConnection;
    }

    public ClientConnection getSendConnection() {
        return sendConnection;
    }

    public static boolean isRetryable(Throwable t) {
        return t instanceof IOException
                || t instanceof HazelcastInstanceNotActiveException
                || t instanceof AuthenticationException;
    }
}
