package dev.unitedplaylists.provider.newpipe;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.springframework.stereotype.Component;

/**
 * The single HTTP call NewPipeExtractor needs, over Java's {@link HttpClient}.
 *
 * <p>NewPipe has no HTTP client of its own by design — the host app supplies one, so
 * it can share connection pooling, proxies, and timeouts. {@link Downloader#execute}
 * is the only abstract method; every {@code get}/{@code post} helper routes through
 * it.
 *
 * <p>A browser-like {@code User-Agent} is set by default. YouTube serves different
 * markup, and sometimes an outright block, to clients that do not look like a
 * browser, and NewPipe's parsers are written against the browser markup.
 */
@Component
public class NewPipeDownloader extends Downloader {

    /**
     * A current desktop Chrome UA. NewPipe keeps its own in step with what YouTube
     * expects; this mirrors that, and is the first thing to revisit if extraction
     * starts failing after a YouTube change.
     */
    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Safari/537.36";

    private final HttpClient client;

    public NewPipeDownloader() {
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public Response execute(Request request) throws IOException, ReCaptchaException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(request.url()))
                .timeout(Duration.ofSeconds(30));

        boolean userAgentSet = false;
        for (Map.Entry<String, List<String>> header : request.headers().entrySet()) {
            for (String value : header.getValue()) {
                // Some of these are on the JDK's restricted-header list; skip rather than
                // throw, since NewPipe does not depend on being able to set them.
                if (isRestrictedHeader(header.getKey())) {
                    continue;
                }
                builder.header(header.getKey(), value);
                if (header.getKey().equalsIgnoreCase("User-Agent")) {
                    userAgentSet = true;
                }
            }
        }
        if (!userAgentSet) {
            builder.header("User-Agent", USER_AGENT);
        }

        byte[] body = request.dataToSend();
        String method = request.httpMethod();
        builder.method(
                method,
                body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));

        try {
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());

            // A YouTube CAPTCHA wall is how a rate limit or a bot check surfaces. NewPipe
            // has a dedicated exception for it, so it can be told apart from a real error.
            if (response.statusCode() == 429) {
                throw new ReCaptchaException("YouTube asked for a CAPTCHA (rate limited)", request.url());
            }

            return new Response(
                    response.statusCode(),
                    null,
                    response.headers().map(),
                    response.body(),
                    response.uri().toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching " + request.url(), e);
        }
    }

    private boolean isRestrictedHeader(String name) {
        String lower = name.toLowerCase();
        // The JDK forbids setting these; it manages them itself.
        return lower.equals("host") || lower.equals("connection")
                || lower.equals("content-length") || lower.equals("upgrade");
    }
}
