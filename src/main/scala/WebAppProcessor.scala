package xsbtWebApp

import sbt._

import xsbtUtil._
import xsbtWebApp.Import.WebAppProcessor

object WebAppProcessor {
	def selective(filter:FileFilter)(delegate:WebAppProcessor):WebAppProcessor	= 
			input => {
			val (accept, reject)	= input partition (PathMapping.getFile andThen filter.accept)
				delegate(accept) ++ reject
			}
			
	def filtering(filter:FileFilter):WebAppProcessor	= 
			_ filter (PathMapping.getFile andThen filter.accept)
		
	val empty:WebAppProcessor	=
			identity
}
