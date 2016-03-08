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

import com.hazelcast.client.impl.protocol.ClientMessage;
import com.hazelcast.logging.LoggingService;
import com.hazelcast.nio.IOUtil;
import com.hazelcast.nio.OutboundFrame;
import com.hazelcast.nio.tcp.nonblocking.NonBlockingIOThread;
import com.hazelcast.util.Clock;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class ClientWriteHandler extends AbstractClientSelectionHandler {

    private final Queue<ClientMessage> writeQueue = new ConcurrentLinkedQueue<ClientMessage>();

    private final AtomicBoolean scheduled = new AtomicBoolean(true);

    private final ByteBuffer buffer;

    private ClientMessage lastMessage;

    private volatile long lastHandle;

    public ClientWriteHandler(ClientConnection connection, NonBlockingIOThread ioThread, int bufferSize,
                              boolean direct, LoggingService loggingService) {
        super(connection, ioThread, loggingService);
        buffer = IOUtil.newByteBuffer(bufferSize, direct);
    }

    @Override
    public void handle() throws Exception {
        lastHandle = Clock.currentTimeMillis();

        if (lastMessage == null) {
            lastMessage = writeQueue.poll();
        }

        writeBuffer();

        unschedule();
    }

    private void writeBuffer() throws IOException {
        while (buffer.hasRemaining() && lastMessage != null) {
            boolean complete = lastMessage.writeTo(buffer);
            if (complete) {
                lastMessage = writeQueue.poll();
            } else {
                break;
            }
        }

        if (buffer.position() == 0) {
            // there is nothing to write, we are done
            return;
        }

        buffer.flip();
        socketChannel.write(buffer);

        if (buffer.hasRemaining()) {
            buffer.compact();
        } else {
            buffer.clear();
        }
    }

    /**
     * Tries to unschedule this WriteHandler.
     * <p/>
     * It will only be unscheduled if:
     * - the outputBuffer is empty
     * - there are no pending frames.
     * <p/>
     * If the outputBuffer is dirty then it will register itself for an OP_WRITE since we are interested in knowing
     * if there is more space in the socket output buffer.
     * If the outputBuffer is not dirty, then it will unregister itself from an OP_WRITE since it isn't interested
     * in space in the socket outputBuffer.
     * <p/>
     * This call is only made by the IO thread.
     */
    private void unschedule() throws IOException {
        if (buffer.position() > 0 || lastMessage != null) {
            // Because not all data was written to the socket, we need to register for OP_WRITE so we get
            // notified when the socketChannel is ready for more data.
            registerOp(SelectionKey.OP_WRITE);

            // If the outputBuffer is not empty, we don't need to unschedule ourselves. This is because the
            // WriteHandler will be triggered by a nio write event to continue sending data.
            return;
        }

        // since everything is written, we are not interested anymore in write-events, so lets unsubscribe
        unregisterOp(SelectionKey.OP_WRITE);
        // So the outputBuffer is empty, so we are going to unschedule ourselves.
        scheduled.set(false);

        if (writeQueue.isEmpty()) {
            // there are no remaining frames, so we are done.
            return;
        }

        // So there are frames, but we just unscheduled ourselves. If we don't try to reschedule, then these
        // Frames are at risk not to be send.
        if (!scheduled.compareAndSet(false, true)) {
            //someone else managed to schedule this WriteHandler, so we are done.
            return;
        }

        // We managed to reschedule. So lets add ourselves to the ioThread so we are processed again.
        // We don't need to call wakeup because the current thread is the IO-thread and the selectionQueue will be processed
        // till it is empty. So it will also pick up tasks that are added while it is processing the selectionQueue.
        ioThread.addHandler(this);
    }

    public void enqueue(OutboundFrame frame) {
        writeQueue.offer((ClientMessage) frame);

        schedule();
    }

    /**
     * Makes sure this WriteHandler is scheduled to be executed by the IO thread.
     * <p/>
     * This call is made by 'outside' threads that interact with the connection. For example when a frame is placed
     * on the connection to be written. It will never be made by an IO thread.
     * <p/>
     * If the WriteHandler already is scheduled, the call is ignored.
     */
    private void schedule() {
        if (scheduled.get()) {
            // So this WriteHandler is still scheduled, we don't need to schedule it again
            return;
        }

        if (!scheduled.compareAndSet(false, true)) {
            // Another thread already has scheduled this WriteHandler, we are done. It
            // doesn't matter which thread does the scheduling, as long as it happens.
            return;
        }

        // We managed to schedule this WriteHandler. This means we need to add a task to
        // the ioThread and give it a kick so that it processes our frames.
        ioThread.addHandlerAndWakeup(this);
    }

    @Override
    public void shutdown() {
        writeQueue.clear();
    }

    long getLastHandle() {
        return lastHandle;
    }
}
