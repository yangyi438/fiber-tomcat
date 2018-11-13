package hello;

import co.paralleluniverse.fibers.Fiber;
import co.paralleluniverse.fibers.Suspendable;
import co.paralleluniverse.strands.concurrent.Semaphore;
import io.netty.util.internal.SocketUtils;
import org.apache.catalina.Context;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import yy.code.io.cosocket.CoSocket;

import java.io.IOException;
import java.net.InetAddress;

/**
 * Created by ${good-yy} on 2018/9/27.
 */
public class Start {


    public static void main(String[] args) throws Exception {
        new Fiber<Void>(() -> {
            try {
                CoSocket coSocket = new CoSocket();
                coSocket.connect(SocketUtils.socketAddress("www.baidu.com", 80), 8000);
                System.out.println("start");
                coSocket.setSoTimeout(1000);
                System.out.println(coSocket.getRemoteSocketAddress());
                System.out.println("end");
                coSocket.readBytes();
                coSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }

        }).start();


        Semaphore semaphore = new Semaphore(10, true);
        Tomcat tomcat = new Tomcat();
        Connector connector = new Connector("org.apache.coyote.http11.Http11Protocol");
        //Connector connector = new Connector("org.apache.coyote.http11.Http11AprProtocol");
//        Connector connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
        // Listen only on localhost
        connector.setAttribute("address",
                InetAddress.getByName("localhost").getHostAddress());
        // Use random free port
        connector.setPort(8080);
        // Mainly set to reduce timeouts during async tests
        connector.setAttribute("connectionTimeout", "3000");
        tomcat.getService().addConnector(connector);
        tomcat.setConnector(connector);
        Context ctx = tomcat.addContext("", null);
        Tomcat.addServlet(ctx, "myServlet", new HelloWorld());
        ctx.addServletMappingDecoded("/", "myServlet");
        tomcat.start();
        Thread.sleep(1000000000);
    }
}
