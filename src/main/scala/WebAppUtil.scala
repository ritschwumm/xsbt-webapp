import sbt._

import xsbtUtil._

import WebAppPlugin.WebAppProcessor

object WebAppUtil {
	def selectiveProcessor(filter:FileFilter)(delegate:WebAppProcessor):WebAppProcessor	= 
			input => {
			val (accept, reject)	= input partition (PathMapping.getFile andThen filter.accept)
				delegate(accept) ++ reject
			}
			
	def filterProcessor(filter:FileFilter):WebAppProcessor	= 
			_ filter (PathMapping.getFile andThen filter.accept)
		
	//------------------------------------------------------------------------------
		
	def concat(files:Seq[File], to:File) {
		val string	= files map { IO read (_, IO.utf8) } mkString "\n"
		IO write (to, string,	IO.utf8)
	}
}
