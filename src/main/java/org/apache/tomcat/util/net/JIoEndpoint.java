/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.tomcat.util.net;

import java.io.IOException;
import java.net.*;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.compat.JreCompat;
import org.apache.tomcat.util.net.AbstractEndpoint.Handler.SocketState;
import org.apache.tomcat.util.net.jsse.JSSESocketFactory;
import org.apache.tomcat.util.net.jsse.NioX509KeyManager;

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
public class JIoEndpoint extends AbstractEndpoint<Socket> {


    // -------------------------------------------------------------- Constants

    private static final Log log = LogFactory.getLog(JIoEndpoint.class);

    // ----------------------------------------------------------------- Fields

    /**
     * Associated server socket.
     */
    private ServerSocketChannel serverSocketChannel;
    private SSLContext sslContext;
    private String[] enabledCiphers;
    private String[] enabledProtocols;


    // ------------------------------------------------------------ Constructor

    public JIoEndpoint() {
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
        public SocketState process(SocketWrapper<Socket> socket,
                                   SocketStatus status);

        public SSLImplementation getSslImplementation();

        public void beforeHandshake(SocketWrapper<Socket> socket);
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
    protected class SocketProcessor implements Runnable {

        protected SocketWrapper<Socket> socket = null;
        protected SocketStatus status = null;

        public SocketProcessor(SocketWrapper<Socket> socket) {
            if (socket == null) throw new NullPointerException();
            this.socket = socket;
        }

        public SocketProcessor(SocketWrapper<Socket> socket, SocketStatus status) {
            this(socket);
            this.status = status;
        }

        @Override
        public void run() {
            boolean launch = false;
            //todo 去掉同步锁,
            //todo 暂时不实现ssl握手
            synchronized (socket) {
                try {
                    SocketState state = SocketState.OPEN;
                    handler.beforeHandshake(socket);
                    try {
                        //todo
                        // SSL handshake
                        serverSocketFactory.handshake(socket.getSocket());
                    } catch (Throwable t) {
                        ExceptionUtils.handleThrowable(t);
                        if (log.isDebugEnabled()) {
                            log.debug(sm.getString("endpoint.err.handshake"), t);
                        }
                        // Tell to close the socket
                        state = SocketState.CLOSED;
                    }

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
                        }
                    }
                }
            }
            socket = null;
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
                if (serverSocketChannel != null)
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




    //todo  处理连接介入
    protected boolean processSocket(SocketChannel socket) {
        // Process the request from this socket
        try {
            SocketWrapper<Socket> wrapper = new SocketWrapper<>(socket);
            wrapper.setKeepAliveLeft(getMaxKeepAliveRequests());
            wrapper.setSecure(isSSLEnabled());
            // During shutdown, executor may be null - avoid NPE
            if (!running) {
                return false;
            }
            getExecutor().execute(new SocketProcessor(wrapper));
        } catch (RejectedExecutionException x) {
            log.warn("Socket processing request was rejected for:" + socket, x);
            return false;
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            // This means we got an OOM or similar creating a thread, or that
            // the pool and its queue are full
            log.error(sm.getString("endpoint.process.fail"), t);
            return false;
        }
        return true;
    }


    @Override
    public void processSocket(SocketWrapper<Socket> socket,
                              SocketStatus status, boolean dispatch) {
        try {
            // Synchronisation is required here as this code may be called as a
            // result of calling AsyncContext.dispatch() from a non-container
            // thread
            synchronized (socket) {
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
            }
        } catch (RejectedExecutionException ree) {
            log.warn(sm.getString("endpoint.executor.fail", socket), ree);
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            // This means we got an OOM or similar creating a thread, or that
            // the pool and its queue are full
            log.error(sm.getString("endpoint.process.fail"), t);
        }
    }

    @Override
    protected Log getLog() {
        return log;
    }
}
