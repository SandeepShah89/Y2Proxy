package proxy;

import com.sun.net.httpserver.HttpServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/**
 * The proxy server.
 * This class contains most of the server part
 * of the SSL proxy that we are assigned to make.
 *
 * @author 150009974
 * @version 2.4
 */
public class SSLProxy {

    /**
     * Controls how much logging should be displayed on the console.
     * Level 0 - minimum logging. Only messages that the proxy has started, and some uncontrollable errors.
     * Level 1 - medium. Also logs some error messages and connection establishing.
     * Level 2 - high. Adds in more detail to error messages, as well as some more log.
     * Level 3 - maximum. Error messages are fully detailed, also prints out communication headers.
     *
     * @see SSLProxy#MEDIUM
     * @see SSLProxy#HIGH
     * @see SSLProxy#MAX
     */
    static int loggingLevel;

    /**
     * Medium logging level.
     *
     * @see SSLProxy#loggingLevel
     */
    static final int MEDIUM = 1;

    /**
     * High logging level.
     *
     * @see SSLProxy#loggingLevel
     */
    static final int HIGH = 2;

    /**
     * Maximum logging level.
     *
     * @see SSLProxy#loggingLevel
     */
    static final int MAX = 3;

    /**
     * The minimum port number on which the proxy can listen.
     * In the computer labs, we are not allowed access to ports below 1024.
     *
     * @see SSLProxy#MAX_PORT
     */
    private static final int MIN_PORT = 1025;

    /**
     * The maximum port number on which the proxy can listen.
     * There are no ports above this.
     *
     * @see SSLProxy#MIN_PORT
     */
    private static final int MAX_PORT = 65535;

    /**
     * Reads one line from the standard input stream and parses a number from it.
     * If the input is not a number or the parsed number is negative,
     * the method prints out an error message and makes a recursive call to itself.
     * If the read fails, a default value of 8888 is used instead.
     *
     * @return the parsed non-negative number
     */
    private static int readUserInput() {

        final int DEFAULT = 8888;

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        String line;
        try {
            line = reader.readLine();
        }
        catch (IOException e) {

            if (loggingLevel >= MEDIUM)
                System.out.println("Could not read user input!");

            if (loggingLevel >= HIGH)
                e.printStackTrace();

            return DEFAULT;

        }

        try {
            int number = Integer.parseInt(line);

            if (number < 0)
                throw new NumberFormatException();

            return number;
        }
        catch (NumberFormatException e) {
            System.out.println("Please, enter a non-negative number!");
            return readUserInput();
        }

    }

    /**
     * Prompts the user and sets the logging level for this proxy start up.
     * Calls {@link SSLProxy#readUserInput()} in a loop to get
     * a non-negative number lower than {@link SSLProxy#MAX}.
     */
    private static void setLoggingLevel() {

        System.out.println("Enter logging level");
        System.out.println(0 + " = Min");
        System.out.println(MEDIUM + " = Medium");
        System.out.println(HIGH + " = High");
        System.out.println(MAX + " = Max");

        int value;
        value = readUserInput();
        while (value > MAX) {
            System.err.println("Please enter a number less then " + MAX);
            value = readUserInput();
        }

        loggingLevel = value;

    }

    /**
     * Prompts the user and sets the port on which the proxy should run.
     * Calls {@link SSLProxy#readUserInput()} in a loop to get
     * a number within {@link SSLProxy#MIN_PORT} and {@link SSLProxy#MAX_PORT}.
     *
     * @return the read port number
     */
    private static int readProxyPort() {

        System.out.println("Enter the port number for the proxy to run on:");

        int value;
        value = readUserInput();
        while (value < MIN_PORT || value > MAX_PORT) {
            System.out.println("Please, enter a port number within " + MIN_PORT + " and " + MAX_PORT);
            value = readUserInput();
        }

        return value;
    }

    /**
     * Starts the SSL proxy.
     * First sets the logging level, then the proxy port, and then starts the proxy server.
     * It then prepares the cache and
     * sets an executor with a pool of cached threads to handle requests.
     *
     * @param args command line arguments are ignored
     * @see SSLProxy#setLoggingLevel()
     * @see SSLProxy#readProxyPort()
     * @see Cacher#prepareCache()
     */
    public static void main(String[] args) {

        setLoggingLevel();

        HttpServer serverPart;
        try {
            serverPart = HttpServer.create(new InetSocketAddress("localhost", readProxyPort()), 10);
        }
        catch (IOException e) {

            System.err.println("Could not create proxy server!");

            if (loggingLevel >= MEDIUM)
                e.printStackTrace();

            return;

        }

        Cacher.prepareCache();

        // Creates a pool of threads to serve requests.
        serverPart.setExecutor(Executors.newCachedThreadPool());

        // Request with any path should be handled by the same class.
        serverPart.createContext("/", new SSLProxyHandler());

        serverPart.start();
        System.out.println("Proxy started!");

    }

}
