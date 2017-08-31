package io.radicalbit.nsdb.actors

import akka.actor.{Actor, Props}
import io.radicalbit.nsdb.actors.DatabaseActorsGuardian.{GetPublisher, GetReadCoordinator, GetWriteCoordinator}
import io.radicalbit.commit_log.CommitLogService
import io.radicalbit.nsdb.coordinator.{ReadCoordinator, WriteCoordinator}
import io.radicalbit.nsdb.metadata.MetadataService

object DatabaseActorsGuardian {

  def props = Props(new DatabaseActorsGuardian)

  sealed trait DatabaseActorsGuardianProtocol

  case object GetReadCoordinator
  case object GetWriteCoordinator
  case object GetPublisher
}

class DatabaseActorsGuardian extends Actor {

  val config = context.system.settings.config

  val indexBasePath = config.getString("radicaldb.index.base-path")

  val metadataService  = context.actorOf(MetadataService.props, "metadata-service")
  val commitLogService = context.actorOf(CommitLogService.props, "commit-log-service")
  val schemaActor      = context.actorOf(NamespaceSchemaActor.props(indexBasePath), "schema-actor")
  val namespaceActor   = context.actorOf(NamespaceDataActor.props(indexBasePath), "namespace-actor")
  val readCoordinator =
    context.actorOf(ReadCoordinator.props(schemaActor, namespaceActor), "read-coordinator")
  val publisherActor = context.actorOf(PublisherActor.props(indexBasePath, readCoordinator), "publisher-actor")
  val writeCoordinator =
    context.actorOf(WriteCoordinator.props(schemaActor, commitLogService, namespaceActor, publisherActor),
                    "write-coordinator")

  def receive = {
    case GetReadCoordinator  => sender() ! readCoordinator
    case GetWriteCoordinator => sender() ! writeCoordinator
    case GetPublisher        => sender() ! publisherActor
  }

}