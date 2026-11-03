enablePlugins(JavaAppPackaging)

name := "heroku-ops"

scalaVersion := "3.10.0"

scalacOptions ++= Seq(
  "-language:strictEquality",
  "-deprecation",
  "-Werror",
)

val zioVersion = "2.1.27"

libraryDependencies ++= Seq(
  "dev.zio"   %% "zio"                 % zioVersion,
  "dev.zio"   %% "zio-config-typesafe" % "4.1.0",
  "dev.zio"   %% "zio-http"            % "3.11.6",
  "org.slf4j"  % "slf4j-simple"        % "2.0.20",
  "dev.zio"   %% "zio-test"            % zioVersion % Test,
  "dev.zio"   %% "zio-test-sbt"        % zioVersion % Test,
)

Compile / packageDoc / publishArtifact := false

Compile / doc / sources := Seq.empty

javaOptions += "-Djava.net.preferIPv4Stack=true"

// SkillsJars: extract agent Skills with `./sbta extractSkillsJars`
skillsJarsOutputDir := Some(file(".kiro/skills"))

libraryDependencies += "com.jamesward" % "skills" % "0.0.21" % Skills
