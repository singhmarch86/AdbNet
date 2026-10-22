package io.pgredis.pubsub;

import io.pgredis.MessageListener;
import io.pgredis.Subscription;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Native PostgreSQL pub/sub using {@code LISTEN}/{@code NOTIFY}. Each
 * subscription holds a dedicated connection for its lifetime.
 */
public class PostgresPubSub implements PubSub {

    private static final Logger log = LoggerFactory.getLogger(PostgresPubSub.class);

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final ExecutorService subscribers;

    public PostgresPubSub(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "pgredis-sub");
            t.setDaemon(true);
            return t;
        };
        this.subscribers = Executors.newCachedThreadPool(tf);
    }

    @Override
    public void publish(String channel, String payload) {
        PubSub.requireValidChannel(channel);
        jdbc.queryForObject("SELECT pg_notify(?, ?)", String.class, channel, payload);
    }

    @Override
    public Subscription subscribe(String channel, MessageListener listener) {
        PubSub.requireValidChannel(channel);
        AtomicBoolean active = new AtomicBoolean(true);
        final Connection conn;
        try {
            conn = dataSource.getConnection();
            PGConnection pg = conn.unwrap(PGConnection.class);
            try (Statement st = conn.createStatement()) {
                st.execute("LISTEN \"" + channel + "\"");
            }
            subscribers.submit(() -> pump(channel, listener, active, conn, pg));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to subscribe to '" + channel + "'", e);
        }

        return new Subscription() {
            @Override
            public String channel() {
                return channel;
            }

            @Override
            public boolean isActive() {
                return active.get();
            }

            @Override
            public void close() {
                active.set(false);
                closeQuietly(conn);
            }
        };
    }

    private void pump(String channel, MessageListener listener, AtomicBoolean active,
                      Connection conn, PGConnection pg) {
        try {
            while (active.get()) {
                PGNotification[] notes = pg.getNotifications(500);
                if (notes != null) {
                    for (PGNotification n : notes) {
                        listener.onMessage(n.getName(), n.getParameter());
                    }
                }
            }
        } catch (Exception e) {
            if (active.get()) {
                log.warn("subscription to '{}' ended: {}", channel, e.getMessage());
            }
        } finally {
            closeQuietly(conn);
        }
    }

    private static void closeQuietly(Connection conn) {
        try {
            if (conn != null && !conn.isClosed()) {
                conn.close();
            }
        } catch (Exception ignore) {
            // best effort
        }
    }

    @Override
    public void close() {
        subscribers.shutdownNow();
    }
}
