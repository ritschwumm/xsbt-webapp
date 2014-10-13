package xsbtWebApp

import sbt._

import xsbtUtil.types._
import xsbtUtil.{ util => xu }

import xsbtWebApp.Import.WebAppProcessor

object WebAppProcessor {
	def selective(filter:FileFilter)(delegate:WebAppProcessor):WebAppProcessor	= 
			input => {
			val (accept, reject)	= input partition (xu.pathMapping.getFile andThen filter.accept)
				delegate(accept) ++ reject
			}
			
	def filtering(filter:FileFilter):WebAppProcessor	= 
			_ filter (xu.pathMapping.getFile andThen filter.accept)
		
	val empty:WebAppProcessor	=
			identity
}
