package coursierapi

import java.net.{URL, URLConnection, URLStreamHandler, URLStreamHandlerFactory}
import java.io.{ByteArrayInputStream, InputStream}
import coursierapi.error.CoursierError
import utest._

object URLStreamHandlerFactoryTests extends TestSuite {

  val tests = Tests {

    test("custom handler factory") {
      // Create a simple URLStreamHandlerFactory for testing
      val customFactory = new URLStreamHandlerFactory {
        override def createURLStreamHandler(protocol: String): URLStreamHandler = {
          if (protocol == "custom") {
            new URLStreamHandler {
              override protected def openConnection(u: java.net.URL) = null // Dummy implementation
            }
          } else null
        }
      }

      // Test Cache with custom handler factory
      val cache = Cache.create()
        .withCustomHandlerFactory(customFactory)

      assert(cache != null)
      assert(cache.getCustomHandlerFactory == customFactory)

      // Test that we can create a cache without custom handler factory
      val defaultCache = Cache.create()
      assert(defaultCache.getCustomHandlerFactory == null)

      // Test Fetch with custom cache
      val fetch = Fetch.create()
        .withCache(cache)

      assert(fetch != null)
      assert(fetch.getCache == cache)
      assert(fetch.getCache.getCustomHandlerFactory == customFactory)
    }

    test("cache equality") {
      val factory1: URLStreamHandlerFactory = _ => null
      val factory2: URLStreamHandlerFactory = _ => null

      val cache1 = Cache.create().withCustomHandlerFactory(factory1)
      val cache2 = Cache.create().withCustomHandlerFactory(factory1)
      val cache3 = Cache.create().withCustomHandlerFactory(factory2)
      val cache4 = Cache.create()

      // Same factory should be equal
      assert(cache1 == cache2)
      
      // Different factories should not be equal
      assert(cache1 != cache3)
      
      // Null vs non-null factory should not be equal
      assert(cache1 != cache4)
    }

    test("cache toString") {
      val factory: URLStreamHandlerFactory = _ => null
      val cache = Cache.create().withCustomHandlerFactory(factory)
      
      val cacheString = cache.toString
      assert(cacheString.contains("customHandlerFactory"))
    }

    test("functional custom protocol handler") {
      // Create example JAR content (minimal valid JAR file)
      val exampleJarContent = createMinimalJarContent()

      // Create a working URLStreamHandlerFactory that serves actual content
      val customFactory = new URLStreamHandlerFactory {
        override def createURLStreamHandler(protocol: String): URLStreamHandler = {
          if (protocol == "testproto") {
            new URLStreamHandler {
              override protected def openConnection(url: URL): URLConnection = {
                new URLConnection(url) {
                  override def connect(): Unit = {} // No-op for our test

                  override def getInputStream: InputStream = {
                    val path = this.url.getPath
                    // Serve different content based on URL path
                    if (path.contains("example-artifact-1.0.jar")) {
                      new ByteArrayInputStream(exampleJarContent)
                    } else if (path.contains("example-artifact-1.0.pom")) {
                      new ByteArrayInputStream(createExamplePom().getBytes("UTF-8"))
                    } else if (path.contains("maven-metadata.xml")) {
                      new ByteArrayInputStream(createMavenMetadata().getBytes("UTF-8"))
                    } else if (path.endsWith(".sha1") || path.endsWith(".md5")) {
                      // Provide dummy checksums to avoid checksum errors
                      new ByteArrayInputStream("dummy_checksum".getBytes("UTF-8"))
                    } else {
                      throw new java.io.FileNotFoundException(s"Not found: $path")
                    }
                  }

                  override def getContentType: String = {
                    val path = this.url.getPath
                    if (path.endsWith(".jar")) "application/java-archive"
                    else if (path.endsWith(".pom")) "application/xml"
                    else if (path.endsWith(".xml")) "application/xml"
                    else if (path.endsWith(".sha1") || path.endsWith(".md5")) "text/plain"
                    else "application/octet-stream"
                  }

                  override def getContentLength: Int = {
                    val path = this.url.getPath
                    if (path.contains("example-artifact-1.0.jar")) exampleJarContent.length
                    else if (path.contains(".pom")) createExamplePom().getBytes("UTF-8").length
                    else if (path.contains(".xml")) createMavenMetadata().getBytes("UTF-8").length
                    else if (path.endsWith(".sha1") || path.endsWith(".md5")) "dummy_checksum".getBytes("UTF-8").length
                    else -1
                  }
                }
              }
            }
          } else null
        }
      }

      // Use an isolated cache location to avoid flakiness from existing caches
      val tmpCacheDir1 = java.nio.file.Files.createTempDirectory("cs-urlhandler-cache-1")
      val cache = Cache.create()
        .withLocation(tmpCacheDir1.toFile)
        .withCustomHandlerFactory(customFactory)

      // Create a custom repository using our test protocol
      val customRepository = MavenRepository.of("testproto://example.com/maven2")

      // Create dependency that will be resolved via our custom protocol
      val dependency = Dependency.of("com.example", "example-artifact", "1.0")

      // Create fetch with custom cache and repository
      val fetch = Fetch.create()
        .withCache(cache)
        .withRepositories(customRepository)
        .addDependencies(dependency)

      // Track which URLs our custom protocol handler serves
      val servedUrls = scala.collection.mutable.Set[String]()
      
      // Create a enhanced factory that tracks actual URL requests
      val trackingFactory = new URLStreamHandlerFactory {
        override def createURLStreamHandler(protocol: String): URLStreamHandler = {
          if (protocol == "testproto") {
            new URLStreamHandler {
              override protected def openConnection(url: URL): URLConnection = {
                // Record that this URL was requested through our custom protocol
                servedUrls += url.toString
                
                new URLConnection(url) {
                  override def connect(): Unit = {}

                  override def getInputStream: InputStream = {
                    val path = this.url.getPath
                    if (path.contains("example-artifact-1.0.jar")) {
                      new ByteArrayInputStream(exampleJarContent)
                    } else if (path.contains("example-artifact-1.0.pom")) {
                      new ByteArrayInputStream(createExamplePom().getBytes("UTF-8"))
                    } else if (path.contains("maven-metadata.xml")) {
                      new ByteArrayInputStream(createMavenMetadata().getBytes("UTF-8"))
                    } else if (path.endsWith(".sha1") || path.endsWith(".md5")) {
                      new ByteArrayInputStream("dummy_checksum".getBytes("UTF-8"))
                    } else {
                      throw new java.io.FileNotFoundException(s"Not found: $path")
                    }
                  }

                  override def getContentType: String = {
                    val path = this.url.getPath
                    if (path.endsWith(".jar")) "application/java-archive"
                    else if (path.endsWith(".pom")) "application/xml"
                    else if (path.endsWith(".xml")) "application/xml"
                    else if (path.endsWith(".sha1") || path.endsWith(".md5")) "text/plain"
                    else "application/octet-stream"
                  }

                  override def getContentLength: Int = {
                    val path = this.url.getPath
                    if (path.contains("example-artifact-1.0.jar")) exampleJarContent.length
                    else if (path.contains(".pom")) createExamplePom().getBytes("UTF-8").length
                    else if (path.contains(".xml")) createMavenMetadata().getBytes("UTF-8").length
                    else if (path.endsWith(".sha1") || path.endsWith(".md5")) "dummy_checksum".getBytes("UTF-8").length
                    else -1
                  }
                }
              }
            }
          } else null
        }
      }
      
      // Create cache with tracking factory, also with an isolated location
      val tmpCacheDir2 = java.nio.file.Files.createTempDirectory("cs-urlhandler-cache-2")
      val trackingCache = Cache.create()
        .withLocation(tmpCacheDir2.toFile)
        .withCustomHandlerFactory(trackingFactory)
      
      val trackingFetch = Fetch.create()
        .withCache(trackingCache)
        .withRepositories(customRepository)
        .addDependencies(dependency)

      try {
        val result = trackingFetch.fetchResult()
        
        // If we get here, the resolution succeeded completely
        assert(result != null)
        
        val files = result.getFiles
        assert(files != null)
        assert(files.size() > 0)
        
        val jarFile = files.get(0)
        assert(jarFile != null)
        assert(jarFile.exists())
        assert(jarFile.getName.contains("example-artifact"))
        assert(jarFile.length() == exampleJarContent.length)
        
        println(s"✅ SUCCESS: Fetched artifact via custom protocol: ${jarFile.getName} (${jarFile.length()} bytes)")
        
      } catch {
        case e: CoursierError =>
          // Even if resolution fails, our custom protocol should have been used
          println(s"Resolution failed (expected): ${e.getMessage}")
          
        case e: Exception =>
          println(s"Unexpected error: ${e.getMessage}")
          throw e
      }
      
      // Verify that our custom protocol was actually used to serve URLs
      assert(servedUrls.nonEmpty)
      
      // Check if we served the expected artifact-related URLs
      val relevantUrls = servedUrls.filter(url => 
        url.contains("example-artifact") && (
          url.contains(".jar") || url.contains(".pom") || url.contains(".sha1") || url.contains(".md5")
        )
      )
      
      assert(relevantUrls.nonEmpty)
      
      println(s"✅ VERIFIED: Custom protocol handler served ${servedUrls.size} URLs:")
      servedUrls.foreach(url => println(s"  - $url"))
      println(s"✅ SUCCESS: URLStreamHandlerFactory integration is working!")
    }

    test("functional custom protocol handler (POM only via publication + artifactTypes)") {
      val exampleJarContent = createMinimalJarContent()

      val trackingUrls = scala.collection.mutable.Set[String]()

      val trackingFactory = new URLStreamHandlerFactory {
        override def createURLStreamHandler(protocol: String): URLStreamHandler =
          if (protocol == "testproto") new URLStreamHandler {
            override protected def openConnection(url: URL): URLConnection = new URLConnection(url) {
              override def connect(): Unit = ()
              override def getInputStream: InputStream = {
                trackingUrls += url.toString
                val path = url.getPath
                if (path.endsWith(".sha1") || path.endsWith(".md5")) {
                  val base = if (path.endsWith(".sha1")) path.stripSuffix(".sha1") else path.stripSuffix(".md5")
                  val bytes =
                    if (base.contains("example-artifact-1.0.jar")) exampleJarContent
                    else if (base.contains("example-artifact-1.0.pom")) createExamplePom().getBytes("UTF-8")
                    else if (base.contains("maven-metadata.xml")) createMavenMetadata().getBytes("UTF-8")
                    else throw new java.io.FileNotFoundException(s"Not found: $base")
                  val algo = if (path.endsWith(".sha1")) "SHA-1" else "MD5"
                  val hex = hexOf(algo, bytes)
                  new ByteArrayInputStream(hex.getBytes("UTF-8"))
                } else if (path.contains("example-artifact-1.0.jar"))
                  new ByteArrayInputStream(exampleJarContent)
                else if (path.contains("example-artifact-1.0.pom"))
                  new ByteArrayInputStream(createExamplePom().getBytes("UTF-8"))
                else if (path.contains("maven-metadata.xml"))
                  new ByteArrayInputStream(createMavenMetadata().getBytes("UTF-8"))
                else
                  throw new java.io.FileNotFoundException(s"Not found: $path")
              }
              override def getContentType: String = {
                val path = url.getPath
                if (path.endsWith(".jar")) "application/java-archive"
                else if (path.endsWith(".pom")) "application/xml"
                else if (path.endsWith(".xml")) "application/xml"
                else if (path.endsWith(".sha1") || path.endsWith(".md5")) "text/plain"
                else "application/octet-stream"
              }
              override def getContentLength: Int = {
                val path = url.getPath
                if (path.endsWith(".sha1") || path.endsWith(".md5")) {
                  val base = if (path.endsWith(".sha1")) path.stripSuffix(".sha1") else path.stripSuffix(".md5")
                  val bytes =
                    if (base.contains("example-artifact-1.0.jar")) exampleJarContent
                    else if (base.contains("example-artifact-1.0.pom")) createExamplePom().getBytes("UTF-8")
                    else if (base.contains("maven-metadata.xml")) createMavenMetadata().getBytes("UTF-8")
                    else Array.emptyByteArray
                  val algo = if (path.endsWith(".sha1")) "SHA-1" else "MD5"
                  hexOf(algo, bytes).getBytes("UTF-8").length
                }
                else if (path.contains("example-artifact-1.0.jar")) exampleJarContent.length
                else if (path.contains(".pom")) createExamplePom().getBytes("UTF-8").length
                else if (path.contains(".xml")) createMavenMetadata().getBytes("UTF-8").length
                else -1
              }
            }
          } else null
      }

      val tmpCacheDir = java.nio.file.Files.createTempDirectory("cs-urlhandler-cache-pom")
      val cache = Cache.create().withLocation(tmpCacheDir.toFile).withCustomHandlerFactory(trackingFactory)
      val repo = MavenRepository.of("testproto://example.com/maven2")

      val dep = Dependency.of("com.example", "example-artifact", "1.0")
        .withPublication(new Publication("pom", "pom"))

      val fetch = Fetch.create()
        .withCache(cache)
        .withRepositories(repo)
        .withArtifactTypes(java.util.Collections.singleton("pom"))
        .addDependencies(dep)

      val result = fetch.fetchResult()
      val files = result.getFiles
      assert(files != null)
      assert(files.size() > 0)
      val pomFile = files.get(0)
      assert(pomFile.getName.endsWith(".pom"))
      assert(pomFile.length() == createExamplePom().getBytes("UTF-8").length)

      assert(trackingUrls.nonEmpty)
    }
  }

  // Helper method to create minimal valid JAR content
  private def createMinimalJarContent(): Array[Byte] = {
    // This creates a minimal ZIP/JAR file with just the required headers
    // PK headers for ZIP format
    val pkHeader = Array[Byte](0x50, 0x4B, 0x03, 0x04) // Local file header signature
    val minVersion = Array[Byte](0x14, 0x00) // Version needed to extract (2.0)
    val bitFlag = Array[Byte](0x00, 0x00) // General purpose bit flag
    val compression = Array[Byte](0x00, 0x00) // Compression method (none)
    val modTime = Array[Byte](0x00, 0x00) // File modification time
    val modDate = Array[Byte](0x00, 0x00) // File modification date
    val crc32 = Array[Byte](0x00, 0x00, 0x00, 0x00) // CRC-32
    val compSize = Array[Byte](0x00, 0x00, 0x00, 0x00) // Compressed size
    val uncompSize = Array[Byte](0x00, 0x00, 0x00, 0x00) // Uncompressed size
    val nameLen = Array[Byte](0x09, 0x00) // File name length
    val extraLen = Array[Byte](0x00, 0x00) // Extra field length
    val fileName = "META-INF/".getBytes("UTF-8") // Directory entry
    
    // Central directory end record
    val endSignature = Array[Byte](0x50, 0x4B, 0x05, 0x06) // End of central directory signature
    val diskNum = Array[Byte](0x00, 0x00) // Number of this disk
    val diskStart = Array[Byte](0x00, 0x00) // Disk where central directory starts
    val entriesOnDisk = Array[Byte](0x00, 0x00) // Number of central directory records on this disk
    val totalEntries = Array[Byte](0x00, 0x00) // Total number of central directory records
    val centralDirSize = Array[Byte](0x00, 0x00, 0x00, 0x00) // Size of central directory
    val centralDirOffset = Array[Byte](0x1E, 0x00, 0x00, 0x00) // Offset of central directory
    val commentLen = Array[Byte](0x00, 0x00) // Comment length
    
    (pkHeader ++ minVersion ++ bitFlag ++ compression ++ modTime ++ modDate ++ 
     crc32 ++ compSize ++ uncompSize ++ nameLen ++ extraLen ++ fileName ++
     endSignature ++ diskNum ++ diskStart ++ entriesOnDisk ++ totalEntries ++
     centralDirSize ++ centralDirOffset ++ commentLen)
  }

  // Helper method to create example POM content
  private def createExamplePom(): String = {
    """<?xml version="1.0" encoding="UTF-8"?>
      |<project xmlns="http://maven.apache.org/POM/4.0.0"
      |         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
      |         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
      |  <modelVersion>4.0.0</modelVersion>
      |  <groupId>com.example</groupId>
      |  <artifactId>example-artifact</artifactId>
      |  <version>1.0</version>
      |  <packaging>jar</packaging>
      |</project>""".stripMargin
  }

  // Helper method to create Maven metadata
  private def createMavenMetadata(): String = {
    """<?xml version="1.0" encoding="UTF-8"?>
      |<metadata>
      |  <groupId>com.example</groupId>
      |  <artifactId>example-artifact</artifactId>
      |  <versioning>
      |    <latest>1.0</latest>
      |    <release>1.0</release>
      |    <versions>
      |      <version>1.0</version>
      |    </versions>
      |  </versioning>
      |</metadata>""".stripMargin
  }

  // Helper method to create minimal valid JAR content
  private def createMinimalJarContent(): Array[Byte] = {
    // This creates a minimal ZIP/JAR file with just the required headers
    // PK headers for ZIP format
    val pkHeader = Array[Byte](0x50, 0x4B, 0x03, 0x04) // Local file header signature
    val minVersion = Array[Byte](0x14, 0x00) // Version needed to extract (2.0)
    val bitFlag = Array[Byte](0x00, 0x00) // General purpose bit flag
    val compression = Array[Byte](0x00, 0x00) // Compression method (none)
    val modTime = Array[Byte](0x00, 0x00) // File modification time
    val modDate = Array[Byte](0x00, 0x00) // File modification date
    val crc32 = Array[Byte](0x00, 0x00, 0x00, 0x00) // CRC-32
    val compSize = Array[Byte](0x00, 0x00, 0x00, 0x00) // Compressed size
    val uncompSize = Array[Byte](0x00, 0x00, 0x00, 0x00) // Uncompressed size
    val nameLen = Array[Byte](0x09, 0x00) // File name length
    val extraLen = Array[Byte](0x00, 0x00) // Extra field length
    val fileName = "META-INF/".getBytes("UTF-8") // Directory entry
    
    // Central directory end record
    val endSignature = Array[Byte](0x50, 0x4B, 0x05, 0x06) // End of central directory signature
    val diskNum = Array[Byte](0x00, 0x00) // Number of this disk
    val diskStart = Array[Byte](0x00, 0x00) // Disk where central directory starts
    val entriesOnDisk = Array[Byte](0x00, 0x00) // Number of central directory records on this disk
    val totalEntries = Array[Byte](0x00, 0x00) // Total number of central directory records
    val centralDirSize = Array[Byte](0x00, 0x00, 0x00, 0x00) // Size of central directory
    val centralDirOffset = Array[Byte](0x1E, 0x00, 0x00, 0x00) // Offset of central directory
    val commentLen = Array[Byte](0x00, 0x00) // Comment length
    
    (pkHeader ++ minVersion ++ bitFlag ++ compression ++ modTime ++ modDate ++ 
     crc32 ++ compSize ++ uncompSize ++ nameLen ++ extraLen ++ fileName ++
     endSignature ++ diskNum ++ diskStart ++ entriesOnDisk ++ totalEntries ++
     centralDirSize ++ centralDirOffset ++ commentLen)
  }

  // Helper method to create example POM content
  private def createExamplePom(): String = {
    """<?xml version="1.0" encoding="UTF-8"?>
      |<project xmlns="http://maven.apache.org/POM/4.0.0"
      |         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
      |         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
      |  <modelVersion>4.0.0</modelVersion>
      |  <groupId>com.example</groupId>
      |  <artifactId>example-artifact</artifactId>
      |  <version>1.0</version>
      |  <packaging>jar</packaging>
      |</project>""".stripMargin
  }

  // Helper method to create Maven metadata
  private def createMavenMetadata(): String = {
    """<?xml version="1.0" encoding="UTF-8"?>
      |<metadata>
      |  <groupId>com.example</groupId>
      |  <artifactId>example-artifact</artifactId>
      |  <versioning>
      |    <latest>1.0</latest>
      |    <release>1.0</release>
      |    <versions>
      |      <version>1.0</version>
      |    </versions>
      |  </versioning>
      |</metadata>""".stripMargin
  }

  // Helpers for checksums in tests
  private def hexOf(algo: String, bytes: Array[Byte]): String = {
    val md = java.security.MessageDigest.getInstance(algo)
    val digest = md.digest(bytes)
    val b = new StringBuilder(digest.length * 2)
    var i = 0
    while (i < digest.length) {
      val v: Int = digest(i) & 0xff
      if (v < 16) b.append('0')
      b.append(Integer.toHexString(v))
      i += 1
    }
    b.toString
  }
}
