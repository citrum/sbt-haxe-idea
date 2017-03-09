package haxeidea

import sbt.Keys._
import sbt._

import scala.collection.mutable.ArrayBuffer
import scala.xml.{Elem, Node, Text}

object HaxeIdea {
  // ------------------------------- Keys to define -------------------------------

  val haxeIdeaModuleDir = settingKey[File]("Idea haxe module base directory. Example: 'app-js'")
  val haxeIdeaModuleFile = settingKey[File]("Idea haxe module .iml file. Example: 'app-js/app-js.iml'")
  val haxeIdeaSourceSubDirs = settingKey[Seq[String]]("Source directories inside base directory of idea haxe module. Example: Seq(\"src\")")

  // ------------------------------- Optional & internal keys -------------------------------

  val haxeIdeaCp = taskKey[Seq[File]]("All haxe source directories (including dependencies). Just for haxe `-cp` parameters.")
  val haxeIdeaManagedLibSubDirs = taskKey[Seq[File]]("All subdirectories in `haxeManagedLibSourceDir`. Internal task.")
  val haxeIdeaManagedSubDir = settingKey[String]("Subdirectory of Idea haxe module to unpack haxe libraries source. Example: 'managed'.")
  val haxeDependsOnIdeaModuleNames = settingKey[Seq[String]]("List of idea haxe module dependencies. Consists of idea module names.")
  val haxeDependsOnIdeaSourceDirs = settingKey[Seq[File]]("List of dependency idea haxe module source dirs. Internal usage when useRef flag is set.")

  val copyHaxeDepsFiles = taskKey[Seq[File]]("Unpack and copy dependent haxe libraries to `haxeManagedLibSourceDir`. Returns all copied files")
  val copyHaxeDepsInternal = taskKey[(Seq[File], Set[File])]("Unpack and copy dependent haxe libraries to `haxeManagedLibSourceDir`. Internal usage. Returns (directories, all_files)")

  val haxeIdeaUpdate = taskKey[Unit]("Update .iml haxe module file")

  // ------------------------------- RelativeHaxeProject -------------------------------

  var relativeHaxeProjectFile: (String) => File = {projectName =>
    file(System.getProperty("user.home") + "/ws/" + projectName)
  }

  case class RelativeHaxeProject(name: String, moduleId: ModuleID, ideaModuleName: String, projectId: String = null, useRef: Boolean = false) {
    if (useRef) println("Using haxe project reference for " + name)

    def projectFile: File = relativeHaxeProjectFile(name)

    val project: ProjectReference =
      if (projectId == null) RootProject(projectFile)
      else ProjectRef(projectFile, projectId)

    def dependsFor(proj: Project): Project =
      if (useRef) proj.settings(
        haxeDependsOnIdeaModuleNames += ideaModuleName,
        haxeDependsOnIdeaSourceDirs ++= (sourceDirectories in Compile in project).value
      )
      else proj.settings(libraryDependencies += moduleId)
  }
  implicit class _ProjectWrapper(proj: Project) {
    def dependsOn(relativeProject: RelativeHaxeProject): Project = relativeProject.dependsFor(proj)
  }

  // ------------------------------- Tasks -------------------------------

  lazy val haxeIdeaCpTask = haxeIdeaCp := {
    haxeIdeaSourceSubDirs.value.map(haxeIdeaModuleDir.value / _) ++
      haxeIdeaManagedLibSubDirs.value ++
      haxeDependsOnIdeaSourceDirs.value
  }

  lazy val haxeManagedLibSubDirsTask = haxeIdeaManagedLibSubDirs := copyHaxeDepsInternal.value._1

  /**
    * Unpack and copy dependent haxe libraries to [[haxeIdeaManagedSubDir]]
    */
  lazy val copyHaxeDepsInternalTask = copyHaxeDepsInternal := {
    val managedSourceDir: File = haxeIdeaModuleDir.value / haxeIdeaManagedSubDir.value

    val classPath = (dependencyClasspath in Compile).value
    var unpackedFiles: Set[File] = Set.empty
    var unpackedDirs = new ArrayBuffer[File]()
    for {attrFile <- classPath
         artifact <- attrFile.metadata.get(artifact.key) if artifact.classifier.exists(_ == "haxe")} {
      val moduleId: ModuleID = attrFile.metadata.get(moduleID.key).get
      println("Unpacking haxe sources from module " + moduleId)
      val unpackDir = new File(managedSourceDir, moduleId.organization + "@" + moduleId.name)
      unpackedDirs += unpackDir
      unpackedFiles ++= IO.unzip(attrFile.data, unpackDir)
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

  /**
    * Update .iml haxe module file
    */
  lazy val haxeIdeaUpdateTask = haxeIdeaUpdate := {
    import _XmlUtils._
    copyHaxeDepsFiles.value
    val log = streams.value.log
    val moduleDir: File = haxeIdeaModuleDir.value
    val imlFile: File = haxeIdeaModuleFile.value
    val subDirs: Seq[String] = haxeIdeaSourceSubDirs.value
    val allLibDirs: Seq[File] = haxeIdeaManagedLibSubDirs.value
    val dependsOnModules: Seq[String] = haxeDependsOnIdeaModuleNames.value

    val root: Elem = scala.xml.XML.loadFile(imlFile)
    (root \ "component").find(hasAttrVal(_, "name", "NewModuleRootManager")).foreach {componentNode =>
      var componentChilds: ArrayBuffer[Node] = componentNode.child.to

      // Update local source dirs
      val contentNode: Elem = (componentNode \ "content").head.asInstanceOf[Elem]
      val newContentNode: Elem = {
        val newChilds: ArrayBuffer[Node] = new ArrayBuffer[Node]()
        for (subDir <- subDirs) {
          newChilds += <sourceFolder isTestSource="false" url={"file://$MODULE_DIR$/" + subDir}/>
        }

        for (libDir <- allLibDirs) {
          libDir.relativeTo(moduleDir) match {
            case Some(relative) => newChilds += <sourceFolder isTestSource="false" url={"file://$MODULE_DIR$/" + relative}/>
            case None => log.warn(s"Cannot libDir path '$libDir' not in module root dir '$moduleDir' so not included")
          }
        }

        contentNode.copy(child = formatXml(newChilds, 6))
      }
      componentChilds(componentChilds.indexOf(contentNode)) = newContentNode

      // Update dependency on modules
      componentChilds = componentChilds.filterNot(n => n.label == "orderEntry" && hasAttrVal(n, "type", "module"))
      for (depModule <- dependsOnModules) {
        componentChilds += <orderEntry type="module" module-name={depModule}/>
      }

      // Replace xml childs
      val newComponentNode = componentNode.asInstanceOf[Elem].copy(child = formatXml(componentChilds, 4))
      val newRoot = root.asInstanceOf[Elem].copy(child = formatXml(root.child.filterNot(_ eq componentNode) :+ newComponentNode, 2))

      // And save new .iml file
      scala.xml.XML.save(imlFile.toString, newRoot, "utf-8")
    }
  }


  private object _XmlUtils {
    def hasAttrVal(n: Node, attrName: String, attrVal: String): Boolean =
      n.attribute(attrName).exists(_.exists(_.text == attrVal))

    def formatXml(nodes: Seq[Node], indent: Int): Seq[Node] = {
      val indentStr: String = "\n" + (" " * indent)
      nodes.filterNot(_.isInstanceOf[Text])
        .flatMap(n => Seq(Text(indentStr), n)) ++ Text("\n" + (" " * (indent - 2)))
    }
  }

  // ------------------------------- Default settings -------------------------------

  /**
    * Default haxe project settings
    */
  lazy val haxeIdeaSettings = Seq[Setting[_]](
    haxeIdeaManagedSubDir := "managed",

    haxeIdeaCpTask,
    haxeManagedLibSubDirsTask,
    copyHaxeDepsFilesTask,
    copyHaxeDepsInternalTask,
    haxeIdeaUpdateTask,

    haxeDependsOnIdeaModuleNames := Nil,
    haxeDependsOnIdeaSourceDirs := Nil,
    resourceGenerators in Compile += copyHaxeDepsFiles.map(_ => Nil).taskValue // Return empty list for one purpose: unpacked and copied haxe files should not be included in final jar
  )
}
