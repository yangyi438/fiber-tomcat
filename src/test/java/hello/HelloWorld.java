package hello;

import javax.servlet.AsyncContext;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@WebServlet(asyncSupported = true)
public  class HelloWorld extends HttpServlet {

        private static final long serialVersionUID = 1L;

        @Override
        public void doPost(HttpServletRequest req, HttpServletResponse res)
                throws IOException {
            System.out.println(Thread.currentThread());
          //  AsyncContext asyncContext = req.startAsync();
            ServletInputStream inputStream = req.getInputStream();
            ServletOutputStream outputStream = res.getOutputStream();
            int counr = 0;
            while (true) {
                if (counr++ > 100) {
                    break;
                }
                int read = inputStream.read();
                if (read != -1) {
                    outputStream.write(read);
                } else {
                    break;
                }
            }
            inputStream.close();
            outputStream.close();
        }
    }