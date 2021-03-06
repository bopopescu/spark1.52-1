/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.storage

import scala.collection.Iterable
import scala.collection.generic.CanBuildFrom
import scala.concurrent.{ Await, Future }

import org.apache.spark.rpc.RpcEndpointRef
import org.apache.spark.{ Logging, SparkConf, SparkException }
import org.apache.spark.storage.BlockManagerMessages._
import org.apache.spark.util.{ ThreadUtils, RpcUtils }
/**
 * Driver上的BlockManagerMaster对存在于Executor上的BlockManager统一管理,
  * 比如Executor需要向Driver发送注册BlockManager,
  * 更新Executor上Block的最新信息,
  * 询问所需要Block目前所在位置以及当前Executor运行结束需要将此Executor移除等
 */
private[spark] class BlockManagerMaster(
  var driverEndpoint: RpcEndpointRef, //RpcEndpointRef该对象引用BlockManagerMasterEndpoint与Driver通信,
  conf: SparkConf,
  isDriver: Boolean)
    extends Logging {

  val timeout = RpcUtils.askRpcTimeout(conf)

  /**
   *  Remove a dead executor from the driver endpoint. This is only called on the driver side.
   *  删除Master上保存的execId对应的Executor上的BlockManager的信息
   */
  def removeExecutor(execId: String) {
    tell(RemoveExecutor(execId))
    logInfo("Removed " + execId + " successfully in removeExecutor")
  }

  /**
   *  Register the BlockManager's id with the driver.
   *  向BlockManagerMaster注册blockManagerId,注册信息包括blockManagerId,标识了Slave的ExecutorId,Hostname和port
   *  节点的最大可用内存
   */
  def registerBlockManager(
    blockManagerId: BlockManagerId, maxMemSize: Long, slaveEndpoint: RpcEndpointRef): Unit = {
    logInfo("Trying to register BlockManager")
    tell(RegisterBlockManager(blockManagerId, maxMemSize, slaveEndpoint))
    logInfo("Registered BlockManager")
  }
  /**
   *  更新driver端上Block的信息,返回成功或失败
   */
  def updateBlockInfo(
    blockManagerId: BlockManagerId,
    blockId: BlockId,
    storageLevel: StorageLevel,
    memSize: Long,
    diskSize: Long,
    externalBlockStoreSize: Long): Boolean = {
    val res = driverEndpoint.askWithRetry[Boolean](
      UpdateBlockInfo(blockManagerId, blockId, storageLevel,
        memSize, diskSize, externalBlockStoreSize))
    logDebug(s"Updated info of block $blockId")
    res
  }

  /**
   *  Get locations of the blockId from the driver
    *  从driver端获取blockId的位置信息,返回BlockManagerId组织的列表(包括Executor Id,Executor所在hostname和port)
   */
  def getLocations(blockId: BlockId): Seq[BlockManagerId] = {
    driverEndpoint.askWithRetry[Seq[BlockManagerId]](GetLocations(blockId))
  }

  /**
   *  Get locations of multiple blockIds from the driver
   *  从driver端获得某个Block所有的位置信息,返回BlockManagerId组织的列表(包括Executor Id,Executor所在hostname和port)
   */
  def getLocations(blockIds: Array[BlockId]): IndexedSeq[Seq[BlockManagerId]] = {
    driverEndpoint.askWithRetry[IndexedSeq[Seq[BlockManagerId]]](
      GetLocationsMultipleBlockIds(blockIds))
  }

  /**
   * Check if block manager master has a block. Note that this can be used to check for only
   * those blocks that are reported to block manager master.
    * 检查master(这里Master就是Driver)块管理器是否有块,请注意,这可以用于仅检查报告阻止管理器主机的那些块。
   */
  def contains(blockId: BlockId): Boolean = {
    !getLocations(blockId).isEmpty
  }

  /**
   *  Get ids of other nodes in the cluster from the driver
   *  从driver端获取集群中其他节点的ID
   */  
  def getPeers(blockManagerId: BlockManagerId): Seq[BlockManagerId] = {
    //getPeers获得其他相同的BlockManagerId,做Block的分布式存储副本时会用到
    driverEndpoint.askWithRetry[Seq[BlockManagerId]](GetPeers(blockManagerId))
  }
  /**根据executorId获得Executor的host和port**/
  def getRpcHostPortForExecutor(executorId: String): Option[(String, Int)] = {
    driverEndpoint.askWithRetry[Option[(String, Int)]](GetRpcHostPortForExecutor(executorId))
  }

  /**
   * Remove a block from the slaves that have it. This can only be used to remove
   * blocks that the driver knows about.
   * 根据blockId删除slaves中的块,这只能用于删除driver端存在的块
   */
  def removeBlock(blockId: BlockId) {
    driverEndpoint.askWithRetry[Boolean](RemoveBlock(blockId))
  }

  /**
   *  Remove all blocks belonging to the given RDD.
   *  根据RddId删除该Diver上和Salve节点中RDD相关联的所有块
   *  参数blocking同步执行即堵塞
   */
  def removeRdd(rddId: Int, blocking: Boolean) {
    val future = driverEndpoint.askWithRetry[Future[Seq[Int]]](RemoveRdd(rddId))
    future.onFailure {
      case e: Exception =>
        logWarning(s"Failed to remove RDD $rddId - ${e.getMessage}", e)
    }(ThreadUtils.sameThread)
    if (blocking) { //是否同步执行堵塞
      timeout.awaitResult(future)
    }
  }

  /**
   *  Remove all blocks belonging to the given shuffle.
   *  根据ShuffleId删除该salve节点上所有和该Shuffles相关关的Block
   */
  def removeShuffle(shuffleId: Int, blocking: Boolean) {
    val future = driverEndpoint.askWithRetry[Future[Seq[Boolean]]](RemoveShuffle(shuffleId))
    future.onFailure {
      case e: Exception =>
        logWarning(s"Failed to remove shuffle $shuffleId - ${e.getMessage}", e)
    }(ThreadUtils.sameThread)
    if (blocking) {
      timeout.awaitResult(future)
    }
  }

  /**
   *  Remove all blocks belonging to the given broadcast.
   *  根据broadcastId删除该广播变量相关的所有block
   */
  def removeBroadcast(broadcastId: Long, removeFromMaster: Boolean, blocking: Boolean) {
    val future = driverEndpoint.askWithRetry[Future[Seq[Int]]](
      RemoveBroadcast(broadcastId, removeFromMaster))
    future.onFailure {
      case e: Exception =>
        logWarning(s"Failed to remove broadcast $broadcastId" +
          s" with removeFromMaster = $removeFromMaster - ${e.getMessage}", e)
    }(ThreadUtils.sameThread)
    if (blocking) {
      timeout.awaitResult(future)
    }
  }

  /**
   * Return the memory status for each block manager, in the form of a map from
   * the block manager's id to two long values. The first value is the maximum
   * amount of memory allocated for the block manager, while the second is the
   * amount of remaining memory.
    * 给定块管理器的id,返回所有块管理器的内存使用状态,
    * 第一个值是分配给块管理器的最大内存量,
    * 第二个值是剩余内存量
   */
  def getMemoryStatus: Map[BlockManagerId, (Long, Long)] = {
    driverEndpoint.askWithRetry[Map[BlockManagerId, (Long, Long)]](GetMemoryStatus)
  }
  /**
   * 获得每个Executor的Storage状态,包括使用的最大的内存大小,剩余的内存大小
   */
  def getStorageStatus: Array[StorageStatus] = {
    driverEndpoint.askWithRetry[Array[StorageStatus]](GetStorageStatus)
  }

  /**
   * Return the block's status on all block managers, if any. NOTE: This is a
   * potentially expensive operation and should only be used for testing.
    *
    * 如果有,返回所有块管理器的块状态,注意：这是一个潜在的昂贵的操作,应该只用于测试
    *
   * If askSlaves is true, this invokes the master to query each block manager for the most
   * updated block statuses. This is useful when the master is not informed of the given block
   * by all block managers.
    * 如果askSlaves为true,则调用主节点查询每个块管理器以获取最新的块状态,当主节点不被所有块管理通知给定的块时,这是有用的。
   */
  def getBlockStatus(
    blockId: BlockId,
    askSlaves: Boolean = true): Map[BlockManagerId, BlockStatus] = {
    val msg = GetBlockStatus(blockId, askSlaves)
    /*
     * To avoid potential deadlocks, the use of Futures is necessary, because the master endpoint
     * should not block on waiting for a block manager, which can in turn be waiting for the
     * master endpoint for a response to a prior message.
     * 为了避免潜在的死锁,使用Futures是必要的,因为主端点不应阻塞等待块管理器,这可能会等待主端点对先前消息的响应。
     */
    val response = driverEndpoint.
      askWithRetry[Map[BlockManagerId, Future[Option[BlockStatus]]]](msg)
    val (blockManagerIds, futures) = response.unzip
    implicit val sameThread = ThreadUtils.sameThread
    val cbf =
      implicitly[CanBuildFrom[Iterable[Future[Option[BlockStatus]]], Option[BlockStatus], Iterable[Option[BlockStatus]]]]
    val blockStatus = timeout.awaitResult(
      Future.sequence[Option[BlockStatus], Iterable](futures)(cbf, ThreadUtils.sameThread))
    if (blockStatus == null) {
      throw new SparkException("BlockManager returned null for BlockStatus query: " + blockId)
    }
    blockManagerIds.zip(blockStatus).flatMap {
      case (blockManagerId, status) =>
        status.map { s => (blockManagerId, s) }
    }.toMap
  }

  /**
   * Return a list of ids of existing blocks such that the ids match the given filter. NOTE: This
   * is a potentially expensive operation and should only be used for testing.
   * 返回现有块的id列表,以使id与给定的过滤器相匹配,注意：这是一个潜在的昂贵的操作，只能用于测试。
   * If askSlaves is true, this invokes the master to query each block manager for the most
   * updated block statuses. This is useful when the master is not informed of the given block
   * by all block managers.
    * 如果askSlaves为真,则调用主节点查询每个块管理器以获取最新的块状态。 当主节点不被所有块管理员通知给定的块时，这是有用的。
   */
  def getMatchingBlockIds(
    filter: BlockId => Boolean,
    askSlaves: Boolean): Seq[BlockId] = {
    val msg = GetMatchingBlockIds(filter, askSlaves)
    val future = driverEndpoint.askWithRetry[Future[Seq[BlockId]]](msg)
    timeout.awaitResult(future)
  }

  /**
   * Find out if the executor has cached blocks. This method does not consider broadcast blocks,
   * since they are not reported the master.
   * 查找executor是否已缓存块,此方法不考虑广播块,因为他们没有报告Master
   */
  def hasCachedBlocks(executorId: String): Boolean = {
    driverEndpoint.askWithRetry[Boolean](HasCachedBlocks(executorId))
  }

  /**
   *  Stop the driver endpoint, called only on the Spark driver node
   *  停止驱动程序端点,仅在Spark驱动器节点上调用
   */
  def stop() {
    if (driverEndpoint != null && isDriver) {
      tell(StopBlockManagerMaster)
      driverEndpoint = null
      logInfo("BlockManagerMaster stopped")
    }
  }

  /**
   *  Send a one-way message to the master endpoint, to which we expect it to reply with true.
   *  发送一个单程消息到Mater终端,期望返回一个true
   */
  private def tell(message: Any) {
    if (!driverEndpoint.askWithRetry[Boolean](message)) {
      throw new SparkException("BlockManagerMasterEndpoint returned false, expected true.")
    }
  }

}

private[spark] object BlockManagerMaster {
  val DRIVER_ENDPOINT_NAME = "BlockManagerMaster"
}
