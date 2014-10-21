package xsbtWebApp

import sbt._
import Keys.TaskStreams

import xsbtUtil.types._
import xsbtUtil.{ util => xu }

import xsbtClasspath.{ Asset => ClasspathAsset, ClasspathPlugin }
import xsbtClasspath.Import.classpathAssets

object Import {
	type WebAppProcessor	= xsbtWebApp.WebAppProcessor
	val WebAppProcessor		= xsbtWebApp.WebAppProcessor
	
	val webapp				= taskKey[File]("complete build, returns the created directory")
	val webappAppDir		= settingKey[File]("directory of the webapp to be built")
	
	val webappWar			= taskKey[File]("complete build, returns the created war file")
	val webappWarFile		= settingKey[File]("where to put the webapp's war file")
	
	val webappPackageName	= settingKey[String]("name of the package built")
	
	val webappStage			= taskKey[Seq[PathMapping]]("stage webapp contents for processing")
	val webappAssetDir		= settingKey[File]("directory with webapp contents")
	val webappAssets		= taskKey[Traversable[PathMapping]]("webapp contents")
	val webappExtras		= taskKey[Traversable[PathMapping]]("additional webapp contents for as a task to allow inclusion of generated content")
	
	val webappPrepare		= taskKey[Seq[PathMapping]]("prepare libraries for processing")
	val webappDependencies	= taskKey[Seq[File]]("additional webapp contents as dependencies")
	val webappExplodeDir	= settingKey[File]("a place to unpack webappDependencies")
	
	val webappProcess		= taskKey[Seq[PathMapping]]("process staged webapp contents")
	val webappPipeline		= settingKey[Seq[TaskKey[WebAppProcessor]]]("pipeline applied to webapp contents")
	val webappProcessors	= taskKey[Seq[WebAppProcessor]]("processors applied to webapp contents")
	
	val webappDeploy		= taskKey[Unit]("copy-deploy the webapp")
	val webappDeployBase	= settingKey[Option[File]]("target directory base for copy-deploy")
	val webappDeployName	= settingKey[String]("target directory name for copy-deploy")

	val webappBuildDir		= settingKey[File]("base directory of built files")
}

object WebAppPlugin extends AutoPlugin {
	val webappConfig		= config("webapp").hide
	
	//------------------------------------------------------------------------------
	//## exports
	
	lazy val autoImport	= Import
	import autoImport._
	
	override val requires:Plugins		= ClasspathPlugin && plugins.JvmPlugin
	
	override val trigger:PluginTrigger	= noTrigger
	
	override lazy val projectConfigurations:Seq[Configuration]	=
			Vector(
				webappConfig
			)
	
	override lazy val projectSettings:Seq[Def.Setting[_]]	=
			Vector(
				// Keys.ivyConfigurations	+= webappConfig,
				
				webapp	:=
						buildTask(
							streams		= Keys.streams.value,
							assets		= classpathAssets.value,
							processed	= webappProcess.value,
							appDir		= webappAppDir.value
						),
				webappAppDir			:= webappBuildDir.value / "output" / webappPackageName.value,
				webappWar	:=
						warTask(
							streams		= Keys.streams.value,
							webapp		= webapp.value,
							warFile		= webappWarFile.value
						),
				webappWarFile			:= webappBuildDir.value / "output" / (webappPackageName.value + ".war"),
						
				webappAssetDir			:= (Keys.sourceDirectory in Compile).value / "webapp",
				webappAssets			:= xu.find allMapped webappAssetDir.value,
				webappExtras			:= Seq.empty,
				webappStage				:= webappPrepare.value ++ webappAssets.value.toVector ++ webappExtras.value.toVector,
				webappPrepare	:=
						prepareTask(
							streams			= Keys.streams.value,
							dependencies	= webappDependencies.value,
							explodeDir		= webappExplodeDir.value
						),
				webappDependencies		:= Keys.update.value select configurationFilter(name = webappConfig.name),
				webappExplodeDir		:= webappBuildDir.value / "work"/ "explode",
			
				webappProcess			:= (webappProcessors.value foldLeft webappStage.value) { (inputs, processor) => processor(inputs) },
				webappPipeline			:= Vector.empty,
				webappProcessors		<<= xu.task chain webappPipeline,
				
				webappDeploy	:=
						deployTask(
							streams		= Keys.streams.value,
							webapp		= webapp.value,
							deployBase	= webappDeployBase.value,
							deployName	= webappDeployName.value
						),
				webappDeployBase		:= None,
				webappDeployName		:= Keys.name.value,
				
				webappPackageName		:= Keys.name.value + "-" + Keys.version.value,
				webappBuildDir			:= Keys.crossTarget.value / "webapp",
				
				Keys.watchSources		:= Keys.watchSources.value ++ (webappAssets.value map xu.pathMapping.getFile)
			)
			
	//------------------------------------------------------------------------------
	//## tasks
	
	/** prepare dependencies for processing */
	private def prepareTask(
		streams:TaskStreams,
		dependencies:Traversable[File],
		explodeDir:File
	):Seq[PathMapping]	= {
		streams.log info s"extracting ${dependencies.size} webapp content libraries to ${explodeDir}"
		IO delete explodeDir
		dependencies.toVector flatMap { dependency	=>
			val out	= explodeDir / dependency.getName
			IO unzip (dependency, out, -xu.filter.JarManifestFilter)
			xu.find allMapped out
		}
	}
	
	/** build webapp directory */
	private def buildTask(
		streams:TaskStreams,	
		assets:Seq[ClasspathAsset],
		processed:Seq[PathMapping],
		appDir:File
	):File = {
		streams.log info s"copying processed resources to ${appDir}"
		val resourcesToCopy	= processed map (xu.pathMapping anchorTo appDir)
		val resourcesCopied	= IO copy resourcesToCopy
		
		val libDir	= appDir / "WEB-INF" / "lib"
		streams.log info s"copying webapp code libraries to ${libDir}"
		libDir.mkdirs()
		val libsToCopy	= assets map { _.flatPathMapping } map (xu.pathMapping anchorTo libDir)
		val libsCopied	= IO copy libsToCopy
		
		streams.log info "cleaning up"
		// NOTE we should delete not-copied directories,
		// but at least for libs we don't have the copied ones
		val allFiles	= (xu.find files appDir).toSet
		val obsolete	= allFiles -- resourcesCopied -- libsCopied
		IO delete obsolete
		
		appDir
	}
	
	/** build webapp war */
	private def warTask(
		streams:TaskStreams,	
		webapp:File,
		warFile:File
	):File = {
		streams.log info s"creating war file ${warFile}"
		xu.zip create (
			sources		= xu.find allMapped webapp,
			outputZip	= warFile
		)
		warFile
	}
	
	/** copy-deploy webapp */
	private def deployTask(
		streams:TaskStreams,	
		webapp:File,
		deployBase:Option[File],
		deployName:String
	):Unit = {
		if (deployBase.isEmpty) {
			xu.fail logging (streams, s"${webappDeployBase.key.label} must be initialized to deploy")
		}
		val deployBase1	= deployBase.get
		
		val webappDir	= deployBase1 / deployName
		val warFile		= deployBase1 / (deployName + ".war")
		
		streams.log info s"deleting old war file ${warFile}"
		IO delete warFile
		
		streams.log info s"deleting old webapp ${webappDir}"
		IO delete webappDir
		
		streams.log info s"deploying webapp to ${webappDir}"
		val webappFiles	= (xu.find allMapped webapp) map (xu.pathMapping anchorTo webappDir)
		IO copy webappFiles
	}
}
