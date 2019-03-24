import sbt.compilerPlugin

val plugins = Seq(
  compilerPlugin("com.olegpy"     %% "better-monadic-for" % "0.3.0-M4"),
  compilerPlugin("org.spire-math" %% "kind-projector"     % "0.9.9")
)

val `conquering-concurrency` = project.in(file("."))
  .settings(
    scalaVersion := "2.12.8",
    libraryDependencies ++= Seq(
      "org.typelevel"              %% "kittens"             % "1.2.0",
      "org.typelevel"              %% "cats-effect"         % "1.2.0",
      "org.typelevel"              %% "cats-effect-laws"    % "1.2.0",
      "org.scalatest"              %% "scalatest"           % "3.0.7",
      "co.fs2"                     %% "fs2-core"            % "1.0.4",
      "co.fs2"                     %% "fs2-io"              % "1.0.4",
      "io.circe"                   %% "circe-generic"       % "0.11.1",
      "io.chrisdavenport"          %% "cats-par"            % "0.2.0",
      "io.chrisdavenport"          %% "log4cats-slf4j"      % "0.3.0",
      "com.olegpy"                 %% "meow-mtl"            % "0.2.0",
      "ch.qos.logback"             % "logback-classic"      % "1.2.3"
    ) ++ plugins,
    scalacOptions ++= Seq(
      "-Ypartial-unification",
      "-language:higherKinds",
      "-Ywarn-value-discard"
    )
  )
