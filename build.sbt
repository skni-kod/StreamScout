ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.3.0"

val akkaVersion = "2.10.1"
val akkaHttpVersion = "10.7.0"
val akkaPersistenceVersion = "2.10.1"
val akkaProjectionVersion = "1.6.8"
val kafkaVersion = "3.8.0"
val sttpVersion = "3.10.0"
val circeVersion = "0.14.7"
val slf4jVersion = "2.0.12"
val pircbotxVersion = "2.1"
val dotenvVersion = "5.2.2"

resolvers += "Akka Repository" at "https://repo.akka.io/maven/"

lazy val root = (project in file("."))
  .settings(
    name := "StreamScout",
    idePackagePrefix := Some("pl.sknikod.streamscout"),
    libraryDependencies ++= Seq(
      // Akka dependencies
      "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
      "com.typesafe.akka" %% "akka-cluster" % akkaVersion,
      "com.typesafe.akka" %% "akka-cluster-sharding-typed" % akkaVersion,
      "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
      "com.typesafe.akka" %% "akka-serialization-jackson" % akkaVersion,
      "com.typesafe.akka" %% "akka-persistence" % akkaPersistenceVersion,
      "com.typesafe.akka" %% "akka-persistence-typed" % akkaPersistenceVersion,
      "com.typesafe.akka" %% "akka-persistence-cassandra" % "1.3.0",
      "com.typesafe.akka" %% "akka-persistence-query" % akkaVersion,
      "com.typesafe.akka" %% "akka-cluster-tools" % akkaVersion,
      "com.typesafe.akka" %% "akka-stream-kafka" % "7.0.1",

      // Akka Projection
      "com.lightbend.akka" %% "akka-projection-core" % akkaProjectionVersion,
      "com.lightbend.akka" %% "akka-projection-cassandra" % akkaProjectionVersion,
      "com.lightbend.akka" %% "akka-projection-eventsourced" % akkaProjectionVersion,

      // Logging and utilities
      "org.slf4j" % "slf4j-api" % slf4jVersion,
      "io.github.cdimascio" % "java-dotenv" % dotenvVersion,

      // Additional dependencies
      "org.pircbotx" % "pircbotx" % pircbotxVersion,
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-parser" % "0.14.10",
      "org.apache.kafka" % "kafka-clients" % kafkaVersion,
      "com.softwaremill.sttp.client3" %% "core" % sttpVersion,
      "com.softwaremill.sttp.client3" %% "circe" % sttpVersion,
      "com.softwaremill.sttp.client3" %% "async-http-client-backend-future" % "3.10.1"
    ),

    scalacOptions ++= Seq(
      "-Xmax-inlines", "64"
    )
  )