package org.apache.tomcat.util.net;

import io.netty.channel.nio.CoSocketEventLoop;
import io.netty.util.concurrent.ScheduledFuture;
import yy.code.io.cosocket.*;

import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

/**
 * Created by ${good-yy} on 2018/11/10.
 */
public class JioReadListenEventHandler extends NioChannelEventHandlerAdapter {

    ScheduledFuture readTimeOut;

    protected void closeReadTimeOutTask() {
        if (readTimeOut != null) {
            readTimeOut.cancel(false);
            readTimeOut = null;
        }
    }

    public JioReadListenEventHandler(SelectionKey selectionKey, SocketChannel socketChannel, CoSocketEventLoop eventLoop) {
        super(selectionKey, socketChannel, eventLoop);
    }
}
