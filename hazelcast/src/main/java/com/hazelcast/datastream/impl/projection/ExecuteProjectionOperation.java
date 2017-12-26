/*
 * Copyright (c) 2008-2019, Hazelcast, Inc. All Rights Reserved.
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

package com.hazelcast.datastream.impl.projection;

import com.hazelcast.datastream.impl.DSDataSerializerHook;
import com.hazelcast.datastream.impl.operations.DataStreamOperation;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.util.function.Consumer;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class ExecuteProjectionOperation extends DataStreamOperation {

    private Map<String, Object> bindings;
    private String preparationId;
    private Consumer consumer;
    private String collectionClass;
    private boolean forkJoin;
    private transient Collection result;

    public ExecuteProjectionOperation() {
    }

    public ExecuteProjectionOperation(String name,
                                      String preparationId,
                                      Map<String, Object> bindings,
                                      String collectionClazz,
                                      boolean forkJoin) {
        super(name);
        this.preparationId = preparationId;
        this.bindings = bindings;
        this.collectionClass = collectionClazz;
    }

    @Override
    public void run() throws Exception {
        Consumer consumer = this.consumer;
        if (consumer == null) {
            result = (Collection) getClass().getClassLoader().loadClass(collectionClass).newInstance();
            consumer = o -> result.add(o);
        }

        partition.executeProjectionPartitionThread(preparationId, bindings, consumer);
    }

    @Override
    public Object getResponse() {
        return result;
    }

    @Override
    public int getId() {
        return DSDataSerializerHook.EXECUTE_PROJECTION_OPERATION;
    }

    @Override
    protected void writeInternal(ObjectDataOutput out) throws IOException {
        super.writeInternal(out);
        out.writeUTF(preparationId);
        out.writeBoolean(forkJoin);
        out.writeUTF(collectionClass);
        out.writeInt(bindings.size());
        for (Map.Entry<String, Object> entry : bindings.entrySet()) {
            out.writeUTF(entry.getKey());
            out.writeObject(entry.getValue());
        }
    }

    @Override
    protected void readInternal(ObjectDataInput in) throws IOException {
        super.readInternal(in);
        preparationId = in.readUTF();
        forkJoin = in.readBoolean();
        collectionClass = in.readUTF();
        int size = in.readInt();
        bindings = new HashMap<>(size);
        for (int k = 0; k < size; k++) {
            bindings.put(in.readUTF(), in.readObject());
        }
    }
}
