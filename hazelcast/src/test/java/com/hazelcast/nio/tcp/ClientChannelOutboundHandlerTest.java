package com.hazelcast.nio.tcp;

import com.hazelcast.client.impl.protocol.ClientMessage;
import com.hazelcast.client.impl.protocol.util.SafeBuffer;
import com.hazelcast.test.HazelcastParallelClassRunner;
import com.hazelcast.test.HazelcastTestSupport;
import com.hazelcast.test.annotation.ParallelTest;
import com.hazelcast.test.annotation.QuickTest;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import java.nio.ByteBuffer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(HazelcastParallelClassRunner.class)
@Category({QuickTest.class, ParallelTest.class})
public class ClientChannelOutboundHandlerTest extends HazelcastTestSupport {

    private ClientChannelOutboundHandler writeHandler;

    @Before
    public void setup() {
        writeHandler = new ClientChannelOutboundHandler();
    }

    @Test
    public void test() throws Exception {
        ClientMessage message = ClientMessage.createForEncode(1000)
                .setPartitionId(10)
                .setMessageType(1);

        ByteBuffer bb = ByteBuffer.allocate(1000);
        boolean result = writeHandler.write(message, bb);

        assertTrue(result);
        bb.flip();
        ClientMessage clone = ClientMessage.createForDecode(new SafeBuffer(bb.array()), 0);

        assertEquals(message.getPartitionId(), clone.getPartitionId());
        assertEquals(message.getMessageType(), clone.getMessageType());
    }
}
