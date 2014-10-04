import sbt._

object WebAppUtil {
	val manifestFilter	= new ExactFilter("META-INF/MANIFEST.MF")
	
	type Pointed	= (File, String)
	type Cloned		= (File, File)
	
	// BETTER this is allSubpaths/selectSubpaths
	def allPointedIn(sourceDir:File):Traversable[Pointed]	=
			allDescendants(sourceDir) pair (Path relativeTo sourceDir)
		
	def allDescendants(sourceDir:File):PathFinder	=
			sourceDir.*** --- PathFinder(sourceDir)
	
	def fileDescendants(sourceDir:File):PathFinder	=
			(sourceDir ** -DirectoryFilter) --- PathFinder(sourceDir)
	
	def copyToBase(sources:Traversable[Pointed], base:File):Set[File]	=
			IO copy cloneToBase(sources, base)
		
	def cloneToBase(inputs:Traversable[Pointed], base:File):Traversable[Cloned]	=
			inputs map cloneTo(base)
	
	def cloneTo(base:File)(it:Pointed):Cloned	= 
			(it._1, base / it._2)
		
	// def flatPoint(file:File):(File,String)	=
			// file -> file.getName
}
