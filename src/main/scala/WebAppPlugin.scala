import sbt._

import Keys.{ Classpath, TaskStreams }
import Project.Initialize
import classpath.ClasspathUtilities

import ClasspathPlugin._
import WebAppUtil._

object WebAppPlugin extends Plugin {
	private val webappConfig	= config("webapp").hide
	
	//------------------------------------------------------------------------------
	//## exported
	
	val webapp				= taskKey[File]("complete build, returns the created directory")
	val webappResources		= settingKey[File]("webapp contents")
	val webappLibraries		= taskKey[Traversable[File]]("library dependencies to include")
	val webappExtras		= taskKey[Traversable[Pointed]]("additional resources as a task to allow inclusion of generated content")
	val webappOutput		= settingKey[File]("where to put the webapp's contents")
	
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
						deployTask(
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
	
	private def buildTask(
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
					IO unzip (library, output, -manifestFilter) 
				}
		streams.log debug s"extracted ${dependenciesCopied.size} files from webapp resource libraries"
		
		streams.log info s"copying webapp resources to ${output}"
		val resourcesToCopy	= allPointedIn(resources)
		val resourcesCopied	= copyToBase(resourcesToCopy, output)
		streams.log debug s"copied ${resourcesCopied.size} files from webapp resources"
		
		val libDir	= output / "WEB-INF" / "lib"
		streams.log info s"copying webapp code libraries to ${libDir}"
		libDir.mkdirs()
		val libsToCopy	= assets map { it => it.jar -> it.name }
		val libsCopied	= copyToBase(libsToCopy, libDir)
		streams.log debug s"copied ${libsToCopy.size} files from webapp code libraries"
		
		streams.log info s"copying webapp extras to ${output}"
		val extrasCopied	= copyToBase(extras, output)
		streams.log debug s"copied ${extrasCopied.size} of ${extras.size} files from webapp extras"
		
		streams.log info "cleaning up"
		// NOTE we should delete not-copied directories,
		// but at least for libs we don't have the copied ones
		val allFiles	= fileDescendants(output).get.toSet
		val obsolete	= allFiles -- resourcesCopied -- dependenciesCopied -- libsCopied -- extrasCopied
		IO delete obsolete
		streams.log debug s"cleaned up ${obsolete.size} obsolete files"
		
		output
	}
	
	private def deployTask(
		streams:TaskStreams,	
		built:File,
		deployBase:Option[File],
		deployName:String
	):Unit = {
		// TODO ugly
		require(deployBase.isDefined, "key webappDeployBase must be initialized to deploy")
		val deployBase1	= deployBase.get
		
		val webappDir	= deployBase1 / deployName
		val warFile		= deployBase1 / (deployName + ".war")
		
		streams.log info s"deleting old war file ${warFile}"
		IO delete warFile
		
		streams.log info s"deleting old webapp ${webappDir}"
		IO delete webappDir
		
		streams.log info s"deploying webapp to ${webappDir}"
		val webappFiles	= allPointedIn(built)
		copyToBase(webappFiles, webappDir)
	}
}
