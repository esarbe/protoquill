package miniquill.quoter

import io.getquill.ast.Ast
import miniquill.parser._
import scala.quoted._
import scala.annotation.StaticAnnotation
import printer.AstPrinter
import derivation._
import scala.deriving._
import scala.quoted.matching.Const

class Query[+T] {
  def map[R](f: T => R): Query[R] = new Query[R]
  def foo(): String = "hello"
}

class EntityQuery[T] extends Query[T] // TODO can have a list of column renames?

case class Quoted[+T](val ast: Ast, lifts: Tuple) {
  //override def toString = ast.toString
  // make a function that uses a stateless transformer to walk through the tuple,
  // gather the lifted quoted blocks, splice their qutations into the ast, and then
  // add their lifted values into the parent tuple.... basically a runtime
  // flattening of the tree. This is the mechanism that will be used by the 'run' function
  // for dynamic queries
}

// TODO Rename to ScalarValueVase
// Scalar value Vase holds a scala asclar value's tree until it's parsed
case class ScalarValueVase[T](value: T, uid: String)
case class QuotationVase[+T](quoted: Quoted[T], uid: String) {
  def unquote: T =
    throw new RuntimeException("Unquotation can only be done from a quoted block.")
}


object QuoteDsl {



  // TODO Move to a LiftDsl trait
  //inline def extractLifts(input: Any): Tuple = ${ extractLiftsImpl('input) }


  
  def extractLifts(input: Expr[Any])(given qctx: QuoteContext): Expr[Tuple] = {
    import qctx.tasty.{Type => TType, _, given _}
    import scala.collection.mutable.ArrayBuffer
    import scala.quoted.util.ExprMap

    // println("++++++++++++++++ Input Tree ++++++++++++++++")
    // printer.ln(input.unseal)
    // printer.ln(input.unseal.showExtractors)
    val quotationParser = new miniquill.parser.QuotationParser
    import quotationParser._

    val buff: ArrayBuffer[(String, Expr[Any])] = ArrayBuffer.empty
    val accum = new ExprMap {
      def transform[T](expr: Expr[T])(given qctx: QuoteContext, tpe: quoted.Type[T]): Expr[T] = {

        expr match {
          // TODO block foldOver in this case?
          // NOTE that using this kind of pattern match, lifts are matched for both compile and run times
          // In compile times the entire tree of passed-in-quotations is matched including the 'lifts' 
          // (i.e. Quotation.lifts) tuples that are returned so we just get ScalarValueVase.apply
          // matched from those (as well as from the body of the passed-in-quotation but that's fine
          // since we dedupe by the UUID *). During runtime however, the actual case class instance
          // of ScalarValueTag is matched by the below term.

          // * That is to say if we have a passed-in-quotation Quoted(body: ... ScalarValueVase.apply, lifts: ..., (ScalarValueVase.apply ....))
          // both the ScalarValueVase in the body as well as the ones in the tuple would be matched. This is fine
          // since we dedupe the scalar value lifts by their UUID.

          // TODO Why can't this be parsed with *: operator?
          case '{ ScalarValueVase($tree, ${Const(uid)}) } => 
            //println("=================================== Match Scalar Vase ===========================")
            buff += ((uid, expr))
            expr // can't go inside here or errors happen

          // If the quotation is runtime, it needs to be matched so that we can add it to the tuple
          // of lifts (i.e. runtime values) and the later evaluate it during the 'run' function.
          // Match the vase and add it to the list.
          //println("=================================== Match Runtime Quotation ===========================")
          case MatchRuntimeQuotation(tree, uid) => // can't go inside here or errors happen
            buff += ((uid, tree))
            expr // can't go inside here or errors happen

          //println("=================================== Match Other ===========================")
          case other =>
            expr
            //transformChildren(expr)
        }
        // Need this or "did not conform to type: Nothing*" error can occur
        //transformChildren[T](expr)

        if (!expr.isInstanceOf[Expr[Expr[Any]]]) { // something tries to go to 2-levels of expression which causes crashes?
          transformChildren(expr)
        } else {
          expr
        }
      }
    }


    // val accum = new TreeAccumulator[ArrayBuffer[Term]] {
    //   def foldTree(terms: ArrayBuffer[Term], tree: Tree)(implicit ctx: Context) = tree match {
    //     case term: Term => {
    //       println("++++++++++++++++ Matched ++++++++++++++++")
    //       printer.ln(term)
    //       printer.ln(term.showExtractors)
    //       term.etaExpand.seal match {
    //         case '{ ScalarValueVase($tree, ${Const(uid)}) } => terms += term
    //         case other => foldOverTree(terms, tree)
    //       }
    //     }
    //     case other => 
    //       println("++++++++++++++++ Not Matched ++++++++++++++++")
    //       printer.ln(other)
    //       printer.ln(other.showExtractors)
    //       foldOverTree(terms, tree)
    //   }
    // }
    //val vases = accum.foldTree(ArrayBuffer.empty, input.unseal.underlyingArgument)

    accum.transform(input) // check if really need underlyingArgument

    val vasesTuple = 
      buff
        .distinctBy((value, uid) => uid) // dedupe by since everything with the same uuid is the same thing
        .map(_._2)
        .foldRight('{ (): Tuple })((elem, term) => '{ (${elem} *: ${term}) })

    //printer.ln("=========== Found Vases =========\n" + vasesTuple.unseal.underlyingArgument.show)

    vasesTuple
  }

  // or maybe implemet this with whitebox macros and a scalar value lift instead of with implicit conversions
  inline def lift[T](value: T): T = ${ liftImpl('value) }
  def liftImpl[T: Type](value: Expr[T])(given qctx: QuoteContext): Expr[T] = {
    val uuid = java.util.UUID.randomUUID().toString
    // Return the value of a created ScalarValueVase. The ScalarValueVase will be in the Scala AST
    // (i.e. and it will be parsed correctly)
    // with the generated UUID but the typing is correct since 'value' is returned.
    '{ ScalarValueVase[T]($value, ${Expr(uuid)}).value }
  }


  def parserFactory: (QuoteContext) => PartialFunction[Expr[_], Ast] = 
    (qctx: QuoteContext) => new Parser(given qctx)

  def lifterFactory: (QuoteContext) => PartialFunction[Ast, Expr[Ast]] =
    (qctx: QuoteContext) => new Lifter(given qctx)

  inline def query[T]: EntityQuery[T] = new EntityQuery

  inline def quote[T](inline bodyExpr: T): Quoted[T] = ${ quoteImpl[T]('bodyExpr) }

  def quoteImpl[T: Type](bodyExpr: Expr[T])(given qctx: QuoteContext): Expr[Quoted[T]] = {
    import qctx.tasty.{_, given _}
    val body = bodyExpr.unseal.underlyingArgument.seal

    // TODo add an error if body cannot be parsed
    val ast = parserFactory(qctx).apply(body)

    println("Ast Is: " + ast)

    // TODO Add an error if the lifting cannot be found
    val reifiedAst = lifterFactory(qctx)(ast)

    val lifts = extractLifts(body)

    '{       
      Quoted[T](${reifiedAst}, ${lifts})
    }
  }


  def runQuery[T](query: Quoted[Query[T]]): String = ???

  def run[T](query: Quoted[T]): String = {
    query.ast.toString
  }

  import scala.language.implicitConversions

  // TODO Or like this?
  //inline implicit def unquoteScalar[T](quoted: =>ScalarValueVase[T]): T = quoted.value
  // inline implicit def unquoteScalar[T](quoted: =>ScalarValueVase[T]): T = ${ unquoteScalarImpl('quoted) }
  // def unquoteScalarImpl[T: Type](quoted: Expr[ScalarValueVase[T]])(given qctx: QuoteContext): Expr[T] = {
  //   '{
  //     ${quoted}.value
  //   }
  // }

  // TODO Should also probably name a method for this so don't need to enable explicit conversion
  inline implicit def unquote[T](inline quoted: Quoted[T]): T = ${ unquoteImpl[T]('quoted) }
  def unquoteImpl[T: Type](quoted: Expr[Quoted[T]])(given qctx: QuoteContext): Expr[T] = {
    '{
      QuotationVase[T](${quoted}, ${Expr(java.util.UUID.randomUUID().toString)}).unquote
    }
  }

  def querySchema[T](entity: String): EntityQuery[T] = ???

}
