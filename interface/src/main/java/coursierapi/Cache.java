package coursierapi;

import coursier.internal.api.ApiHelper;

import java.io.File;
import java.net.URLStreamHandlerFactory;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

public final class Cache {

    private ExecutorService pool;
    private File location;
    private Logger logger;
    private URLStreamHandlerFactory customHandlerFactory;
    private java.util.Set<String> protocolsServedFresh;

    private Cache() {
        pool = ApiHelper.defaultPool();
        location = ApiHelper.defaultLocation();
        logger = null;
        customHandlerFactory = null;
        protocolsServedFresh = java.util.Collections.emptySet();
    }

    public static Cache create() {
        return new Cache();
    }

    public File get(Artifact artifact) {
        return ApiHelper.cacheGet(this, artifact);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Cache) {
            Cache other = (Cache) obj;
            return this.pool.equals(other.pool) &&
                    this.location.equals(other.location) &&
                    Objects.equals(this.logger, other.logger) &&
                    Objects.equals(this.customHandlerFactory, other.customHandlerFactory) &&
                    this.protocolsServedFresh.equals(other.protocolsServedFresh);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return 37 * (37 * (37 * (37 * (17 + pool.hashCode()) + location.hashCode()) + Objects.hashCode(logger)) + Objects.hashCode(customHandlerFactory)) + protocolsServedFresh.hashCode();
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder("Cache(pool=");
        b.append(pool.toString());
        b.append(", location=");
        b.append(location.toString());
        if (logger != null) {
            b.append(", logger=");
            b.append(logger.toString());
        }
        if (customHandlerFactory != null) {
            b.append(", customHandlerFactory=");
            b.append(customHandlerFactory.toString());
        }
        b.append(")");
        return b.toString();
    }

    public Cache withPool(ExecutorService pool) {
        this.pool = pool;
        return this;
    }

    public Cache withLocation(File location) {
        this.location = location;
        return this;
    }

    public Cache withLogger(Logger logger) {
        this.logger = logger;
        return this;
    }

    public Cache withCustomHandlerFactory(URLStreamHandlerFactory customHandlerFactory) {
        this.customHandlerFactory = customHandlerFactory;
        return this;
    }

    /**
     * Artifacts whose URL scheme is in {@code protocols} are served "fresh" — resolved through a
     * per-fetch cache location instead of the shared global cache. This is for custom
     * URLStreamHandler protocols (e.g. {@code "bldbinary"}, {@code "memrepo"}) that serve MUTABLE,
     * build-specific content: it prevents Coursier's URL-keyed cache from serving a stale
     * workspace-built artifact and stops concurrent builds in different workspaces from clobbering
     * each other. Remote (immutable) Maven artifacts are unaffected and keep using the shared cache.
     */
    public Cache withProtocolsServedFresh(java.util.Set<String> protocols) {
        this.protocolsServedFresh = new java.util.HashSet<>(protocols);
        return this;
    }

    public ExecutorService getPool() {
        return pool;
    }

    public File getLocation() {
        return location;
    }

    public Logger getLogger() {
        return logger;
    }

    public URLStreamHandlerFactory getCustomHandlerFactory() {
        return customHandlerFactory;
    }

    public java.util.Set<String> getProtocolsServedFresh() {
        return protocolsServedFresh;
    }
}
