package xsbtWebApp

import sbt._
import Keys.TaskStreams

import xsbtUtil._
import xsbtClasspath.{ Asset => ClasspathAsset, ClasspathPlugin }
import xsbtClasspath.Import.classpathAssets

object Import {
	type WebAppProcessor	= Seq[PathMapping]=>Seq[PathMapping]
	
	val webapp				= taskKey[File]("complete build, returns the created directory")
	val webappOutputDir		= settingKey[File]("where to put the webapp's contents")
	
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
}

object WebAppPlugin extends AutoPlugin {
	val webappConfig		= config("webapp").hide
	
	//------------------------------------------------------------------------------
	//## exports
	
	override def requires:Plugins		= ClasspathPlugin
	
	override def trigger:PluginTrigger	= allRequirements
	
	lazy val autoImport	= Import
	import autoImport._
	
	override def projectConfigurations:Seq[Configuration]	=
			Vector(
				webappConfig
			)
	
	override def projectSettings:Seq[Def.Setting[_]]	=
			Vector(
				// Keys.ivyConfigurations	+= webappConfig,
				
				webapp	:=
						buildTask(
							streams		= Keys.streams.value,
							assets		= classpathAssets.value,
							processed	= webappProcess.value,
							outputDir	= webappOutputDir.value
						),
				webappOutputDir			:= Keys.crossTarget.value / "webapp" / "build",
						
				webappAssetDir			:= (Keys.sourceDirectory in Compile).value / "webapp",
				webappAssets			:= allPathMappingsIn(webappAssetDir.value),
				webappExtras			:= Seq.empty,
				webappStage				:= webappPrepare.value ++ webappAssets.value.toVector ++	webappExtras.value.toVector,
				webappPrepare	:=
						prepareTask(
							streams			= Keys.streams.value,
							dependencies	= webappDependencies.value,
							explodeDir		= webappExplodeDir.value
						),
				webappDependencies		:= Keys.update.value select configurationFilter(name = webappConfig.name),
				webappExplodeDir		:= Keys.crossTarget.value / "webapp" / "exploded",
			
				webappProcess			:= (webappProcessors.value foldLeft webappStage.value) { (inputs, processor) => processor(inputs) },
				webappPipeline			:= Vector.empty,
				webappProcessors		<<= chainTasks(webappPipeline),
				
				webappDeploy	:=
						deployTask(
							streams		= Keys.streams.value,
							built		= webapp.value,
							deployBase	= webappDeployBase.value,
							deployName	= webappDeployName.value
						),
				webappDeployBase		:= None,
				webappDeployName		:= Keys.name.value,
				
				Keys.watchSources		:= Keys.watchSources.value ++ (webappAssets.value map PathMapping.getFile)
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
			IO unzip (dependency, out, -JarManifestFilter)
			allPathMappingsIn(out)
		}
	}
	
	/** build webapp directory */
	private def buildTask(
		streams:TaskStreams,	
		assets:Seq[ClasspathAsset],
		processed:Seq[PathMapping],
		outputDir:File
	):File = {
		streams.log info s"copying processed resources to ${outputDir}"
		val resourcesToCopy	= processed map (PathMapping anchorTo outputDir)
		val resourcesCopied	= IO copy resourcesToCopy
		
		val libDir	= outputDir / "WEB-INF" / "lib"
		streams.log info s"copying webapp code libraries to ${libDir}"
		libDir.mkdirs()
		val libsToCopy	= assets map { _.flatPathMapping } map (PathMapping anchorTo libDir)
		val libsCopied	= IO copy libsToCopy
		
		streams.log info "cleaning up"
		// NOTE we should delete not-copied directories,
		// but at least for libs we don't have the copied ones
		val allFiles	= filesIn(outputDir).toSet
		val obsolete	= allFiles -- resourcesCopied -- libsCopied
		IO delete obsolete
		
		outputDir
	}
	
	/** copy-deploy webapp */
	private def deployTask(
		streams:TaskStreams,	
		built:File,
		deployBase:Option[File],
		deployName:String
	):Unit = {
		if (deployBase.isEmpty) {
			failWithError(streams, s"${webappDeployBase.key.label} must be initialized to deploy")
		}
		val deployBase1	= deployBase.get
		
		val webappDir	= deployBase1 / deployName
		val warFile		= deployBase1 / (deployName + ".war")
		
		streams.log info s"deleting old war file ${warFile}"
		IO delete warFile
		
		streams.log info s"deleting old webapp ${webappDir}"
		IO delete webappDir
		
		streams.log info s"deploying webapp to ${webappDir}"
		val webappFiles	= allPathMappingsIn(built) map (PathMapping anchorTo webappDir)
		IO copy webappFiles
	}
}
