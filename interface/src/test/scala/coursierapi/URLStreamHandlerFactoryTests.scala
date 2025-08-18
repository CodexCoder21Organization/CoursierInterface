package coursierapi

import java.net.{URLStreamHandler, URLStreamHandlerFactory}
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
  }
}