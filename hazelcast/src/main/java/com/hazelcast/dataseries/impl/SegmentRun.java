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

package com.hazelcast.dataseries.impl;

import com.hazelcast.internal.memory.impl.UnsafeUtil;
import sun.misc.Unsafe;

import java.util.Map;

public abstract class SegmentRun<R> {

    public final Unsafe unsafe = UnsafeUtil.UNSAFE;

    public long dataAddress;
    public long indicesAddress;
    public long recordDataSize;
    public long recordCount;
    public long indexOffset;
    public boolean indicesAvailable;

    public final void runAllFullScan(Segment segment) {
        while (segment != null) {
            dataAddress = segment.dataAddress();
            recordCount = segment.count();
            runFullScan();
            segment = segment.previous;
        }
    }

    public final void runSingleFullScan(Segment segment) {
        if (segment == null) {
            return;
        }

        dataAddress = segment.dataAddress();
        recordCount = segment.count();
        runFullScan();

        //System.out.println("runSingleFullScan completed:" + Thread.currentThread().getName());
    }

    protected abstract void runFullScan();

    public final void runAllWithIndex(Segment segment) {
        while (segment != null) {
            dataAddress = segment.dataAddress();
            recordCount = segment.count();
            indicesAddress = segment.indicesAddress();
            runWithIndex();
            segment = segment.previous;
        }
    }

    protected abstract void runWithIndex();

    public abstract void bind(Map<String, Object> bindings);

    public abstract R result();
}
