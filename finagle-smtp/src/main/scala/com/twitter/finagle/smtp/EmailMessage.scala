package com.twitter.finagle.smtp

import java.text.SimpleDateFormat
import java.util.{Calendar, Date}

import com.twitter.util.{Try, TimeFormat, Time}

/** Defines email address */
private[smtp] class MailingAddress(val local: String, val domain: String) {
  val mailbox: String = {
    if (isEmpty) ""
    else local + "@" + domain
  }
  val isEmpty: Boolean = local.isEmpty && domain.isEmpty
  val nonEmpty: Boolean = !isEmpty
}

/** Factory for mailing addresses. */
object MailingAddress {

  /**
   * Checks if address is syntactically correct according to
   * [[http://tools.ietf.org/search/rfc5321#section-4.1.2]] (for example, user@domain.org)
   */
  def correct(address: String): Boolean = Try {
    val Array(local, domain) = address split "@"
    require(local.nonEmpty)
    require(domain.nonEmpty)
  }.isReturn

  def correct(addrs: Seq[String]): Boolean = addrs.map(MailingAddress.correct(_)).contains(false)

  /**
   * Creates a [[com.twitter.finagle.smtp.MailingAddress]] from the given string.
   * The given string representation of a mailbox should be syntactically correct
   * (according to [[http://tools.ietf.org/search/rfc5321#section-4.1.2]], for
   * example: user@domain.org). If it is not, an IllegalArgumentException is thrown.
   *
   * @param address String representation of a mailbox
   */
  def apply(address: String): MailingAddress = {
    if (MailingAddress.correct(address)) {
      val Array(local, domain) = address split "@"
      new MailingAddress(local, domain)
    }
    else throw new IllegalArgumentException("Incorrect mailbox syntax: %s" format address)
  }

  /** An empty mailing address */
  val empty: MailingAddress = new MailingAddress("","")

  /**
   * Creates a string representation of given list of mailboxes.
   *
   * @param addrs The addresses in the list
   * @return String containing string representation of given
   *         addresses divided with a comma.
   */
  def mailboxList(addrs: Seq[MailingAddress]): String = {
    val mailboxes = addrs collect {
      case addr: MailingAddress if addr.nonEmpty => addr.mailbox
    }
    mailboxes mkString ","
  }
}

/**
 * Defines fields and body of an email message.
 * */
trait EmailMessage {
  def getFrom: Seq[MailingAddress]
  def getSender: MailingAddress
  def getTo: Seq[MailingAddress]
  def getCc: Seq[MailingAddress]
  def getBcc: Seq[MailingAddress]
  def getReplyTo: Seq[MailingAddress]
  def getDate: Date
  def getSubject: String
  def getBody: Mime
}

object EmailMessage {
  val DateFormat: TimeFormat = new TimeFormat("EE, dd MMM yyyy HH:mm:ss ZZ")
}
