package haxeidea

import sbt.Keys.{artifact, _}
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

  val haxeTestRunner = taskKey[String]("Qualified Java/Scala class name for responsible for running haxe tests")
  val haxeTest = taskKey[Unit]("Run haxe tests")
  val haxeTestOnly = inputKey[Unit]("Run selected haxe test")

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
          .settings(
            haxeDependsOnIdeaSourceDirs in Compile ++=
              (sourceDirectories in Compile in project).value ++
                (unmanagedResourceDirectories in Compile in project).value,
            haxeDependsOnIdeaSourceDirs in Test ++=
              (sourceDirectories in Test in project).value ++
                (unmanagedResourceDirectories in Test in project).value)
      else proj.settings(libraryDependencies += moduleId)
  }
  implicit class _ProjectWrapper(proj: Project) {
    def dependsOn(relativeProject: RelativeHaxeProject): Project = relativeProject.dependsFor(proj)
  }

  // ------------------------------- Tasks -------------------------------

  lazy val haxeCpTask = haxeCp := {
    ((haxeDependsOnIdeaSourceDirs in Compile).value ++
      (managedHaxeSourceSubDirs in Compile).value ++
      (sourceDirectories in Compile).value
      ).filter(_.exists()).distinct
  }
  lazy val haxeCpTaskTest = haxeCp in Test := {
    ((haxeDependsOnIdeaSourceDirs in Compile).value ++ (haxeDependsOnIdeaSourceDirs in Test).value ++
      (managedHaxeSourceSubDirs in Compile).value ++ (managedHaxeSourceSubDirs in Test).value ++
      (sourceDirectories in Compile).value ++ (sourceDirectories in Test).value
      ).filter(_.exists()).distinct
  }

  lazy val managedHaxeSourceSubDirsTask = managedHaxeSourceSubDirs in Compile := (copyHaxeDepsInternal in Compile).value._1
  lazy val managedHaxeSourceSubDirsTaskTest = managedHaxeSourceSubDirs in Test := (copyHaxeDepsInternal in Test).value._1

  /**
    * Unpack and copy dependent haxe libraries to [[managedHaxeSourceSubDirs]]
    */
  lazy val copyHaxeDepsInternalTask = copyHaxeDepsInternal in Compile := {
    val managedSourceDir: File = (managedHaxeSourceDirectory in Compile).value
    val classPath: Classpath = (dependencyClasspath in Compile).value
    copyHaxeDepsInternal0(managedSourceDir, classPath)
  }

  lazy val copyHaxeDepsInternalTaskTest = copyHaxeDepsInternal in Test := {
    val managedSourceDir: File = (managedHaxeSourceDirectory in Test).value
    val classPath: Classpath = (dependencyClasspath in Test).value
    copyHaxeDepsInternal0(managedSourceDir, classPath)
  }

  def copyHaxeDepsInternal0(managedSourceDir: File, classPath: Classpath) = {
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
      var files: Set[File] = IO.unzip(attrFile.data, unpackDir)

      if (isHaxeJar) {
        // Small hack: after unpack make haxe binaries executable
        (unpackDir / "bin").***.filter(!_.isDirectory).get.foreach(_.setExecutable(true))

        // Workaround for IDEA Scala bug https://youtrack.jetbrains.com/issue/SCL-12839
        val sys_ = unpackDir / "sys_"
        if (sys_.exists()) {
          sys_.renameTo(unpackDir / "sys")
          files = files.map {f =>
            if (f.toString.contains("/std/sys_/")) new File(f.toString.replaceFirst("/std/sys_/", "/std/sys/"))
            else f
          }
        }
      }
      unpackedFiles ++= files
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


  lazy val copyHaxeDepsFilesTask = copyHaxeDepsFiles in Compile := (copyHaxeDepsInternal in Compile).value._2.toVector
  lazy val copyHaxeDepsFilesTaskTest = copyHaxeDepsFiles in Test := (copyHaxeDepsInternal in Test).value._2.toVector

  def haxeCompilerJavaParamsTask0(dirs: Seq[File]) = {
    val (haxeStdSeq: Seq[File], haxeCpDirs: Seq[File]) = dirs.partition(_ / "Std.hx" exists())
    require(haxeStdSeq.nonEmpty, "No haxe standard library found. Add dependency to `haxe-jar` artifact.")
    require(haxeStdSeq.size == 1, "More than one standard library defined. Choose only one `haxe-jar` artifact.")
    val haxeStd: File = haxeStdSeq.head
    val haxeBin: File = getHaxeBin(haxeStd)

    Seq("-Dhaxe.bin=" + haxeBin,
      "-Dhaxe.std=" + haxeStd,
      "-Dhaxe.cp=" + haxeCpDirs.mkString(":"))
  }

  lazy val haxeCompilerJavaParamsTask = haxeCompilerJavaParams in Compile := haxeCompilerJavaParamsTask0((haxeCp in Compile).value)
  lazy val haxeCompilerJavaParamsTaskTest = haxeCompilerJavaParams in Test := haxeCompilerJavaParamsTask0((haxeCp in Test).value)

  def getHaxeBin(haxeStd: File): File = {
    val os: String = System.getProperty("os.name")
    if (os.startsWith("Windows")) {
      // Windows
      val haxeBin: File = haxeStd / (if (isArch64bit) "bin/win64/haxe" else "bin/win/haxe")
      require(haxeBin.exists(), "Cannot find haxe binary in " + haxeBin)
      haxeBin
    } else if (os.startsWith("Mac")) {
      // OSX
      val haxeBin: File = haxeStd / "bin/osx/haxe"
      require(haxeBin.exists(), "Cannot find haxe binary in " + haxeBin)
      require(haxeBin.canExecute, "Haxe binary cannot be executable (no executable flag set?)")
      haxeBin
    } else {
      // Linux
      require(isArch64bit, "Haxe doesn't support 32-bit Linux. Sorry")
      val haxeBin: File = haxeStd / "bin/linux64/haxe"
      require(haxeBin.exists(), "Cannot find haxe binary in " + haxeBin)
      require(haxeBin.canExecute, "Haxe binary cannot be executable (no executable flag set?)")
      haxeBin
    }
  }

  private def isArch64bit: Boolean = {
    if (System.getProperty("os.name").startsWith("Windows")) {
      val arch: String = System.getenv("PROCESSOR_ARCHITECTURE")
      val wow64Arch: String = System.getenv("PROCESSOR_ARCHITEW6432")

      arch.endsWith("64") || wow64Arch != null && wow64Arch.endsWith("64")
    } else {
      System.getProperty("os.arch").contains("64")
    }
  }

  /**
    * Include `useHaxeTestTask` in your project config to enable automatically run `haxeTest` on `test` task.
    */
  lazy val useHaxeTestTask = test in Test := {val _ = haxeTest.value; (test in Test).value}

  lazy val haxeTestTask = haxeTest := {
    // If you use your own HaxeTestRunner, add `(compile in Test).value` command prior to this task
    val baseDir = baseDirectory.value
    val classPath = Seq(baseDir, (classDirectory in Runtime).value) ++
      (dependencyClasspath in Test).value.files ++
      (resourceDirectories in Runtime).value
    runScala0(classPath, haxeTestRunner.value, runJVMOptions = (haxeCompilerJavaParams in Test).value)
  }

  lazy val haxeTestOnlyTask = haxeTestOnly := {
    val args: Seq[String] = complete.DefaultParsers.spaceDelimited("<arg>").parsed
    // If you use your own HaxeTestRunner, add `(compile in Test).value` command prior to this task
    val baseDir = baseDirectory.value
    val classPath = Seq(baseDir, (classDirectory in Runtime).value) ++
      (dependencyClasspath in Test).value.files ++
      (resourceDirectories in Runtime).value
    runScala0(classPath, haxeTestRunner.value, runJVMOptions = (haxeCompilerJavaParams in Test).value,
      arguments = args)
  }

  /**
    * Run codegen scala class in another process
    */
  private def runScala0(classPath: Seq[File], className: String,
                        arguments: Seq[String] = Nil,
                        runJVMOptions: Seq[String] = Nil) {
    val ret: Int = new Fork("java", Some(className)).apply(
      // здесь мы не используем параметр bootJars, потому что он добавляет jar'ки через -Xbootclasspath/a,
      // а это чревато тем, что getClass.getClassLoader == null для всех классов
      ForkOptions(
        runJVMOptions = Seq("-cp", classPath.mkString(":")) ++ runJVMOptions,
        outputStrategy = Some(StdoutOutput)),
      arguments)
    if (ret != 0) sys.error("Execution " + className + " ends with error")
  }

  // ------------------------------- Default settings -------------------------------

  /**
    * Default haxe project settings
    */
  lazy val haxeIdeaSettings = Seq[Setting[_]](
    managedHaxeSourceDirectory := target.value / "managed-haxe",
    haxeTestRunner := "webby.mvc.script.HaxeTestRunner",

    haxeCpTask,
    haxeCpTaskTest,
    managedHaxeSourceSubDirsTask,
    managedHaxeSourceSubDirsTaskTest,
    copyHaxeDepsFilesTask,
    copyHaxeDepsFilesTaskTest,
    copyHaxeDepsInternalTask,
    copyHaxeDepsInternalTaskTest,
    haxeCompilerJavaParamsTask,
    haxeCompilerJavaParamsTaskTest,
    haxeTestTask,
    haxeTestOnlyTask,

    haxeDependsOnIdeaSourceDirs := Nil,
    resourceGenerators in Compile += (copyHaxeDepsFiles in Compile).map(_ => Nil).taskValue, // Return empty list for one purpose: unpacked and copied haxe files should not be included in final jar
    resourceGenerators in Test += (copyHaxeDepsFiles in Test).map(_ => Nil).taskValue
  )
}
