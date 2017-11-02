name := "sbt-haxe-idea"
organization := "com.github.citrum"
version := "0.2.1"

description := "An SBT plugin to help support Haxe dependencies for Intellij IDEA"
homepage := Some(url("https://github.com/citrum/sbt-haxe-idea"))
licenses += ("Apache-2.0", url("https://www.apache.org/licenses/LICENSE-2.0.html"))
bintrayVcsUrl := Some("https://github.com/citrum/sbt-haxe-idea")

bintrayRepository := "sbt-plugins"
bintrayOrganization := Some("citrum")
publishMavenStyle := false

// No Javadoc
publishArtifact in(Compile, packageDoc) := false
publishArtifact in packageDoc := false
sources in(Compile, doc) := Nil
