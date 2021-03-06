import scala.sys.process._

Global / onChangedBuildSource := ReloadOnSourceChanges

resolvers in ThisBuild += Resolver.sonatypeRepo("releases")
resolvers in ThisBuild += Resolver.sonatypeRepo("snapshots")
resolvers in ThisBuild += "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"
resolvers in ThisBuild += Resolver.typesafeRepo("snapshots")
resolvers in ThisBuild += Resolver.bintrayRepo("scalameta", "maven") // for latset scalafmt
resolvers in ThisBuild += Resolver.bintrayRepo("definitelyscala", "maven") // for latest facades
resolvers in ThisBuild += Resolver.jcenterRepo

autoCompilerPlugins := true

lazy val licenseSettings = Seq(
  headerMappings := headerMappings.value +
    (HeaderFileType.scala -> HeaderCommentStyle.cppStyleLineComment),
    headerLicense  := Some(HeaderLicense.Custom(
      """|Copyright (c) 2018 The Trapelo Group LLC
         |This software is licensed under the MIT License (MIT).
         |For more information see LICENSE or https://opensource.org/licenses/MIT
         |""".stripMargin
    )))

lazy val buildSettings = Seq(
  organization := "ttg",
  licenses ++= Seq(("MIT", url("http://opensource.org/licenses/MIT"))),
  scalaVersion := "2.13.1",
  scalaModuleInfo ~= (_.map(_.withOverrideScalaVersion(true))),
  parallelExecution in Test := false
) ++ licenseSettings

lazy val noPublishSettings = Seq(
  skip in publish := true,
  publish := {},
  publishLocal := {},
  publishArtifact := false
)

lazy val publishSettings = Seq(
  homepage := Some(url("https://github.com/aappddeevv/odata-client"))
)

lazy val commonSettings = Seq(
  autoAPIMappings := true,
  scalacOptions ++=
    Dependencies.commonScalacOptions ++
    (if (scalaJSVersion.startsWith("0.6."))
      Seq("-P:scalajs:sjsDefinedByDefault")
    else Nil),
  libraryDependencies ++= Dependencies.commonDependencies.value,
  addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.11.0" cross CrossVersion.full),
  //addCompilerPlugin("org.scalamacros" % "paradise" % "3.1.1" cross CrossVersion.full),
)

lazy val jsServerSettings = Seq(
  libraryDependencies ++=
    Dependencies.jsServerDependencies.value
)

lazy val dynamicsSettings = buildSettings ++ commonSettings

lazy val root = project.in(file("."))
  .settings(dynamicsSettings)
  .settings(noPublishSettings)
  .settings(name := "http-client")
  .aggregate(
    http,
    odata,
    `scalajs-common`,
    `scalajs-common-cats`,
    `scalajs-common-server`,    
    docs,
    msad,
    `node-fetch`,
    `browser-fetch`)
  .enablePlugins(ScalaJSPlugin, AutomateHeaderPlugin)

lazy val `scalajs-common` = project
  .settings(dynamicsSettings)
  .settings(description := "Common components")
  .settings(name := "scalajs-common")
  .enablePlugins(ScalaJSPlugin, AutomateHeaderPlugin)

lazy val `scalajs-common-cats` = project
  .settings(dynamicsSettings)
  .settings(libraryDependencies ++= Dependencies.catsDependencies.value)
  .settings(description := "Common components that rely on cats e.g. monad hierarchy or effects")
  .settings(name := "scalajs-common-cats")
  .dependsOn(`scalajs-common`)
  .enablePlugins(ScalaJSPlugin, AutomateHeaderPlugin)

lazy val `scalajs-common-server` = project
  .settings(dynamicsSettings)
  .settings(jsServerSettings)
  .dependsOn(`scalajs-common`)
  .settings(description := "Common components server side")
  .settings(name := "scalajs-common-server")
  .enablePlugins(ScalaJSPlugin, AutomateHeaderPlugin)

lazy val http = project
  .settings(dynamicsSettings)
  .settings(name := "http-client-http")
  .settings(description := "HTTP client")
  .enablePlugins(ScalaJSPlugin, AutomateHeaderPlugin)
  .dependsOn(`scalajs-common-cats`)

lazy val `node-fetch` = project
  .settings(dynamicsSettings)
  .settings(name := "http-client-node-fetch")
  .settings(description := "odata client based on node fetch")
  .enablePlugins(ScalaJSPlugin, AutomateHeaderPlugin)
  .dependsOn(http,`scalajs-common-server`)

lazy val `browser-fetch` = project
  .settings(dynamicsSettings)
  .settings(libraryDependencies ++= Seq(
	"org.scala-js" %%% "scalajs-dom" % "0.9.7"
  ))
  .settings(name := "http-client-browser-fetch")
  .settings(description := "client based on a browser's fetch API")
  .enablePlugins(ScalaJSPlugin, AutomateHeaderPlugin)
  .dependsOn(http,`scalajs-common-cats`)
  //.settings(requireJsDomEnv in Test := true)

lazy val msad = project
  .settings(dynamicsSettings)
  .settings(name := "http-client-msad")
  .settings(description := "Microsoft AD authentication via msal.js")
  .enablePlugins(ScalaJSPlugin, AutomateHeaderPlugin)
  .dependsOn(http, `scalajs-common-cats`)

lazy val odata = project
  .settings(dynamicsSettings)
  .settings(name := "http-client-odata")
  .settings(description := "OData v4 client")
  .dependsOn(http, `scalajs-common-server`)
  .enablePlugins(ScalaJSPlugin, AutomateHeaderPlugin)

lazy val docs = project
  .settings(buildSettings)
  .settings(noPublishSettings)
  .settings(commonSettings)
  .settings(libraryDependencies ++= Dependencies.appDependencies.value)
  .enablePlugins(MicrositesPlugin, ScalaUnidocPlugin, ScalaJSPlugin)
  .aggregate(odata, http, `scalajs-common-server`, `node-fetch`, `browser-fetch`)
  .settings(
    micrositeName := "odata-client",
    micrositeDescription := "A Microsoft Dynamics CLI swiss-army knife and browser/server library.",
    micrositeBaseUrl := "/odata-client",
    micrositeGitterChannel := false,
    micrositeDocumentationUrl := "/odata-client/docs",
    micrositeAuthor := "aappddeevv",
    micrositeGithubRepo := "odata-client",
    micrositeGithubOwner := sys.env.get("GITHUB_USER").getOrElse("unknown"),
    micrositeGithubToken := sys.env.get("GITHUB_TOKEN"),
    micrositePushSiteWith := GitHub4s
  )
  .settings(
    siteSubdirName in ScalaUnidoc := "api",
    addMappingsToSiteDir(mappings in (ScalaUnidoc, packageDoc), siteSubdirName in ScalaUnidoc)
  )

val npmBuild = taskKey[Unit]("fullOptJS then webpack")
npmBuild := {
  //(fullOptJS in (`cli-main`, Compile)).value
  "npm run afterScalaJSFull" !
}

val npmBuildFast = taskKey[Unit]("fastOptJS then webpack")
npmBuildFast := {
  //(fastOptJS in (`cli-main`, Compile)).value
  "npm run afterScalaJSFast" !
}

addCommandAlias("watchit", "~ ;fastOptJS; npmBuildFast")
addCommandAlias("fmt", ";scalafmt")

buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion)
// buildInfoPackage := "dynamics-client"

bintrayReleaseOnPublish in ThisBuild := false
bintrayPackageLabels := Seq("scalajs", "odata", "scala", "client", "http")
bintrayVcsUrl := Some("git:git@github.com:aappddeevv/odata-client")
bintrayRepository := "maven"

