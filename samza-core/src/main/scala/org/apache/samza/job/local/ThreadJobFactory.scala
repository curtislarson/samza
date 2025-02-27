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

package org.apache.samza.job.local

import org.apache.samza.SamzaException
import org.apache.samza.application.ApplicationUtil
import org.apache.samza.application.descriptors.ApplicationDescriptorUtil
import org.apache.samza.config.JobConfig._
import org.apache.samza.config.{Config, JobConfig, ShellCommandConfig}
import org.apache.samza.container.{SamzaContainer, SamzaContainerListener, TaskName}
import org.apache.samza.context.{ExternalContext, JobContextImpl}
import org.apache.samza.coordinator.metadatastore.{CoordinatorStreamStore, NamespaceAwareCoordinatorStreamStore}
import org.apache.samza.coordinator.stream.messages.SetChangelogMapping
import org.apache.samza.coordinator.{JobModelManager, MetadataResourceUtil}
import org.apache.samza.execution.RemoteJobPlanner
import org.apache.samza.job.model.JobModelUtil
import org.apache.samza.job.{StreamJob, StreamJobFactory}
import org.apache.samza.metrics.{JmxServer, MetricsRegistryMap, MetricsReporter}
import org.apache.samza.runtime.ProcessorContext
import org.apache.samza.startpoint.StartpointManager
import org.apache.samza.storage.ChangelogStreamManager
import org.apache.samza.task.{TaskFactory, TaskFactoryUtil}
import org.apache.samza.util.ScalaJavaUtil.JavaOptionals
import org.apache.samza.util.{ConfigUtil, CoordinatorStreamUtil, DiagnosticsUtil, Logging}

import scala.collection.JavaConversions._
import scala.collection.mutable

/**
  * Creates a new Thread job with the given config
  */
class ThreadJobFactory extends StreamJobFactory with Logging {
  def getJob(submissionConfig: Config): StreamJob = {
    info("Creating a ThreadJob, which is only meant for debugging.")
    var config = submissionConfig

    val metricsRegistry = new MetricsRegistryMap()
    val coordinatorStreamStore: CoordinatorStreamStore = new CoordinatorStreamStore(config, metricsRegistry)
    coordinatorStreamStore.init()

    val changelogStreamManager = new ChangelogStreamManager(new NamespaceAwareCoordinatorStreamStore(coordinatorStreamStore, SetChangelogMapping.TYPE))

    val jobModelManager = JobModelManager(config, changelogStreamManager.readPartitionMapping(),
      coordinatorStreamStore, metricsRegistry)
    val jobModel = jobModelManager.jobModel

    val taskPartitionMappings: mutable.Map[TaskName, Integer] = mutable.Map[TaskName, Integer]()
    for (containerModel <- jobModel.getContainers.values) {
      for (taskModel <- containerModel.getTasks.values) {
        taskPartitionMappings.put(taskModel.getTaskName, taskModel.getChangelogPartition.getPartitionId)
      }
    }

    changelogStreamManager.writePartitionMapping(taskPartitionMappings)

    //create necessary checkpoint and changelog streams
    val metadataResourceUtil = new MetadataResourceUtil(jobModel, metricsRegistry, config)
    metadataResourceUtil.createResources()

    if (new JobConfig(config).getStartpointEnabled()) {
      // fan out the startpoints
      val startpointManager = new StartpointManager(coordinatorStreamStore)
      startpointManager.start()
      try {
        startpointManager.fanOut(JobModelUtil.getTaskToSystemStreamPartitions(jobModel))
      } finally {
        startpointManager.stop()
      }
    }

    val containerId = "0"
    var jmxServer: JmxServer = null
    if (new JobConfig(config).getJMXEnabled) {
      jmxServer = new JmxServer()
    }

    val appDesc = ApplicationDescriptorUtil.getAppDescriptor(ApplicationUtil.fromConfig(config), config)
    val taskFactory: TaskFactory[_] = TaskFactoryUtil.getTaskFactory(appDesc)

    // Give developers a nice friendly warning if they've specified task.opts and are using a threaded job.
    JavaOptionals.toRichOptional(new ShellCommandConfig(config).getTaskOpts).toOption match {
      case Some(taskOpts) => warn("%s was specified in config, but is not being used because job is being executed with ThreadJob. " +
        "You probably want to run %s=%s." format(ShellCommandConfig.TASK_JVM_OPTS, STREAM_JOB_FACTORY_CLASS,
        classOf[ProcessJobFactory].getName))
      case _ => None
    }

    val containerListener = {
      val processorLifecycleListener = appDesc.getProcessorLifecycleListenerFactory().createInstance(new ProcessorContext() {}, config)
      new SamzaContainerListener {
        override def afterFailure(t: Throwable): Unit = {
          processorLifecycleListener.afterFailure(t)
          throw t
        }

        override def afterStart(): Unit = {
          processorLifecycleListener.afterStart()
        }

        override def afterStop(): Unit = {
          processorLifecycleListener.afterStop()
        }

        override def beforeStart(): Unit = {
          processorLifecycleListener.beforeStart()
        }

      }
    }

    try {
      jobModelManager.start
      val container = SamzaContainer(
        containerId,
        jobModel,
        Map[String, MetricsReporter](),
        metricsRegistry,
        taskFactory,
        JobContextImpl.fromConfigWithDefaults(config, jobModel),
        Option(appDesc.getApplicationContainerContextFactory.orElse(null)),
        Option(appDesc.getApplicationTaskContextFactory.orElse(null)),
        buildExternalContext(config)
      )
      container.setContainerListener(containerListener)

      val threadJob = new ThreadJob(container)
      threadJob
    } finally {
      jobModelManager.stop
      if (jmxServer != null) {
        jmxServer.stop
      }
      coordinatorStreamStore.close()
    }
  }

  private def buildExternalContext(config: Config): Option[ExternalContext] = {
    /*
     * By default, use an empty ExternalContext here. In a custom fork of Samza, this can be implemented to pass
     * a non-empty ExternalContext to SamzaContainer. Only config should be used to build the external context. In the
     * future, components like the application descriptor may not be available.
     */
    None
  }
}
