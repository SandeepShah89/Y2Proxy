package proxy;


import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.FileReader;
import java.io.IOException;
import java.io.FileNotFoundException;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;

/**
 * This class handles where the cache is stored and provides methods to access it.
 * Currently, the cache is stored in the file system.
 * Each time a response is stored, a folder with three files is created.
 * They contain the response body (in bytes),
 * the response headers (plain text key-value pairs), and a bit of meta data.
 * The meta data is used to determine whether or not a cache exists and if it has expired.
 * Expired caches get deleted with every proxy boot up.
 * <p>
 * The name of the folder that contains the response to a given request
 * is the hash code of the string representation of the request.
 * This way, any request can be cached uniquely and special characters escaped.
 * <p>
 * This class relies on {@link SSLProxy} to know when to store a request.
 * Storing a request overrides a previously stored one with the same hash code.
 *
 * @author 150009974
 * @version 2.3
 * @see Cacher#prepareCache()
 */
class Cacher {

    /**
     * The name of the directory where all the cache is stored.
     * All other cache directories are within this one.
     */
    private static final String CACHE_ROOT = "cache";

    /**
     * The name of the file containing meta data.
     */
    private static final String META = "meta";

    /**
     * The name of the file containing the response headers.
     */
    private static final String HEADERS = "responseHeaders";

    /**
     * The name of the file containing the response body.
     */
    private static final String BODY = "responseBody";

    /**
     * The field in HTTP response headers that says when a cache should expire.
     * It gets put in the meta data file.
     * Some HTTP packets may be missing this header.
     * In that case, an expiry is set to the current time plus one day.
     */
    private static final String EXPIRY = "Expires";

    /**
     * The delimiter used to separate the different strings that form the value to a header field.
     * In HTTP, each header is a key to a List of Strings.
     * When saving those to a file, this ATTR_DELIMITER (attribute delimiter)
     * is used to separate the strings.
     */
    private static final char ATTR_DELIMITER = ';';

    /**
     * A {@link File} reference to the cache root.
     * It is used to easily get to the cache root and create subdirectories.
     */
    private static File cachesFolder = new File(CACHE_ROOT);

    /**
     * Prepares the {@link Cacher#cachesFolder} for use.
     * If such a directory does not exist, it is created
     * (deletes any file with that name - {@link Cacher#CACHE_ROOT}).
     * If the cache root exists, it clears any expired caches.
     *
     * @see Cacher#clearOldCache()
     */
    static void prepareCache() {

        // the folder must exist AND must be a directory
        if (cachesFolder.exists() && cachesFolder.isDirectory()) {
            // If logging, it's ok.
            clearOldCache();
        }
        else { // Otherwise, overwrite any existing cache file and make a directory of it.
            cachesFolder.delete();
            cachesFolder.mkdir();
        }

    }

    /**
     * Clears any old cache. Opens all subdirectories of {@link Cacher#cachesFolder}.
     * Looks at the {@link Cacher#META} file and sees if the expiration date is older than now.
     * If so, delete the cached response - all the files within the subfolder and the subfolder itself.
     */
    private static void clearOldCache() {

        BufferedReader reader;
        Date expiryDate;
        File[] caches = cachesFolder.listFiles();

        if (SSLProxy.loggingLevel >= SSLProxy.MEDIUM)
            System.out.println("Cleaning up cache of size " + caches.length);

        for (File cachedResponse : caches) {

            File meta = new File(cachedResponse, META);
            String rawDate;
            try {
                reader = new BufferedReader(new FileReader(meta));
                reader.readLine(); // Skips over the response code

                // Read the expiration entry, parse it, retrieve the date
                rawDate = parse(reader.readLine()).get(1);

                reader.close();
            }
            catch (IOException e) {
                if (SSLProxy.loggingLevel >= SSLProxy.HIGH) {
                    System.err.println("Could not read " + cachedResponse.toString());
                    e.printStackTrace();
                }
                continue;
            }
            catch (Exception e) {
                if (SSLProxy.loggingLevel >= SSLProxy.HIGH)
                    e.printStackTrace();
                continue;
            }

            expiryDate = parseDate(rawDate);

            // If it's older than now,
            if (expiryDate.before(new Date())) {

                // delete the cache files
                File[] files = cachedResponse.listFiles();
                for (File f : files)
                    f.delete();

                // and then the directory.
                cachedResponse.delete();

            }

        }
        if (SSLProxy.loggingLevel >= SSLProxy.MEDIUM)
            System.out.println("New cache size is " + cachesFolder.listFiles().length);

    }

    /**
     * Parses a date from raw string representation.
     * It is expected that the argument is either a full date stamp,
     * or UTC time in milliseconds.
     * If all false, returns null.
     *
     * @param rawDate the raw date to be parsed
     * @return a {@link Date} object from the parsed raw date, or null if all parsing fails.
     */
    private static Date parseDate(String rawDate) {
        Date d;
        try {
            d = new Date(rawDate);
        }
        catch (IllegalArgumentException e) {
            try {
                d = new Date(Integer.parseInt(rawDate));
            }
            catch (Exception all) {
                d = null;
            }
        }

        return d;
    }

    /**
     * Parses a header, followed by attributes, from a string.
     * These are the key-value pair part of a HTTP header.
     * Returns a {@link LinkedList} where the first element is the header field (key),
     * and the rest are the List of Strings (value).
     * The expected format is "field:attr;attr;" (any positive number of arr;).
     * This method does the opposite of {@link Cacher#format(String, List)}.
     *
     * @param headerWithAttributes the string representation in the stated format.
     * @return a {@link LinkedList} with all the data
     * @see Cacher#format(String, List)
     */
    private static LinkedList<String> parse(String headerWithAttributes) {

        LinkedList<String> parsed = new LinkedList<>();
        int delimiterIndex = headerWithAttributes.indexOf(':');
        String field = headerWithAttributes.substring(0, delimiterIndex);
        parsed.add(field);

        do {
            headerWithAttributes = headerWithAttributes.substring(delimiterIndex + 1);
            delimiterIndex = headerWithAttributes.indexOf(ATTR_DELIMITER);
            String attribute = headerWithAttributes.substring(0, delimiterIndex);
            parsed.add(attribute);
        } while (delimiterIndex + 1 < headerWithAttributes.length());

        return parsed;

    }

    /**
     * Forms a string out of a header field with attributes.
     * Takes a key-value pair of String - List of Strings and saves them in one string
     * in the format "field:attr;attr;" (any positive number of arr;).
     * This method does the opposite of {@link Cacher#parse(String)}.
     *
     * @param field      the header field
     * @param attributes the List of attributes
     * @return a String with all the data
     */
    private static String format(String field, List<String> attributes) {
        String formatted = field + ":";
        for (String attr : attributes)
            formatted += attr + ATTR_DELIMITER;

        return formatted + "\n";
    }

    /**
     * Takes a response to store in cache.
     * Saves the data in separate files under a subdirectory of {@link Cacher#cachesFolder}.
     * The name of the subdirectory is the hash code of the request string.
     * The body and the headers are stored in respectively named files.
     * The code (HTTP response code) and the expiration time
     * are stored in the {@link Cacher#META} file.
     *
     * @param request the pure client request
     * @param code    the response code that was originally received from the server
     * @param headers the headers that the server originally sent
     * @param body    the body of the response
     * @see Cacher#META
     * @see Cacher#HEADERS
     * @see Cacher#BODY
     */
    static void holdResponse(String request, int code, Map<String, List<String>> headers, byte[] body) {

        File responseFolder = new File(cachesFolder, request.hashCode() + "");
        responseFolder.delete();
        responseFolder.mkdir();

        File resMetaFile = new File(responseFolder, META);
        File resHeadersFile = new File(responseFolder, HEADERS);
        File resBodyFile = new File(responseFolder, BODY);

        String expiryToSave = null;
        BufferedWriter writer;
        try {

            resHeadersFile.createNewFile();
            writer = new BufferedWriter(new FileWriter(resHeadersFile));
            Set<String> fields = headers.keySet();
            for (String f : fields)
                if (f != null) {
                    writer.write(format(f, headers.get(f)));
                    if (f.equals(EXPIRY))
                        expiryToSave = format(EXPIRY, headers.get(f));
                }
            writer.close();

            resMetaFile.createNewFile();
            writer = new BufferedWriter(new FileWriter(resMetaFile));
            writer.write(code + "\n");
            // If there was no expiry, add a custom one.
            if (expiryToSave == null) {
                long now = System.currentTimeMillis();
                // milliseconds in a second, in a minute, in an hour, in a day
                int day = 1000 * 60 * 60 * 24;
                Date tomorrow = new Date(now + day);
                List<String> expiryDate = new LinkedList<>();
                expiryDate.add(tomorrow.toString());
                writer.write(format(EXPIRY, expiryDate));
            }
            else writer.write(expiryToSave);
            writer.close();

            resBodyFile.createNewFile();
            FileOutputStream fos = new FileOutputStream(resBodyFile);
            fos.write(body);
            fos.close();

        }
        catch (IOException e) {
            if (SSLProxy.loggingLevel >= SSLProxy.HIGH) {
                System.err.println("Could not save response!");
                e.printStackTrace();
            }
        }

    }

    /**
     * Accesses the response code to a given request.
     * Returns the response code from the cache's meta data or zero.
     * This method is used to determine if a cached response exists.
     * Accessing a file under a directory of the requests hash code that does not exists would throw
     * a {@link FileNotFoundException} and that is when zero is returned.
     *
     * @param request the request to look for
     * @return the response code to that request or zero if it has not been cached
     */
    static int takeResponseCode(String request) {

        final int NO_CACHE = 0;

        File responseFolder = new File(cachesFolder, request.hashCode() + "");
        File resMetaFile = new File(responseFolder, META);
        try {
            BufferedReader reader = new BufferedReader(new FileReader(resMetaFile));
            int code = Integer.parseInt(reader.readLine());

            LinkedList<String> expiry = parse(reader.readLine());
            Date expirationDate = new Date(expiry.get(1));

            // If expiration date is before now,
            if (expirationDate.before(new Date()))
                // say that there is no such cache.
                return NO_CACHE;

            else return code;
        }
        catch (FileNotFoundException e) {
            // No cache record found.
            return NO_CACHE;
        }
        catch (IOException e) {
            if (SSLProxy.loggingLevel >= SSLProxy.HIGH) {
                System.err.println("Could not take response code!");
                e.printStackTrace();
            }
            return NO_CACHE;
        }

    }

    /**
     * Accesses the body of the response to a given request.
     * Returns a byte array containing all the bytes that were originally in the server's response.
     * It is expected that such a file exists because {@link Cacher#takeResponseCode(String)}
     * has been called first.
     *
     * @param request the request to look for
     * @return the byte array response to that request
     */
    static byte[] takeResponseBody(String request) {

        File responseFolder = new File(cachesFolder, request.hashCode() + "");
        File resBodyFile = new File(responseFolder, BODY);

        byte[] body = new byte[(int) resBodyFile.length()];

        try {
            FileInputStream bodyByteStream = new FileInputStream(resBodyFile);
            bodyByteStream.read(body);
            bodyByteStream.close();
            return body;
        }
        catch (IOException e) {
            if (SSLProxy.loggingLevel >= SSLProxy.HIGH) {
                System.err.println("Could not take response body!");
                e.printStackTrace();
            }
            return null;
        }

    }

    /**
     * Accesses the headers of the response to a given request.
     * Returns a map of String to List of Strings containing all the headers
     * that were originally in the server's response.
     * It is expected that such a file exists because {@link Cacher#takeResponseCode(String)}
     * has been called first.
     *
     * @param request the request to look for
     * @return the headers of the response to that request
     */
    static Map<String, List<String>> takeHeaders(String request) {

        File responseFolder = new File(cachesFolder, request.hashCode() + "");
        File resHeadersFile = new File(responseFolder, HEADERS);
        Map<String, List<String>> headers = new HashMap<>();

        try {
            BufferedReader reader = new BufferedReader(new FileReader(resHeadersFile));

            // For each line
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                // Parse the header and attributes.
                LinkedList<String> parsedLine = parse(line);

                // Put them in key-value pairs.
                String key = parsedLine.pollFirst();
                LinkedList<String> values = new LinkedList<>();
                while (!parsedLine.isEmpty())
                    values.add(parsedLine.pollFirst());

                headers.put(key, values);
            }
        }
        catch (IOException e) {
            if (SSLProxy.loggingLevel >= SSLProxy.HIGH) {
                System.err.println("Could not take response headers!");
                e.printStackTrace();
            }
            return null;
        }

        return headers;
    }

    /**
     * Determines whether given content should be cached.
     * Takes a string that is the content type of a HTTP response.
     * Performs multiple if-checks to determine if it is cacheble.
     * It is expected that this method is called before
     * {@link Cacher#holdResponse(String, int, Map, byte[])}
     *
     * @param contentType the string representing the content type
     * @return whether or not that type of data is cacheble
     */
    static boolean cacheble(String contentType) {

        if (contentType == null)
            return false;

        // cache images
        if (contentType.startsWith("image"))
            return true;

        // cache javascript files
        if (contentType.startsWith("text/javascript") || contentType.startsWith("application/javascript"))
            return true;
        if (contentType.startsWith("application/x-javascript"))
            return true;

        // cache css files
        if (contentType.startsWith("text/css"))
            return true;

        // cache videos
        if (contentType.startsWith("application/octet-stream"))
            return true;
        if (contentType.startsWith("video/webm"))
            return true;

        // do not cache anything else
        return false;

    }

}
