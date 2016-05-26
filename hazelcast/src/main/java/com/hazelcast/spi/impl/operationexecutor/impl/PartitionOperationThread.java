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

package com.hazelcast.spi.impl.operationexecutor.impl;

import com.hazelcast.instance.HazelcastThreadGroup;
import com.hazelcast.instance.NodeExtension;
import com.hazelcast.internal.metrics.Probe;
import com.hazelcast.logging.ILogger;
import com.hazelcast.nio.Packet;
import com.hazelcast.spi.Operation;
import com.hazelcast.spi.impl.PartitionSpecificRunnable;
import com.hazelcast.spi.impl.operationexecutor.OperationRunner;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import static com.hazelcast.instance.OutOfMemoryErrorDispatcher.inspectOutputMemoryError;

/**
 * An {@link OperationThread} that executes Operations for a particular partition, e.g. a map.get operation.
 */
public final class PartitionOperationThread extends OperationThread {

    private final OperationRunner[] runners;
    protected final OperationRunnerReference runnerReference;
    private final PartitionLocks partitionLocks;

    @SuppressFBWarnings("EI_EXPOSE_REP")
    public PartitionOperationThread(String name,
                                    int threadId,
                                    OperationQueue queue,
                                    ILogger logger,
                                    HazelcastThreadGroup threadGroup,
                                    NodeExtension nodeExtension,
                                    OperationRunner[] runners,
                                    PartitionLocks partitionLocks) {
        super(name, threadId, queue, logger, threadGroup, nodeExtension, false);
        this.runners = runners;
        this.partitionLocks = partitionLocks;
        this.runnerReference = OperationExecutorImpl.PARTITION_OPERATION_RUNNER_THREAD_LOCAL.get();
    }

    @Override
    protected void process(Object task) {
        try {
            if (task.getClass() == Packet.class) {
                Packet packet = (Packet) task;
                OperationRunner runner = runners[packet.getPartitionId()];
                runnerReference.runner = runner;
                lock(packet.getPartitionId());
                try {
                    runner.run(packet);
                } finally {
                    unlock(packet.getPartitionId());
                    runnerReference.runner = null;
                }
                completedPacketCount.inc();
            } else if (task instanceof Operation) {
                Operation operation = (Operation) task;
                OperationRunner runner = runners[operation.getPartitionId()];
                runnerReference.runner = runner;
                lock(operation.getPartitionId());
                try {
                    runner.run(operation);
                } finally {
                    unlock(operation.getPartitionId());
                    runnerReference.runner = null;
                }
                completedOperationCount.inc();
            } else if (task instanceof PartitionSpecificRunnable) {
                PartitionSpecificRunnable partitionRunnable = (PartitionSpecificRunnable) task;
                OperationRunner runner = runners[partitionRunnable.getPartitionId()];
                runnerReference.runner = runner;
                lock(partitionRunnable.getPartitionId());

                try {
                    runner.run(partitionRunnable);
                } finally {
                    unlock(partitionRunnable.getPartitionId());
                    runnerReference.runner = null;
                }
                completedPartitionSpecificRunnableCount.inc();
            } else if (task instanceof Runnable) {
                Runnable runnable = (Runnable) task;
                runnable.run();
                completedRunnableCount.inc();
            } else {
                throw new IllegalStateException("Unhandled task type for task:" + task);
            }
            completedTotalCount.inc();
        } catch (Throwable t) {
            errorCount.inc();
            inspectOutputMemoryError(t);
            logger.severe("Failed to process: " + task + " on " + getName(), t);
        }
    }

    private void lock(int partitionId) {
        if (partitionLocks == null) {
            return;
        }

        partitionLocks.lock(partitionId, this);
    }

    private void unlock(int partitionId) {
        if (partitionLocks == null) {
            return;
        }

        partitionLocks.unlock(partitionId);
    }

    @Probe
    int priorityPendingCount() {
        return queue.prioritySize();
    }

    @Probe
    int normalPendingCount() {
        return queue.normalSize();
    }
}
