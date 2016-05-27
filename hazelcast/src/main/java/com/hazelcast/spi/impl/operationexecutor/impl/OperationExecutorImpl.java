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

import com.hazelcast.concurrent.atomiclong.operations.AddAndGetOperation;
import com.hazelcast.concurrent.atomiclong.operations.GetOperation;
import com.hazelcast.concurrent.atomiclong.operations.SetOperation;
import com.hazelcast.instance.HazelcastThreadGroup;
import com.hazelcast.instance.NodeExtension;
import com.hazelcast.internal.metrics.MetricsProvider;
import com.hazelcast.internal.metrics.MetricsRegistry;
import com.hazelcast.internal.metrics.Probe;
import com.hazelcast.internal.util.collection.MPSCQueue;
import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.LoggingService;
import com.hazelcast.nio.Address;
import com.hazelcast.nio.Packet;
import com.hazelcast.spi.LiveOperations;
import com.hazelcast.spi.Operation;
import com.hazelcast.spi.UrgentSystemOperation;
import com.hazelcast.spi.impl.PartitionSpecificRunnable;
import com.hazelcast.spi.impl.operationexecutor.OperationExecutor;
import com.hazelcast.spi.impl.operationexecutor.OperationHostileThread;
import com.hazelcast.spi.impl.operationexecutor.OperationRunner;
import com.hazelcast.spi.impl.operationexecutor.OperationRunnerFactory;
import com.hazelcast.spi.impl.operationservice.impl.operations.Backup;
import com.hazelcast.spi.properties.HazelcastProperties;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

import static com.hazelcast.internal.metrics.ProbeLevel.MANDATORY;
import static com.hazelcast.spi.properties.GroupProperty.GENERIC_OPERATION_THREAD_COUNT;
import static com.hazelcast.spi.properties.GroupProperty.OPERATION_CALLER_RUNS;
import static com.hazelcast.spi.properties.GroupProperty.PARTITION_COUNT;
import static com.hazelcast.spi.properties.GroupProperty.PARTITION_OPERATION_THREAD_COUNT;
import static com.hazelcast.spi.properties.GroupProperty.PRIORITY_GENERIC_OPERATION_THREAD_COUNT;
import static com.hazelcast.util.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * A {@link com.hazelcast.spi.impl.operationexecutor.OperationExecutor} that schedules:
 * <ol>
 * <li>partition specific operations to a specific partition-operation-thread (using a mod on the partition-id)</li>
 * <li>non specific operations to generic-operation-threads</li>
 * </ol>
 * The {@link #execute(Object, int, boolean)} accepts an Object instead of a runnable to prevent needing to
 * create wrapper runnables around tasks. This is done to reduce the amount of object litter and therefor
 * reduce pressure on the gc.
 * <p/>
 * There are 2 category of operation threads:
 * <ol>
 * <li>partition specific operation threads: these threads are responsible for executing e.g. a map.put.
 * Operations for the same partition, always end up in the same thread.
 * </li>
 * <li>
 * generic operation threads: these threads are responsible for executing operations that are not
 * specific to a partition. E.g. a heart beat.
 * </li>
 * </ol>
 *
 * <h1>Caller runs</h1>
 * In older versions of Hazelcast a partition specific operation always gets executed by an partition operation-thread.
 * However this has a significant overhead because the task needs to be offloaded from the userthread to the operation thread
 * and the response of the operation needs to wakeup the user thread if it is blocking on that result. To reduce this
 * overhead a userthread is allowed to execute a partition specific operation under the condition that it can acquire the
 * partition lock.
 *
 * <h2>Generic OperationRunner</h2>
 * If the outer call is a generic call, no OperationRunner is set on a threadlocal because a generic operation-runner is used.
 * The problem here is that these calls are invisible to the system since generic operation runners aren't sampled,
 *
 * <h2>Partition OperationRunner</h2>
 * The PartitionSpecific operation runner needs to be set on a ThreadLocal of the user thread so that the OperationExecutor
 * can detect if there is a nested call is happening. So if there is no nested call, a user thread can execute an operation
 * on any partition (local + unlocked), however when there is a nested call (e.g. a entry processor doing a map.get), then the
 * nested call is only allowed to access the same partition.
 *
 * The reason behind this restriction is that otherwise the system could easily run into a deadlock. Imagine 2 user threads and
 * the first one does an entry processor an partition A and a nested call on B, and the other user thread doing an entry processor
 * on partition B and a nested call on A, then you have a deadlock. Because the nested invocations can't every complete. It
 * doesn't matter if the nested operations get offloaded to the partition thread or not.
 *
 * todo:
 * - probe for number of conflicts
 * - deadlock prevention for caller runs
 * - support for nested calls in same partition:
 * - this should be fixed now. Only the outer call places and removed the OperationRunner on the thread-local
 * - support for data-structures other than IAtomicLong
 * - better mechanism for the partition thread to 'idle' when his partition locked by user-thread.
 * - better mechanism for triggering the optimization.
 *
 * done:
 * - instead of using AtomicReference as partitionLock, use an AtomicReferenceArray to prevent false sharing.
 */
@SuppressWarnings("checkstyle:methodcount")
public final class OperationExecutorImpl implements OperationExecutor, MetricsProvider {

    public static final ThreadLocal<OperationRunnerReference> PARTITION_OPERATION_RUNNER_THREAD_LOCAL
            = new ThreadLocal<OperationRunnerReference>() {
        @Override
        protected OperationRunnerReference initialValue() {
            return new OperationRunnerReference();
        }
    };

    private static final int TERMINATION_TIMEOUT_SECONDS = 3;

    private final ILogger logger;

    // all operations for specific partitions will be executed on these threads, e.g. map.put(key, value)
    private final PartitionOperationThread[] partitionThreads;
    private final OperationRunner[] partitionOperationRunners;

    private final OperationQueue genericQueue
            = new DefaultOperationQueue(new LinkedBlockingQueue<Object>(), new LinkedBlockingQueue<Object>());

    // all operations that are not specific for a partition will be executed here, e.g. heartbeat or map.size()
    private final GenericOperationThread[] genericThreads;
    private final OperationRunner[] genericOperationRunners;

    private final Address thisAddress;
    private final OperationRunner adHocOperationRunner;
    private final int priorityThreadCount;

    private final PartitionLocks partitionLocks;
    private final boolean callerRuns;

    @Probe
    private final AtomicLong conflictCount = new AtomicLong();

    public OperationExecutorImpl(HazelcastProperties properties,
                                 LoggingService loggerService,
                                 Address thisAddress,
                                 OperationRunnerFactory operationRunnerFactory,
                                 HazelcastThreadGroup threadGroup,
                                 NodeExtension nodeExtension) {
        this.thisAddress = thisAddress;
        this.logger = loggerService.getLogger(OperationExecutorImpl.class);

        this.callerRuns = properties.getBoolean(OPERATION_CALLER_RUNS);
        this.partitionLocks = newPartitionLocks(properties);

        this.adHocOperationRunner = operationRunnerFactory.createAdHocRunner();

        this.partitionOperationRunners = initPartitionOperationRunners(properties, operationRunnerFactory);
        this.partitionThreads = initPartitionThreads(properties, threadGroup, nodeExtension);

        this.priorityThreadCount = properties.getInteger(PRIORITY_GENERIC_OPERATION_THREAD_COUNT);
        this.genericOperationRunners = initGenericOperationRunners(properties, operationRunnerFactory);
        this.genericThreads = initGenericThreads(threadGroup, nodeExtension);
    }

    private PartitionLocks newPartitionLocks(HazelcastProperties properties) {
        int partitionCount = properties.getInteger(PARTITION_COUNT);
        return callerRuns ? new PartitionLocks(partitionCount) : null;
    }

    private OperationRunner[] initPartitionOperationRunners(HazelcastProperties properties,
                                                            OperationRunnerFactory handlerFactory) {
        OperationRunner[] operationRunners = new OperationRunner[properties.getInteger(PARTITION_COUNT)];
        for (int partitionId = 0; partitionId < operationRunners.length; partitionId++) {
            operationRunners[partitionId] = handlerFactory.createPartitionRunner(partitionId);
        }
        return operationRunners;
    }

    private OperationRunner[] initGenericOperationRunners(HazelcastProperties properties, OperationRunnerFactory runnerFactory) {
        int threadCount = properties.getInteger(GENERIC_OPERATION_THREAD_COUNT);
        if (threadCount <= 0) {
            // default generic operation thread count
            int coreSize = Runtime.getRuntime().availableProcessors();
            threadCount = Math.max(2, coreSize / 2);
        }

        OperationRunner[] operationRunners = new OperationRunner[threadCount + priorityThreadCount];
        for (int partitionId = 0; partitionId < operationRunners.length; partitionId++) {
            operationRunners[partitionId] = runnerFactory.createGenericRunner();
        }

        return operationRunners;
    }

    private PartitionOperationThread[] initPartitionThreads(HazelcastProperties properties, HazelcastThreadGroup threadGroup,
                                                            NodeExtension nodeExtension) {

        int threadCount = properties.getInteger(PARTITION_OPERATION_THREAD_COUNT);
        if (threadCount <= 0) {
            // default partition operation thread count
            int coreSize = Runtime.getRuntime().availableProcessors();
            threadCount = Math.max(2, coreSize);
        }

        PartitionOperationThread[] threads = new PartitionOperationThread[threadCount];
        for (int threadId = 0; threadId < threads.length; threadId++) {
            String threadName = threadGroup.getThreadPoolNamePrefix("partition-operation") + threadId;
            // the normalQueue will be a blocking queue. We don't want to idle, because there are many operation threads.
            MPSCQueue<Object> normalQueue = new MPSCQueue<Object>(null);
            OperationQueue operationQueue = new DefaultOperationQueue(normalQueue, new ConcurrentLinkedQueue<Object>());

            PartitionOperationThread partitionThread = new PartitionOperationThread(threadName, threadId, operationQueue, logger,
                    threadGroup, nodeExtension, partitionOperationRunners, partitionLocks);

            threads[threadId] = partitionThread;
            normalQueue.setConsumerThread(partitionThread);
        }

        // we need to assign the PartitionOperationThreads to all OperationRunners they own
        for (int partitionId = 0; partitionId < partitionOperationRunners.length; partitionId++) {
            int threadId = partitionId % threadCount;
            Thread thread = threads[threadId];
            OperationRunner runner = partitionOperationRunners[partitionId];
            runner.setCurrentThread(thread);
        }

        return threads;
    }

    private GenericOperationThread[] initGenericThreads(HazelcastThreadGroup threadGroup,
                                                        NodeExtension nodeExtension) {
        // we created as many generic operation handlers, as there are generic threads
        int threadCount = genericOperationRunners.length;

        GenericOperationThread[] threads = new GenericOperationThread[threadCount];

        int threadId = 0;
        for (int threadIndex = 0; threadIndex < threads.length; threadIndex++) {
            boolean priority = threadIndex < priorityThreadCount;
            String baseName = priority ? "priority-generic-operation" : "generic-operation";
            String threadName = threadGroup.getThreadPoolNamePrefix(baseName) + threadId;
            OperationRunner operationRunner = genericOperationRunners[threadIndex];

            GenericOperationThread operationThread = new GenericOperationThread(
                    threadName, threadIndex, genericQueue, logger, threadGroup, nodeExtension, operationRunner, priority);

            threads[threadIndex] = operationThread;
            operationRunner.setCurrentThread(operationThread);

            if (threadIndex == priorityThreadCount - 1) {
                threadId = 0;
            } else {
                threadId++;
            }
        }

        return threads;
    }

    @Override
    public void provideMetrics(MetricsRegistry metricsRegistry) {
        metricsRegistry.scanAndRegister(this, "operation");

        metricsRegistry.collectMetrics((Object[]) genericThreads);
        metricsRegistry.collectMetrics((Object[]) partitionThreads);
        metricsRegistry.collectMetrics(adHocOperationRunner);
        metricsRegistry.collectMetrics((Object[]) genericOperationRunners);
        metricsRegistry.collectMetrics((Object[]) partitionOperationRunners);
    }

    @SuppressFBWarnings("EI_EXPOSE_REP")
    @Override
    public OperationRunner[] getPartitionOperationRunners() {
        return partitionOperationRunners;
    }

    @SuppressFBWarnings("EI_EXPOSE_REP")
    @Override
    public OperationRunner[] getGenericOperationRunners() {
        return genericOperationRunners;
    }

    @Override
    public void scan(LiveOperations result) {
        scan(partitionOperationRunners, result);
        scan(genericOperationRunners, result);
    }

    private void scan(OperationRunner[] runners, LiveOperations result) {
        for (OperationRunner runner : runners) {
            Object task = runner.currentTask();
            if (!(task instanceof Operation) || task.getClass() == Backup.class) {
                continue;
            }
            Operation operation = (Operation) task;
            result.add(operation.getCallerAddress(), operation.getCallId());
        }
    }

    @Probe(name = "runningCount")
    @Override
    public int getRunningOperationCount() {
        return getRunningPartitionOperationCount() + getRunningGenericOperationCount();
    }

    @Probe(name = "runningPartitionCount")
    private int getRunningPartitionOperationCount() {
        return getRunningOperationCount(partitionOperationRunners);
    }

    @Probe(name = "runningGenericCount")
    private int getRunningGenericOperationCount() {
        return getRunningOperationCount(genericOperationRunners);
    }

    private static int getRunningOperationCount(OperationRunner[] runners) {
        int result = 0;
        for (OperationRunner runner : runners) {
            if (runner.currentTask() != null) {
                result++;
            }
        }
        return result;
    }

    @Override
    @Probe(name = "queueSize", level = MANDATORY)
    public int getQueueSize() {
        int size = 0;
        for (PartitionOperationThread partitionThread : partitionThreads) {
            size += partitionThread.queue.normalSize();
        }
        size += genericQueue.normalSize();
        return size;
    }

    @Override
    @Probe(name = "priorityQueueSize", level = MANDATORY)
    public int getPriorityQueueSize() {
        int size = 0;
        for (PartitionOperationThread partitionThread : partitionThreads) {
            size += partitionThread.queue.prioritySize();
        }
        size += genericQueue.prioritySize();
        return size;
    }

    @Probe(name = "genericQueueSize")
    private int getGenericQueueSize() {
        return genericQueue.normalSize();
    }

    @Probe(name = "genericPriorityQueueSize")
    private int getGenericPriorityQueueSize() {
        return genericQueue.prioritySize();
    }

    @Override
    @Probe(name = "partitionThreadCount")
    public int getPartitionThreadCount() {
        return partitionThreads.length;
    }

    @Override
    @Probe(name = "genericThreadCount")
    public int getGenericThreadCount() {
        return genericThreads.length;
    }

    @Override
    public boolean isOperationThread() {
        return Thread.currentThread() instanceof OperationThread;
    }

    @Override
    public void execute(Operation op) {
        checkNotNull(op, "op can't be null");

        execute(op, op.getPartitionId(), op.isUrgent());
    }

    @Override
    public void execute(PartitionSpecificRunnable task) {
        checkNotNull(task, "task can't be null");

        execute(task, task.getPartitionId(), task instanceof UrgentSystemOperation);
    }

    @Override
    public void handle(Packet packet) {
        execute(packet, packet.getPartitionId(), packet.isUrgent());
    }

    private void execute(Object task, int partitionId, boolean priority) {
        if (partitionId < 0) {
            genericQueue.add(task, priority);
        } else {
            OperationThread partitionThread = partitionThreads[toPartitionThreadIndex(partitionId)];
            partitionThread.queue.add(task, priority);
        }
    }

    @Override
    public void executeOnPartitionThreads(Runnable task) {
        checkNotNull(task, "task can't be null");

        for (OperationThread partitionThread : partitionThreads) {
            partitionThread.queue.add(task, true);
        }
    }

    @Override
    public void interruptPartitionThreads() {
        for (PartitionOperationThread partitionThread : partitionThreads) {
            partitionThread.interrupt();
        }
    }

    @Override
    public void run(Operation operation) {
        checkNotNull(operation, "operation can't be null");

        if (!isRunAllowed(operation)) {
            throw new IllegalThreadStateException("Operation '" + operation + "' cannot be run in current thread: "
                    + Thread.currentThread());
        }

        OperationRunner runner = getOperationRunner(operation);
        runner.run(operation);
    }

    OperationRunner getOperationRunner(Operation operation) {
        checkNotNull(operation, "operation can't be null");

        if (operation.getPartitionId() >= 0) {
            // retrieving an OperationRunner for a partition specific operation is easy; we can just use the partition id.
            return partitionOperationRunners[operation.getPartitionId()];
        }

        Thread currentThread = Thread.currentThread();
        if (currentThread.getClass() != GenericOperationThread.class) {
            // if thread is not an operation thread, we return the adHocOperationRunner
            return adHocOperationRunner;
        }

        // It is a generic operation and we are running on an operation-thread. So we can just return the operation-runner
        // for that thread. There won't be any partition-conflict since generic operations are allowed to be executed by
        // a partition-specific operation-runner.
        return ((GenericOperationThread) currentThread).operationRunner;
    }

    @Override
    public void runOrExecute(Operation op) {
        Thread currentThread = Thread.currentThread();

        if (currentThread instanceof OperationHostileThread) {
            // OperationHostileThreads are not allowed to run any operation
            throw new IllegalThreadStateException("Can't call runOrExecute from " + currentThread.getName() + " for:" + op);
        }

        OperationRunnerReference runnerRef = PARTITION_OPERATION_RUNNER_THREAD_LOCAL.get();
        if (runnerRef.runner == null) {
            runOrExecuteNotNested(op, currentThread, runnerRef);
        } else {
            runNested(op, currentThread, runnerRef.runner);
        }
    }

    private void runOrExecuteNotNested(Operation op, Thread currentThread, OperationRunnerReference runnerRef) {
        int partitionId = op.getPartitionId();

        if (partitionId < 0) {
            // it is a generic call and there is no nesting. So we can run it directly
            run(op);
            return;
        }

        if (callerRuns && isCallerRunsOp(op)) {
            OperationRunner runner = partitionOperationRunners[partitionId];

            if (partitionLocks.tryLock(partitionId, currentThread)) {
                // we successfully managed to lock, so we can run the operation.
                runnerRef.runner = runner;
                try {
                    runner.run(op);
                } finally {
                    partitionLocks.unlock(partitionId);
                    runnerRef.runner = null;
                }
            } else {
                // we failed to acquire the lock. So lets offload it.
                conflictCount.incrementAndGet();
                partitionThreads[toPartitionThreadIndex(partitionId)].queue.add(op, op.isUrgent());
            }
        } else {
            // caller runs can't be applied.  So lets offload it.
            partitionThreads[toPartitionThreadIndex(partitionId)].queue.add(op, op.isUrgent());
        }
    }

    private void runNested(Operation op, Thread currentThread, OperationRunner runner) {
        int partitionId = op.getPartitionId();

        if (partitionId < 0) {
            // a nested generic operation is not allowed from an outer partition operation because it could self deadlock.
            throw new IllegalThreadStateException("Can't call runOrExecute from " + currentThread.getName() + " for:" + op);
        }

        if (partitionId != runner.getPartitionId()) {
            throw new IllegalThreadStateException("Can't call runOrExecute from " + currentThread.getName() + " for:" + op);
        }

        runner.run(op);
    }

    // quick hack to only allow it for certain IAtomicLong operations.
    private boolean isCallerRunsOp(Operation op) {
        return op.getClass() == GetOperation.class
                || op.getClass() == SetOperation.class
                || op.getClass() == AddAndGetOperation.class;
    }

    @Override
    public boolean isRunAllowed(Operation op) {
        checkNotNull(op, "op can't be null");

        Thread currentThread = Thread.currentThread();

        // IO threads are not allowed to run any operation
        if (currentThread instanceof OperationHostileThread) {
            return false;
        }

        int partitionId = op.getPartitionId();
        // TODO: do we want to allow non partition specific tasks to be run on a partitionSpecific operation thread?
        // this could lead to deadlocks. e.g. a map.size call being called from within entry processor
        if (partitionId < 0) {
            return true;
        }

        if (currentThread.getClass() != PartitionOperationThread.class) {
//            OperationRunnerReference ref = PARTITION_OPERATION_RUNNER_THREAD_LOCAL.get();
//            if (ref. == null) {
//                return true;
//            }
//
//            return operationRunner.getPartitionId() < 0 || operationRunner.getPartitionId() == partitionId;
            return false;
        } else {
            PartitionOperationThread partitionThread = (PartitionOperationThread) currentThread;

            // so it's a partition operation thread, now we need to make sure that this operation thread is allowed
            // to execute operations for this particular partitionId
            return toPartitionThreadIndex(partitionId) == partitionThread.threadId;
        }
    }

    @Override
    public boolean isInvocationAllowed(Operation op, boolean isAsync) {
        checkNotNull(op, "op can't be null");

        Thread currentThread = Thread.currentThread();

        // IO threads are not allowed to run any operation
        if (currentThread instanceof OperationHostileThread) {
            return false;
        }

        // if it is async we don't need to check if it is PartitionOperationThread or not
        if (isAsync) {
            return true;
        }

        // allowed to invoke non partition specific task
        if (op.getPartitionId() < 0) {
            return true;
        }

        // allowed to invoke from non PartitionOperationThreads (including GenericOperationThread)
        if (currentThread.getClass() != PartitionOperationThread.class) {
            return true;
        }

        PartitionOperationThread partitionThread = (PartitionOperationThread) currentThread;
        OperationRunner runner = partitionThread.runnerReference.runner;
        if (runner != null) {
            // non null runner means it's a nested call
            // in this case partitionId of both inner and outer operations have to match
            return runner.getPartitionId() == op.getPartitionId();
        }

        return toPartitionThreadIndex(op.getPartitionId()) == partitionThread.threadId;
    }

    // public for testing purposes
    public int toPartitionThreadIndex(int partitionId) {
        return partitionId % partitionThreads.length;
    }

    @Override
    public void start() {
        logger.info("Starting " + partitionThreads.length + " partition threads");
        startAll(partitionThreads);

        logger.info("Starting " + genericThreads.length + " generic threads ("
                + priorityThreadCount + " dedicated for priority tasks)");
        startAll(genericThreads);

        logger.info("Caller runs optimization " + (callerRuns ? "enabled" : "disabled"));
    }

    private static void startAll(OperationThread[] operationThreads) {
        for (OperationThread thread : operationThreads) {
            thread.start();
        }
    }

    @Override
    public void shutdown() {
        shutdownAll(partitionThreads);
        shutdownAll(genericThreads);
        awaitTermination(partitionThreads);
        awaitTermination(genericThreads);

        logger.warning("Conflict count:" + conflictCount);
    }

    private static void shutdownAll(OperationThread[] operationThreads) {
        for (OperationThread thread : operationThreads) {
            thread.shutdown();
        }
    }

    private static void awaitTermination(OperationThread[] operationThreads) {
        for (OperationThread thread : operationThreads) {
            try {
                thread.awaitTermination(TERMINATION_TIMEOUT_SECONDS, SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public String toString() {
        return "OperationExecutorImpl{node=" + thisAddress + '}';
    }
}
