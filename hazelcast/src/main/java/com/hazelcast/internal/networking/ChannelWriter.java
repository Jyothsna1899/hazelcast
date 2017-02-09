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

package com.hazelcast.internal.networking;

import com.hazelcast.nio.OutboundFrame;
import com.hazelcast.nio.tcp.TcpIpConnection;

import java.io.Closeable;

/**
 * Each {@link TcpIpConnection} has a {@link ChannelWriter} and it writes {@link OutboundFrame} instances to the socket. Copying
 * the Frame instances to the byte-buffer is done using the {@link ChannelOutboundHandler}.
 *
 * Each {@link TcpIpConnection} has its own {@link ChannelWriter} instance.
 *
 * Before Hazelcast 3.6 the name of this interface was ChannelOutboundHandler.
 *
 * @see ChannelReader
 * @see ChannelInboundHandler
 * @see IOThreadingModel
 */
public interface ChannelWriter extends Closeable {

    /**
     * Returns the last {@link com.hazelcast.util.Clock#currentTimeMillis()} that a write to the socket completed.
     *
     * Writing to the socket doesn't mean that data has been send or received; it means that data was written to the
     * SocketChannel. It could very well be that this data is stuck somewhere in an io-buffer.
     *
     * @return the last time something was written to the socket.
     */
    long lastWriteMillis();

    /**
     * Offers a Frame to be written to the socket.
     *
     * No guarantees are made that the frame is going to be written or received by the other side.
     *
     * @param frame the Frame to write.
     */
    void write(OutboundFrame frame);

    /**
     * Does the handshake. This initializes the connection to start sending/receiving data. This method is only called
     * on the side that initiates the connection.
     *
     * todo: this method should probably be deleted. Should be integrated in the Connector.
     */
    void handshake();
}
