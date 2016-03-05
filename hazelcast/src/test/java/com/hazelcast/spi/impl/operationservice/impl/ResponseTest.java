package com.hazelcast.spi.impl.operationservice.impl;


import com.hazelcast.internal.serialization.SerializationService;
import com.hazelcast.internal.serialization.impl.DefaultSerializationServiceBuilder;
import com.hazelcast.internal.serialization.impl.HeapData;
import com.hazelcast.internal.serialization.impl.SerializationConstants;
import com.hazelcast.nio.Packet;
import com.hazelcast.nio.serialization.Data;
import com.hazelcast.spi.impl.operationservice.impl.responses.ErrorResponse;
import com.hazelcast.spi.impl.operationservice.impl.responses.NormalResponse;
import com.hazelcast.spi.impl.operationservice.impl.responses.Response;
import com.hazelcast.test.ExpectedRuntimeException;
import com.hazelcast.test.HazelcastParallelClassRunner;
import com.hazelcast.test.HazelcastTestSupport;
import com.hazelcast.test.annotation.ParallelTest;
import com.hazelcast.test.annotation.QuickTest;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;

@RunWith(HazelcastParallelClassRunner.class)
@Category({QuickTest.class, ParallelTest.class})
public class ResponseTest extends HazelcastTestSupport {

    private SerializationService serializationService;

    @Before
    public void setup() {
        serializationService = new DefaultSerializationServiceBuilder().build();
        setLoggingLog4j();
    }

    @Test
    public void getCallId() {
        NormalResponse response = new NormalResponse("foo", 10, 1, true);
        Packet packet = new Packet(serializationService.toBytes(response));

        assertEquals(response.getCallId(), Response.callId(packet.toByteArray()));
    }

    @Test
    public void getTypeId() {
        NormalResponse response = new NormalResponse("foo", 10, 1, true);
        Packet packet = new Packet(serializationService.toBytes(response));

        assertEquals(response.getId(), Response.typeId(packet.toByteArray()));
    }

    @Test
    public void getBackupCount() {
        NormalResponse response = new NormalResponse("foo", 10, 3, true);
        Packet packet = new Packet(serializationService.toBytes(response));

        assertEquals(response.getBackupCount(), Response.backupCount(packet.toByteArray()));
    }

    @Test
    public void getFactoryId() {
        NormalResponse response = new NormalResponse("foo", 10, 3, true);
        Packet packet = new Packet(serializationService.toBytes(response));

        assertEquals(response.getFactoryId(), Response.factoryId(packet.toByteArray()));
    }

    @Test
    public void getSerializerId() {
        NormalResponse response = new NormalResponse("foo", 10, 3, true);
        Packet packet = new Packet(serializationService.toBytes(response));

        assertEquals(SerializationConstants.CONSTANT_TYPE_DATA_SERIALIZABLE, Response.serializerId(packet.toByteArray()));
    }

    @Test
    public void deserializeValue_whenNormalResponse() {
        Object value = "foo";
        NormalResponse response = new NormalResponse("foo", 10, 3, true);
        Packet packet = new Packet(serializationService.toBytes(response));

        Object foundValue = Response.deserializeValue(serializationService, packet);
        assertEquals(value, foundValue);
    }

    @Test
    public void deserializeValue_whenErrorResponse() {
        Object value = "foo";
        ErrorResponse response = new ErrorResponse(new ExpectedRuntimeException(), 10, true);
        Packet packet = new Packet(serializationService.toBytes(response));

        Object foundValue = Response.deserializeValue(serializationService, packet);
        assertInstanceOf(ExpectedRuntimeException.class, foundValue);
    }

    @Test
    public void deserializeValue_whenNotResponse() {
        Object value = "foo";
        Packet packet = new Packet(serializationService.toBytes(value));

        Object foundValue = Response.deserializeValue(serializationService, packet);
        assertEquals(value, foundValue);
    }

    @Test
    public void getValueAsData_whenResponse() {
        Object value = "foo";
        Data valueData = new HeapData(serializationService.toBytes(value));

        NormalResponse response = new NormalResponse(valueData, 10, 1, true);
        Data responseData = new HeapData(serializationService.toBytes(response));

        Data foundValueData = Response.getValueAsData(serializationService, responseData);
        assertEquals(value, serializationService.toObject(foundValueData));
    }
}
