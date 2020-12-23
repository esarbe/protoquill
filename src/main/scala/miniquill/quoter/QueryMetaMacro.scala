package miniquill.quoter

import scala.quoted._
import miniquill.parser.ParserFactory
import io.getquill.Query

object QueryMetaMacro {
  def embed[T: Type, R: Type, P <: ParserFactory: Type](qm: Expr[MetaDsl[P]], expand: Expr[Quoted[Query[T] => Query[R]]], extract: Expr[R => T])(using Quotes): Expr[QueryMeta[T, R]] = {
    val uuid = Expr(java.util.UUID.randomUUID().toString)
    '{ QueryMeta[T, R]($expand, $uuid, $extract) }
  }
}