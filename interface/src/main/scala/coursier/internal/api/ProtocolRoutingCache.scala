package coursier.internal.api

import java.io.File

import coursier.cache.{ArtifactError, Cache}
import coursier.util.{Artifact, EitherT, Task}

import scala.concurrent.ExecutionContext

/**
 * A [[coursier.cache.Cache]] that routes each artifact to one of two delegate caches based on the
 * artifact URL's scheme.
 *
 * Artifacts whose scheme is in [[freshProtocols]] — the custom `URLStreamHandler` protocols that
 * serve MUTABLE, build-specific content (e.g. `bldbinary`, `memrepo`) — are routed to [[fresh]], a
 * cache backed by a per-fetch location, so a workspace-built artifact is never served stale and
 * concurrent builds in different workspaces never clobber each other through Coursier's global,
 * URL-keyed download cache. Every other artifact (remote, immutable Maven coordinates) is routed to
 * [[normal]], the standard shared `FileCache`, so normal caching is fully preserved.
 *
 * This implements Coursier's first-class `Cache` extension point; it changes none of Coursier's
 * resolution or caching behaviour.
 */
final class ProtocolRoutingCache(
    normal: Cache[Task],
    fresh: Cache[Task],
    freshProtocols: Set[String]
) extends Cache[Task] {

  private def schemeOf(url: String): String = {
    val i = url.indexOf("://")
    if (i >= 0) url.substring(0, i) else ""
  }

  private def pick(artifact: Artifact): Cache[Task] =
    if (freshProtocols.contains(schemeOf(artifact.url))) fresh else normal

  def fetch: Artifact => EitherT[Task, String, String] =
    artifact => pick(artifact).fetch(artifact)

  def file(artifact: Artifact): EitherT[Task, ArtifactError, File] =
    pick(artifact).file(artifact)

  def ec: ExecutionContext = normal.ec
}
