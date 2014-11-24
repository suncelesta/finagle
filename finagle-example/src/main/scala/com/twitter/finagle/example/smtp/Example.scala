package com.twitter.finagle.example.smtp

import com.twitter.finagle.Smtp
import com.twitter.finagle.smtp._
import com.twitter.logging.Logger
import com.twitter.util.{Await, Future}

/**
 * Simple SMTP client with an example of error handling
 */
object Example {
  private val log = Logger.get(getClass.getName)

  def main(args: Array[String]): Unit = {
    // Raw text email
    val email = DefaultEmail()
      .from_("from@from.com")
      .to_("first@to.com", "second@to.com")
      .subject_("test")
      .text("first line", "second line") //body is a sequence of lines

    // Connect to a local SMTP server
    val send = Smtp.client.simple.newService("localhost:2525")

    // Send email
    val res: Future[Unit] = send(email) onFailure {
    // An error group
    case ex: SyntaxErrorReply => log.error("Syntax error: %s", ex.info)

    // A concrete reply
    case ProcessingError(info) => log.error("Error processing request: %s", info)
    }

    println("Sending email...") //this will be printed before the future returns

    //blocking just for test purposes
    Await.ready(res)
    println("Sent")
  }
}
