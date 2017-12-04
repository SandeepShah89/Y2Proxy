package client;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.util.Set;

/**
 * A basic http client that can be used to send singleton requests.
 * Reads user input from the standard input stream and sends them as http requests.
 *
 * @author 150009974
 * @version 2.0
 */
public class TestURLHttpClient {

    /**
     * The name of the file where the client stores the retrieved data.
     * The file is overwritten after every request.
     */
    private static final String CLIENT_COPY_FILENAME = "src/client/clientReceived.html";

    /**
     * Starts the HTTP client.
     * Reads input lines from the user.
     * Each line is assumed to be an URL.
     * It gets send to the {@link proxy.SSLProxy}.
     * The client can be stopped by typing 'quit'.
     * Any exceptions are logged for reference but otherwise, ignored.
     *
     * @param args command line arguments are ignored
     */
    public static void main(String[] args) {
        // The proxy to use.
        Proxy secureProxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("localhost", 8888));

        BufferedReader stdIn = new BufferedReader(new InputStreamReader(System.in));
        String input;
        try {
            input = stdIn.readLine();
        }
        catch (IOException e) {
            System.out.println("Could not read user input!");
            e.printStackTrace();
            return;
        }

        URL target;
        URLConnection connection;
        Set<String> keys;
        BufferedReader reader;
        BufferedWriter writer;

        while (!input.equals("quit")) try {

            target = new URL(input);
            connection = target.openConnection(secureProxy);

            System.out.println("connection headers:");
            keys = connection.getHeaderFields().keySet();
            for (String k : keys)
                System.out.println(connection.getHeaderFields().get(k));

            reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            writer = new BufferedWriter(new FileWriter(CLIENT_COPY_FILENAME));
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                System.out.println(line);
                writer.write(line + "\n");
            }

            writer.close();
            reader.close();

            input = stdIn.readLine();

        }
        catch (IOException e) {
            // Log exception and move on.
            e.printStackTrace();
        }

    }

}
