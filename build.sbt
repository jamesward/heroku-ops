// ./sbta -Dlocal ...  builds against ../zio-http-mcp instead of the published artifact (stop the sbt daemon first:
// ./sbta shutdown). Never commit it enabled.
val useLocal = sys.props.contains("local")
val localMcp = file("../zio-http-mcp")

lazy val root = (project in file(".")).dependsOn(
  Option.when(useLocal)(RootProject(localMcp): ClasspathDep[ProjectReference]).toSeq*
)

libraryDependencies ++= Option.unless(useLocal)("com.jamesward" %% "zio-http-mcp" % "0.12.2").toSeq

enablePlugins(JavaAppPackaging)

name := "heroku-ops"

Compile / mainClass := Some("herokuops.HerokuOps")

scalaVersion := "3.10.0"

scalacOptions ++= Seq(
  "-language:strictEquality",
  "-deprecation",
  "-Werror",
)

val zioVersion = "2.1.27"

libraryDependencies ++= Seq(
  "dev.zio"   %% "zio"                 % zioVersion,
  "dev.zio"   %% "zio-http"            % "3.11.6",
  "dev.zio"   %% "zio-logging"         % "2.5.3",
  // The slf4j2 bridge is the sole slf4j provider, so Netty and the other Java libraries log through ZIO's logger.
  "dev.zio"   %% "zio-logging-slf4j2-bridge" % "2.5.3",
  "dev.zio"   %% "zio-test"            % zioVersion % Test,
  "dev.zio"   %% "zio-test-sbt"        % zioVersion % Test,
)

Compile / packageDoc / publishArtifact := false

Compile / doc / sources := Seq.empty

javaOptions += "-Djava.net.preferIPv4Stack=true"

// SkillsJars: extract agent Skills with `./sbta extractSkillsJars`
skillsJarsOutputDir := Some(file(".kiro/skills"))

libraryDependencies += "com.jamesward" % "skills" % "0.0.21" % Skills
