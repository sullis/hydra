/*
 * Copyright (C) 2016 Pluralsight, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package hydra.kafka.producer

import akka.actor.Actor
import akka.util.Timeout
import configs.syntax._
import hydra.common.config.ConfigSupport
import hydra.core.ingest.HydraRequest
import hydra.core.protocol._
import hydra.core.transport.AckStrategy
import hydra.core.transport.AckStrategy.Explicit

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

/**
  * Mix this trait in to get a KafkaProducerActor automatically looked up.
  *
  * Created by alexsilva on 12/29/15.
  */
trait KafkaProducerSupport extends ConfigSupport {
  this: Actor =>

  private val resolveTimeout = applicationConfig.get[FiniteDuration]("transports.kafka.resolve-timeout")
    .valueOrElse(5 seconds)

  val path = applicationConfig.get[String]("transports.kafka.path")
    .valueOrElse("/user/service/kafka_transport")

  val kafkaProducer = Await.result(context.actorSelection(path).resolveOne()(Timeout(resolveTimeout)), resolveTimeout)

  def produce(request: HydraRequest): IngestorStatus = {
    Try(KafkaRecordFactories.build(request)) match {
      case Success(record) =>
        request.ackStrategy match {
          case AckStrategy.None =>
            kafkaProducer ! Produce(record)
            IngestorCompleted

          case Explicit =>
            kafkaProducer ! ProduceWithAck(record, self, sender)
            WaitingForAck
        }
      case Failure(ex) =>
        InvalidRequest(ex)
    }
  }
}