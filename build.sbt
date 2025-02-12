ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.3.0"

resolvers += "Akka Repository" at "https://repo.akka.io/maven/"

lazy val root = (project in file("."))
  .settings(
    name := "StreamScout",
    idePackagePrefix := Some("pl.sknikod.streamscout"),
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-actor-typed" % "2.10.0",
      "com.typesafe.akka" %% "akka-cluster" % "2.10.0",
      "com.typesafe.akka" %% "akka-cluster-sharding-typed" % "2.10.0",
      "com.typesafe.akka" %% "akka-http" % "10.7.0",
      "com.typesafe.akka" %% "akka-serialization-jackson" % "2.10.0",
      "com.typesafe.akka" %% "akka-persistence" % "2.10.0",
      "com.typesafe.akka" %% "akka-persistence-typed" % "2.10.0",
      "com.typesafe.akka" %% "akka-persistence-cassandra" % "1.3.0",
      "com.typesafe.akka" %% "akka-persistence-query" % "2.10.0",
      "com.typesafe.akka" %% "akka-stream-kafka" % "7.0.1",
      "org.slf4j" % "slf4j-api" % "2.0.12",
      "io.github.cdimascio" % "java-dotenv" % "5.2.2",
      "org.pircbotx" % "pircbotx" % "2.1",
      "io.circe" %% "circe-core" % "0.14.7",
      "io.circe" %% "circe-generic" % "0.14.7",
      "io.circe" %% "circe-parser" % "0.14.10",
      "org.apache.kafka" % "kafka-clients" % "3.8.0",
    )
  )