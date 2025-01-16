ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.3.0"

resolvers += "Akka Repository" at "https://repo.akka.io/maven/"

lazy val root = (project in file("."))
  .settings(
    name := "StreamScout",
    idePackagePrefix := Some("pl.sknikod.streamscout"),
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-actor-typed" % "2.10.0-M1",
      "com.typesafe.akka" %% "akka-cluster" % "2.10.0-M1",
      "com.typesafe.akka" %% "akka-cluster-sharding-typed" % "2.10.0-M1",
      "com.typesafe.akka" %% "akka-http" % "10.7.0-M1",
      "com.typesafe.akka" %% "akka-serialization-jackson" % "2.10.0-M1",
      "org.slf4j" % "slf4j-api" % "2.0.12",
      "io.github.cdimascio" % "java-dotenv" % "5.2.2"
    )
  )