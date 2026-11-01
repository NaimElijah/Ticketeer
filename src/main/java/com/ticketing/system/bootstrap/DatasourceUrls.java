package com.ticketing.system.bootstrap;

import java.util.Objects;

/**
 * Prepares a JDBC URL for a log line, a console banner or a failure report: it is shown without its
 * query string, which can carry credentials ({@code ?user=…&password=…}).
 */
public final class DatasourceUrls {

    private static final char QUERY_START = '?';

    private DatasourceUrls() {
    }

    /**
     * @param url a JDBC URL, such as {@code spring.datasource.url}
     * @return the URL up to, and not including, its first {@code ?}; the whole URL when it has none
     * @throws NullPointerException when {@code url} is null
     */
    public static String withoutQuery(String url) {
        Objects.requireNonNull(url, "url");
        int query = url.indexOf(QUERY_START);
        return query < 0 ? url : url.substring(0, query);
    }
}
