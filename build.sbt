import com.typesafe.sbt.SbtNativePackager.packageArchetype

name := "akka-dcm"

version := "0.1"

organization := "se.vgregion"

scalaVersion := "2.11.2"

scalacOptions := Seq("-encoding", "UTF-8", "-Xlint","-deprecation", "-unchecked", "-feature")

Revolver.settings

packageArchetype.java_server

maintainer in Windows := "Exini Diagnostics AB"

packageSummary in Windows := "Reactive Dcm Node"

packageDescription in Windows := "Longer message here..."

resolvers ++= Seq("Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
                  "Sonatype snapshots"  at "http://oss.sonatype.org/content/repositories/snapshots/",
                  "Spray Repository"    at "http://repo.spray.io",
                  "Spray Nightlies"     at "http://nightlies.spray.io/")

libraryDependencies ++= {
  val akkaVersion       = "2.3.6"
  val sprayVersion      = "1.3.2"
  Seq(
    "com.typesafe.scala-logging" 	%% "scala-logging" 		% "3.1.0",
    "com.typesafe.akka" 			%% "akka-actor"      	% akkaVersion,
    "io.spray"          			%% "spray-can"       	% sprayVersion,
    "io.spray"          			%% "spray-routing"   	% sprayVersion,
    "io.spray"          			%% "spray-json"      	% "1.3.1",
	"io.spray" 						%% "spray-testkit" 		% sprayVersion 		% "test",
    "com.typesafe.akka" 			%% "akka-slf4j"      	% akkaVersion,
    "com.typesafe.akka" 			%% "akka-testkit"    	% akkaVersion   	% "test",
    "ch.qos.logback"    			%  "logback-classic" 	% "1.1.2",
	"com.typesafe.slick" 			%% "slick" 				% "2.1.0",
	"com.h2database" 				%  "h2" 				% "1.3.170",
    "org.scalatest"     			%% "scalatest"       	% "2.2.1"       	% "test",
	"org.webjars" 					%  "bootstrap" 			% "3.3.1"
  )
}

fork in Test := true

EclipseKeys.createSrc := EclipseCreateSrc.Default + EclipseCreateSrc.Resource

EclipseKeys.withSource := true

mainClass in Compile := Some("se.vgregion.app.Main")

lazy val akkadcm = (project in file(".")).enablePlugins(SbtTwirl, SbtWeb)

//unmanagedResourceDirectories in Compile <++= baseDirectory { base =>
//    Seq( base / "src/main/public" )
//}