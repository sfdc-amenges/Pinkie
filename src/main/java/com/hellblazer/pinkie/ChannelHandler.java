/*
 * Copyright (c) 2009, 2011 Hal Hildebrand, all rights reserved.
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
package com.hellblazer.pinkie;

import static java.lang.String.format;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class provides a full featured non blocking NIO handler for selectable
 * channels. In addition, it provides facilities for dealing with outbound
 * connections.
 * 
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 * 
 * 
 */
public class ChannelHandler {

    private final static Logger                 log           = LoggerFactory.getLogger(ChannelHandler.class);

    private final ReentrantLock                 handlersLock  = new ReentrantLock();
    private volatile SocketChannelHandler       openHandlers;
    protected final AtomicBoolean               run           = new AtomicBoolean();
    private final Thread[]                      readSelectorThreads;
    private final Thread[]                      writeSelectorThreads;
    protected final int                         selectTimeout = 1000;
    protected final ExecutorService             executor;
    protected final String                      name;
    protected final SocketOptions               options;
    private final LinkedBlockingDeque<Runnable> readRegisters[];
    private final LinkedBlockingDeque<Runnable> writeRegisters[];
    private final Selector[]                    readSelectors;
    private final Selector[]                    writeSelectors;
    private final AtomicInteger                 nextQueue     = new AtomicInteger();

    /**
     * Construct a new channel handler with a single selector queue
     * 
     * @param handlerName
     *            - the String name used to mark the selection thread
     * @param socketOptions
     *            - the socket options to configure new sockets
     * @param executor
     *            - the executor service to handle I/O and selection events
     * @throws IOException
     *             - if things go pear shaped when opening the selector
     */
    public ChannelHandler(String handlerName, SocketOptions socketOptions,
                          ExecutorService executor) throws IOException {
        this(handlerName, socketOptions, executor, 1);
    }

    /**
     * Construct a new channel handler
     * 
     * @param handlerName
     *            - the String name used to mark the selection thread
     * @param socketOptions
     *            - the socket options to configure new sockets
     * @param executor
     *            - the executor service to handle I/O and selection events
     * @param selectorQueues
     *            - the number of selectors to use
     * @throws IOException
     *             - if things go pear shaped when opening the selector
     */
    @SuppressWarnings("unchecked")
    public ChannelHandler(String handlerName, SocketOptions socketOptions,
                          ExecutorService executor, int selectorQueues)
                                                                       throws IOException {
        name = handlerName;
        if (selectorQueues <= 0) {
            throw new IllegalArgumentException(
                                               String.format("selectorQueues must be > 0: %s",
                                                             selectorQueues));
        }
        readSelectors = new Selector[selectorQueues];
        writeSelectors = new Selector[selectorQueues];
        readSelectorThreads = new Thread[selectorQueues];
        writeSelectorThreads = new Thread[selectorQueues];
        LinkedBlockingDeque<Runnable>[] regs = new LinkedBlockingDeque[selectorQueues];
        writeRegisters = regs;
        regs = new LinkedBlockingDeque[selectorQueues];
        readRegisters = regs;
        this.executor = executor;
        options = socketOptions;

        for (int i = 0; i < selectorQueues; i++) {
            readSelectorThreads[i] = new Thread(
                                                readSelectorTask(i),
                                                String.format("Read selector[%s (%s)]",
                                                              name, i));
            readSelectorThreads[i].setDaemon(true);
            readSelectors[i] = Selector.open();
            readRegisters[i] = new LinkedBlockingDeque<>();

            writeSelectorThreads[i] = new Thread(
                                                 writeSelectorTask(i),
                                                 String.format("Write selector[%s (%s)]",
                                                               name, i));
            writeSelectorThreads[i].setDaemon(true);
            writeSelectors[i] = Selector.open();
            writeRegisters[i] = new LinkedBlockingDeque<>();
        }
    }

    /**
     * Close the open handlers managed by the receiver
     */
    public void closeOpenHandlers() {
        final Lock myLock = handlersLock;
        myLock.lock();
        try {
            SocketChannelHandler handler = openHandlers;
            while (handler != null) {
                handler.close();
                handler = handler.next();
            }
            openHandlers = null;
        } finally {
            myLock.unlock();
        }
    }

    /**
     * Connect to the remote address. The connection will be made in a
     * non-blocking fashion. The
     * CommunicationsHandler.handleConnect(SocketChannel) on the event handler
     * will be called when the socket channel actually connects.
     * 
     * @param remoteAddress
     * @param eventHandler
     * @throws IOException
     */
    public void connectTo(final InetSocketAddress remoteAddress,
                          CommunicationsHandler eventHandler)
                                                             throws IOException {
        assert remoteAddress != null : "Remote address cannot be null";
        assert eventHandler != null : "Handler cannot be null";
        final int index = nextQueueIndex();
        final SocketChannel socketChannel = SocketChannel.open();
        final SocketChannelHandler handler = new SocketChannelHandler(
                                                                      eventHandler,
                                                                      ChannelHandler.this,
                                                                      socketChannel,
                                                                      index);
        options.configure(socketChannel.socket());
        addHandler(handler);
        socketChannel.configureBlocking(false);
        registerConnect(index, socketChannel, handler);
        try {
            socketChannel.connect(remoteAddress);
        } catch (IOException e) {
            log.warn(String.format("Cannot connect to %s [%s]", remoteAddress,
                                   name), e);
            handler.close();
            return;
        }
        return;
    }

    void registerConnect(final int index, final SocketChannel socketChannel,
                         final SocketChannelHandler handler) {
        readRegisters[index].add(new Runnable() {
            @Override
            public void run() {
                if (log.isTraceEnabled()) {
                    log.trace("registering connect for {}", socketChannel);
                }
                register(index, socketChannel, handler, SelectionKey.OP_CONNECT);
            }
        });
        readWakeup(index);
    }

    int nextQueueIndex() {
        return nextQueue.getAndIncrement() % readSelectors.length;
    }

    public List<CommunicationsHandler> getOpenHandlers() {
        LinkedList<CommunicationsHandler> handlers = new LinkedList<CommunicationsHandler>();
        final Lock myLock = handlersLock;
        myLock.lock();
        try {
            SocketChannelHandler current = openHandlers;
            while (current != null) {
                handlers.add(current.getHandler());
                current = current.next();
            }
        } finally {
            myLock.unlock();
        }

        return handlers;
    }

    /**
     * Answer the socket options of the receiver
     * 
     * @return
     */
    public SocketOptions getOptions() {
        return options;
    }

    /**
     * Answer true if the receiver is running
     * 
     * @return
     */
    public boolean isRunning() {
        return run.get();
    }

    /**
     * Starts the socket handler service
     */
    public void start() {
        if (run.compareAndSet(false, true)) {
            startService();
        }
    }

    /**
     * Terminates the socket handler service
     */
    public void terminate() {
        if (run.compareAndSet(true, false)) {
            terminateService();
        }
    }

    private Runnable writeSelectorTask(final int index) {
        return new Runnable() {
            @Override
            public void run() {
                while (run.get()) {
                    try {
                        writeSelect(index);
                    } catch (ClosedSelectorException e) {
                        if (log.isTraceEnabled()) {
                            log.trace(String.format("Channel closed [%s]", name),
                                      e);
                        }
                        return;
                    } catch (IOException e) {
                        if (log.isTraceEnabled()) {
                            log.trace(String.format("Error when selecting [%s]",
                                                    name), e);
                        }
                        return;
                    } catch (Throwable e) {
                        log.error(String.format("Runtime exception when selecting [%s]",
                                                name), e);
                        return;
                    }
                }
            }
        };
    }

    private Runnable readSelectorTask(final int index) {
        return new Runnable() {
            @Override
            public void run() {
                while (run.get()) {
                    try {
                        readSelect(index);
                    } catch (ClosedSelectorException e) {
                        if (log.isTraceEnabled()) {
                            log.trace(String.format("Channel closed [%s]", name),
                                      e);
                        }
                        return;
                    } catch (IOException e) {
                        if (log.isTraceEnabled()) {
                            log.trace(String.format("Error when selecting [%s]",
                                                    name), e);
                        }
                        return;
                    } catch (Throwable e) {
                        log.error(String.format("Runtime exception when selecting [%s]",
                                                name), e);
                        return;
                    }
                }
            }
        };
    }

    void addHandler(SocketChannelHandler handler) {
        final Lock myLock = handlersLock;
        myLock.lock();
        try {
            if (openHandlers == null) {
                openHandlers = handler;
            } else {
                openHandlers.link(handler);
            }
        } finally {
            myLock.unlock();
        }
    }

    void closeHandler(SocketChannelHandler handler) {
        final Lock myLock = handlersLock;
        myLock.lock();
        try {
            if (openHandlers == null) {
                openHandlers = handler.next();
            } else {
                handler.delink();
            }
        } finally {
            myLock.unlock();
        }
    }

    private void handleConnect(SocketChannelHandler handler,
                               SocketChannel channel) {
        if (log.isTraceEnabled()) {
            log.trace(String.format("Handling connect [%s]", name));
        }
        try {
            channel.finishConnect();
        } catch (IOException e) {
            log.info(String.format("Unable to finish connection %s [%s] error: %s",
                                   handler.getChannel(), name, e));
            handler.close();
            return;
        }
        if (log.isTraceEnabled()) {
            log.trace(String.format("Dispatching connected action [%s]", name));
        }
        try {
            executor.execute(handler.connectHandler());
        } catch (RejectedExecutionException e) {
            if (log.isInfoEnabled()) {
                log.info(String.format("too busy to execute connect handling [%s] of [%s]",
                                       name, handler.getChannel()));
            }
            handler.close();
        }
    }

    private void handleRead(SocketChannelHandler handler, SocketChannel channel) {
        if (log.isTraceEnabled()) {
            log.trace(String.format("Handling read [%s]", name));
        }
        try {
            executor.execute(handler.readHandler);
        } catch (RejectedExecutionException e) {
            if (log.isInfoEnabled()) {
                log.info(String.format("too busy to execute read handling [%s], reselecting [%s]",
                                       name, handler.getChannel()));
            }
            handler.selectForRead();
        }
    }

    private void handleWrite(SocketChannelHandler handler, SocketChannel channel) {
        if (log.isTraceEnabled()) {
            log.trace(String.format("Handling write [%s]", name));
        }
        try {
            executor.execute(handler.writeHandler);
        } catch (RejectedExecutionException e) {
            if (log.isInfoEnabled()) {
                log.info(String.format("too busy to execute write handling [%s], reselecting [%s]",
                                       name, handler.getChannel()));
            }
            handler.selectForWrite();
        }
    }

    final void registerRead(int index, Runnable select) {
        readRegisters[index].add(select);
        readWakeup(index);
    }

    final void registerWrite(int index, Runnable select) {
        writeRegisters[index].add(select);
        writeWakeup(index);
    }

    SelectionKey register(int index, SocketChannel channel,
                          SocketChannelHandler handler, int operation) {
        assert !channel.isBlocking() : String.format("Socket has not been set to non blocking mode [%s]",
                                                     name);
        SelectionKey key = null;
        Selector selector;
        switch (operation) {
            case SelectionKey.OP_CONNECT:
                selector = readSelectors[index];
                break;
            case SelectionKey.OP_READ:
                selector = readSelectors[index];
                break;
            case SelectionKey.OP_WRITE:
                selector = writeSelectors[index];
                break;
            default:
                throw new IllegalArgumentException(
                                                   String.format("Inavlid operation %s",
                                                                 operation));
        }
        try {
            key = channel.register(selector, operation, handler);
        } catch (NullPointerException e) {
            // apparently the file descriptor can be nulled
            log.trace(String.format("anamalous null pointer exception [%s]",
                                    name), e);
        } catch (ClosedChannelException e) {
            if (log.isTraceEnabled()) {
                log.trace(String.format("channel has been closed [%s]", name),
                          e);
            }
            handler.close();
        }
        return key;
    }

    private void readSelect(int index) throws IOException {
        if (log.isTraceEnabled()) {
            log.trace(String.format("Selecting for read [%s]", name));
        }

        Runnable register = readRegisters[index].pollFirst();
        while (register != null) {
            try {
                register.run();
            } catch (Throwable e) {
                log.error("Error when registering read [{}]", name, e);
            }
            register = readRegisters[index].pollFirst();
        }

        int ready = readSelectors[index].select(selectTimeout);

        if (log.isTraceEnabled()) {
            log.trace(String.format("Selected %s read ready channels [%s]",
                                    ready, name));
        }

        // get an iterator over the set of selected keys
        Iterator<SelectionKey> selected;
        try {
            selected = readSelectors[index].selectedKeys().iterator();
        } catch (ClosedSelectorException e) {
            return;
        }

        while (run.get() && selected.hasNext()) {
            SelectionKey key = selected.next();
            selected.remove();
            SocketChannelHandler handler = (SocketChannelHandler) key.attachment();
            SocketChannel channel = (SocketChannel) key.channel();
            if (key.isConnectable()) {
                key.interestOps(0);
                handleConnect(handler, channel);
            } else if (key.isReadable()) {
                key.interestOps(0);
                handleRead(handler, channel);
            } else {
                log.error("Invalid selection key operation: {}", key);
                continue;
            }
        }
    }

    private void writeSelect(int index) throws IOException {
        if (log.isTraceEnabled()) {
            log.trace(String.format("Selecting for write [%s]", name));
        }

        Runnable register = writeRegisters[index].pollFirst();
        while (register != null) {
            try {
                register.run();
            } catch (Throwable e) {
                log.error("Error when registering write [{}]", name, e);
            }
            register = writeRegisters[index].pollFirst();
        }

        int ready = writeSelectors[index].select(selectTimeout);

        if (log.isTraceEnabled()) {
            log.trace(String.format("Selected %s write ready channels [%s]",
                                    ready, name));
        }

        // get an iterator over the set of selected keys
        Iterator<SelectionKey> selected;
        try {
            selected = writeSelectors[index].selectedKeys().iterator();
        } catch (ClosedSelectorException e) {
            return;
        }

        while (run.get() && selected.hasNext()) {
            SelectionKey key = selected.next();
            selected.remove();
            SocketChannelHandler handler = (SocketChannelHandler) key.attachment();
            SocketChannel channel = (SocketChannel) key.channel();
            if (key.isWritable()) {
                key.interestOps(0);
                handleWrite(handler, channel);
            } else {
                log.error("Invalid selection key operation: {}", key);
                continue;
            }
        }
    }

    final Runnable selectForRead(final int index,
                                 final SocketChannelHandler handler) {
        return new Runnable() {
            @Override
            public void run() {
                if (log.isTraceEnabled()) {
                    log.trace("registering read for {}", handler.getChannel());
                }
                register(index, handler.getChannel(), handler,
                         SelectionKey.OP_READ);
            }
        };
    }

    final Runnable selectForWrite(final int index,
                                  final SocketChannelHandler handler) {
        return new Runnable() {
            @Override
            public void run() {
                if (log.isTraceEnabled()) {
                    log.trace("registering write for {}", handler.getChannel());
                }
                register(index, handler.getChannel(), handler,
                         SelectionKey.OP_WRITE);
            }
        };
    }

    void startService() {
        for (Thread selectorThread : readSelectorThreads) {
            selectorThread.start();
        }
        for (Thread selectorThread : writeSelectorThreads) {
            selectorThread.start();
        }
        log.info(format("%s is started", name));
    }

    void terminateService() {
        for (Selector selector : writeSelectors) {
            selector.wakeup();
            try {
                selector.close();
            } catch (IOException e) {
                log.info(String.format("Error closing write selector [%s]",
                                       name), e);
            }
        }
        for (Selector selector : readSelectors) {
            selector.wakeup();
            try {
                selector.close();
            } catch (IOException e) {
                log.info(String.format("Error closing read selector [%s]", name),
                         e);
            }
        }
        closeOpenHandlers();
        log.info(format("%s is terminated", name));
    }

    void writeWakeup(int index) {
        try {
            writeSelectors[index].wakeup();
        } catch (NullPointerException e) {
            // Bug in JRE
            if (log.isTraceEnabled()) {
                log.trace(String.format("Caught null pointer in selector wakeup [%s]",
                                        name), e);
            }
        }
    }

    void readWakeup(int index) {
        try {
            readSelectors[index].wakeup();
        } catch (NullPointerException e) {
            // Bug in JRE
            if (log.isTraceEnabled()) {
                log.trace(String.format("Caught null pointer in selector wakeup [%s]",
                                        name), e);
            }
        }
    }

    void wakeup(int index) {
        writeWakeup(index);
        readWakeup(index);
    }
}