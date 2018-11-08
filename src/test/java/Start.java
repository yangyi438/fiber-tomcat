import hello.HelloWorld;
import org.apache.catalina.Context;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;

import java.net.InetAddress;

/**
 * Created by ${good-yy} on 2018/9/27.
 */
public class Start {

    public static void main(String[] args) throws Exception {
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
        ctx.addServletMapping("/", "myServlet");
        tomcat.start();
        Thread.sleep(1000000000);
    }
}
