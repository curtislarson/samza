/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.samza.checkpoint

import java.util
import java.util.HashMap
import java.util.concurrent.ConcurrentHashMap
import org.apache.commons.lang3.StringUtils
import org.apache.samza.SamzaException
import org.apache.samza.annotation.InterfaceStability
import org.apache.samza.checkpoint.OffsetManager.info
import org.apache.samza.config.{Config, JobConfig, StreamConfig, SystemConfig}
import org.apache.samza.container.TaskName
import org.apache.samza.elasticity.util.ElasticityUtils
import org.apache.samza.startpoint.{Startpoint, StartpointManager}
import org.apache.samza.system.SystemStreamMetadata.OffsetType
import org.apache.samza.system._
import org.apache.samza.util.Logging

import scala.collection.JavaConverters._
import scala.collection._

/**
 * OffsetSetting encapsulates a SystemStream's metadata, default offset, and
 * reset offset settings. It's just a convenience class to make OffsetManager
 * easier to work with.
 */
case class OffsetSetting(
  /**
   * The metadata for the SystemStream.
   */
  metadata: SystemStreamMetadata,

  /**
   * The default offset (oldest, newest, or upcoming) for the SystemStream.
   * This setting is used when no checkpoint is available for a SystemStream
   * if the job is starting for the first time, or the SystemStream has been
   * reset (see resetOffsets, below).
   */
  defaultOffset: OffsetType,

  /**
   * Whether the SystemStream's offset should be reset or not. Determines
   * whether an offset should be ignored at initialization time, even if a
   * checkpoint is available. This is useful for jobs that wish to restart
   * reading from a stream at a different position than where they last
   * checkpointed. If this is true, then defaultOffset will be used to find
   * the new starting position in the stream.
   */
  resetOffset: Boolean)

/**
 * OffsetManager object is a helper that does wiring to build an OffsetManager
 * from a config object.
 */
object OffsetManager extends Logging {
  def apply(
    systemStreamMetadata: Map[SystemStream, SystemStreamMetadata],
    config: Config,
    checkpointManager: CheckpointManager = null,
    startpointManager: StartpointManager = null,
    systemAdmins: SystemAdmins = SystemAdmins.empty(),
    checkpointListeners: Map[String, CheckpointListener] = Map(),
    offsetManagerMetrics: OffsetManagerMetrics = new OffsetManagerMetrics) = {
    debug("Building offset manager for %s." format systemStreamMetadata)

    val streamConfig = new StreamConfig(config)

    val offsetSettings = systemStreamMetadata
      .map {
        case (systemStream, systemStreamMetadata) =>
          // Get default offset.
          val streamDefaultOffset = streamConfig.getDefaultStreamOffset(systemStream)
          val systemDefaultOffset = new SystemConfig(config).getSystemOffsetDefault(systemStream.getSystem)
          val defaultOffsetType = if (streamDefaultOffset.isPresent) {
            OffsetType.valueOf(streamDefaultOffset.get.toUpperCase)
          } else if (systemDefaultOffset != null) {
            OffsetType.valueOf(systemDefaultOffset.toUpperCase)
          } else {
            info("No default offset for %s defined. Using upcoming." format systemStream)
            OffsetType.UPCOMING
          }
          debug("Using default offset %s for %s." format (defaultOffsetType, systemStream))

          // Get reset offset.
          val resetOffset = streamConfig.getResetOffset(systemStream)
          debug("Using reset offset %s for %s." format (resetOffset, systemStream))

          // Build OffsetSetting so we can create a map for OffsetManager.
          (systemStream, OffsetSetting(systemStreamMetadata, defaultOffsetType, resetOffset))
      }.toMap

    new OffsetManager(offsetSettings, checkpointManager, startpointManager, systemAdmins, checkpointListeners,
      offsetManagerMetrics)
  }
}

/**
 * OffsetManager does several things:
 *
 * <ul>
 * <li>Loads last checkpointed offset and startpoints for all input SystemStreamPartitions in a
 * SamzaContainer. See SEP-18 for details.</li>
 * <li>Uses last checkpointed offset or startpoint to figure out the next offset to start
 * reading from for each input SystemStreamPartition in a SamzaContainer. Startpoints have a higher precedence than
 * checkpoints.</li>
 * <li>Keep track of the last processed offset for each SystemStreamPartitions
 * in a SamzaContainer.</li>
 * <li>Checkpoints the last processed offset for each SystemStreamPartitions
 * in a SamzaContainer periodically to the CheckpointManager and deletes any associated startpoints.</li>
 * </ul>
 *
 * All partitions must be registered before start is called, and start must be
 * called before get/update/checkpoint/stop are called.
 */
class OffsetManager(

  /**
   * Offset settings for all streams that the OffsetManager is managing.
   */
  val offsetSettings: Map[SystemStream, OffsetSetting] = Map(),

  /**
   * Optional checkpoint manager for checkpointing offsets whenever
   * checkpoint is called.
   */
  val checkpointManager: CheckpointManager = null,

  /**
    * Optional startpoint manager for overridden offsets.
    */
  val startpointManager: StartpointManager = null,

  /**
   * SystemAdmins that are used to get next offsets from last checkpointed
   * offsets. Map is from system name to SystemAdmin class for the system.
   */
  val systemAdmins: SystemAdmins = SystemAdmins.empty(),

  /**
   * Map of checkpointListeners for the systems that chose to provide one.
   * OffsetManager will call the listeners on each checkpoint.
   */
  checkpointListeners: Map[String, CheckpointListener] = Map(),

  /**
   * offsetManagerMetrics for keeping track of checkpointed offsets of each SystemStreamPartition.
   */
  val offsetManagerMetrics: OffsetManagerMetrics = new OffsetManagerMetrics) extends Logging {

  /**
   * Last offsets processed for each SystemStreamPartition.
   */
  val lastProcessedOffsets = new ConcurrentHashMap[TaskName, ConcurrentHashMap[SystemStreamPartition, String]]()

  /**
   * Offsets to start reading from for each SystemStreamPartition. This
   * variable is populated after all checkpoints have been restored.
   */
  var startingOffsets = Map[TaskName, Map[SystemStreamPartition, String]]()

  /**
    * Startpoints loaded for each SystemStreamPartition.
    */
  var startpoints = Map[TaskName, Map[SystemStreamPartition, Startpoint]]()

  /**
   * The set of system stream partitions that have been registered with the
   * OffsetManager, grouped by the taskName they belong to. These are the SSPs
   * that will be tracked within the offset manager.
   */
  val systemStreamPartitions = mutable.Map[TaskName, mutable.Set[SystemStreamPartition]]()

  def register(taskName: TaskName, systemStreamPartitionsToRegister: Set[SystemStreamPartition]) {
    systemStreamPartitions.getOrElseUpdate(taskName, mutable.Set[SystemStreamPartition]()) ++= systemStreamPartitionsToRegister
    // register metrics
    systemStreamPartitions.foreach { case (taskName, ssp) => ssp.foreach (ssp => offsetManagerMetrics.addCheckpointedOffset(ssp, "")) }
  }

  def start {
    registerCheckpointManager
    loadOffsetsFromCheckpointManager
    stripResetStreams
    loadStartingOffsets
    loadStartpoints
    loadDefaults

    info("Successfully loaded last processed offsets: %s" format lastProcessedOffsets)
    info("Successfully loaded starting offsets: %s" format startingOffsets)
  }

  /**
   * Set the last processed offset for a given SystemStreamPartition.
   */
  def update(taskName: TaskName, systemStreamPartition: SystemStreamPartition, offset: String) {
    // without elasticity enabled, there is exactly one entry of an ssp in the systemStreamPartitions map for a taskName
    // with elasticity enabled, there is exactly one of the keyBuckets of an ssp that a task consumes
    // and hence exactly one entry of an ssp with keyBucket in in the systemStreamPartitions map for a taskName
    // hence from the given ssp, find its sspWithKeybucket for the task and use that for updating lastProcessedOffsets
    val sspWithKeyBucket = systemStreamPartitions.getOrElse(taskName,
      throw new SamzaException("No SSPs registered for task: " + taskName))
      .filter(ssp => ssp.getSystemStream.equals(systemStreamPartition.getSystemStream)
        && ssp.getPartition.equals(systemStreamPartition.getPartition))
      .toIterator.next()

    lastProcessedOffsets.putIfAbsent(taskName, new ConcurrentHashMap[SystemStreamPartition, String]())
    if (offset != null && !offset.equals(IncomingMessageEnvelope.END_OF_STREAM_OFFSET)) {
      lastProcessedOffsets.get(taskName).put(sspWithKeyBucket, offset)
    }
  }

  /**
   * Get the last processed offset for a SystemStreamPartition.
   */
  def getLastProcessedOffset(taskName: TaskName, systemStreamPartition: SystemStreamPartition): Option[String] = {
    Option(lastProcessedOffsets.get(taskName)).map(_.get(systemStreamPartition))
  }

  /**
   * Get the last checkpoint saved in the checkpoint manager or null if there is no recorded checkpoints for the task
   */
  def getLastTaskCheckpoint(taskName: TaskName): Checkpoint = {
    if (checkpointManager != null) {
      checkpointManager.readLastCheckpoint(taskName)
    }
    null
  }

  /**
   * Get the starting offset for a SystemStreamPartition. This is the offset
   * where a SamzaContainer begins reading from when it starts up.
   */
  def getStartingOffset(taskName: TaskName, systemStreamPartition: SystemStreamPartition) = {
    startingOffsets.get(taskName) match {
      case Some(sspToOffsets) => sspToOffsets.get(systemStreamPartition)
      case None => None
    }
  }

  def setStartingOffset(taskName: TaskName, ssp: SystemStreamPartition, offset: String): Unit = {
    startingOffsets += taskName -> (startingOffsets(taskName) + (ssp -> offset))
  }

  /**
    * Gets Startpoints that are loaded into the OffsetManager after OffsetManager#start is called.
    */
  @InterfaceStability.Unstable
  def getStartpoint(taskName: TaskName, systemStreamPartition: SystemStreamPartition): Option[Startpoint] = {
    // Startpoints already loaded when this method is available for use. Similar to getStartingOffset above.
    startpoints.get(taskName) match {
      case Some(sspToStartpoint) => sspToStartpoint.get(systemStreamPartition)
      case None => None
    }
  }

  /**
    * Sets the Startpoint into the OffsetManager. Does not write directly to the StartpointManager. This is to be
    * used by the TaskContext only for setting Startpoints during processor initialization, similar to the
    * OffsetManager#setStartingOffset method. To write Startpoints to the metadata store, use the StartpointManager.
    */
  @InterfaceStability.Unstable
  def setStartpoint(taskName: TaskName, ssp: SystemStreamPartition, startpoint: Startpoint) = {
    // Startpoints already loaded when this method is available for use. Similar to setStartingOffset above.
    startpoints += {
      startpoints.get(taskName) match {
        case Some(sspToStartpont) => taskName -> (sspToStartpont + (ssp -> startpoint))
        case None => taskName -> new ConcurrentHashMap[SystemStreamPartition, Startpoint]() { put(ssp, startpoint) }.asScala
      }
    }
  }

  /**
    * Gets a snapshot of all the current offsets for the specified task. This is useful to
    * ensure there are no concurrent updates to the offsets between when this method is
    * invoked and the corresponding call to [[OffsetManager.writeCheckpoint()]]
    */
  def getLastProcessedOffsets(taskName: TaskName): util.Map[SystemStreamPartition, String] = {
    if (checkpointManager != null || checkpointListeners.nonEmpty) {
      debug("Getting last processed offsets to checkpoint for taskName %s." format taskName)

      val taskStartingOffsets = startingOffsets.getOrElse(taskName,
        throw new SamzaException("Couldn't find starting offsets for task: " + taskName))

      val taskSSPs = systemStreamPartitions.getOrElse(taskName,
        throw new SamzaException("No SSPs registered for task: " + taskName))

      // filter the offsets in case the task model changed since the last checkpoint was written.
      val taskLastProcessedOffsets =
        lastProcessedOffsets.getOrDefault(taskName, new ConcurrentHashMap()).asScala
          .filterKeys(taskSSPs.contains)

      val modifiedTaskOffsets = getModifiedOffsets(taskStartingOffsets, taskLastProcessedOffsets)
      new util.HashMap(modifiedTaskOffsets)
    } else {
      debug("Returning empty offsets for taskName %s because no checkpoint manager/callback is defined." format taskName)
      new util.HashMap()
    }
  }

  /**
    * Call the beforeCheckpoint() method on [[CheckpointListener]] SystemConsumers to give them a chance to inspect and
    * potentially modify the offsets about to be checkpointed.
    */
  def getModifiedOffsets(taskStartingOffsets: Map[SystemStreamPartition, String],
      taskLastProcessedOffsets: Map[SystemStreamPartition, String]): HashMap[SystemStreamPartition, String] = {
    val modifiedOffsets = new HashMap[SystemStreamPartition, String](taskLastProcessedOffsets.asJava)

    taskLastProcessedOffsets
      .groupBy { case (ssp, offset) => ssp.getSystem }  // Map(systemName -> Map(ssp -> offset))
      .foreach { case (systemName, systemSSPLastProcessedOffsets) =>
      if (checkpointListeners.contains(systemName)) {
        val checkpointListener = checkpointListeners(systemName)

        // if we haven't processed any messages from the system since we started, we don't need to call
        // the checkpoint listener. also, in case of Kafka, getting the safe offset is only possible after
        // first poll() is successful. check if we need to adjust the offset for any of the ssp in the system.
        val needModifiedOffsets = systemSSPLastProcessedOffsets.exists { case (ssp, sspLastProcessedOffset) =>
          // starting offsets are checkpointed offsets + 1 (see 'loadStartingOffsets'),
          // while on container start last processed offsets are equal to checkpointed offsets.
          // compare last processed offsets with starting offsets to see if we've processed anything.
          // last processed offsets < starting offsets after container start but before we've processed anything.
          taskStartingOffsets.get(ssp) match {
            case Some(startingOffset) if startingOffset != null =>
              val systemAdmin = systemAdmins.getSystemAdmin(systemName)
              val comparisionResult = systemAdmin.offsetComparator(sspLastProcessedOffset, startingOffset)
              if (comparisionResult != null) comparisionResult < 0 else false
            case _ => false
          }
        }

        if (needModifiedOffsets) {
          val systemModifiedOffsets = checkpointListener.beforeCheckpoint(systemSSPLastProcessedOffsets.asJava)
          modifiedOffsets.putAll(systemModifiedOffsets)
        }
      }
    }

    modifiedOffsets
  }

  /**
    * Write the specified checkpoint for the given task and delete the corresponding startpoint, if available.
    */
  def writeCheckpoint(taskName: TaskName, checkpoint: Checkpoint) {
    if (checkpoint != null && (checkpointManager != null || checkpointListeners.nonEmpty)) {
      debug("Writing checkpoint for taskName: %s as: %s." format (taskName, checkpoint))

      val sspToOffsets = checkpoint.getOffsets

      if(checkpointManager != null) {
        checkpointManager.writeCheckpoint(taskName, checkpoint)

        if(sspToOffsets != null) {
          sspToOffsets.asScala.foreach {
            case (ssp, cp) => {
              val metric = offsetManagerMetrics.checkpointedOffsets.get(ssp)
              // metric will be null for changelog SSPs since they're not registered with / tracked by the
              // OffsetManager. Ignore such SSPs.
              if (metric != null) metric.set(cp)
            }
          }
        }
      }

      // Invoke checkpoint listeners only for SSPs that are registered with the OffsetManager. For example,
      // changelog SSPs are not registered but may be present in the Checkpoint if transactional state checkpointing
      // is enabled.
      val registeredSSPs = systemStreamPartitions.getOrElse(taskName, Set[SystemStreamPartition]())
      sspToOffsets.asScala
        .filterKeys(registeredSSPs.contains)
        .groupBy { case (ssp, _) => ssp.getSystem }.foreach {
          case (systemName:String, offsets: Map[SystemStreamPartition, String]) => {
            // Option is empty if there is no checkpointListener for this systemName
            checkpointListeners.get(systemName).foreach(_.onCheckpoint(offsets.asJava))
          }
        }
    }

    // delete corresponding startpoints after checkpoint is supposed to be committed
    if (startpointManager != null && startpoints.contains(taskName)) {
      info("%d startpoint(s) for taskName: %s have been committed to the checkpoint." format (startpoints.get(taskName).size, taskName.getTaskName))
      startpointManager.removeFanOutForTask(taskName)
      startpoints -= taskName

      if (startpoints.isEmpty) {
        info("All outstanding startpoints have been committed to the checkpoint.")
        startpointManager.stop
      }
    }
  }

  def stop {
    if (checkpointManager != null) {
      debug("Shutting down checkpoint manager.")

      checkpointManager.stop
    } else {
      debug("Skipping checkpoint manager shutdown because no checkpoint manager is defined.")
    }

    if (startpointManager != null) {
      debug("Shutting down startpoint manager.")

      startpointManager.stop
    } else {
      debug("Skipping startpoint manager shutdown because no startpoint manager is defined.")
    }
  }

  /**
   * Register all partitions with the CheckpointManager.
   */
  private def registerCheckpointManager {
    if (checkpointManager != null) {
      debug("Registering checkpoint manager.")
      systemStreamPartitions.keys.foreach(checkpointManager.register)
    } else {
      debug("Skipping checkpoint manager registration because no manager was defined.")
    }
  }

  /**
   * Loads last processed offsets from checkpoint manager for all registered
   * partitions.
   */
  private def loadOffsetsFromCheckpointManager {
    if (checkpointManager != null) {
      debug("Loading offsets from checkpoint manager.")

      checkpointManager.start
      val result = systemStreamPartitions
        .keys
        .flatMap(restoreOffsetsFromCheckpoint(_))
        .toMap
      result.map { case (taskName, sspToOffset) => {
          lastProcessedOffsets.put(taskName, new ConcurrentHashMap[SystemStreamPartition, String](sspToOffset.filter {
            case (systemStreamPartition, offset) =>
              val shouldKeep = offsetSettings.contains(systemStreamPartition.getSystemStream)
              if (!shouldKeep) {
                info("Ignoring previously checkpointed offset %s for %s since the offset is for a stream that is not currently an input stream." format (offset, systemStreamPartition))
              }
              info("Checkpointed offset is currently %s for %s" format (offset, systemStreamPartition))
              shouldKeep
          }.asJava))
        }
      }
    } else {
      debug("Skipping offset load from checkpoint manager because no manager was defined.")
    }
  }

  /**
   * Loads last processed offsets for a single taskName.
   */
  private def restoreOffsetsFromCheckpoint(taskName: TaskName): Map[TaskName, Map[SystemStreamPartition, String]] = {
    debug("Loading checkpoints for taskName: %s." format taskName)

    val checkpoint = checkpointManager.readLastCheckpoint(taskName)

    val checkpointMap = checkpointManager.readAllCheckpoints()
    if (!ElasticityUtils.wasElasticityEnabled(checkpointMap)) {
      if (checkpoint != null) {
        return Map(taskName -> checkpoint.getOffsets.asScala.toMap)
      } else {
        info("Did not receive a checkpoint for taskName %s. Proceeding without a checkpoint." format taskName)
        return Map(taskName -> Map())
      }
    }
    info("There was elasticity enabled in one of the previous deploys." +
      "Last processed offsets computation at container start will use elasticity checkpoints if available.")
    Map(taskName -> ElasticityUtils.computeLastProcessedOffsetsFromCheckpointMap(taskName,
      systemStreamPartitions.get(taskName).get.asJava, checkpointMap, systemAdmins).asScala)
  }

  /**
   * Removes offset settings for all SystemStreams that are to be forcibly
   * reset using resetOffsets.
   */
  private def stripResetStreams {
    val systemStreamPartitionsToReset = getSystemStreamPartitionsToReset(lastProcessedOffsets)

    systemStreamPartitionsToReset.foreach {
      case (taskName, systemStreamPartitions) => {
        systemStreamPartitions.foreach {
          systemStreamPartition =>
            {
              val offset = lastProcessedOffsets.get(taskName).get(systemStreamPartition)
              info("Got offset %s for %s, but ignoring, since stream was configured to reset offsets." format (offset, systemStreamPartition))
            }
        }
      }
    }

    lastProcessedOffsets.keys().asScala.foreach { taskName =>
      lastProcessedOffsets.get(taskName).keySet().removeAll(systemStreamPartitionsToReset(taskName).asJava)
    }
  }

  /**
   * Returns a map of all SystemStreamPartitions in lastProcessedOffsets that need to be reset
   */
  private def getSystemStreamPartitionsToReset(taskNameTosystemStreamPartitions: ConcurrentHashMap[TaskName, ConcurrentHashMap[SystemStreamPartition, String]]): Map[TaskName, Set[SystemStreamPartition]] = {
    taskNameTosystemStreamPartitions.asScala.map {
      case (taskName, sspToOffsets) => {
        taskName -> (sspToOffsets.asScala.filter {
          case (systemStreamPartition, offset) => {
            val systemStream = systemStreamPartition.getSystemStream
            offsetSettings
              .getOrElse(systemStream, throw new SamzaException("Attempting to reset a stream that doesn't have offset settings %s." format systemStream))
              .resetOffset
          }
        }.keys.toSet)
      }
    }
  }

  /**
   * Use last processed offsets to get next available offset for each
   * SystemStreamPartition, and populate startingOffsets.
   */
  private def loadStartingOffsets {
    startingOffsets = lastProcessedOffsets.asScala.map {
      case (taskName, sspToOffsets) => {
        taskName -> {
          sspToOffsets.asScala.groupBy(_._1.getSystem).flatMap {
            case (systemName, systemStreamPartitionOffsets) =>
              systemAdmins.getSystemAdmin(systemName).getOffsetsAfter(systemStreamPartitionOffsets.asJava).asScala
          }
        }
      }
    }
  }

  /**
    * Load Startpoints for each SystemStreamPartition+TaskName
    */
  private def loadStartpoints: Unit = {
    if (startpointManager != null) {
      info("Starting startpoint manager.")
      startpointManager.start

      systemStreamPartitions.foreach {
        case (taskName, systemStreamPartitionSet) => {
          Option(startpointManager.getFanOutForTask(taskName)) match {
            case Some(fanOut) => {
              val filteredFanOut = fanOut.asScala
                .filter(f => systemStreamPartitionSet.contains(f._1))
                .toMap
              if (!filteredFanOut.isEmpty) {
                startpoints += taskName -> filteredFanOut
                info("Startpoint fan out for task: %s - %s" format (taskName, filteredFanOut))
              }
            }
            case None => debug("No startpoints fanned out on taskName: %s" format taskName.getTaskName)
          }
        }
      }

      if (startpoints.isEmpty) {
        info("No startpoints to consume.")
        startpointManager.stop
      } else {
        startpoints
          .foreach(taskMap => taskMap._2
            .foreach(sspMap => info("Loaded startpoint: %s for SSP: %s and task: %s" format (sspMap._2, sspMap._1, taskMap._1))))
        resolveStartpointsToStartingOffsets
      }
    }
  }

  /**
    * Overwrite starting offsets with resolved offsets from startpoints
    */
  private def resolveStartpointsToStartingOffsets: Unit = {
    startpoints.foreach {
      case (taskName, sspToStartpoint) => {
        var resolvedOffsets: Map[SystemStreamPartition, String] = Map()
        sspToStartpoint.foreach {
          case (ssp, startpoint) => {
            try {
              val systemAdmin: SystemAdmin = systemAdmins.getSystemAdmin(ssp.getSystem)
              val resolvedOffset: String = systemAdmin.resolveStartpointToOffset(ssp, startpoint)
              if (StringUtils.isNotBlank(resolvedOffset)) {
                resolvedOffsets += ssp -> resolvedOffset
                info("Resolved the startpoint: %s of system stream partition: %s to offset: %s." format(startpoint, ssp, resolvedOffset))
              }
            } catch {
              case e: Exception => {
                error("Exception occurred when resolving startpoint: %s of system stream partition: %s to offset." format(startpoint, ssp), e)
              }
            }
          }
        }

        // copy starting offsets and overwrite with resolved offsets
        var mergedOffsets: Map[SystemStreamPartition, String] = startingOffsets.getOrElse(taskName, Map())
        resolvedOffsets.foreach {
          case (ssp, resolvedOffset) => {
            mergedOffsets += ssp -> resolvedOffset
          }
        }
        startingOffsets += taskName -> mergedOffsets
      }
    }
  }

  /**
   * Use defaultOffsets to get a next offset for every SystemStreamPartition
   * that was registered, but has no offset.
   */
  private def loadDefaults {
    val taskNameToSSPs: Map[TaskName, Set[SystemStreamPartition]] = systemStreamPartitions

    taskNameToSSPs.foreach {
      case (taskName, systemStreamPartitionsSet) => {
        systemStreamPartitionsSet.foreach { systemStreamPartition =>
          if (!startingOffsets.contains(taskName) || !startingOffsets(taskName).contains(systemStreamPartition)) {
            val systemStream = systemStreamPartition.getSystemStream
            val partition = systemStreamPartition.getPartition
            val offsetSetting = offsetSettings.getOrElse(systemStream, throw new SamzaException("Attempting to load defaults for stream %s, which has no offset settings." format systemStream))
            val systemStreamMetadata = offsetSetting.metadata
            val offsetType = offsetSetting.defaultOffset

            debug("Got default offset type %s for %s" format (offsetType, systemStreamPartition))

            val systemStreamPartitionMetadata = systemStreamMetadata
              .getSystemStreamPartitionMetadata
              .get(partition)

            if (systemStreamPartitionMetadata != null) {
              val nextOffset = {
                val requested = systemStreamPartitionMetadata.getOffset(offsetType)

                if (requested == null) {
                  warn("Requested offset type %s in %s, but the stream is empty. Defaulting to the upcoming offset." format (offsetType, systemStreamPartition))
                  systemStreamPartitionMetadata.getOffset(OffsetType.UPCOMING)
                } else requested
              }

              debug("Got next default offset %s for %s" format (nextOffset, systemStreamPartition))

              startingOffsets.get(taskName) match {
                case Some(sspToOffsets) => startingOffsets += taskName -> (sspToOffsets + (systemStreamPartition -> nextOffset))
                case None => startingOffsets += taskName -> Map(systemStreamPartition -> nextOffset)
              }

            } else {
              throw new SamzaException("No metadata available for partition %s." format systemStreamPartition)
            }
          }
        }
      }
    }
  }
}
