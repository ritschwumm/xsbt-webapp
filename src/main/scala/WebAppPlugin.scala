import sbt._

import Keys.{ Classpath, TaskStreams }
import Project.Initialize
import classpath.ClasspathUtilities

import ClasspathPlugin._
import WebAppUtil._
import xsbtUtil._

object WebAppPlugin extends Plugin {
	private val webappConfig	= config("webapp").hide
	
	type WebAppProcessor		= Seq[PathMapping]=>Seq[PathMapping]
	
	//------------------------------------------------------------------------------
	//## exported
	
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
	
	lazy val webappSettings:Seq[Def.Setting[_]]	= 
			classpathSettings ++
			Vector(
				Keys.ivyConfigurations	+= webappConfig,
				
				webapp	:=
						buildTask(
							streams		= Keys.streams.value,
							assets		= classpathAssets.value,
							processed	= webappProcess.value,
							outputDir	= webappOutputDir.value
						),
				webappOutputDir			:= Keys.crossTarget.value / "webapp" / "build",
						
				webappAssetDir			:= (Keys.sourceDirectory in Compile).value / "webapp",
				webappAssets			:=
						assetsTask(
							streams		= Keys.streams.value,
							assetDir	= webappAssetDir.value
						),
				
				webappExtras			:= Seq.empty,
				webappStage	:=
						stageTask(
							streams		= Keys.streams.value,
							assets		= webappAssets.value,
							prepared	= webappPrepare.value,
							extras		= webappExtras.value
						),
			
				webappPrepare	:=
						prepareTask(
							streams			= Keys.streams.value,
							dependencies	= webappDependencies.value,
							explodeDir		= webappExplodeDir.value
						),
				webappDependencies		:= Keys.update.value select configurationFilter(name = webappConfig.name),
				webappExplodeDir		:= Keys.crossTarget.value / "webapp" / "exploded",
			
				webappProcess	:=
						processTask(
							streams		= Keys.streams.value,
							staged		= webappStage.value,
							processors	= webappProcessors.value
						),
				webappPipeline			:= Vector.empty,
				webappProcessors		<<= pipelineTask(webappPipeline),
				
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
	
	/** find assets for processing */
	private def assetsTask(
		streams:TaskStreams,
		assetDir:File
	):Traversable[PathMapping]	= {
		allPathMappingsIn(assetDir)
	}
						
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
	
	/** stage all inputs for processing */
	private def stageTask(
		streams:TaskStreams,
		assets:Traversable[PathMapping],
		prepared:Seq[PathMapping],
		extras:Traversable[PathMapping]
	):Seq[PathMapping]	= {
		streams.log info s"staging assets, libraries and extras"
		prepared		++
		assets.toVector	++
		extras.toVector
	}
	
	/** merge pipeline into a simple processor */
	private def pipelineTask(
		processorsKey:SettingKey[Seq[TaskKey[WebAppProcessor]]]
	):Def.Initialize[Task[Seq[WebAppProcessor]]]	=
			chainTasks(processorsKey)
	
	/** process inputs */
	private def processTask(
		streams:TaskStreams,
		staged:Seq[PathMapping],
		processors:Seq[WebAppProcessor]
	):Seq[PathMapping]	= {
		(processors foldLeft staged) { (inputs, processor) =>
			processor(inputs) 
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
