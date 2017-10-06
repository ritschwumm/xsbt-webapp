sbtPlugin		:= true

name			:= "xsbt-webapp"
organization	:= "de.djini"
version			:= "1.15.0"

scalacOptions	++= Seq(
	"-deprecation",
	"-unchecked",
	// "-language:implicitConversions",
	// "-language:existentials",
	// "-language:higherKinds",
	// "-language:reflectiveCalls",
	// "-language:dynamics",
	// "-language:postfixOps",
	// "-language:experimental.macros"
	"-feature",
	"-Xfatal-warnings"
)

conflictManager	:= ConflictManager.strict
addSbtPlugin("de.djini" % "xsbt-util"		% "0.9.0")
addSbtPlugin("de.djini" % "xsbt-classpath"	% "1.10.0")
