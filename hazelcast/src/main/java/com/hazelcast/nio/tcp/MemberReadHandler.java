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

package com.hazelcast.nio.tcp;

import com.hazelcast.internal.util.counters.Counter;
import com.hazelcast.nio.Packet;
import com.hazelcast.spi.impl.operationservice.impl.AsyncResponseHandler;
import com.hazelcast.spi.impl.packetdispatcher.PacketDispatcher;
import com.hazelcast.spi.impl.packetdispatcher.impl.PacketDispatcherImpl;

import java.nio.ByteBuffer;

import static com.hazelcast.nio.Packet.FLAG_OP;
import static com.hazelcast.nio.Packet.FLAG_RESPONSE;

/**
 * The {@link ReadHandler} for member to member communication.
 * <p>
 * It reads as many packets from the src ByteBuffer as possible, and each of the Packets is send to the {@link PacketDispatcher}.
 *
 * @see PacketDispatcher
 * @see MemberWriteHandler
 */
public class MemberReadHandler implements ReadHandler {

    protected final TcpIpConnection connection;
    private final AsyncResponseHandler asyncResponseHandler;
    protected Packet packet;

    private final PacketDispatcherImpl packetDispatcher;
    private final Counter normalPacketsRead;
    private final Counter priorityPacketsRead;
    private final Packet[] responsePackets = new Packet[1024];

    public MemberReadHandler(TcpIpConnection connection, PacketDispatcher packetDispatcher) {
        this.connection = connection;
        this.packetDispatcher = (PacketDispatcherImpl) packetDispatcher;
        SocketReader socketReader = connection.getSocketReader();
        this.normalPacketsRead = socketReader.getNormalFramesReadCounter();
        this.priorityPacketsRead = socketReader.getPriorityFramesReadCounter();
        this.asyncResponseHandler = (AsyncResponseHandler) ((PacketDispatcherImpl) packetDispatcher).responseHandler;
    }

    @Override
    public void onRead(ByteBuffer src) throws Exception {
        int responsePacketIndex = 0;

        while (src.hasRemaining()) {
            if (packet == null) {
                packet = new Packet();
            }

            boolean complete = packet.readFrom(src);

            if (!complete) {
                return;
            }

            if (packet.isFlagSet(Packet.FLAG_URGENT)) {
                priorityPacketsRead.inc();
            } else {
                normalPacketsRead.inc();
            }

            packet.setConn(connection);

            if (packet.isFlagSet(FLAG_OP) && packet.isFlagSet(FLAG_RESPONSE)) {
                responsePackets[responsePacketIndex] = packet;
                responsePacketIndex++;
            } else {
                packetDispatcher.dispatch(packet);
            }

            packet = null;
        }

        if (responsePacketIndex > 0) {
            asyncResponseHandler.handle(responsePackets);
        }
    }
}
