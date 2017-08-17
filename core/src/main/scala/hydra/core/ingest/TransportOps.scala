package hydra.core.ingest

import akka.util.Timeout
import configs.syntax._
import hydra.common.config.ConfigSupport
import hydra.common.logging.LoggingAdapter
import hydra.core.ingest.Ingestor.{IngestorInitializationError, IngestorInitialized}
import hydra.core.protocol._
import hydra.core.transport.AckStrategy.Explicit
import hydra.core.transport.{AckStrategy, RecordFactory}

import scala.concurrent.Future
import scala.concurrent.duration.{FiniteDuration, _}

/**
  * Encapsulates basic transport operations: Look up an existing transport and
  * transports a HydraRequest using the looked up transport.
  *
  * Also has logic for dealing with errors.
  *
  * Created by alexsilva on 5/27/17.
  */
trait TransportOps extends ConfigSupport with LoggingAdapter {
  this: Ingestor =>

  implicit val ec = context.dispatcher

  /**
    * Always override this with a def due to how Scala initializes val in subtraits.
    */
  def transportName: String

  val transportPath = applicationConfig.get[String](s"transports.$transportName.path")
    .valueOrElse(s"/user/service/${transportName}_transport")

  private val transportResolveTimeout = Timeout(applicationConfig
    .getOrElse[FiniteDuration](s"transports.$transportName.resolve-timeout", 5 seconds).value)

  lazy val transportActorFuture = context.actorSelection(transportPath).resolveOne()(transportResolveTimeout)

  /**
    * Overrides the init method to look up a transport
    */
  override def initIngestor: Future[HydraMessage] = {
    transportActorFuture
      .map(t => IngestorInitialized)
      .recover {
        case e => IngestorInitializationError(new IllegalArgumentException(s"[$thisActorName]: No transport found " +
          s" $transportPath", e))
      }
  }

  def transport[K, V](request: HydraRequest)
                     (implicit recordFactory: RecordFactory[K, V]): IngestorStatus = {
    val record = recordFactory.build(request)
    val status = record.map { rec =>
      request.ackStrategy match {
        case AckStrategy.None =>
          transportActorFuture.foreach(_ ! Produce(rec))
          IngestorCompleted

        case Explicit =>
          transportActorFuture.foreach(_ ! ProduceWithAck(rec, self, sender))
          WaitingForAck
      }
    }.recover { case e =>
      InvalidRequest(e)
    }

    status.get
  }
}