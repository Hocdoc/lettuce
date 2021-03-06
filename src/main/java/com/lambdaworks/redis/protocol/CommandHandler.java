// Copyright (C) 2011 - Will Glozer.  All rights reserved.

package com.lambdaworks.redis.protocol;

import static com.google.common.base.Preconditions.checkArgument;

import java.nio.channels.ClosedChannelException;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.locks.ReentrantLock;

import com.google.common.collect.ImmutableList;
import com.lambdaworks.redis.ClientOptions;
import com.lambdaworks.redis.ConnectionEvents;
import com.lambdaworks.redis.RedisChannelHandler;
import com.lambdaworks.redis.RedisChannelWriter;
import com.lambdaworks.redis.RedisException;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

/**
 * A netty {@link ChannelHandler} responsible for writing redis commands and reading responses from the server.
 * 
 * @param <K> Key type.
 * @param <V> Value type.
 * @author Will Glozer
 */
@ChannelHandler.Sharable
public class CommandHandler<K, V> extends ChannelDuplexHandler implements RedisChannelWriter<K, V> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(CommandHandler.class);
    private static final WriteLogListener WRITE_LOG_LISTENER = new WriteLogListener();

    protected ClientOptions clientOptions;
    protected Queue<RedisCommand<K, V, ?>> queue;

    // all access to the commandBuffer is synchronized
    protected Queue<RedisCommand<K, V, ?>> commandBuffer = newCommandBuffer();

    protected ByteBuf buffer;
    protected RedisStateMachine<K, V> rsm;
    protected Channel channel;
    protected final ReentrantLock writeLock = new ReentrantLock();

    private LifecycleState lifecycleState = LifecycleState.NOT_CONNECTED;
    private Object stateLock = new Object();

    /**
     * If TRACE level logging has been enabled at startup.
     */
    private final boolean traceEnabled;

    /**
     * If DEBUG level logging has been enabled at startup.
     */
    private final boolean debugEnabled;

    private final Reliability reliability;
    private RedisChannelHandler<K, V> redisChannelHandler;
    private Throwable connectionError;
    private String logPrefix;
    private boolean autoFlushCommands = true;

    /**
     * Initialize a new instance that handles commands from the supplied queue.
     *
     * @param clientOptions client options for this connection
     * @param queue The command queue
     */
    public CommandHandler(ClientOptions clientOptions, Queue<RedisCommand<K, V, ?>> queue) {
        this.clientOptions = clientOptions;
        this.queue = queue;
        this.traceEnabled = logger.isTraceEnabled();
        this.debugEnabled = logger.isDebugEnabled();
        this.reliability = clientOptions.isAutoReconnect() ? Reliability.AT_LEAST_ONCE : Reliability.AT_MOST_ONCE;
    }

    /**
     * 
     * @see io.netty.channel.ChannelInboundHandlerAdapter#channelRegistered(io.netty.channel.ChannelHandlerContext)
     */
    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        setState(LifecycleState.REGISTERED);
        buffer = ctx.alloc().directBuffer(8192 * 8);
        rsm = new RedisStateMachine<K, V>();
        synchronized (stateLock) {
            channel = ctx.channel();
        }
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        releaseBuffer();

        if (lifecycleState == LifecycleState.CLOSED) {
            cancelCommands("Connection closed");
        }
        synchronized (stateLock) {
            channel = null;
        }
    }

    /**
     * 
     * @see io.netty.channel.ChannelInboundHandlerAdapter#channelRead(io.netty.channel.ChannelHandlerContext, java.lang.Object)
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        ByteBuf input = (ByteBuf) msg;

        if (!input.isReadable() || input.refCnt() == 0 || buffer == null) {
            return;
        }

        try {
            buffer.writeBytes(input);

            if (traceEnabled) {
                logger.trace("{} Received: {}", logPrefix(), buffer.toString(Charset.defaultCharset()).trim());
            }

            decode(ctx, buffer);
        } finally {
            input.release();
        }
    }

    protected void decode(ChannelHandlerContext ctx, ByteBuf buffer) throws InterruptedException {

        while (!queue.isEmpty()) {

            RedisCommand<K, V, ?> command = queue.peek();

            if (debugEnabled) {
                logger.debug("{} Queue contains: {} commands", logPrefix(), queue.size());
            }

            if (!rsm.decode(buffer, command, command.getOutput())) {
                return;
            }

            command = queue.poll();
            command.complete();
            if (buffer != null && buffer.refCnt() != 0) {
                buffer.discardReadBytes();
            }
        }
    }

    @Override
    public <T> RedisCommand<K, V, T> write(RedisCommand<K, V, T> command) {

        checkArgument(command != null, "command must not be null");

        if (lifecycleState == LifecycleState.CLOSED) {
            throw new RedisException("Connection is closed");
        }

        if ((channel == null || !isConnected()) && !clientOptions.isAutoReconnect()) {
            throw new RedisException(
                    "Connection is in a disconnected state and reconnect is disabled. Commands are not accepted.");
        }

        try {
            /**
             * This lock causes safety for connection activation and somehow netty gets more stable and predictable performance
             * than without a lock and all threads are hammering towards writeAndFlush.
             */

            writeLock.lock();
            Channel channel = this.channel;
            if (autoFlushCommands) {

                if (channel != null && isConnected() && channel.isActive()) {
                    if (debugEnabled) {
                        logger.debug("{} write() writeAndFlush Command {}", logPrefix(), command);
                    }

                    if (reliability == Reliability.AT_MOST_ONCE) {
                        // cancel on exceptions and remove from queue, because there is no housekeeping
                        channel.writeAndFlush(command).addListener(new AtMostOnceWriteListener(command, queue));
                    }

                    if (reliability == Reliability.AT_LEAST_ONCE) {
                        // commands are ok to stay within the queue, reconnect will retrigger them
                        channel.writeAndFlush(command).addListener(WRITE_LOG_LISTENER);
                    }
                } else {

                    if (commandBuffer.contains(command) || queue.contains(command)) {
                        return command;
                    }

                    if (connectionError != null) {
                        if (debugEnabled) {
                            logger.debug("{} write() completing Command {} due to connection error", logPrefix(), command);
                        }
                        command.setException(connectionError);
                        command.complete();
                        return command;
                    }

                    bufferCommand(command);
                }
            } else {
                bufferCommand(command);
            }
        } finally {
            writeLock.unlock();
            if (debugEnabled) {
                logger.debug("{} write() done", logPrefix());
            }
        }

        return command;
    }

    private <T> void bufferCommand(RedisCommand<K, V, T> command) {
        if (debugEnabled) {
            logger.debug("{} write() buffering Command {}", logPrefix(), command);
        }
        commandBuffer.add(command);
    }

    private boolean isConnected() {
        synchronized (lifecycleState) {
            return lifecycleState.ordinal() >= LifecycleState.CONNECTED.ordinal()
                    && lifecycleState.ordinal() <= LifecycleState.DISCONNECTED.ordinal();
        }
    }

    @Override
    @SuppressWarnings("rawtypes")
    public void flushCommands() {
        if (channel != null && isConnected()) {
            Queue<RedisCommand<?, ?, ?>> queuedCommands;
            try {
                writeLock.lock();
                queuedCommands = (Queue) commandBuffer;
                commandBuffer = newCommandBuffer();
            } finally {
                writeLock.unlock();
            }

            if (reliability == Reliability.AT_MOST_ONCE) {
                // cancel on exceptions and remove from queue, because there is no housekeeping
                channel.writeAndFlush(queuedCommands).addListener(new AtMostOnceWriteListener(queuedCommands, this.queue));
            }

            if (reliability == Reliability.AT_LEAST_ONCE) {
                // commands are ok to stay within the queue, reconnect will retrigger them
                channel.writeAndFlush(queuedCommands).addListener(WRITE_LOG_LISTENER);
            }
        }
    }

    /**
     *
     * @see io.netty.channel.ChannelDuplexHandler#write(io.netty.channel.ChannelHandlerContext, java.lang.Object,
     *      io.netty.channel.ChannelPromise)
     */
    @Override
    @SuppressWarnings("unchecked")
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {

        if (msg instanceof Collection) {
            Collection<RedisCommand<K, V, ?>> commands = (Collection<RedisCommand<K, V, ?>>) msg;
            for (RedisCommand<K, V, ?> command : commands) {
                queueCommand(promise, command);
            }
            ctx.write(commands, promise);
            return;
        }

        RedisCommand<K, V, ?> cmd = (RedisCommand<K, V, ?>) msg;
        queueCommand(promise, cmd);
        ctx.write(cmd, promise);
    }

    private void queueCommand(ChannelPromise promise, RedisCommand<K, V, ?> cmd) throws Exception {
        if (cmd.isCancelled()) {
            return;
        }

        try {
            if (cmd.getOutput() == null) {
                cmd.complete();
            } else {
                queue.add(cmd);
            }
        } catch (Exception e) {
            cmd.setException(e);
            cmd.cancel(true);
            promise.setFailure(e);
            throw e;
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        logPrefix = null;
        if (debugEnabled) {
            logger.debug("{} channelActive()", logPrefix());
        }
        setStateIfNotClosed(LifecycleState.CONNECTED);

        try {
            executeQueuedCommands(ctx);
        } catch (Exception e) {
            if (debugEnabled) {
                logger.debug("{} channelActive() ran into an exception", logPrefix());
            }
            if (clientOptions.isCancelCommandsOnReconnectFailure()) {
                reset();
            }
            throw e;
        }

        super.channelActive(ctx);
        channel.eventLoop().submit(new Runnable() {
            @Override
            public void run() {
                channel.pipeline().fireUserEventTriggered(new ConnectionEvents.Activated());
            }
        });

        if (debugEnabled) {
            logger.debug("{} channelActive() done", logPrefix());
        }
    }

    protected void executeQueuedCommands(ChannelHandlerContext ctx) {
        Queue<RedisCommand<K, V, ?>> tmp = newCommandBuffer();

        try {
            writeLock.lock();
            connectionError = null;

            tmp.addAll(commandBuffer);
            tmp.addAll(queue);

            queue.clear();
            commandBuffer = tmp;

            if (debugEnabled) {
                logger.debug("{} executeQueuedCommands {} command(s) queued", logPrefix(), tmp.size());
            }

            synchronized (stateLock) {
                channel = ctx.channel();
            }

            if (redisChannelHandler != null) {
                if (debugEnabled) {
                    logger.debug("{} activating channel handler", logPrefix());
                }
                setStateIfNotClosed(LifecycleState.ACTIVATING);
                redisChannelHandler.activated();
            }
            setStateIfNotClosed(LifecycleState.ACTIVE);

            flushCommands();
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 
     * @see io.netty.channel.ChannelInboundHandlerAdapter#channelInactive(io.netty.channel.ChannelHandlerContext)
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (debugEnabled) {
            logger.debug("{} channelInactive()", logPrefix());
        }
        setStateIfNotClosed(LifecycleState.DISCONNECTED);

        if (redisChannelHandler != null) {
            if (debugEnabled) {
                logger.debug("{} deactivating channel handler", logPrefix());
            }
            setStateIfNotClosed(LifecycleState.DEACTIVATING);
            redisChannelHandler.deactivated();
        }
        setStateIfNotClosed(LifecycleState.DEACTIVATED);

        if (buffer != null) {
            rsm.reset();
            buffer.clear();
        }

        if (debugEnabled) {
            logger.debug("{} channelInactive() done", logPrefix());
        }
        super.channelInactive(ctx);
    }

    protected void setStateIfNotClosed(LifecycleState lifecycleState) {
        if (this.lifecycleState != LifecycleState.CLOSED) {
            setState(lifecycleState);
        }
    }

    protected void setState(LifecycleState lifecycleState) {
        synchronized (stateLock) {
            this.lifecycleState = lifecycleState;
        }
    }

    protected LifecycleState getState() {
        return lifecycleState;
    }

    private void cancelCommands(String message) {
        int size = 0;
        if (queue != null) {
            size += queue.size();
        }

        if (commandBuffer != null) {
            size += commandBuffer.size();
        }

        List<RedisCommand<K, V, ?>> toCancel = new ArrayList<RedisCommand<K, V, ?>>(size);

        if (queue != null) {
            toCancel.addAll(queue);
            queue.clear();
        }

        if (commandBuffer != null) {
            toCancel.addAll(commandBuffer);
            commandBuffer.clear();
        }

        for (RedisCommand<K, V, ?> cmd : toCancel) {
            if (cmd.getOutput() != null) {
                cmd.getOutput().setError(message);
            }
            cmd.cancel(true);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (debugEnabled) {
            logger.debug("{} exceptionCaught()", logPrefix(), cause);
            logger.debug(cause.getMessage(), cause);
        }
        if (!queue.isEmpty()) {
            RedisCommand<K, V, ?> command = queue.poll();
            if (debugEnabled) {
                logger.debug("{} Storing exception in {}", logPrefix(), command);
            }
            command.setException(cause);
            command.complete();
        }

        if (channel == null || !channel.isActive() || !isConnected()) {
            if (debugEnabled) {
                logger.debug("{} Storing exception in connectionError", logPrefix());
            }
            connectionError = cause;
            return;
        }
        super.exceptionCaught(ctx, cause);
    }

    /**
     * Close the connection.
     */
    @Override
    public void close() {

        if (debugEnabled) {
            logger.debug("{} close()", logPrefix());
        }

        if (lifecycleState == LifecycleState.CLOSED) {
            return;
        }

        setStateIfNotClosed(LifecycleState.CLOSED);
        Channel currentChannel = this.channel;
        if (currentChannel != null) {
            currentChannel.pipeline().fireUserEventTriggered(new ConnectionEvents.PrepareClose());
            currentChannel.pipeline().fireUserEventTriggered(new ConnectionEvents.Close());

            ChannelFuture close = currentChannel.pipeline().close();
            if (currentChannel.isOpen()) {
                close.syncUninterruptibly();
            }
        }
    }

    private void releaseBuffer() {
        if (buffer != null) {
            buffer.release();
            buffer = null;
        }
    }

    public boolean isClosed() {
        return lifecycleState == LifecycleState.CLOSED;
    }

    /**
     * Reset the writer state. Queued commands will be canceled and the internal state will be reset. This is useful when the
     * internal state machine gets out of sync with the connection.
     */
    @Override
    public void reset() {
        if (debugEnabled) {
            logger.debug("{} reset()", logPrefix());
        }
        try {
            writeLock.lock();
            cancelCommands("Reset");
        } finally {
            writeLock.unlock();
        }

        if (buffer != null) {
            rsm.reset();
            buffer.clear();
        }
    }

    @Override
    public void setRedisChannelHandler(RedisChannelHandler<K, V> redisChannelHandler) {
        this.redisChannelHandler = redisChannelHandler;
    }

    @Override
    public void setAutoFlushCommands(boolean autoFlush) {
        synchronized (stateLock) {
            this.autoFlushCommands = autoFlush;
        }
    }

    protected String logPrefix() {
        if (logPrefix != null) {
            return logPrefix;
        }
        StringBuffer buffer = new StringBuffer(64);
        buffer.append('[').append(ChannelLogDescriptor.logDescriptor(channel)).append(']');
        return logPrefix = buffer.toString();
    }

     private ArrayDeque<RedisCommand<K, V, ?>> newCommandBuffer() {
        return new ArrayDeque<RedisCommand<K, V, ?>>(512);
    }

    public enum LifecycleState {
        NOT_CONNECTED, REGISTERED, CONNECTED, ACTIVATING, ACTIVE, DISCONNECTED, DEACTIVATING, DEACTIVATED, CLOSED,
    }

    private enum Reliability {
        AT_MOST_ONCE, AT_LEAST_ONCE;
    }

    private static class AtMostOnceWriteListener implements ChannelFutureListener {

        private final Collection<RedisCommand<?, ?, ?>> sentCommands;
        private final Queue<?> queue;

        @SuppressWarnings("rawtypes")
        public AtMostOnceWriteListener(RedisCommand<?, ?, ?> sentCommand, Queue<?> queue) {
            this.sentCommands = (Collection) ImmutableList.of(sentCommand);
            this.queue = queue;
        }

        public AtMostOnceWriteListener(Collection<RedisCommand<?, ?, ?>> sentCommand, Queue<?> queue) {
            this.sentCommands = sentCommand;
            this.queue = queue;
        }

        @Override
        public void operationComplete(ChannelFuture future) throws Exception {
            future.await();
            if (future.cause() != null) {
                for (RedisCommand<?, ?, ?> sentCommand : sentCommands) {
                    sentCommand.setException(future.cause());
                    sentCommand.cancel(true);
                }
                queue.removeAll(sentCommands);
            }
        }
    }

    /**
     * A generic future listener which logs unsuccessful writes.
     *
     */
    static class WriteLogListener implements GenericFutureListener<Future<Void>> {
        @Override
        public void operationComplete(Future<Void> future) throws Exception {
            if (!future.isSuccess() && !(future.cause() instanceof ClosedChannelException))
                logger.warn(future.cause().getMessage(), future.cause());
        }

    }
}
