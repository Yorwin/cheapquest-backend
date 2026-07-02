package com.cheapquest.backend.endpoint;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Boots the JDK {@link HttpServer} and registers the routes the
 * application exposes. The executor is a fixed thread pool so
 * concurrent requests do not share a request thread; the size
 * is a deliberate small default because every endpoint is
 * essentially I/O-bound against a single backend.
 *
 * <p>The server is returned started; the caller is responsible
 * for keeping a reference and calling {@code stop()} on shutdown.
 */
public final class HttpServerBootstrap {

    private static final Logger log = LoggerFactory.getLogger(HttpServerBootstrap.class);
    private static final int THREAD_POOL_SIZE = 4;

    private HttpServerBootstrap() {
    }

    public static HttpServer start(int port, Map<String, HttpHandler> routes) throws IOException {
        Objects.requireNonNull(routes, "routes");
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE, runnable -> {
            Thread t = new Thread(runnable, "http-handler");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);
        for (Map.Entry<String, HttpHandler> entry : routes.entrySet()) {
            server.createContext(entry.getKey(), entry.getValue());
            log.info("http_route_registered path={}", entry.getKey());
        }
        server.start();
        log.info("http_server_started port={} routes={}", port, routes.size());
        return server;
    }
}
