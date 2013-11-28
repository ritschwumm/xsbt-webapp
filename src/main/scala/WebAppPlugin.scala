import sbt._

import Keys.{ Classpath, TaskStreams }
import Project.Initialize
import classpath.ClasspathUtilities

import ClasspathPlugin._

object WebAppPlugin extends Plugin {
	private val configName		= "webapp"
	private val webappConfig	= config(configName).hide
	private def webappDependencyFilter	= new DependencyFilter {
		def apply(configuration:String, module:ModuleID, artifact:Artifact):Boolean	=
				configuration == configName	&&
				// TODO check why we have scala-library.jar in the webapp configuration
				artifact.configurations.nonEmpty
	}
	
	//------------------------------------------------------------------------------
	//## exported
	
	/*
	// add webapp code library
	libraryDependencies	++= 
			Seq(
				"com.test"	% "foobar"	% "0.1.0"	% "webapp"
			)
	
	// use standard tomcat installation
	webappDeployBase	:=
			Option(System getenv "CATALINA_HOME") map file map { _ / "webapps" } filter { _.exists } getOrElse (
				sys error "$CATALINA_HOME is not set or $CATALINA_HOME/webapps does not exist"
			)
	*/
	
	/** complete build, returns the created directory */
	val webappBuild				= TaskKey[File]("webapp")
	/** webapp contents */
	val webappResources			= SettingKey[File]("webapp-resources")
	/** library dependencies to include. */
	val webappLibraries			= TaskKey[Traversable[File]]("webapp-libraries")
	/** additional resources as a task to allow inclusion of generated content. */
	val webappExtras			= TaskKey[Traversable[(File,String)]]("webapp-extras")
	/** where to put the webapp's contents */
	val webappOutput			= SettingKey[File]("webapp-output")
	
	/** copy-deploy the webapp */
	val webappDeploy			= TaskKey[Unit]("webapp-deploy")
	/** dirname for copy-deploy */
	val webappDeployBase		= SettingKey[Option[File]]("webapp-deploy-base")
	/** basename for copy-deploy */
	val webappDeployName		= SettingKey[String]("webapp-deploy-name")
	
	lazy val webappSettings:Seq[Def.Setting[_]]	= 
			classpathSettings ++
			Vector(
				webappBuild				<<= buildTask,
				webappResources			<<= (Keys.sourceDirectory in Compile) { _ / "webapp" },
				//webappLibraries		<<= Keys.update map { _ select configurationFilter("webapp") },
				webappLibraries			<<= Keys.update map { _ matching webappDependencyFilter },
				webappExtras			:= Seq.empty,
				webappOutput			<<= Keys.crossTarget { _ / "webapp" },
				
				webappDeploy			<<= deployTask,
				webappDeployBase		:= null,
				webappDeployName		<<= Keys.name,
				
				Keys.ivyConfigurations	+= webappConfig,
				Keys.watchSources		<<= (Keys.watchSources, webappResources) map { 
					(watchSources, webappResources) => {
						val resourceFiles	= webappResources.***.get
						watchSources ++ resourceFiles
					}
				}
			)
	
	//------------------------------------------------------------------------------
	//## tasks
	
	private def buildTask:Def.Initialize[Task[File]] = (
		Keys.streams,
		classpathAssets,
		webappResources,
		webappLibraries,
		webappExtras,
		webappOutput
	) map buildTaskImpl
	
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
		val resourcesToCopy	= resources.*** x rebase(resources, output)
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
	
	private def deployTask:Def.Initialize[Task[Unit]] = (
		Keys.streams,
		webappBuild,
		webappDeployBase,
		webappDeployName
	) map deployTaskImpl
	
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
		
		val webappFiles	= built.*** x rebase(built, webappDir)
		streams.log info s"deploying webapp to ${webappDir}"
		IO copy webappFiles
	}
}
