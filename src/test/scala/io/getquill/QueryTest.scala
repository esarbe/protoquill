package io.getquill

import scala.language.implicitConversions
import miniquill.quoter.Dsl._
import miniquill.quoter.Quoted
import miniquill.quoter._
import io.getquill._
import io.getquill.ast._
import miniquill.quoter.QuotationLot
import miniquill.quoter.QuotationVase
import io.getquill.context.ExecutionType
import org.scalatest._

class QueryTest {

  case class Person(name: String, age: Int)
  val ctx = new MirrorContext(MirrorIdiom, Literal)

  def peopleRuntime = quote {
    query[Person]
  }
  
  def main(args: Array[String]): Unit = {
    println( ctx.runAndTest(  peopleRuntime.map(p => p.name)) ) //helloooooooooooooooooooooooooooo
  }

}