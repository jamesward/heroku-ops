// ./sbta -Dlocal ...  builds against ../zio-http-mcp instead of the published artifact (stop the sbt daemon first:
// ./sbta shutdown). Never commit it enabled.
val useLocal = sys.props.contains("local")
val localMcp = file("../zio-http-mcp")

lazy val root = (project in file(".")).dependsOn(
  Option.when(useLocal)(RootProject(localMcp): ClasspathDep[ProjectReference]).toSeq*
)

libraryDependencies ++= Option.unless(useLocal)("com.jamesward" %% "zio-http-mcp" % "0.12.0").toSeq

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
