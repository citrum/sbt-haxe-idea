package haxeidea
import sbt.Keys._
import sbt._

/**
  * Small set of sbt settings to help to build a Haxe jar artifact
  */
object HaxeLib {
  val packageHaxe = taskKey[File]("Produces a Haxe jar artifact")
  lazy val packageHaxeTask = packageHaxe := {
    val sourceDirs: Seq[File] = (sourceDirectories in Compile).value
    val outputZip = artifactPath.value
    sbt.IO.zip(sourceDirs.map(Path.allSubpaths).flatten, outputZip)
    outputZip
  }

  // ------------------------------- Default settings -------------------------------

  lazy val haxeLibSettings = Seq[Setting[_]](
    artifact := Artifact(name.value, "haxe"), // Enables 'haxe' classifier
    crossPaths := false, // Turn off scala versions

    // Override `package`, `packageBin` tasks with `packageHaxe`
    packageHaxeTask,
    Keys.`package` := packageHaxe.value,
    packageBin := packageHaxe.value,

    packagedArtifacts := Classpaths.packaged(Seq(makePom)).value ++ Map(artifact.value -> packageHaxe.value)
  )
}
