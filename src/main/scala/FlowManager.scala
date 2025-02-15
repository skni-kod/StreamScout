package pl.sknikod.streamscout

import akka.actor.typed.{ActorRef, ActorSystem}
import akka.cluster.sharding.typed.ClusterShardingSettings
import akka.cluster.sharding.typed.ClusterShardingSettings.PassivationStrategySettings
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity}
import akka.stream.alpakka.cassandra.scaladsl.CassandraSession
import pl.sknikod.streamscout.infrastructure.kafka.KafkaProducerConfig
import pl.sknikod.streamscout.token.{TwitchToken, TwitchTokenActor, TwitchTokenDAO}

import scala.concurrent.{Await, ExecutionContext}
import concurrent.duration.DurationInt

class FlowManager(sharding: ClusterSharding, session: CassandraSession)(implicit ec: ExecutionContext) {
  private val tokenDao = new TwitchTokenDAO(session)
  private val tokens: Map[String, TwitchToken] = Await.result(tokenDao.getAllTokens, 10.seconds).map(t => t.clientId -> t).toMap

  def initializeSharding()(implicit system: ActorSystem[_]) = {
    sharding.init(
      Entity(TwitchTokenActor.TypeKey) { entityContext =>
        val clientId = entityContext.entityId
        val token = tokens(clientId)
        TwitchTokenActor(token, tokenDao)
      }.withSettings(ClusterShardingSettings(system).withPassivationStrategy(PassivationStrategySettings.disabled))
    )
  }

  def groupChannelsByClient(channelsName: Set[String]): Map[String, Set[String]] = {
    val channelGroups = channelsName.grouped(Channels.MAX_CHANNELS).toList
    val clientIds = tokens.keys.toList

    val groupedMap = channelGroups.zipWithIndex.map { case (group, index) =>
      val clientId = clientIds(index % clientIds.length)
      clientId -> group.toSet
    }

    groupedMap.groupMapReduce(_._1)(_._2)(_ ++ _)
  }

  def initializeFlow()(implicit system: ActorSystem[_]) : Unit = {
    Channels.fromJsonFile("local_channels.json") match
      case Left(error) =>
        println(s"Error loading channels: $error")
      case Right(channels) =>
        val clientChannelsMap: Map[String, Set[String]] = groupChannelsByClient(channels.channelsName)
        clientChannelsMap.foreach { case (clientId, channelsSet) =>
          val ircBotActor: ActorRef[IrcBot.Command] =
            system.systemActorOf(
              IrcBot(
                channels = Channels(channelsSet),
                delaySeconds = 3,
                kafkaProducer = KafkaProducerConfig(),
                clientId = clientId,
                sharding = sharding
              ),
              s"ircBot-$clientId"
            )

          ircBotActor ! IrcBot.Start()

          val twitchApiActor = sharding.entityRefFor(TwitchApiActor.TypeKey, clientId)
          
          twitchApiActor ! TwitchApiActor.Start()
        }

  }


}
