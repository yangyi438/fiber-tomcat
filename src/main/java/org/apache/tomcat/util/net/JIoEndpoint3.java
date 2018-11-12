
package org.apache.tomcat.util.net;

import java.io.IOException;
import java.net.*;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Locale;
import java.util.UnknownFormatConversionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import co.paralleluniverse.fibers.Fiber;
import co.paralleluniverse.fibers.FiberScheduler;
import co.paralleluniverse.fibers.Suspendable;
import io.netty.channel.nio.CoSocketEventLoop;
import io.netty.channel.nio.CoSocketRegisterUtils;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ScheduledFuture;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.compat.JreCompat;
import org.apache.tomcat.util.net.AbstractEndpoint.Handler.SocketState;
import org.apache.tomcat.util.net.jsse.NioX509KeyManager;
import org.apache.tomcat.util.threads.TaskQueue;
import org.apache.tomcat.util.threads.TaskThreadFactory;
import org.apache.tomcat.util.threads.ThreadPoolExecutor;
import yy.code.io.cosocket.*;
import yy.code.io.cosocket.config.CoSocketConfig;
import yy.code.io.cosocket.eventloop.CoSocketEventLoopGroup;
import yy.code.io.cosocket.status.SelectionKeyUtils;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSessionContext;
import javax.net.ssl.X509KeyManager;


/**
 * Handle incoming TCP connections.
 * <p>
 * This class implement a simple server model: one listener thread accepts on a socket and
 * creates a new worker thread for each incoming connection.
 * <p>
 * More advanced Endpoints will reuse the threads, use queues, etc.
 *
 * @author James Duncan Davidson
 * @author Jason Hunter
 * @author James Todd
 * @author Costin Manolache
 * @author Gal Shachor
 * @author Yoav Shapira
 * @author Remy Maucherat
 */
public class JIoEndpoint3 extends AbstractEndpoint<CoSocket> {

    //全局的事件循环线程组
    private static final CoSocketEventLoopGroup EVENT_EXECUTORS;

    static {
        Runtime runtime = Runtime.getRuntime();
        int i = runtime.availableProcessors();
        i = (int) (i * 0.75);
        if (i < 1) {
            i = 1;
        }
        Integer integer = Integer.getInteger("global.jio.event.executors.size", i);
        EVENT_EXECUTORS = new CoSocketEventLoopGroup(integer);
        //和谐关机,优雅关闭全局的event_executors
        runtime.addShutdownHook(new Thread(() -> {
            Future<?> future = EVENT_EXECUTORS.shutdownGracefully();
            try {
                future.get();
            } catch (InterruptedException e) {
                e.printStackTrace();
            } catch (ExecutionException e) {
                e.printStackTrace();
            }
        }));
    }

    // -------------------------------------------------------------- Constants

    private static final Log log = LogFactory.getLog(JIoEndpoint.class);

    // ----------------------------------------------------------------- Fields

    /**
     * Associated server socket.
     */
    private ServerSocketChannel serverSocketChannel;
    //nio ssl support
    private SSLContext sslContext;
    private String[] enabledCiphers;
    private String[] enabledProtocols;


    // ------------------------------------------------------------ Constructor

    public JIoEndpoint3() {
        // Set maxConnections to zero so we can tell if the user has specified
        // their own value on the connector when we reach bind()
        setMaxConnections(0);
        // Reduce the executor timeout for BIO as threads in keep-alive will not
        // terminate when the executor interrupts them.
        setExecutorTerminationTimeoutMillis(0);
        // If running on Java 7, the insecure DHE ciphers need to be excluded by
        // default
        if (!JreCompat.isJre8Available()) {
            setCiphers(DEFAULT_CIPHERS + ":!DHE");
        }
    }

    // ------------------------------------------------------------- Properties

    /**
     * Handling of accepted sockets.
     */
    protected Handler handler = null;

    public void setHandler(Handler handler) {
        this.handler = handler;
    }

    public Handler getHandler() {
        return handler;
    }

    /**
     * Port in use.
     */
    @Override
    public int getLocalPort() {
        ServerSocket s = serverSocketChannel.socket();
        if (s == null) {
            return -1;
        } else {
            return s.getLocalPort();
        }
    }


    @Override
    public String[] getCiphersUsed() {
        return enabledCiphers;
    }


    /*
     * Optional feature support.
     */
    @Override
    public boolean getUseSendfile() {
        return false;
    } // Not supported

    @Override
    public boolean getUseComet() {
        return false;
    } // Not supported

    @Override
    public boolean getUseCometTimeout() {
        return false;
    } // Not supported

    @Override
    public boolean getDeferAccept() {
        return false;
    } // Not supported

    @Override
    public boolean getUsePolling() {
        return false;
    } // Not supported


    // ------------------------------------------------ Handler Inner Interface

    /**
     * Bare bones interface used for socket processing. Per thread data is to be
     * stored in the ThreadWithAttributes extra folders, or alternately in
     * thread local fields.
     */
    public interface Handler extends AbstractEndpoint.Handler {
        public SocketState process(SocketWrapper<CoSocket> socket,
                                   SocketStatus status);

        public SSLImplementation getSslImplementation();

        public void beforeHandshake(SocketWrapper<CoSocket> socket);
    }


    // --------------------------------------------------- Acceptor Inner Class

    /**
     * The background thread that listens for incoming TCP/IP connections and
     * hands them off to an appropriate processor.
     */
    protected class Acceptor extends AbstractEndpoint.Acceptor {

        @Override
        public void run() {

            int errorDelay = 0;

            // Loop until we receive a shutdown command
            while (running) {

                // Loop if endpoint is paused
                while (paused && running) {
                    state = AcceptorState.PAUSED;
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                }

                if (!running) {
                    break;
                }
                state = AcceptorState.RUNNING;

                try {
                    //if we have reached max connections, wait
                    countUpOrAwaitConnection();

                    SocketChannel channel = null;
                    try {
                        // Accept the next incoming connection from the server
                        // socket
                        channel = serverSocketChannel.accept();
                    } catch (IOException ioe) {
                        countDownConnection();
                        // Introduce delay if necessary
                        errorDelay = handleExceptionWithDelay(errorDelay);
                        // re-throw
                        throw ioe;
                    }
                    // Successful accept, reset the error delay
                    errorDelay = 0;

                    // Configure the socket
                    if (running && !paused && setSocketOptions(channel)) {
                        // Hand this socket off to an appropriate processor
                        if (!processSocket(channel)) {
                            countDownConnection();
                            // Close socket right away
                            closeSocket(channel);
                        }
                    } else {
                        countDownConnection();
                        // Close socket right away
                        closeSocket(channel);
                    }
                } catch (IOException x) {
                    if (running) {
                        log.error(sm.getString("endpoint.accept.fail"), x);
                    }
                } catch (NullPointerException npe) {
                    if (running) {
                        log.error(sm.getString("endpoint.accept.fail"), npe);
                    }
                } catch (Throwable t) {
                    ExceptionUtils.handleThrowable(t);
                    log.error(sm.getString("endpoint.accept.fail"), t);
                }
            }
            state = AcceptorState.ENDED;
        }
    }


    private void closeSocket(SocketChannel socket) {
        try {
            socket.close();
        } catch (IOException e) {
            // Ignore
        }
    }


    // ------------------------------------------- SocketProcessor Inner Class


    /**
     * This class is the equivalent of the Worker, but will simply use in an
     * external Executor thread pool.
     */
    public class SocketProcessor implements Runnable {

        protected SocketWrapper<CoSocket> socket = null;
        protected SocketStatus status = null;

        public SocketProcessor(SocketWrapper<CoSocket> socket) {
            if (socket == null) throw new NullPointerException();
            this.socket = socket;
        }

        public SocketProcessor(SocketWrapper<CoSocket> socket, SocketStatus status) {
            this(socket);
            this.status = status;
        }

        @Suspendable
        @Override
        public void run() {
            boolean launch = false;
            //同步锁由这个来代替了
            socket.Synchronized();
            try {
                SocketState state = SocketState.OPEN;
                //todo 暂时不实现ssl握手
//                    handler.beforeHandshake(socket);
//                    try {
//                        //todo 第一版我们暂时不支持ssl握手,
//                        // SSL handshake
//                        serverSocketFactory.handshake(socket.getSocket());
//                    } catch (Throwable t) {
//                        ExceptionUtils.handleThrowable(t);
//                        if (log.isDebugEnabled()) {
//                            log.debug(sm.getString("endpoint.err.handshake"), t);
//                        }
//                        // Tell to close the socket
//                        state = SocketState.CLOSED;
//                    }

                if ((state != SocketState.CLOSED)) {
                    if (status == null) {
                        state = handler.process(socket, SocketStatus.OPEN_READ);
                    } else {
                        state = handler.process(socket, status);
                    }
                }
                if (state == SocketState.CLOSED) {
                    // Close socket
                    if (log.isTraceEnabled()) {
                        log.trace("Closing socket:" + socket);
                    }
                    countDownConnection();
                    try {
                        socket.getSocket().close();
                    } catch (IOException e) {
                        // Ignore
                    }
                } else if (state == SocketState.OPEN ||
                        state == SocketState.UPGRADING ||
                        state == SocketState.UPGRADED) {
                    socket.setKeptAlive(true);
                    socket.access();
                    launch = true;
                } else if (state == SocketState.LONG) {
                    socket.access();
                    waitingRequests.add(socket);
                }
            } finally {
                if (launch) {
                    try {
                        getExecutor().execute(new SocketProcessor(socket, SocketStatus.OPEN_READ));
                    } catch (RejectedExecutionException x) {
                        log.warn("Socket reprocessing request was rejected for:" + socket, x);
                        try {
                            //unable to handle connection at this time
                            handler.process(socket, SocketStatus.DISCONNECT);
                        } finally {
                            countDownConnection();
                        }


                    } catch (NullPointerException npe) {
                        if (running) {
                            log.error(sm.getString("endpoint.launch.fail"),
                                    npe);
                        }
                    } finally {
                        socket.releaseSynchronized();
                    }
                } else {
                    socket.releaseSynchronized();
                }
            }
            socket = null;
            // }
            // Finish up this request
        }

    }


    // -------------------- Public methods --------------------

    @Override
    public void bind() throws Exception {

        serverSocketChannel = ServerSocketChannel.open();
        socketProperties.setProperties(serverSocketChannel.socket());
        InetSocketAddress addr = (getAddress() != null ? new InetSocketAddress(getAddress(), getPort()) : new InetSocketAddress(getPort()));
        serverSocketChannel.socket().bind(addr, getBacklog());
        serverSocketChannel.configureBlocking(true); //mimic APR behavior
        serverSocketChannel.socket().setSoTimeout(getSocketProperties().getSoTimeout());

        acceptorThreadCount = 1;


        //fixme copy ssl from nioEndpoint
        // Initialize SSL if needed
        if (isSSLEnabled()) {
            SSLUtil sslUtil = handler.getSslImplementation().getSSLUtil(this);

            sslContext = sslUtil.createSSLContext();
            sslContext.init(wrap(sslUtil.getKeyManagers()),
                    sslUtil.getTrustManagers(), null);

            SSLSessionContext sessionContext =
                    sslContext.getServerSessionContext();
            if (sessionContext != null) {
                sslUtil.configureSessionContext(sessionContext);
            }
            // Determine which cipher suites and protocols to enable
            enabledCiphers = sslUtil.getEnableableCiphers(sslContext);
            enabledProtocols = sslUtil.getEnableableProtocols(sslContext);
        }
    }

    public KeyManager[] wrap(KeyManager[] managers) {
        if (managers == null) return null;
        KeyManager[] result = new KeyManager[managers.length];
        for (int i = 0; i < result.length; i++) {
            if (managers[i] instanceof X509KeyManager && getKeyAlias() != null) {
                String keyAlias = getKeyAlias();
                // JKS keystores always convert the alias name to lower case
                if ("jks".equalsIgnoreCase(getKeystoreType())) {
                    keyAlias = keyAlias.toLowerCase(Locale.ENGLISH);
                }
                result[i] = new NioX509KeyManager((X509KeyManager) managers[i], keyAlias);
            } else {
                result[i] = managers[i];
            }
        }
        return result;
    }

    @Override
    public void createExecutor() {
        internalExecutor = true;
        int maxThreadsWithExecutor = getMaxThreadsWithExecutor();
        int i = Runtime.getRuntime().availableProcessors();
        if (maxThreadsWithExecutor > 2*i) {
            maxThreadsWithExecutor = 2 * i;
        }
        if (maxThreadsWithExecutor < 0) {
            maxThreadsWithExecutor = i;
        }
        FiberScheduler fiberScheduler = ContainerFiberScheduler.createFiberScheduler("http-" + getLocalPort() + "fiber", maxThreadsWithExecutor);
        this.executor = new Executor() {
            @Override
            public void execute(Runnable command) {
                Fiber<Void> fiber = new Fiber<>("http--fiber", fiberScheduler, command::run);
                fiber.start();
            }
        };
    }

    @Override
    public void setExecutor(Executor executor) {
        //fixme 不支持设置Executor 目前
        return;
    }

    @Override
    public int getAcceptorThreadCount() {
        //fixme acceptor线程固定为 1 只是一个
        return 1;
    }

    @Override
    public void startInternal() throws Exception {

        if (!running) {
            running = true;
            paused = false;

            // Create worker collection
            if (getExecutor() == null) {
                createExecutor();
            }

            initializeConnectionLatch();

            startAcceptorThreads();

            // Start async timeout thread
            setAsyncTimeout(new AsyncTimeout());
            Thread timeoutThread = new Thread(getAsyncTimeout(), getName() + "-AsyncTimeout");
            timeoutThread.setPriority(threadPriority);
            timeoutThread.setDaemon(true);
            timeoutThread.start();
        }
    }

    @Override
    public void stopInternal() {
        releaseConnectionLatch();
        if (!paused) {
            pause();
        }
        if (running) {
            running = false;
            getAsyncTimeout().stop();
            unlockAccept();
        }
        shutdownExecutor();
    }

    /**
     * Deallocate APR memory pools, and close server socket.
     */
    @Override
    public void unbind() throws Exception {
        if (running) {
            stop();
        }
        if (serverSocketChannel != null) {
            try {
                serverSocketChannel.close();
            } catch (Exception e) {
                log.error(sm.getString("endpoint.err.close"), e);
            }
            serverSocketChannel = null;
        }
        handler.recycle();
    }


    @Override
    protected AbstractEndpoint.Acceptor createAcceptor() {
        return new Acceptor();
    }


    private class ReadListenEventHandler extends JioReadListenEventHandler {

        public ReadListenEventHandler(SelectionKey selectionKey, SocketChannel socketChannel, CoSocketEventLoop eventLoop) {
            super(selectionKey, socketChannel, eventLoop);
        }

        public void closeConnect(SelectionKey key, SocketChannel channel, CoSocketEventLoop coSocketEventLoop) {
            countDownConnection();
            AbstractNioChannelEventHandler.SafeCloseEventLoopNioChannel(key, coSocketEventLoop, channel);
        }

        @Override
        protected CloseEventHandler getCloseHandler() {
            closeReadTimeOutTask();
            return this::closeConnect;
        }

        @Override
        protected ReadEventHandler getReadHandler() {
            return (key, channel, coSocketEventLoop) -> {
                closeReadTimeOutTask();
                try {
                    if (!running) {
                        closeConnect(key, channel, coSocketEventLoop);
                        return;
                    }
                    int soTimeout = socketProperties.getSoTimeout();
                    CoSocket coSocket = new CoSocket(channel, new CoSocketConfig(), coSocketEventLoop);
                    coSocket.setSoTimeout(soTimeout);
                    key.attach(coSocket);
                    SocketWrapper<CoSocket> wrapper = new SocketWrapper<CoSocket>(coSocket);
                    wrapper.setKeepAliveLeft(getMaxKeepAliveRequests());
//            wrapper.setSecure(isSSLEnabled());
                    //暂时不支持https的相关操作,以后在支持,先完成核心的功能
                    wrapper.setSecure(false);
                    //关闭读监听
                    SelectionKeyUtils.removeOps(key, SelectionKey.OP_READ);
                    // During shutdown, executor may be null - avoid NPE
                    getExecutor().execute(new SocketProcessor(wrapper));
                } catch (Throwable e) {
                    closeConnect(key, channel, coSocketEventLoop);
                    if (log.isInfoEnabled()) {
                        log.error("error for readActive.",e);
                    }
                }
            };
        }
    };


    /**
     * Configure the socket.
     */
    protected boolean setSocketOptions(SocketChannel channel) {
        try {
            channel.configureBlocking(false);
            Socket sock = channel.socket();
            socketProperties.setProperties(sock);
        } catch (SocketException s) {
            //error here is common if the client has reset the connection
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("endpoint.err.unexpected"), s);
            }
            // Close the socket
            return false;
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            log.error(sm.getString("endpoint.err.unexpected"), t);
            // Close the socket
            return false;
        }
        return true;
    }


    //处理连接,然后就是等待数据可读,所以我们注册到eventLoop中,等待读事件,或者超过一定的时间,
    //这样就按照原来计划走tomcat的bio的计划,但是使用了我们的CoSocket
    protected boolean processSocket(SocketChannel socket) {
        // Process the request from this socket
        CoSocketEventLoop nextEventLoop = EVENT_EXECUTORS.nextCoSocketEventLoop();
        try {
            nextEventLoop.execute(() -> {
                try {
                    ReadListenEventHandler readListen = new ReadListenEventHandler(null, socket, nextEventLoop);
                    SelectionKey key = CoSocketRegisterUtils.register(nextEventLoop, socket, SelectionKey.OP_READ, readListen);
                    readListen.setSelectionKey(key);
                    Runnable runnable = () -> {
                        readListen.readActive();
                    };
                    int soTimeout = getSoTimeout();
                    if (soTimeout < 10) {
                        //最少延迟10毫秒了
                        soTimeout = 10;
                    }
                    ScheduledFuture<?> schedule = nextEventLoop.schedule(runnable, soTimeout, TimeUnit.MILLISECONDS);
                    readListen.readTimeOut = schedule;
                } catch (Throwable e) {
                    countDownConnection();
                    ExceptionUtils.handleThrowable(e);
                    if (log.isErrorEnabled()) {
                        log.error("error process connect,", e);
                    }
                }
            });
            return true;
        } catch (Throwable e) {
            countDownConnection();
            closeSocket(socket);
            ExceptionUtils.handleThrowable(e);
            // This means we got an OOM or similar creating a thread, or that
            // the pool and its queue are full
            log.error(sm.getString("error process connect,"), e);
            return false;
        }
    }


    @Override
    public void processSocket(SocketWrapper<CoSocket> socket,
                              SocketStatus status, boolean dispatch) {
        try {
            // Synchronisation is required here as this code may be called as a
            // result of calling AsyncContext.dispatch() from a non-container
            // thread
            //todo 替换掉socket的同步语法
//            synchronized (socket) {
            socket.Synchronized();
                if (waitingRequests.remove(socket)) {
                    SocketProcessor proc = new SocketProcessor(socket, status);
                    Executor executor = getExecutor();
                    if (dispatch && executor != null) {
                        // During shutdown, executor may be null - avoid NPE
                        if (!running) {
                            return;
                        }
                        getExecutor().execute(proc);
                    } else {
                        proc.run();
                    }
                }
        } catch (RejectedExecutionException ree) {
            log.warn(sm.getString("endpoint.executor.fail", socket), ree);
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            // This means we got an OOM or similar creating a thread, or that
            // the pool and its queue are full
            log.error(sm.getString("endpoint.process.fail"), t);
        }finally {
            //必须释放,代替同步锁的作用
            socket.releaseSynchronized();
        }
    }

    @Override
    protected Log getLog() {
        return log;
    }
}
