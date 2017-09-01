package haxeidea

import sbt.Keys._
import sbt._

import scala.collection.mutable.ArrayBuffer

object HaxeIdea {
  // ------------------------------- Optional & internal keys -------------------------------

  val managedHaxeSourceDirectory = settingKey[File]("Managed haxe source directory for unpacking dependencies.")

  val managedHaxeSourceSubDirs = taskKey[Seq[File]]("All subdirectories in `managedHaxeSourceDirectory`. Internal task.")
  val haxeCp = taskKey[Seq[File]]("All haxe source directories (including dependencies). Just for haxe `-cp` parameters.")

  val haxeDependsOnIdeaSourceDirs = settingKey[Seq[File]]("List of dependency idea haxe module source dirs. Internal usage when useRef flag is set.")

  val copyHaxeDepsFiles = taskKey[Seq[File]]("Unpack and copy dependent haxe libraries to `haxeManagedLibSourceDir`. Returns all copied files")
  val copyHaxeDepsInternal = taskKey[(Seq[File], Set[File])]("Unpack and copy dependent haxe libraries to `haxeManagedLibSourceDir`. Internal usage. Returns (directories, all_files)")

  val haxeCompilerJavaParams = taskKey[Seq[String]]("Parameters for haxe compiler in application ready to be sent via command line in `javaOptions`. For example: [\"-Dhaxe.cp=...\", \"-Dhaxe.bin=...\"]")

  // ------------------------------- RelativeHaxeProject -------------------------------

  var relativeHaxeProjectFile: (String) => File = {projectName =>
    file(System.getProperty("user.home") + "/ws/" + projectName)
  }

  case class RelativeHaxeProject(name: String, moduleId: ModuleID, projectId: String = null, useRef: Boolean = false) {
    if (useRef) println("Using haxe project reference for " + name)

    def projectFile: File = relativeHaxeProjectFile(name)

    val project: ProjectReference =
      if (projectId == null) RootProject(projectFile)
      else ProjectRef(projectFile, projectId)

    def dependsFor(proj: Project): Project =
      if (useRef)
        proj
          .dependsOn(project)
          .settings(haxeDependsOnIdeaSourceDirs ++=
            (sourceDirectories in Compile in project).value ++
              (unmanagedResourceDirectories in Compile in project).value)
      else proj.settings(libraryDependencies += moduleId)
  }
  implicit class _ProjectWrapper(proj: Project) {
    def dependsOn(relativeProject: RelativeHaxeProject): Project = relativeProject.dependsFor(proj)
  }

  // ------------------------------- Tasks -------------------------------

  lazy val haxeCpTask = haxeCp := {
    (haxeDependsOnIdeaSourceDirs.value ++
      managedHaxeSourceSubDirs.value ++
      (sourceDirectories in Compile).value
      ).filter(_.exists()).distinct
  }

  lazy val managedHaxeSourceSubDirsTask = managedHaxeSourceSubDirs := copyHaxeDepsInternal.value._1

  /**
    * Unpack and copy dependent haxe libraries to [[managedHaxeSourceSubDirs]]
    */
  lazy val copyHaxeDepsInternalTask = copyHaxeDepsInternal := {
    val managedSourceDir: File = managedHaxeSourceDirectory.value

    val classPath: Classpath = (dependencyClasspath in Compile).value
    var unpackedFiles: Set[File] = Set.empty
    var unpackedDirs = new ArrayBuffer[File]()
    for {attrFile <- classPath if !attrFile.data.isDirectory
         artifact <- attrFile.metadata.get(artifact.key) if artifact.classifier.exists(_ == "haxe")} {
      val isHaxeJar: Boolean = artifact.name == "haxe-jar"
      val moduleId: ModuleID = attrFile.metadata.get(moduleID.key).get
      println("Unpacking haxe sources from module " + moduleId)
      var unpackDir = new File(managedSourceDir, moduleId.organization + "@" + moduleId.name)

      // Hack: standard library must be in directory named "std"
      if (isHaxeJar) {
        unpackDir = unpackDir / "std"
      }

      unpackedDirs += unpackDir
      unpackedFiles ++= IO.unzip(attrFile.data, unpackDir)

      // Small hack: after unpack make haxe binaries executable
      if (isHaxeJar) {
        (unpackDir / "bin").***.filter(!_.isDirectory).get.foreach(_.setExecutable(true))
      }
    }

    // Delete all other files in managedSourceDir
    var removed = false
    for ((file, _) <- Path.allSubpaths(managedSourceDir)) {
      if (file.isDirectory && file.list().length == 0) {
        file.delete()
        removed = true
      }
      else if (!unpackedFiles.contains(file)) {
        file.delete()
        removed = true
      }
    }
    while (removed) {
      removed = false
      for ((file, _) <- Path.allSubpaths(managedSourceDir)) {
        if (file.isDirectory && file.list().length == 0) {
          file.delete()
          removed = true
        }
      }
    }
    (unpackedDirs, unpackedFiles)
  }

  lazy val copyHaxeDepsFilesTask = copyHaxeDepsFiles := copyHaxeDepsInternal.value._2.toVector

  lazy val haxeCompilerJavaParamsTask = haxeCompilerJavaParams := {
    val dirs: Seq[File] = haxeCp.value
    val (haxeStdSeq: Seq[File], haxeCpDirs: Seq[File]) = dirs.partition(_ / "Std.hx" exists())
    require(haxeStdSeq.nonEmpty, "No haxe standard library found. Add dependency to `haxe-jar` artifact.")
    require(haxeStdSeq.size == 1, "More than one standard library defined. Choose only one `haxe-jar` artifact.")
    val haxeStd: File = haxeStdSeq.head
    val haxeBin: File = haxeStd / "bin/linux64/haxe"
    require(haxeBin.exists(), "Cannot find haxe binary in " + haxeBin)
    require(haxeBin.canExecute, "Haxe binary cannot be executable (no executable flag set?)")

    Seq("-Dhaxe.bin=" + haxeBin,
      "-Dhaxe.std=" + haxeStd,
      "-Dhaxe.cp=" + haxeCpDirs.mkString(":"))
  }

  // ------------------------------- Default settings -------------------------------

  /**
    * Default haxe project settings
    */
  lazy val haxeIdeaSettings = Seq[Setting[_]](
    managedHaxeSourceDirectory := target.value / "managed-haxe",

    haxeCpTask,
    managedHaxeSourceSubDirsTask,
    copyHaxeDepsFilesTask,
    copyHaxeDepsInternalTask,
    haxeCompilerJavaParamsTask,

    haxeDependsOnIdeaSourceDirs := Nil,
    resourceGenerators in Compile += copyHaxeDepsFiles.map(_ => Nil).taskValue // Return empty list for one purpose: unpacked and copied haxe files should not be included in final jar
  )
}
