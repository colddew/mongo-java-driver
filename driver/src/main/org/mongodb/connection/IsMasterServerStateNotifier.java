/*
 * Copyright (c) 2008 - 2013 10gen, Inc. <http://10gen.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.mongodb.connection;

import org.mongodb.Document;
import org.mongodb.annotations.ThreadSafe;
import org.mongodb.codecs.DocumentCodec;
import org.mongodb.command.Command;
import org.mongodb.operation.CommandOperation;
import org.mongodb.operation.CommandResult;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.mongodb.connection.ClusterConnectionMode.Direct;
import static org.mongodb.connection.ServerConnectionState.Connected;
import static org.mongodb.connection.ServerConnectionState.Connecting;
import static org.mongodb.connection.ServerConnectionState.Unconnected;
import static org.mongodb.connection.ServerType.ReplicaSetArbiter;
import static org.mongodb.connection.ServerType.ReplicaSetOther;
import static org.mongodb.connection.ServerType.ReplicaSetPrimary;
import static org.mongodb.connection.ServerType.ReplicaSetSecondary;
import static org.mongodb.connection.ServerType.ShardRouter;
import static org.mongodb.connection.ServerType.StandAlone;
import static org.mongodb.connection.ServerType.Unknown;

@ThreadSafe
class IsMasterServerStateNotifier implements ServerStateNotifier {

    private static final Logger LOGGER = Logger.getLogger("org.mongodb.connection.monitor");

    private final ChangeListener<ServerDescription> serverStateListener;
    private final ConnectionFactory connectionFactory;
    private final BufferPool<ByteBuffer> bufferPool;
    private Connection connection;
    private int count;
    private long elapsedNanosSum;
    private volatile ServerDescription serverDescription;
    private volatile boolean isClosed;

    IsMasterServerStateNotifier(final ChangeListener<ServerDescription> serverStateListener, final ConnectionFactory connectionFactory,
                                final BufferPool<ByteBuffer> bufferPool) {
        this.serverStateListener = serverStateListener;
        this.connectionFactory = connectionFactory;
        serverDescription = getConnectingServerDescription();
        this.bufferPool = bufferPool;
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized void run() {
        if (isClosed) {
            return;
        }

        ServerDescription currentServerDescription = serverDescription;
        Throwable throwable = null;
        try {
            if (connection == null) {
                connection = connectionFactory.create();
            }
            try {
                final CommandResult commandResult = new CommandOperation("admin",
                        new Command(new Document("ismaster", 1)), new DocumentCodec(), new ClusterDescription(Direct), bufferPool)
                        .execute(new ConnectingServerConnection(connection));
                count++;
                elapsedNanosSum += commandResult.getElapsedNanoseconds();

                serverDescription = createDescription(commandResult, elapsedNanosSum / count);
            } catch (MongoSocketException e) {
                if (!isClosed) {
                    connection.close();
                    connection = null;
                    count = 0;
                    elapsedNanosSum = 0;
                    throw e;
                }
            }
        } catch (Throwable t) {
            throwable = t;
            serverDescription = getUnconnectedServerDescription();
        }

        if (!isClosed) {
            try {
                // Note that the ServerDescription.equals method does not include the average ping time as part of the comparison,
                // so this will not spam the logs too hard.
                if (!currentServerDescription.equals(serverDescription)) {
                    if (throwable != null) {
                        LOGGER.log(Level.INFO, String.format(
                                "Exception in monitor thread while connecting to server %s", connectionFactory.getServerAddress()),
                                throwable);
                    }
                    else {
                        LOGGER.log(Level.INFO, String.format("Monitor thread successfully connected to server with description %s",
                                serverDescription));
                    }
                }
                serverStateListener.stateChanged(new ChangeEvent<ServerDescription>(currentServerDescription, serverDescription));
            } catch (Throwable t) {
                LOGGER.log(Level.WARNING, "Exception in monitor thread during notification of server description state change", t);
            }
        }
    }

    @Override
    public void close() {
        isClosed = true;
        if (connection != null) {
            connection.close();
            connection = null;
        }
    }

    @SuppressWarnings("unchecked")
    private ServerDescription createDescription(final CommandResult commandResult, final long averagePingTimeNanos) {
        return ServerDescription.builder()
                .state(Connected)
                .address(commandResult.getAddress())
                .type(getServerType(commandResult.getResponse()))
                .hosts(listToSet((List<String>) commandResult.getResponse().get("hosts")))
                .passives(listToSet((List<String>) commandResult.getResponse().get("passives")))
                .arbiters(listToSet((List<String>) commandResult.getResponse().get("arbiters")))
                .primary(commandResult.getResponse().getString("primary"))
                .maxDocumentSize(getInteger(commandResult.getResponse().getInteger("maxBsonObjectSize"),
                        ServerDescription.getDefaultMaxDocumentSize()))
                .maxMessageSize(getInteger(commandResult.getResponse().getInteger("maxMessageSizeBytes"),
                        ServerDescription.getDefaultMaxMessageSize()))
                .tags(getTagsFromDocument((Document) commandResult.getResponse().get("tags")))
                .setName(commandResult.getResponse().getString("setName"))
                .averagePingTime(averagePingTimeNanos, TimeUnit.NANOSECONDS)
                .ok(commandResult.isOk()).build();
    }

    private Set<String> listToSet(final List<String> list) {
        if (list == null || list.isEmpty()) {
            return Collections.emptySet();
        }
        else {
            return new HashSet<String>(list);
        }
    }

    private static ServerType getServerType(final Document isMasterResult) {
        if (isReplicaSetMember(isMasterResult)) {
            if (getBoolean(isMasterResult.getBoolean("ismaster"), false)) {
                return ReplicaSetPrimary;
            }

            if (getBoolean(isMasterResult.getBoolean("secondary"), false)) {
                return ReplicaSetSecondary;
            }

            if (getBoolean(isMasterResult.getBoolean("arbiterOnly"), false)) {
                return ReplicaSetArbiter;
            }

            return ReplicaSetOther;
        }

        if (isMasterResult.containsKey("msg") && isMasterResult.get("msg").equals("isdbgrid")) {
            return ShardRouter;
        }

        return StandAlone;
    }

    private static boolean isReplicaSetMember(final Document isMasterResult) {
        return isMasterResult.containsKey("setName") || getBoolean(isMasterResult.getBoolean("isreplicaset"), false);
    }

    private static boolean getBoolean(final Boolean value, final boolean defaultValue) {
        return value == null ? defaultValue : value;
    }


    private static int getInteger(final Integer value, final int defaultValue) {
        return (value != null) ? value : defaultValue;
    }

    private static Tags getTagsFromDocument(final Document tagsDocuments) {
        if (tagsDocuments == null) {
            return new Tags();
        }
        final Tags tags = new Tags();
        for (final Map.Entry<String, Object> curEntry : tagsDocuments.entrySet()) {
            tags.put(curEntry.getKey(), curEntry.getValue().toString());
        }
        return tags;
    }

    private ServerDescription getConnectingServerDescription() {
        return ServerDescription.builder().type(Unknown).state(Connecting).address(connectionFactory.getServerAddress()).build();
    }

    private ServerDescription getUnconnectedServerDescription() {
        return ServerDescription.builder().type(Unknown).state(Unconnected).address(connectionFactory.getServerAddress()).build();
    }
}