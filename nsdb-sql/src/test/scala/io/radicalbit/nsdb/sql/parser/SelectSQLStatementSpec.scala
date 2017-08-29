package io.radicalbit.nsdb.sql.parser

import io.radicalbit.nsdb.common.statement._
import org.scalatest.{Matchers, WordSpec}

import scala.util.Success

class SelectSQLStatementSpec extends WordSpec with Matchers {

  private val parser = new SQLStatementParser

  "A parser instance" when {

    "receive a select projecting a wildcard" should {
      "parse it successfully" in {
        parser.parse(namespace = "registry", input = "SELECT * FROM people") should be(
          Success(SelectSQLStatement(namespace = "registry", metric = "people", fields = AllFields)))
      }
    }

    "receive a select projecting a single field" should {
      "parse it successfully" in {
        parser.parse(namespace = "registry", input = "SELECT name FROM people") should be(
          Success(
            SelectSQLStatement(namespace = "registry",
                               metric = "people",
                               fields = ListFields(List(Field("name", None)))))
        )
      }
    }

    "receive a select projecting a list of fields" should {
      "parse it successfully" in {
        parser.parse(namespace = "registry", input = "SELECT name,surname,creationDate FROM people") should be(
          Success(
            SelectSQLStatement(
              namespace = "registry",
              metric = "people",
              fields = ListFields(List(Field("name", None), Field("surname", None), Field("creationDate", None))))))
      }
    }

    "receive a select containing a range selection" should {
      "parse it successfully" in {
        parser.parse(namespace = "registry", input = "SELECT name FROM people WHERE timestamp IN (2,4)") should be(
          Success(SelectSQLStatement(
            namespace = "registry",
            metric = "people",
            fields = ListFields(List(Field("name", None))),
            condition = Some(Condition(RangeExpression(dimension = "timestamp", value1 = 2L, value2 = 4L)))
          )))
      }
    }

    "receive a select containing a GTE selection" should {
      "parse it successfully" in {
        parser.parse(namespace = "registry", input = "SELECT name FROM people WHERE timestamp >= 10") should be(
          Success(SelectSQLStatement(
            namespace = "registry",
            metric = "people",
            fields = ListFields(List(Field("name", None))),
            condition = Some(Condition(
              ComparisonExpression(dimension = "timestamp", comparison = GreaterOrEqualToOperator, value = 10L)))
          )))
      }
    }

    "receive a select containing a GT AND a LTE selection" should {
      "parse it successfully" in {
        parser.parse(namespace = "registry", input = "SELECT name FROM people WHERE timestamp > 2 AND timestamp <= 4") should be(
          Success(SelectSQLStatement(
            namespace = "registry",
            metric = "people",
            fields = ListFields(List(Field("name", None))),
            condition = Some(Condition(TupledLogicalExpression(
              expression1 = ComparisonExpression(dimension = "timestamp", comparison = GreaterThanOperator, value = 2L),
              operator = AndOperator,
              expression2 =
                ComparisonExpression(dimension = "timestamp", comparison = LessOrEqualToOperator, value = 4l)
            )))
          )))
      }
    }

    "receive a select containing a GTE OR a LT selection" should {
      "parse it successfully" in {
        parser.parse(namespace = "registry",
                     input = "SELECT name FROM people WHERE NOT timestamp >= 2 OR timestamp < 4") should be(
          Success(SelectSQLStatement(
            namespace = "registry",
            metric = "people",
            fields = ListFields(List(Field("name", None))),
            condition = Some(Condition(UnaryLogicalExpression(
              expression = TupledLogicalExpression(
                expression1 =
                  ComparisonExpression(dimension = "timestamp", comparison = GreaterOrEqualToOperator, value = 2L),
                operator = OrOperator,
                expression2 = ComparisonExpression(dimension = "timestamp", comparison = LessThanOperator, value = 4L)
              ),
              operator = NotOperator
            )))
          )))
      }
    }

    "receive a select containing a ordering statement" should {
      "parse it successfully" in {
        parser.parse(namespace = "registry", input = "SELECT * FROM people ORDER BY name") should be(
          Success(
            SelectSQLStatement(namespace = "registry",
                               metric = "people",
                               fields = AllFields,
                               order = Some(AscOrderOperator("name")))))
      }
    }

    "receive a select containing a limit statement" should {
      "parse it successfully" in {
        parser.parse(namespace = "registry", input = "SELECT * FROM people LIMIT 10") should be(
          Success(
            SelectSQLStatement(namespace = "registry",
                               metric = "people",
                               fields = AllFields,
                               limit = Some(LimitOperator(10)))))
      }
    }

    "receive a complex select containing a range selection a desc ordering statement and a limit statement" should {
      "parse it successfully" in {
        parser.parse(namespace = "registry",
                     input = "SELECT name FROM people WHERE timestamp IN (2,4) ORDER BY name DESC LIMIT 5") should be(
          Success(SelectSQLStatement(
            namespace = "registry",
            metric = "people",
            fields = ListFields(List(Field("name", None))),
            condition = Some(Condition(RangeExpression(dimension = "timestamp", value1 = 2, value2 = 4))),
            order = Some(DescOrderOperator(dimension = "name")),
            limit = Some(LimitOperator(5))
          )))
      }
      "parse it successfully ignoring case" in {
        parser.parse(namespace = "registry",
                     input = "sElect name FrOm people where timestamp in (2,4) Order bY name dEsc limit 5") should be(
          Success(SelectSQLStatement(
            namespace = "registry",
            metric = "people",
            fields = ListFields(List(Field("name", None))),
            condition = Some(Condition(RangeExpression(dimension = "timestamp", value1 = 2, value2 = 4))),
            order = Some(DescOrderOperator(dimension = "name")),
            limit = Some(LimitOperator(5))
          )))
      }
    }

    "receive random string sequences" should {
      "fail" in {
        parser.parse(namespace = "registry", input = "fkjdskjfdlsf") shouldBe 'failure
      }
    }

    "receive wrong fields" should {
      "fail" in {
        parser.parse(namespace = "registry", input = "SELECT name surname FROM people") shouldBe 'failure
        parser.parse(namespace = "registry", input = "SELECT name,surname age FROM people") shouldBe 'failure
      }
    }

    "receive a wrong metric without where clause" should {
      "fail" in {
        // FIXME: this must be a failure
        parser.parse(namespace = "registry", input = "SELECT name,surname FROM people cats dogs") shouldBe 'failure
      }
    }

    "receive a wrong metric with where clause" should {
      "fail" in {
        // FIXME: this must be a failure
        parser.parse(namespace = "registry", input = "SELECT name,surname FROM people cats dogs WHERE timestamp > 10") shouldBe 'failure
      }
    }
  }
}
