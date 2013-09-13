sbtPlugin		:= true

name			:= "xsbt-webapp"

organization	:= "de.djini"

version			:= "0.2.0"

addSbtPlugin("de.djini" % "xsbt-classpath" % "0.5.0")

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
	"-feature"
)