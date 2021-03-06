package com.bonnalenzo.flexiblememoization.cache.impl.ignite

import com.bonnalenzo.flexiblememoization.cache.{Cache, CacheBuilder}
import CacheMode.CacheMode
import OffHeapEviction.OffHeapEviction
import OnHeapEviction.OnHeapEviction
import org.apache.ignite.cache.eviction.fifo.FifoEvictionPolicyFactory
import org.apache.ignite.cache.eviction.lru.LruEvictionPolicyFactory
import org.apache.ignite.cache.eviction.sorted.SortedEvictionPolicyFactory
import org.apache.ignite.configuration._


/**
  * Design: Functional Builder Pattern allowing fluent customization of ignite based Cache.
  *
  * @param onHeapMaxSize         : in Bytes
  * @param offHeapMaxSize        : in Bytes
  * @param offHeapInitialSize    : in Bytes
  * @param onHeapEvictionPolicy  : Policy used to move entries from on-heap to off-heap when
  *                              on-heap reaches onHeapMaxSize.
  * @param offHeapEvictionPolicy : Policy used to evict entries from off-heap when
  *                              offHeapMaxSize reached.
  * @param cacheMode: replication behavior
  */
class IgniteCacheBuilder private(onHeapMaxSize: Option[Long],
                                 offHeapMaxSize: Option[Long],
                                 offHeapInitialSize: Option[Long],
                                 onHeapEvictionPolicy: OnHeapEviction,
                                 offHeapEvictionPolicy: OffHeapEviction,
                                 cacheMode: CacheMode) extends CacheBuilder {

  def this() = this(
    None,
    None,
    None,
    OnHeapEviction.LRU,
    OffHeapEviction.RANDOM_2_LRU,
    CacheMode.PARTITIONED
  )

  def withOnHeapMaxSize(size: Long): IgniteCacheBuilder = {
    new IgniteCacheBuilder(
      Some(size),
      offHeapMaxSize,
      offHeapInitialSize,
      onHeapEvictionPolicy,
      offHeapEvictionPolicy,
      cacheMode)
  }

  def withOffHeapMaxSize(size: Long): IgniteCacheBuilder = {
    new IgniteCacheBuilder(
      onHeapMaxSize,
      Some(size),
      offHeapInitialSize,
      onHeapEvictionPolicy,
      offHeapEvictionPolicy,
      cacheMode)
  }
  def withOffHeapInitialSize(size: Long): IgniteCacheBuilder = {
    new IgniteCacheBuilder(
      onHeapMaxSize,
      offHeapMaxSize,
      Some(size),
      onHeapEvictionPolicy,
      offHeapEvictionPolicy,
      cacheMode)
  }

  def withOnHeapEviction(eviction: OnHeapEviction): IgniteCacheBuilder = {
    new IgniteCacheBuilder(
      onHeapMaxSize,
      offHeapMaxSize,
      offHeapInitialSize,
      eviction,
      offHeapEvictionPolicy,
      cacheMode)
  }

  def withOffHeapEviction(eviction: OffHeapEviction): IgniteCacheBuilder = {
    new IgniteCacheBuilder(
      onHeapMaxSize,
      offHeapMaxSize,
      offHeapInitialSize,
      onHeapEvictionPolicy,
      eviction,
      cacheMode)
  }
  def withCacheMode(mode: CacheMode): IgniteCacheBuilder = {
    new IgniteCacheBuilder(
      onHeapMaxSize,
      offHeapMaxSize,
      offHeapInitialSize,
      onHeapEvictionPolicy,
      offHeapEvictionPolicy,
      mode)
  }

  override def build(): Cache = {

    // Creating a new data region.
    val regionCfg = new DataRegionConfiguration()
      .setName(IgniteCacheBuilder.STORAGE_REGION_NAME)

    offHeapInitialSize match {
      case Some(size) => regionCfg.setInitialSize(size) // 500 MB initial size (RAM).
      case None => ()
    }

    regionCfg.setPageEvictionMode(
      this.offHeapEvictionPolicy match {
        case OffHeapEviction.RANDOM_LRU => DataPageEvictionMode.RANDOM_2_LRU
        case OffHeapEviction.RANDOM_2_LRU => DataPageEvictionMode.RANDOM_LRU
      }
    )

    this.offHeapMaxSize match {
      case Some(size) => regionCfg.setMaxSize(size)
      case None => ()
    }

    val storageCfg = new DataStorageConfiguration().setDataRegionConfigurations(regionCfg)

    val cacheConfig: CacheConfiguration[Long, Any] = new CacheConfiguration(IgniteCacheBuilder.CACHE_NAME)
      .setOnheapCacheEnabled(true) // on heap cache backed by off-heap + eviction

    cacheMode match {
      case CacheMode.LOCAL => cacheConfig.setCacheMode(org.apache.ignite.cache.CacheMode.LOCAL)
      case CacheMode.PARTITIONED => cacheConfig.setCacheMode(org.apache.ignite.cache.CacheMode.PARTITIONED)
      case CacheMode.REPLICATED => cacheConfig.setCacheMode(org.apache.ignite.cache.CacheMode.REPLICATED)
    }

    val evictionPolicyFactory = this.onHeapEvictionPolicy match {
      case OnHeapEviction.LRU =>
        new LruEvictionPolicyFactory()
          .asInstanceOf[LruEvictionPolicyFactory[Long, Any]]
      case OnHeapEviction.FIFO =>
        new FifoEvictionPolicyFactory()
          .asInstanceOf[FifoEvictionPolicyFactory[Long, Any]]
      case OnHeapEviction.SORTED =>
        new SortedEvictionPolicyFactory()
          .asInstanceOf[SortedEvictionPolicyFactory[Long, Any]]
    }
    this.onHeapMaxSize match {
      case Some(size) => evictionPolicyFactory.setMaxMemorySize(size)
      case None => ()
    }

    cacheConfig.setEvictionPolicyFactory(evictionPolicyFactory)

    val icf: IgniteConfiguration = new IgniteConfiguration()
      .setCacheConfiguration(cacheConfig)
      // ensures 2 nodes can be started on same JVM, thus close of one node triggered by end of use of*
      // one IgniteCacheAdapter still let others have their lives
      .setDataStorageConfiguration(storageCfg)

    new IgniteCacheAdapter(icf)
  }
}

object IgniteCacheBuilder {
  val CACHE_NAME = "MemoizationCache" // always same name to have a sharing among cluster
  val STORAGE_REGION_NAME = s"MemoizationStorageRegion"
}