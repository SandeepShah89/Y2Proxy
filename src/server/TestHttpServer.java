package server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;

/**
 * A basic http server that always returns a static page.
 * It is used to test the behaviour of the {@link proxy.SSLProxy} with simple web pages.
 * The server only supports HTTP, as we do not have access to port 443 (where SSL is).
 * It demonstrates the ability of the {@link proxy.SSLProxy} to fall back to HTTP when necessary.
 *
 * @author 150009974
 * @version 1.1
 */
public class TestHttpServer {

    /**
     * The port on which the server listens.
     */
    private static final int WELL_KNOWN_PORT = 8080;

    /**
     * The 200 OK status code, that is always returned by this server.
     * As this server always returns a static page,
     * there is no reason to return other status codes.
     */
    private static final int HTTP_STATUS_CODE_SUCCESS = 200;

    /**
     * An inner class that describes how HTTP requests are handled.
     * Only accessed by {@link TestHttpServer}.
     *
     * @author 150009974
     * @version 1.1
     */
    private static class MyHandler implements HttpHandler {

        /**
         * The static 'index' file that is always returned.
         */
        File indexFile = new File("src/server/index.html");

        /**
         * A private constructor that only the
         * {@link TestHttpServer#main(String[])}} method can access.
         */
        private MyHandler() {
        }

        /**
         * Handles all requests by transmitting the bytes
         * that make up the {@link MyHandler#indexFile}.
         * @param httpExchange
         * @throws IOException
         */
        @Override
        public void handle(HttpExchange httpExchange) throws IOException {

            // Takes a hold of the index file.
            Path p = indexFile.toPath();
            byte[] asBytes = Files.readAllBytes(p);


            // Sets the headers of the response.
            httpExchange.sendResponseHeaders(HTTP_STATUS_CODE_SUCCESS, asBytes.length);

            // Prepares and sends the body of the response.
            OutputStream os = httpExchange.getResponseBody();
            os.write(asBytes);
            os.close();

        }

    }

    /**
     * Starts the HTTP server.
     * Sets an executor with a standard size pool of cached threads,
     * so that multiple clients can be served simultaneously.
     *
     * @param args command line arguments are ignored
     */
    public static void main(String[] args) {
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", WELL_KNOWN_PORT), 0);
        }
        catch (IOException e) {
            System.out.println("Could not create server!");
            e.printStackTrace();
            return;
        }

        // A pool of threads.
        server.setExecutor(Executors.newCachedThreadPool());

        // How to react to a / request with.
        server.createContext("/", new MyHandler());//matches anything that starts with /

        server.start();
    }

}
