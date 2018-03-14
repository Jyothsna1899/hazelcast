/*
 * Copyright (c) 2008-2018, Hazelcast, Inc. All Rights Reserved.
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

package com.hazelcast.dataseries.impl.query;

import com.hazelcast.dataseries.impl.DataSeriesDataSerializerHook;
import com.hazelcast.dataseries.impl.operations.DataSeriesOperation;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ExecuteQueryOperation extends DataSeriesOperation {

    private Map<String, Object> bindings;
    private String preparationId;
    private List result;

    public ExecuteQueryOperation() {
    }

    public ExecuteQueryOperation(String name,
                                 String preparationId,
                                 Map<String, Object> bindings) {
        super(name);
        this.preparationId = preparationId;
        this.bindings = bindings;
    }

    @Override
    public void run() throws Exception {
        result = partition.executeQuery(preparationId, bindings);
    }

    @Override
    public Object getResponse() {
        return result;
    }

    @Override
    public int getId() {
        return DataSeriesDataSerializerHook.EXECUTE_QUERY_OPERATION;
    }

    @Override
    protected void writeInternal(ObjectDataOutput out) throws IOException {
        super.writeInternal(out);
        out.writeUTF(preparationId);
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
        int size = in.readInt();
        bindings = new HashMap<>();
        for (int k = 0; k < size; k++) {
            bindings.put(in.readUTF(), in.readObject());
        }
    }
}
