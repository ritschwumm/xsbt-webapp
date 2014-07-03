import sbt._

import Keys.{ Classpath, TaskStreams }
import Project.Initialize
import classpath.ClasspathUtilities

import ClasspathPlugin._

object WebAppPlugin extends Plugin {
	private val webappConfig	= config("webapp").hide
	
	//------------------------------------------------------------------------------
	//## exported
	
	val webapp				= taskKey[File]("complete build, returns the created directory")
	val webappResources		= settingKey[File]("webapp contents")
	val webappLibraries		= taskKey[Traversable[File]]("library dependencies to include")
	val webappExtras		= taskKey[Traversable[(File,String)]]("additional resources as a task to allow inclusion of generated content")
	val webappOutput		= settingKey[File]("where to put the webapp's contents")
	
	val webappDeploy		= taskKey[Unit]("copy-deploy the webapp")
	val webappDeployBase	= settingKey[Option[File]]("target directory base for copy-deploy")
	val webappDeployName	= settingKey[String]("target directory name for copy-deploy")
	
	lazy val webappSettings:Seq[Def.Setting[_]]	= 
			classpathSettings ++
			Vector(
				Keys.ivyConfigurations	+= webappConfig,
				
				webapp	:=
						buildTaskImpl(
							streams		= Keys.streams.value,
							assets		= classpathAssets.value,
							resources	= webappResources.value,
							libraries	= webappLibraries.value,
							extras		= webappExtras.value,
							output		= webappOutput.value
						),
				webappResources			:= (Keys.sourceDirectory in Compile).value / "webapp",
				webappLibraries			:= Keys.update.value select configurationFilter(name = webappConfig.name),
				webappExtras			:= Seq.empty,
				webappOutput			:= Keys.crossTarget.value / "webapp",
				
				webappDeploy	:=
						deployTaskImpl(
							streams		= Keys.streams.value,
							built		= webapp.value,
							deployBase	= webappDeployBase.value,
							deployName	= webappDeployName.value
						),
				webappDeployBase		:= None,
				webappDeployName		:= Keys.name.value,
				
				Keys.watchSources		:= Keys.watchSources.value ++ webappResources.value.***.get
			)
	
	//------------------------------------------------------------------------------
	//## tasks
	
	private def buildTaskImpl(
		streams:TaskStreams,	
		assets:Seq[ClasspathAsset],
		resources:File,
		libraries:Traversable[File],
		extras:Traversable[(File,String)],
		output:File
	):File = {
		streams.log info s"extracting ${libraries.size} webapp resource libraries to ${output}"
		val dependenciesCopied	=
				libraries flatMap { library =>
					IO unzip (library, output, -(new ExactFilter("META-INF/MANIFEST.MF"))) 
				}
		streams.log debug s"extracted ${dependenciesCopied.size} files from webapp resource libraries"
		
		streams.log info s"copying webapp resources to ${output}"
		val resourcesToCopy	= resources.*** pair rebase(resources, output)
		val resourcesCopied	= IO copy resourcesToCopy
		streams.log debug s"copied ${resourcesCopied.size} files from webapp resources"
		
		val libDir	= output / "WEB-INF" / "lib"
		streams.log info s"copying webapp code libraries to ${libDir}"
		libDir.mkdirs()
		val libsToCopy	=
				for {
					asset	<- assets
					source	= asset.jar
					target	= libDir / asset.name
				}
				yield (source, target)
		val libsCopied	= IO copy libsToCopy
		streams.log debug s"copied ${libsToCopy.size} files from webapp code libraries"
		
		streams.log info s"copying webapp extras to ${output}"
		val extrasToCopy	= extras map { case (file,path) => (file, output / path) }
		val extrasCopied	= IO copy extrasToCopy
		streams.log debug s"copied ${extrasCopied.size} of ${extrasToCopy.size} files from webapp extras"
		
		streams.log info "cleaning up"
		val allFiles	= (output ** (-DirectoryFilter)).get.toSet
		val obsolete	= allFiles -- resourcesCopied -- dependenciesCopied -- libsCopied -- extrasCopied
		IO delete obsolete
		streams.log debug s"cleaned up ${obsolete.size} obsolete files"
		
		output
	}
	
	private def deployTaskImpl(
		streams:TaskStreams,	
		built:File,
		deployBase:Option[File],
		deployName:String
	):Unit = {
		// TODO ugly
		require(deployBase.isDefined, "key webapp-deploy-base must be initialized to deploy")
		val deployBase1	= deployBase.get
		
		val webappDir	= deployBase1 / deployName
		val warFile		= deployBase1 / (deployName + ".war")
		
		streams.log info s"deleting old war file ${warFile}"
		IO delete warFile
		
		streams.log info s"deleting old webapp ${webappDir}"
		IO delete webappDir
		
		val webappFiles	= built.*** pair rebase(built, webappDir)
		streams.log info s"deploying webapp to ${webappDir}"
		IO copy webappFiles
	}
}
