package zio.flow.mock

import zio.flow.Operation
import zio.test.Assertion.anything

import zio._
import zio.flow.mock.MockedOperation.Match

// TODO: move to a separate published module to support testing user flows

trait MockedOperation { self =>
  def matchOperation[R, A](operation: Operation[R, A], input: R): (Option[Match[A]], MockedOperation)

  def andThen(other: MockedOperation): MockedOperation =
    MockedOperation.Then(self, other)
  def ++(other: MockedOperation): MockedOperation =
    andThen(other)

  def orElse(other: MockedOperation): MockedOperation =
    MockedOperation.Or(self, other)
  def |(other: MockedOperation): MockedOperation =
    orElse(other)

  def repeated(atMost: Int = Int.MaxValue): MockedOperation =
    MockedOperation.Repeated(self, atMost)
}

object MockedOperation {
  case class Match[A](result: A, delay: Duration)

  case object Empty extends MockedOperation {
    override def matchOperation[R, A](operation: Operation[R, A], input: R): (Option[Match[A]], MockedOperation) =
      (None, Empty)
  }
  final case class Http[R, A](
    urlMatcher: zio.test.Assertion[String] = anything,
    methodMatcher: zio.test.Assertion[String] = anything,
    headersMatcher: zio.test.Assertion[Map[String, String]] = anything,
    inputMatcher: zio.test.Assertion[R] = anything,
    result: () => A,
    duration: Duration = Duration.Zero
  ) extends MockedOperation {
    override def matchOperation[R1, A1](operation: Operation[R1, A1], input: R1): (Option[Match[A1]], MockedOperation) =
      operation match {
        case Operation.Http(url, api) =>
          // TODO: check R1 and A1 types too
          // TODO: check headers as well
          val m =
            urlMatcher.run(url) && methodMatcher.run(api.method.toString()) && inputMatcher.run(
              input.asInstanceOf[R]
            )
          if (m.isSuccess) {
            (Some(Match(result().asInstanceOf[A1], duration)), Empty)
          } else {
            (None, this)
          }
      }
  }

  final case class Then(first: MockedOperation, second: MockedOperation) extends MockedOperation {
    override def matchOperation[R, A](operation: Operation[R, A], input: R): (Option[Match[A]], MockedOperation) =
      first.matchOperation(operation, input) match {
        case (result, firstRemaining) =>
          (result, Then(firstRemaining, second).normalize)
      }

    def normalize: MockedOperation =
      this match {
        case Then(Empty, second) => second
        case Then(first, Empty)  => first
        case _                   => this
      }
  }

  final case class Or(left: MockedOperation, right: MockedOperation) extends MockedOperation {
    override def matchOperation[R, A](operation: Operation[R, A], input: R): (Option[Match[A]], MockedOperation) =
      left.matchOperation(operation, input) match {
        case (None, leftRemaining) =>
          right.matchOperation(operation, input) match {
            case (result, rightRemaining) =>
              (result, Or(leftRemaining, rightRemaining).normalize)
          }
        case (result, firstRemaining) =>
          (result, Or(firstRemaining, right).normalize)
      }

    def normalize: MockedOperation =
      this match {
        case Or(Empty, right) => right
        case Or(left, Empty)  => left
        case _                => this
      }
  }

  final case class Repeated(mock: MockedOperation, atMost: Int) extends MockedOperation {
    override def matchOperation[R, A](operation: Operation[R, A], input: R): (Option[Match[A]], MockedOperation) =
      mock.matchOperation(operation, input) match {
        case (result, _) =>
          if (atMost > 1)
            (result, Repeated(mock, atMost - 1))
          else
            (result, mock)
      }
  }
}
