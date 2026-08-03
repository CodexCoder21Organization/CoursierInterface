package coursierapi

import java.io.{ByteArrayInputStream, File, FileNotFoundException, InputStream}
import java.net.{URL, URLConnection, URLStreamHandler, URLStreamHandlerFactory}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.Executors
import java.util.jar.JarOutputStream
import coursierapi.error.CoursierError
import utest._

object FreshProtocolCacheCleanupTests extends TestSuite {

  val tests = Tests {
    test("fresh protocol fetches recycle private caches and keep returned files") {
      val unique = java.util.UUID.randomUUID().toString.replace("-", "")
      val group = s"fresh.cleanup.$unique"
      val artifact = "fixture"
      val version = "1.0"
      val jarBytes = emptyJar()
      val pomBytes =
        s"""<?xml version="1.0" encoding="UTF-8"?>
           |<project xmlns="http://maven.apache.org/POM/4.0.0">
           |  <modelVersion>4.0.0</modelVersion>
           |  <groupId>$group</groupId>
           |  <artifactId>$artifact</artifactId>
           |  <version>$version</version>
           |</project>
           |""".stripMargin.getBytes(StandardCharsets.UTF_8)
      val baseCache = Files.createTempDirectory("coursier-fresh-cleanup-base-").toFile
      val before = freshCacheDirectories()

      def bytesFor(path: String): Array[Byte] = {
        val withoutChecksum =
          if (path.endsWith(".sha1")) path.stripSuffix(".sha1")
          else if (path.endsWith(".md5")) path.stripSuffix(".md5")
          else path
        val payload =
          if (withoutChecksum.endsWith(s"$artifact-$version.jar")) jarBytes
          else if (withoutChecksum.endsWith(s"$artifact-$version.pom")) pomBytes
          else throw new FileNotFoundException(s"No fixture artifact exists for $path")
        if (path.endsWith(".sha1")) hex("SHA-1", payload).getBytes(StandardCharsets.UTF_8)
        else if (path.endsWith(".md5")) hex("MD5", payload).getBytes(StandardCharsets.UTF_8)
        else payload
      }

      val handlerFactory = new URLStreamHandlerFactory {
        override def createURLStreamHandler(protocol: String): URLStreamHandler =
          if (protocol == "freshfixture") new URLStreamHandler {
            override protected def openConnection(url: URL): URLConnection =
              new URLConnection(url) {
                override def connect(): Unit = ()
                override def getInputStream: InputStream =
                  new ByteArrayInputStream(bytesFor(this.url.getPath))
                override def getContentLength: Int = bytesFor(this.url.getPath).length
              }
          }
          else null
      }

      val cache = Cache.create()
        .withLocation(baseCache)
        .withCustomHandlerFactory(handlerFactory)
        .withProtocolsServedFresh(Collections.singleton("freshfixture"))
      val repository = MavenRepository.of("freshfixture://repository")

      try {
        def fetchFixture(): File = {
          val files = Fetch.create()
            .withCache(cache)
            .withRepositories(repository)
            .addDependencies(Dependency.of(group, artifact, version).withTransitive(false))
            .fetch()
          assert(files.size() == 1)
          val returned = files.get(0)
          assert(returned.isFile)
          assert(java.util.Arrays.equals(Files.readAllBytes(returned.toPath), jarBytes))
          returned.getCanonicalFile
        }

        val sequentialFiles = (1 to 10).map(_ => fetchFixture())
        val executor = Executors.newFixedThreadPool(8)
        val concurrentFiles =
          try {
            val futures = (1 to 20).map(_ => executor.submit(() => fetchFixture()))
            futures.map(_.get())
          }
          finally executor.shutdown()
        val returnedFiles = sequentialFiles ++ concurrentFiles

        val retained = freshCacheDirectories().diff(before)
        assert(retained.isEmpty)
        assert(returnedFiles.distinct.size == 1)

        val materialized = returnedFiles.head
        Files.write(materialized.toPath, Array[Byte](1, 2, 3))
        val repaired = fetchFixture()
        assert(repaired == materialized)
        assert(java.util.Arrays.equals(Files.readAllBytes(repaired.toPath), jarBytes))

        intercept[CoursierError] {
          Fetch.create()
            .withCache(cache)
            .withRepositories(repository)
            .addDependencies(Dependency.of(group, "missing", version).withTransitive(false))
            .fetch()
        }
        val retainedAfterFailure = freshCacheDirectories().diff(before)
        assert(retainedAfterFailure.isEmpty)
      } finally {
        freshCacheDirectories().diff(before).foreach(path => deleteTree(new File(path)))
        deleteTree(baseCache)
      }
    }
  }

  private def freshCacheDirectories(): Set[String] = {
    val tmp = new File(System.getProperty("java.io.tmpdir"))
    Option(tmp.listFiles())
      .toSeq
      .flatten
      .filter(file => file.isDirectory && file.getName.startsWith("coursier-fresh-"))
      .map(_.getCanonicalPath)
      .toSet
  }

  private def emptyJar(): Array[Byte] = {
    val bytes = new java.io.ByteArrayOutputStream()
    new JarOutputStream(bytes).close()
    bytes.toByteArray
  }

  private def hex(algorithm: String, bytes: Array[Byte]): String =
    MessageDigest.getInstance(algorithm)
      .digest(bytes)
      .map(byte => f"${byte & 0xff}%02x")
      .mkString

  private def deleteTree(root: File): Unit = {
    if (!root.exists()) return
    val paths = Files.walk(root.toPath)
    try {
      paths.sorted(java.util.Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
    } finally {
      paths.close()
    }
  }
}
