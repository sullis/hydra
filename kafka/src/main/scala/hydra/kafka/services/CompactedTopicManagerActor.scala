package hydra.kafka.services

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorLogging, Props}
import com.typesafe.config.Config
import hydra.common.config.ConfigSupport
import hydra.kafka.services.CompactedTopicManagerActor._
import hydra.kafka.util.KafkaUtils
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient
import org.apache.kafka.common.requests.CreateTopicsRequest.TopicDetails

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import akka.pattern.pipe

class CompactedTopicManagerActor(consumerConfig: Config,
                            bootstrapServers: String,
                            schemaRegistryClient: SchemaRegistryClient,
                            metadataTopicName: String,
                                 kafkaUtils: KafkaUtils) extends Actor
  with ConfigSupport
  with ActorLogging {

  //maintains map of actorRefs, we can use the actorRef to query the actor and get its status?
  private val compactedStreamsMap = new collection.mutable.HashMap[String, String]()
  private final val COMPACTED_PREFIX = "_compacted."

  override def receive: Receive = {

    case CreateCompactedTopic(topicName, topicDetails) => {
      createCompactedTopic(topicName, topicDetails).map { _ =>
        self ! CreateCompactedStream(topicName)
      }.recover {
        case e: Exception => throw e
      }
    }

    case CreateCompactedStream(topicName) => {
      pipe(createCompactedStream(topicName)) to sender
    }


  }

  private[kafka] def createCompactedTopic(topicName: String, topicDetails: TopicDetails): Future[Unit] = {

    val timeout = 2000
    val topicExists = kafkaUtils.topicExists(topicName) match {
      case Success(value) => value
      case Failure(exception) =>
        log.error(s"Unable to determine if topic exists: ${exception.getMessage}")
        return Future.failed(exception)
    }

    // Don't fail when topic already exists
    if (topicExists) {
      log.info(s"Topic $topicName already exists, proceeding anyway...")
      Future.successful(())
    }

    else {
      kafkaUtils.createTopic(topicName, topicDetails, timeout)
        .map { r =>
          r.all.get(timeout, TimeUnit.MILLISECONDS)
        }
        .map { _ =>
          ()
        }
        .recover {
          case e: Exception => throw e
        }
    }
  }

  private[kafka] def createCompactedStream(topicName: String): Future[Unit] = {
    //do we want to return a future unit? how do we signal to the client that compacted was successful?
    val compactedActor = context.actorOf()
  }

}

object CompactedTopicManagerActor {

  case class CreateCompactedStream(topicName: String)
  case class CreateCompactedTopic(topicName: String, topicDetails: TopicDetails)

  sealed trait CompactedTopicManagerResult

  def props(consumerConfig: Config,
            bootstrapServers: String,
            schemaRegistryClient: SchemaRegistryClient,
            metadataTopicName: String) = {
    Props(classOf[CompactedTopicManagerActor], consumerConfig, bootstrapServers, schemaRegistryClient, metadataTopicName)
  }

}

