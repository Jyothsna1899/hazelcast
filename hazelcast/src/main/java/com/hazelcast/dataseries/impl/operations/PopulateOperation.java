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

package com.hazelcast.dataseries.impl.operations;

import com.hazelcast.map.impl.MapService;
import com.hazelcast.map.impl.PartitionContainer;
import com.hazelcast.map.impl.recordstore.RecordStore;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;

import java.io.IOException;
import java.util.Iterator;

import static com.hazelcast.dataseries.impl.DataSeriesDataSerializerHook.POPULATE_OPERATION;

public class PopulateOperation extends DataSeriesOperation {

    private String srcName;

    public PopulateOperation() {
    }

    public PopulateOperation(String dstName, String srcName) {
        super(dstName);
        this.srcName = srcName;
    }

    @Override
    public void run() throws Exception {
        MapService mapService = getNodeEngine().getService(MapService.SERVICE_NAME);
        PartitionContainer partitionContainer = mapService
                .getMapServiceContext()
                .getPartitionContainer(getPartitionId());
        RecordStore recordStore = partitionContainer.getRecordStore(srcName);
        Iterator<byte[]> it = recordStore.iterator();

        for (; it.hasNext(); ) {
            byte[] value = it.next();
            partition.append(value);
        }
        //partition.freeze();
    }

    @Override
    public int getId() {
        return POPULATE_OPERATION;
    }

    @Override
    protected void writeInternal(ObjectDataOutput out) throws IOException {
        super.writeInternal(out);
        out.writeUTF(srcName);
    }

    @Override
    protected void readInternal(ObjectDataInput in) throws IOException {
        super.readInternal(in);
        srcName = in.readUTF();
    }
}
