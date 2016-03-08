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

package com.hazelcast.nio.tcp.nonblocking;

import com.hazelcast.core.HazelcastException;
import com.hazelcast.internal.metrics.Probe;
import com.hazelcast.internal.metrics.ProbeLevel;
import com.hazelcast.internal.util.counters.SwCounter;
import com.hazelcast.logging.ILogger;
import com.hazelcast.spi.impl.operationexecutor.OperationHostileThread;

import java.io.IOException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static com.hazelcast.internal.util.counters.SwCounter.newSwCounter;
import static com.hazelcast.nio.tcp.nonblocking.SelectorOptimizer.optimize;
import static java.lang.Math.max;
import static java.lang.System.currentTimeMillis;

public class NonBlockingIOThread extends Thread implements OperationHostileThread {

    // WARNING: This value has significant effect on idle CPU usage!
    private static final int SELECT_WAIT_TIME_MILLIS = 5000;
    private static final int SELECT_FAILURE_PAUSE_MILLIS = 1000;

    // this field is set during construction and is meant for the probes so that the read/write handler can
    // indicate which thread they are currently bound to.
    @Probe(name = "ioThreadId", level = ProbeLevel.INFO)
    int id;

    @Probe(name = "taskQueueSize")
    private final Queue<Object> taskQueue = new ConcurrentLinkedQueue<Object>();
    @Probe
    private final SwCounter eventCount = newSwCounter();
    @Probe
    private final SwCounter selectorIOExceptionCount = newSwCounter();
    @Probe
    private final SwCounter completedTaskCount = newSwCounter();

    private final ILogger logger;

    private final Selector selector;

    private final NonBlockingIOThreadOutOfMemoryHandler oomeHandler;

    private final boolean selectNow;

    private volatile long lastSelectTimeMs;

    public NonBlockingIOThread(ThreadGroup threadGroup,
                               String threadName,
                               ILogger logger,
                               NonBlockingIOThreadOutOfMemoryHandler oomeHandler) {
        this(threadGroup, threadName, logger, oomeHandler, false);
    }

    public NonBlockingIOThread(ThreadGroup threadGroup,
                               String threadName,
                               ILogger logger,
                               NonBlockingIOThreadOutOfMemoryHandler oomeHandler,
                               boolean selectNow) {
        this(threadGroup, threadName, logger, oomeHandler, selectNow, newSelector(logger));
    }

    public NonBlockingIOThread(ThreadGroup threadGroup,
                               String threadName,
                               ILogger logger,
                               NonBlockingIOThreadOutOfMemoryHandler oomeHandler,
                               boolean selectNow,
                               Selector selector) {
        super(threadGroup, threadName);
        this.logger = logger;
        this.selectNow = selectNow;
        this.oomeHandler = oomeHandler;
        this.selector = selector;
    }

    private static Selector newSelector(ILogger logger) {
        try {
            Selector selector = Selector.open();
            if (Boolean.getBoolean("tcp.optimizedselector")) {
                optimize(selector, logger);
            }
            return selector;
        } catch (final IOException e) {
            throw new HazelcastException("Failed to open a Selector", e);
        }
    }

    /**
     * Gets the Selector
     *
     * @return the Selector
     */
    public final Selector getSelector() {
        return selector;
    }

    /**
     * Returns the total number of selection-key events that have been processed by this thread.
     *
     * @return total number of selection-key events.
     */
    public long getEventCount() {
        return eventCount.get();
    }

    /**
     * A probe that measure how long this NonBlockingIOThread has not received any events.
     *
     * @return the idle time in ms.
     */
    @Probe
    private long idleTimeMs() {
        return max(currentTimeMillis() - lastSelectTimeMs, 0);
    }

    /**
     * Adds a task to this NonBlockingIOThread without notifying the thread.
     *
     * @param task the task to add
     * @throws NullPointerException if task is null
     */
    public final void addTask(Runnable task) {
        taskQueue.add(task);
    }

    /**
     * Adds a task to be executed by the NonBlockingIOThread and wakes up the selector so that it will
     * eventually pick up the task.
     *
     * @param task the task to add.
     * @throws NullPointerException if task is null
     */
    public void addTaskAndWakeup(Runnable task) {
        taskQueue.add(task);
        if (!selectNow) {
            selector.wakeup();
        }
    }

    public void addTask(SelectionHandler handler) {
        taskQueue.add(handler);
    }

    public void addTaskAndWakeup(SelectionHandler task) {
        taskQueue.add(task);
        if (!selectNow) {
            selector.wakeup();
        }
    }

    @Override
    public final void run() {
        // This outer loop is a bit complex but it takes care of a lot of stuff:
        // * it calls runSelectNowLoop or runSelectLoop based on selectNow enabled or not.
        // * handles backoff and retrying in case if io exception is thrown
        // * it takes care of other exception handling.
        //
        // The idea about this approach is that the runSelectNowLoop and runSelectLoop are as clean as possible and don't contain
        // any logic that isn't happening on the happy-path.
        try {
            for (; ; ) {
                try {
                    if (selectNow) {
                        runSelectNowLoop();
                    } else {
                        runSelectLoop();
                    }
                    // break the for loop; we are done
                    break;
                } catch (IOException nonFatalException) {
                    selectorIOExceptionCount.inc();
                    logger.warning(getName() + " " + nonFatalException.toString(), nonFatalException);
                    coolDown();
                }
            }
        } catch (OutOfMemoryError e) {
            oomeHandler.handle(e);
        } catch (Throwable e) {
            logger.warning("Unhandled exception in " + getName(), e);
        } finally {
            closeSelector();
        }

        logger.finest(getName() + " finished");
    }

    /**
     * When an IOException happened, the loop is going to be retried but we need to wait a bit
     * before retrying. If we don't wait, it can be that a subsequent call will run into an IOException
     * immediately. This can lead to a very hot loop and we don't want that. A similar approach is used
     * in Netty
     */
    private void coolDown() {
        try {
            Thread.sleep(SELECT_FAILURE_PAUSE_MILLIS);
        } catch (InterruptedException i) {
            // if the thread is interrupted, we just restore the interrupt flag and let one of the loops deal with it
            interrupt();
        }
    }

    private void runSelectLoop() throws IOException {
        while (!isInterrupted()) {
            processTaskQueue();

            int selectedKeys = selector.select(SELECT_WAIT_TIME_MILLIS);
            if (selectedKeys > 0) {
                lastSelectTimeMs = currentTimeMillis();
                handleSelectionKeys();
            }
        }
    }

    private void runSelectNowLoop() throws IOException {
        while (!isInterrupted()) {
            processTaskQueue();

            int selectedKeys = selector.selectNow();
            if (selectedKeys > 0) {
                lastSelectTimeMs = currentTimeMillis();
                handleSelectionKeys();
            }
        }
    }

    private void processTaskQueue() {
        while (!isInterrupted()) {
            Object task = taskQueue.poll();
            if (task == null) {
                return;
            }
            executeTask(task);
        }
    }

    private void executeTask(Object task) {
        completedTaskCount.inc();

        if (task.getClass() == RunnableWrapper.class) {
            ((RunnableWrapper) task).runnable.run();
            return;
        }

        SelectionHandler handler = (SelectionHandler) task;
        NonBlockingIOThread owner = handler.getOwner();
        if (owner == this) {
            try {
                handler.handle();
            } catch (Exception e) {
                handler.onFailure(e);
            }
        } else {
            owner.addTaskAndWakeup(handler);
        }
    }

    private void handleSelectionKeys() {
        Iterator<SelectionKey> it = selector.selectedKeys().iterator();
        while (it.hasNext()) {
            SelectionKey sk = it.next();
            it.remove();

            handleSelectionKey(sk);
        }
    }

    protected void handleSelectionKey(SelectionKey sk) {
        SelectionHandler handler = (SelectionHandler) sk.attachment();
        try {
            if (!sk.isValid()) {
                // if the selectionKey isn't valid, we throw this exception to feedback the situation into the handler.onFailure
                throw new CancelledKeyException();
            }

            // we don't need to check for sk.isReadable/sk.isWritable since the handler has only registered
            // for events it can handle.
            eventCount.inc();
            handler.handle();
        } catch (Throwable t) {
            handler.onFailure(t);
        }
    }

    private void closeSelector() {
        if (logger.isFinestEnabled()) {
            logger.finest("Closing selector for:" + getName());
        }

        try {
            selector.close();
        } catch (Exception e) {
            logger.finest("Failed to close selector", e);
        }
    }

    public final void shutdown() {
        taskQueue.clear();
        interrupt();
    }

    @Override
    public String toString() {
        return getName();
    }

    static final class RunnableWrapper {
        private final Runnable runnable;

        RunnableWrapper(Runnable runnable) {
            this.runnable = runnable;
        }
    }
}
