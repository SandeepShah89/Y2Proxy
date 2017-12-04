package proxy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import sun.misc.IOUtils;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

/**
 * An implementation of the {@link HttpHandler} used to handle requests
 * accepted by {@link SSLProxy}. Instances of this class only have their handle method called.
 *
 * @author 150009974
 * @version 3.1
 */
class SSLProxyHandler implements HttpHandler {

    /**
     * Used to control the amount of redirected connections the proxy makes.
     * If the {@link SSLProxyHandler#redirectCounter} exceeds this amount,
     * the instance will no longer try to make secure connections.
     * If it goes more than twice this amount, it will stop trying all together.
     * <p>
     * The presumption is that a website returns a redirect to itself but with HTTP instead of HTTPS.
     * This constant and the respective counter prevent an infinite loop of requests.
     *
     * @see SSLProxyHandler#redirectCounter
     */
    private static final int REDIRECT_RETRIES = 10;

    /**
     * Keeps track of the total number of requests that have been received.
     */
    private static int totalRequests = 0;

    /**
     * Keeps track of the number of responses that came from the cache.
     */
    private static int cacheResponses = 0;

    /**
     * Keeps track of the number of queries sent to other servers.
     */
    private static int serverQueries = 0;

    /**
     * A set of header fields that should be copied from the server response and send to the client.
     * Some headers should not be copied from the server to the browser (YouTube has such).
     * This set is filled when the class is first loaded through a sequence of add() operations
     * in the static scope.
     *
     * @see SSLProxyHandler#copyResponseHeaders(Map, HttpExchange)
     */
    private static Set<String> headersForResponse = new HashSet<>();

    static {
        headersForResponse.add("Server");
        headersForResponse.add("Date");
        headersForResponse.add("X-Frame-Options");
        headersForResponse.add("Cache-Control");
        headersForResponse.add("Vary");
        headersForResponse.add("Content-Type");
        headersForResponse.add("Content-Encoding");
        headersForResponse.add("Content-Length");
    }

    /**
     * Prints all the headers, and their type.
     * The type says whether the headers were received from the client or the server.
     * The argument passed as headers should be a map of strings to list of strings.
     * The method constructs a string with all these values and prints it to the standard output.
     * Constructing the string first,
     * prevents from interference with other threads invoking this method.
     *
     * @param headers a map as described above
     * @param type    the type of the headers, used only for user reference
     */
    private static void printHeaders(Map<String, List<String>> headers, String type) {

        final String delim = "==============\n";
        String stringified = delim + type + " headers:::\n";

        Set<String> keys = headers.keySet();
        for (String k : keys)
            stringified += k + ":" + headers.get(k) + "\n";

        stringified += delim;

        System.out.println(stringified);

    }

    /**
     * Copies the client request headers to the server request headers.
     * With this method, any headers sent from the client are resend to the server.
     * This method is called with the request headers from a {@link HttpExchange} object and
     * a {@link URLConnection} object that is to have its request properties set.
     *
     * @param requestHeaders a map of string to a list of strings representing the request headers
     * @param connection     a connection object that is being prepared to send a request to the server
     */
    private static void copyRequestHeaders(Map<String, List<String>> requestHeaders, URLConnection connection) {

        if (SSLProxy.loggingLevel == SSLProxy.MAX)
            printHeaders(requestHeaders, "request");

        Set<String> keys = requestHeaders.keySet();
        for (String k : keys)
            connection.setRequestProperty(k, requestHeaders.get(k).toString());

    }

    /**
     * Copies the server response headers to the {@link HttpExchange} object that can transfer them
     * to the client. Not all header fields should be transferred back.
     *
     * @param serverResponseHeaders a map of string to a list of strings representing the response headers
     * @param httpExchange          the httpExchange reference that is to have its response headers set
     */
    private static void copyResponseHeaders(Map<String, List<String>> serverResponseHeaders, HttpExchange httpExchange) {

        if (SSLProxy.loggingLevel == SSLProxy.MAX)
            printHeaders(serverResponseHeaders, "response");

        Set<String> keys = serverResponseHeaders.keySet();
        for (String k : keys)
            if (headersForResponse.contains(k)) {
                String v = serverResponseHeaders.get(k).toString();
                int length = v.length();
                httpExchange.getResponseHeaders().set(k, v.substring(1, length - 1));//first and last are []
            }

    }

    /**
     * Extracts the pure request from a URI string.
     * The pure request is the whole URI without the protocol at the start.
     * The resulting string is used in caching and in connecting with both HTTP and HTTPS.
     *
     * @param requestURI a string representation of the requested URI
     * @return the pure request, without the protocol at the start
     */
    private static String takePureRequest(String requestURI) {

        if (requestURI.charAt("http".length()) == 's')
            return requestURI.substring("https://".length());

        else return requestURI.substring("http://".length());

    }

    /**
     * This field counts how many times the handler was redirected to another website.
     * I.e, how many 3xx response codes were received.
     * It is used to prevent an infinite loop of requests.
     *
     * @see SSLProxyHandler#REDIRECT_RETRIES
     */
    private int redirectCounter = 0;

    /**
     * Handles a succession of redirect codes from the server.
     * Redirect codes are handled by opening a connection
     * to the URL specified in the 'Location' header field.
     * For every redirect received, the {@link SSLProxyHandler#redirectCounter} is incremented.
     * Once its twice as large as {@link SSLProxyHandler#REDIRECT_RETRIES}, the method exists
     * and returns with whatever the last response code and connection are.
     *
     * @param connection   the {@link HttpURLConnection} reference to the connection with the server
     * @param responseCode the initially received response code
     * @param httpExchange the {@link HttpExchange} reference to the connection with the client
     * @return the new response code (or 3xx if the {@link SSLProxyHandler#redirectCounter}
     * became larger than {@link SSLProxyHandler#REDIRECT_RETRIES})
     * @throws IOException because of {@link SSLProxyHandler#makeConnectionTo(String, HttpExchange)}
     *                     or getting the user response
     */
    private int handleRedirects(HttpURLConnection connection, int responseCode, HttpExchange httpExchange) throws IOException {
        while (299 < responseCode && responseCode < 400) {
            if (redirectCounter < REDIRECT_RETRIES * 2) {

                if (SSLProxy.loggingLevel >= SSLProxy.HIGH)
                    System.out.println("Location:" + connection.getHeaderField("Location"));

                String redirectTarget = takePureRequest(connection.getHeaderField("Location"));
                connection = makeConnectionTo(redirectTarget, httpExchange);
                responseCode = connection.getResponseCode();

                if (SSLProxy.loggingLevel >= SSLProxy.HIGH)
                    System.out.println("redirect: " + connection.getURL() + "\nnew response code: " + responseCode);

                redirectCounter++;

            }
            else {
                if (SSLProxy.loggingLevel >= SSLProxy.MEDIUM) {
                    System.err.println("Too many connections failed to redirect websites.");
                    System.err.println("Client request abandoned: " + httpExchange.getRequestURI().toString());
                }
                break;
            }
        }

        return responseCode;
    }

    /**
     * Tries to open a secure connection to the requested server.
     * The method opens and returns an HttpsURLConnection to the requested server.
     * If the server does not support HTTPS, it will make a non-secure connection instead.
     * This method is called only by {@link SSLProxyHandler#handle(HttpExchange)}.
     * It is assumed that the request argument is a string representation of a pure request
     * (without http(s) at the start).
     * This method fist connects with https to the requested server,
     * if that fails, it connects with http.
     *
     * @param pureRequest  the URL as string address of the server (w/o http(s))
     * @param httpExchange the reference to the HttpExchange object passed to the caller handler method
     *                     it is used to copy the headers to the connection request properties
     * @return a connection to the requested server
     * @throws IOException multiple methods used here may throw the exception,
     *                     note that this method is called within the try-catch block of the {@link SSLProxyHandler#handle(HttpExchange)}
     * @see SSLProxyHandler#takePureRequest(String)
     */
    private HttpURLConnection makeConnectionTo(String pureRequest, HttpExchange httpExchange) throws IOException {

        if (SSLProxy.loggingLevel >= SSLProxy.HIGH)
            System.out.println("client request (w/o protocol):\n" + pureRequest);

        URL targetURL = new URL("https://" + pureRequest);
        HttpURLConnection connection = (HttpsURLConnection) targetURL.openConnection();
        copyRequestHeaders(httpExchange.getRequestHeaders(), connection);
        try {

            if (redirectCounter >= REDIRECT_RETRIES)
                throw new SSLHandshakeException("Too many redirect SSL connections have failed.");

            // Fails if target does not support httpS.
            connection.connect();

            if (SSLProxy.loggingLevel >= SSLProxy.MEDIUM)
                System.out.println("secure connection: " + targetURL);

        }
        catch (SSLException e) { // Connect to the 'original' (http) website. (insecure)

            if (SSLProxy.loggingLevel >= SSLProxy.HIGH)
                System.err.println("Secure connection failed.");

            if (SSLProxy.loggingLevel == SSLProxy.MAX)
                e.printStackTrace();

            targetURL = new URL("http://" + pureRequest);
            connection = (HttpURLConnection) targetURL.openConnection();
            copyRequestHeaders(httpExchange.getRequestHeaders(), connection);
            connection.connect();

            if (SSLProxy.loggingLevel >= SSLProxy.MEDIUM)
                System.out.println("insecure connection: " + targetURL);

        }

        return connection;

    }

    /**
     * Handles an http request sent by a client.
     * Takes out the pure request, checks if a cache is present.
     * If there is a cache, return that.
     * Otherwise, contact the real server and send the client response.
     * Than maybe cache the server response before sending it to the client.
     *
     * @param httpExchange the httpExchange object passed to this method when a thread invokes it
     * @see SSLProxyHandler#takePureRequest(String)
     * @see Cacher
     * @see SSLProxy
     */
    @Override
    public void handle(HttpExchange httpExchange) {
        redirectCounter = 0;
        totalRequests++;
        try {

            // 1. Where the real client is trying to get to (without http/https)
            String pureRequest = takePureRequest(httpExchange.getRequestURI().toString());

            // and check whether there is a cached response.
            byte[] responseToSend = new byte[1];
            Map<String, List<String>> headersToSend = new HashMap<>();
            int responseCode = Cacher.takeResponseCode(pureRequest);

            // If the response is in cache, take that data.
            if (responseCode != 0) {
                cacheResponses++;
                responseToSend = Cacher.takeResponseBody(pureRequest);
                headersToSend = Cacher.takeHeaders(pureRequest);
            }

            // Otherwise, ...
            else {
                serverQueries++;

                // 2. Connect to the real server.
                HttpURLConnection connection = makeConnectionTo(pureRequest, httpExchange);

                // 3. Check the response code.
                responseCode = connection.getResponseCode();
                if (SSLProxy.loggingLevel >= SSLProxy.HIGH)
                    if (responseCode != 200)
                        System.err.println("response code " + responseCode + " from " + connection.getURL().toString());


                if (299 < responseCode && responseCode < 400)
                    responseCode = handleRedirects(connection, responseCode, httpExchange);

                /*
                 * 4xx and 5xx response codes are ignored.
                 * If the HttpURLConnection object has such a response code,
                 * an exception is thrown when getting the InputStream.
                 */

                // 4. Save the response if the response code is 1xx or 2xx.
                if (99 < responseCode && responseCode < 300) {

                    responseToSend = IOUtils.readFully(connection.getInputStream(), connection.getContentLength(), true);
                    headersToSend = connection.getHeaderFields();

                    if (Cacher.cacheble(connection.getContentType()))
                        Cacher.holdResponse(pureRequest, responseCode, headersToSend, responseToSend);

                }

            }

            // 5. Send the response.
            copyResponseHeaders(headersToSend, httpExchange);
            httpExchange.sendResponseHeaders(responseCode, responseToSend.length);

            httpExchange.getResponseBody().write(responseToSend);
            httpExchange.getResponseBody().close();

        }
        catch (IOException e) {
            /*
             * Multiple methods from the http classes may throw an IOException.
             * Log it and move on.
             */
            if (SSLProxy.loggingLevel >= SSLProxy.HIGH)
                e.printStackTrace();

        }

        if (SSLProxy.loggingLevel >= SSLProxy.HIGH)
            if (totalRequests % 20 == 0) {
                String msg = "total_requests=" + totalRequests + "\n";
                msg += "responses_from_cache=" + cacheResponses + "\n";
                msg += "queries_sent=" + serverQueries;
                System.out.println(msg);
            }

    }

}