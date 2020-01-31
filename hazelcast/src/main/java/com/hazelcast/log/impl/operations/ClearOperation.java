/*
 * Copyright (c) 2008-2020, Hazelcast, Inc. All Rights Reserved.
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

package com.hazelcast.log.impl.operations;

import com.hazelcast.log.impl.LogContainer;
import com.hazelcast.log.impl.LogDataSerializerHook;

public class ClearOperation extends LogOperation {
    public ClearOperation() {
    }

    public ClearOperation(String name) {
        super(name);
    }

    @Override
    public void run() throws Exception {
        LogContainer container = getContainer();
        container.clear();
    }

    @Override
    public int getClassId() {
        return LogDataSerializerHook.CLEAR;
    }
}

