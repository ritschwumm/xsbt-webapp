import xsbtUtil.types._

package object xsbtWebApp {
	type WebAppProcessor	= Seq[PathMapping]=>Seq[PathMapping]
}
