/*
 * Copyright (c) 2008-2016, Hazelcast, Inc. All Rights Reserved.
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

package com.hazelcast.client.connection.nio;

import com.hazelcast.client.connection.ClientConnectionManager;
import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.LoggingService;
import com.hazelcast.nio.tcp.SocketChannelWrapper;
import com.hazelcast.nio.tcp.nonblocking.NonBlockingIOThread;
import com.hazelcast.nio.tcp.nonblocking.SelectionHandler;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;

public abstract class AbstractClientSelectionHandler implements SelectionHandler {

    protected final ILogger logger;
    protected final SocketChannelWrapper socketChannel;
    protected final ClientConnection connection;
    protected final ClientConnectionManager connectionManager;
    protected final NonBlockingIOThread ioThread;
    private SelectionKey sk;

    public AbstractClientSelectionHandler(final ClientConnection connection, NonBlockingIOThread ioThread,
                                          LoggingService loggingService) {
        this.connection = connection;
        this.ioThread = ioThread;
        this.socketChannel = connection.getSocketChannelWrapper();
        this.connectionManager = connection.getConnectionManager();
        this.logger = loggingService.getLogger(getClass().getName());
    }

    final void unregisterOp(int operation) throws IOException {
        sk.interestOps(sk.interestOps() & ~operation);
    }

    protected void shutdown() {
    }

    @Override
    public void requestMigration(NonBlockingIOThread newOwner) {
        //ignore
    }

    @Override
    public NonBlockingIOThread getOwner() {
        return ioThread;
    }

    @Override
    public long getEventCount() {
        //return 0; we are not going to participae
        return 0;
    }

    @Override
    public final void onFailure(Throwable e) {
        if (sk != null) {
            sk.cancel();
        }
        connectionManager.destroyConnection(connection);
        if (e instanceof IOException) {
            logger.warning(Thread.currentThread().getName() + " Closing socket to endpoint "
                    + connection.getEndPoint() + ", Cause:" + e.getMessage());
        } else {
            logger.warning(Thread.currentThread().getName() + " Closing socket to endpoint "
                    + connection.getEndPoint() + ", Cause:" + e, e);
        }
    }

    final void registerOp(final int operation) throws ClosedChannelException {
        if (!connection.isAlive()) {
            return;
        }
        if (sk == null) {
            sk = socketChannel.keyFor(ioThread.getSelector());
        }
        if (sk == null) {
            sk = socketChannel.register(ioThread.getSelector(), operation, this);
        } else {
            sk.interestOps(sk.interestOps() | operation);
            if (sk.attachment() != this) {
                sk.attach(this);
            }
        }
    }
}
