package common.validation

import java.net.URL

import cats.data.NonEmptyList
import cats.data.Validated.{Invalid, Valid}
import cats.syntax.validated._
import com.typesafe.config.ConfigFactory
import common.exception.AggregatedMessageException
import common.validation.Validation._
import net.ceedubs.ficus.Ficus._
import org.scalatest.{FlatSpec, Matchers}
import org.slf4j.Logger
import org.specs2.mock.Mockito

import scala.util.{Failure, Success}

class ValidationSpec extends FlatSpec with Matchers with Mockito {

  behavior of "Validation"

  it should "warn unrecognized keys" in {
    var warnings = List.empty[Any]
    val mockLogger = mock[Logger]
    mockLogger.warn(anyString).answers(warnings :+= _)
    val keys = Set("hello")
    val reference = Set("world")
    val context = "warnings"
    warnNotRecognized(keys, reference, context, mockLogger)
    warnings should contain theSameElementsAs List("Unrecognized configuration key(s) for warnings: hello")
  }

  it should "not warn recognized keys" in {
    var warnings = List.empty[Any]
    val mockLogger = mock[Logger]
    mockLogger.warn(anyString).answers(warnings :+= _)
    val keys = Set("hello")
    val reference = Set("hello", "world")
    val context = "warnings"
    warnNotRecognized(keys, reference, context, mockLogger)
    warnings should be(empty)
  }

  it should "read config URLs with urlReader" in {
    val config = ConfigFactory.parseString("""url: "http://hello/world"""")
    val url = config.as[URL]("url")
    url.getProtocol should be ("http")
    url.getHost should be("hello")
    url.getPath should be("/world")
  }

  it should "succeed to validate a valid value" in {
    val result = validate("hello")
    result should be("hello".valid)
  }

  it should "fail to validate an invalid value" in {
    val result = validate(throw new RuntimeException("fail"))
    result should be("fail".invalidNel)
  }

  it should "convert a Try to an ErrorOr" in {
    val success = Success("yeah")
    val failure = Failure(new Exception(":("))
    import common.validation.Validation._
    success.toErrorOr shouldBe Valid("yeah")
    failure.toErrorOr shouldBe Invalid(NonEmptyList.of(":("))
  }

  it should "convert a Try to a Checked" in {
    val success = Success("yeah")
    val failure = Failure(new Exception(":("))
    import common.validation.Validation._
    success.toChecked shouldBe Right("yeah")
    failure.toChecked shouldBe Left(NonEmptyList.of(":("))
  }

  it should "convert an ErrorOr to a Try" in {
    val valid = "yeah".valid
    val invalid = ":(".invalidNel
    import common.validation.Validation._
    valid.toTry should be(Success("yeah"))
    val exception = intercept[AggregatedMessageException](invalid.toTry.get)
    exception.exceptionContext should be("Error(s)")
    exception.errorMessages should contain theSameElementsAs List(":(")
  }

}
