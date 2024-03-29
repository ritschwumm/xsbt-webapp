package xsbtWebApp

import scala.xml.{ Node => XmlNode, NodeSeq => XmlNodeSeq, _ }
import scala.xml.transform.{ RewriteRule, RuleTransformer }

import sbt._
import Keys.TaskStreams

import xsbtUtil.types._
import xsbtUtil.{ util => xu }

import xsbtClasspath.{ Asset => ClasspathAsset, ClasspathPlugin }
import xsbtClasspath.Import.classpathAssets

object Import {
	val webapp				= taskKey[File]("complete build, returns the created directory")
	val webappAppDir		= settingKey[File]("directory of the webapp to be built")

	val webappWar			= taskKey[File]("complete build, returns the created war file")
	val webappWarFile		= settingKey[File]("where to put the webapp's war file")

	val webappPackageName	= settingKey[String]("name of the package built")

	val webappStage			= taskKey[Seq[PathMapping]]("gathered webapp assets")
	val webappAssetDir		= settingKey[File]("directory with webapp contents")
	val webappAssets		= taskKey[Traversable[PathMapping]]("webapp contents")
	val webappExtras		= taskKey[Traversable[PathMapping]]("additional webapp contents+")

	val webappDeploy		= taskKey[Unit]("copy-deploy the webapp")
	val webappDeployBase	= settingKey[Option[File]]("target directory base for copy-deploy")
	val webappDeployName	= settingKey[String]("target directory name for copy-deploy")

	val webappBuildDir		= settingKey[File]("base directory of built files")
}

object WebAppPlugin extends AutoPlugin {
	//------------------------------------------------------------------------------
	//## exports

	lazy val autoImport	= Import
	import autoImport._

	override val requires:Plugins		= ClasspathPlugin && plugins.JvmPlugin

	override val trigger:PluginTrigger	= noTrigger

	override lazy val projectSettings:Seq[Def.Setting[_]]	=
		Vector(
			webapp	:=
				buildTask(
					streams	= Keys.streams.value,
					libs	= classpathAssets.value,
					assets	= webappStage.value,
					appDir	= webappAppDir.value
				),
			webappAppDir			:= webappBuildDir.value / "output" / webappPackageName.value,

			webappStage				:= webappAssets.value.toVector ++ webappExtras.value.toVector,
			webappAssetDir			:= (Compile / Keys.sourceDirectory).value / "webapp",
			webappAssets			:= xu.find allMapped webappAssetDir.value,
			webappExtras			:= Seq.empty,

			webappWar	:=
				warTask(
					streams		= Keys.streams.value,
					webapp		= webapp.value,
					warFile		= webappWarFile.value
				),
			webappWarFile			:= webappBuildDir.value / "output" / (webappPackageName.value + ".war"),

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

			Keys.watchSources		:= Keys.watchSources.value :+ WatchSource(webappAssetDir.value),

			// disable standard artifact, xsbt-webapp publishes webappWar
			Compile / Keys.packageBin / Keys.publishArtifact := false,

			// add war artifact
			Compile / webappWar / Keys.artifact ~= {
				_ withType "war" withExtension "war"
			},

			// remove dependencies and repositories from pom
			Keys.pomPostProcess		:= removeDependencies
		) ++
		addArtifact(Compile / webappWar / Keys.artifact, webappWar)

	//------------------------------------------------------------------------------
	//## pom transformation

	private def removeDependencies(node:XmlNode):XmlNode	=
			(new RuleTransformer(pomRewriteRule) transform node).head

	private val pomRewriteRule	=
		new RewriteRule {
			override def transform(node:XmlNode):XmlNodeSeq =
				node match {
					case el:Elem if el.label == "dependency" =>
						val organization	= childText(el, "groupId")
						val artifact		= childText(el, "artifactId")
						val version			= childText(el, "version")
						val scope			= childText(el, "scope")
						Comment(s"$organization#$artifact;$version ($scope)")
					case el:Elem if el.label == "repository" =>
						/*
						val id		= childText(el, "id")
						val name	= childText(el, "name")
						val url		= childText(el, "url")
						val layout	= childText(el, "layout")
						*/
						Comment(s"redacted")
					case _ =>
						node
				}
			private def childText(el:Elem, label:String):String	=
				el.child filter { _.label == label } flatMap { _.text } mkString ""
		}

	//------------------------------------------------------------------------------
	//## tasks

	/** build webapp directory */
	private def buildTask(
		streams:TaskStreams,
		libs:Seq[ClasspathAsset],
		assets:Seq[PathMapping],
		appDir:File
	):File = {
		streams.log info s"copying resources and libraries to ${appDir}"
		val libsToCopy	= libs map { _.flatPathMapping } map (xu.pathMapping modifyPath ("WEB-INF/lib/" + _))
		xu.file mirror (appDir, assets ++ libsToCopy)
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

		streams.log info s"deploying webapp to ${webappDir}"
		val webappFiles		= xu.find allMapped webapp
		xu.file mirror (webappDir, webappFiles)
	}
}
