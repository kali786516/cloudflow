/*
 * Copyright (C) 2016-2021 Lightbend Inc. <https://www.lightbend.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cloudflow.operator

import java.lang.management.ManagementFactory

import akka.actor._
import skuber._
import skuber.api.Configuration
import scala.concurrent.Await
import skuber.apiextensions._
import skuber.json.format._

import scala.jdk.CollectionConverters._
import scala.concurrent._
import scala.concurrent.duration._
import skuber.apps.v1.Deployment
import cloudflow.operator.action._

object Main extends {

  def main(args: Array[String]): Unit = {
    implicit val system = ActorSystem()

    try {
      implicit val ec  = system.dispatcher
      val settings     = Settings(system)
      implicit val ctx = settings.deploymentContext

      logStartOperatorMessage(settings)

      HealthChecks.serve(settings)

      val client          = connectToKubernetes()
      val ownerReferences = getDeploymentOwnerReferences(settings, client.usingNamespace(settings.podNamespace))
      installProtocolVersion(client.usingNamespace(settings.podNamespace), ownerReferences)
      installCRD(client)

      import cloudflow.operator.action.runner._
      val runners = Map(
        AkkaRunner.Runtime  -> new AkkaRunner(ctx.akkaRunnerDefaults),
        SparkRunner.Runtime -> new SparkRunner(ctx.sparkRunnerDefaults),
        FlinkRunner.Runtime -> new FlinkRunner(ctx.flinkRunnerDefaults)
      )
      Operator.handleAppEvents(client, runners, ctx.podName, ctx.podNamespace)
      Operator.handleConfigurationUpdates(client, runners, ctx.podName)
      Operator.handleStatusUpdates(client, runners)
    } catch {
      case t: Throwable =>
        system.log.error(t, "Unexpected error starting cloudflow operator, terminating.")
        system.registerOnTermination(exitWithFailure())
        system.terminate()
    }
  }

  private def logStartOperatorMessage(settings: Settings)(implicit system: ActorSystem) =
    system.log.info(s"""
      |Started cloudflow operator ..
      |\n${box("Build Info")}
      |${formatBuildInfo}
      |\n${box("JVM Resources")}
      |${getJVMRuntimeParameters}
      |\n${box("GC Type")}
      |\n${getGCInfo}
      |\n${box("Cloudflow Context")}
      |${settings.deploymentContext.infoMessage}
      |\n${box("Deployment")}
      |${formatDeploymentInfo(settings)}
      """.stripMargin)

  private def getDeploymentOwnerReferences(settings: Settings, client: skuber.api.client.KubernetesClient)(implicit ec: ExecutionContext) =
    Await.result(client
                   .getInNamespace[Deployment](Name.ofCloudflowOperatorDeployment, settings.podNamespace)
                   .map(_.metadata.ownerReferences),
                 10 seconds)

  private def connectToKubernetes()(implicit system: ActorSystem) = {
    val conf   = Configuration.defaultK8sConfig
    val client = k8sInit(conf).usingNamespace("")
    system.log.info(s"Connected to Kubernetes cluster: ${conf.currentContext.cluster.server}")
    client
  }

  private def exitWithFailure() = System.exit(-1)

  //TODO move to helm charts and add schema.
  private def installCRD(client: skuber.api.client.KubernetesClient)(implicit ec: ExecutionContext): Unit = {
    val crdTimeout = 20.seconds
    // TODO check if version is the same, if not, also create.
    Await.result(
      client.getOption[CustomResourceDefinition](CloudflowApplication.CRD.name).flatMap { result =>
        result.fold(client.create(CloudflowApplication.CRD)) { crd =>
          if (crd.spec.version != CloudflowApplication.CRD.spec.version) {
            client.create(CloudflowApplication.CRD)
          } else {
            Future.successful(crd)
          }
        }
      },
      crdTimeout
    )
  }

  private def installProtocolVersion(client: skuber.api.client.KubernetesClient,
                                     ownerReferences: List[OwnerReference])(implicit ec: ExecutionContext): Unit = {
    val protocolVersionTimeout = 20.seconds
    Await.result(
      client.getOption[ConfigMap](Operator.ProtocolVersionConfigMapName).flatMap {
        _.fold(client.create(Operator.ProtocolVersionConfigMap(ownerReferences))) { configMap =>
          if (configMap.data.getOrElse(Operator.ProtocolVersionKey, "") != Operator.ProtocolVersion) {
            client.update(configMap.copy(data = Map(Operator.ProtocolVersionKey -> Operator.ProtocolVersion)))
          } else {
            Future.successful(configMap)
          }
        }
      },
      protocolVersionTimeout
    )
  }

  private def getGCInfo: List[(String, javax.management.ObjectName)] = {
    val gcMxBeans = ManagementFactory.getGarbageCollectorMXBeans()
    gcMxBeans.asScala.map(b => (b.getName, b.getObjectName)).toList
  }

  private def box(str: String): String =
    if ((str == null) || (str.isEmpty)) ""
    else {
      val line = s"""+${"-" * 80}+"""
      s"$line\n$str\n$line"
    }

  private def formatBuildInfo: String = {
    import BuildInfo._

    s"""
    |Name          : $name
    |Version       : $version
    |Scala Version : $scalaVersion
    |sbt Version   : $sbtVersion
    |Build Time    : $buildTime
    |Build User    : $buildUser
    """.stripMargin
  }

  private def formatDeploymentInfo(settings: Settings): String =
    s"""
    |Release version : ${settings.releaseVersion}
    """.stripMargin

  private def getJVMRuntimeParameters: String = {
    val runtime = Runtime.getRuntime
    import runtime._

    s"""
     |Available processors    : $availableProcessors
     |Free Memory in the JVM  : $freeMemory
     |Max Memory JVM can use  : $maxMemory
     |Total Memory in the JVM : $maxMemory
    """.stripMargin
  }
}
