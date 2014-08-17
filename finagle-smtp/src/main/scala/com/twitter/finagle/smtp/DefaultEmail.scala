package com.twitter.finagle.smtp

import java.nio.charset.Charset

import com.twitter.util.Time

/**
 * Constructs an [[com.twitter.finagle.smtp.EmailMessage]].
 */
case class DefaultEmail(
  override val from: Seq[MailingAddress] = Seq.empty,
  override val to: Seq[MailingAddress] = Seq.empty,
  override val cc: Seq[MailingAddress] = Seq.empty,
  override val bcc: Seq[MailingAddress] = Seq.empty,
  override val replyTo: Seq[MailingAddress] = Seq.empty,
  override val date: Time = Time.now,
  override val subject: String = "",
  whoSent: Option[MailingAddress] = None,
  body : Mime = MimePart.empty
  ) extends EmailMessage {

  /**
   * Adds originator addresses, which will appear in the ''From:'' field.
   * These addresses are validated when converted to
   * [[com.twitter.finagle.smtp.MailingAddress]]
   *
   * @param addrs Addresses to be added
   */
  def from_(addrs: String*): DefaultEmail = setFrom(from ++ addrs.map(MailingAddress(_)))

  /**
   * Set the ''From:'' field to contain given originator addresses.
   *
   * @param addrs Addresses to be set
   */
  def setFrom(addrs: Seq[MailingAddress]): DefaultEmail = copy(from = addrs)

  /**
   * Sets the ''Sender:'' field to contain given mailing address.
   * Sender should be specified if there are multiple
   * originator addresses.
   * If the given address is not included in ''From:'' field,
   * it is added there.
   * This address is validated when converted to
   * [[com.twitter.finagle.smtp.MailingAddress]]
   *
   * @param addr Address to be set
   */
  def sender_(addr: String): DefaultEmail = {
    val mb = MailingAddress(addr)
    if(from contains mb) copy(whoSent = Some(mb))
    else copy(whoSent = Some(mb), from = from :+ mb)
  }

  /**
   * Gets the sender of this message
   */
  override def sender = whoSent getOrElse (from.headOption getOrElse MailingAddress.empty)

  /**
   * Adds recipient addresses, which will appear in the ''To:'' field.
   * These addresses are validated when converted to
   * [[com.twitter.finagle.smtp.MailingAddress]]
   *
   * @param addrs Addresses to be added
   */
  def to_(addrs: String*): DefaultEmail = setTo(to ++ addrs.map(MailingAddress(_)))

  /**
   * Set the ''To:'' field to contain given recipient addresses.
   *
   * @param addrs Addresses to be set
   */
  def setTo(addrs: Seq[MailingAddress]): DefaultEmail = copy(to = addrs)

  /**
   * Adds carbon copy addresses, which will appear in the ''Cc:'' field.
   * These addresses are validated when converted to
   * [[com.twitter.finagle.smtp.MailingAddress]]
   *
   * @param addrs Addresses to be added
   */
  def cc_(addrs: String*): DefaultEmail = setCc(cc ++ addrs.map(MailingAddress(_)))

  /**
   * Set the ''Cc:'' field to contain given carbon copy addresses.
   *
   * @param addrs Addresses to be set
   */
  def setCc(addrs: Seq[MailingAddress]): DefaultEmail = copy(cc = addrs)

  /**
   * Adds blind carbon copy addresses, which will appear in the ''Bcc:'' field.
   * These addresses are validated when converted to
   * [[com.twitter.finagle.smtp.MailingAddress]]
   *
   * @param addrs Addresses to be added
   */
  def bcc_(addrs: String*): DefaultEmail = setBcc(bcc ++ addrs.map(MailingAddress(_)))

  /**
   * Set the ''Bcc:'' field to contain given blind carbon copy addresses.
   *
   * @param addrs Addresses to be set
   */
  def setBcc(addrs: Seq[MailingAddress]): DefaultEmail = copy(bcc = addrs)

  /**
   * Adds the addresses to reply to, which will appear in the ''Reply-To:'' field.
   * These addresses are validated when converted to
   * [[com.twitter.finagle.smtp.MailingAddress]]
   *
   * @param addrs Addresses to be added
   */
  def replyTo_(addrs: String*): DefaultEmail = setReplyTo(replyTo ++ addrs.map(MailingAddress(_)))

  /**
   * Set the ''Reply-To:'' field to contain given addresses to reply to.
   *
   * @param addrs Addresses to be set
   */
  def setReplyTo(addrs: Seq[MailingAddress]): DefaultEmail = copy(replyTo = addrs)

  /**
   * Set the date of sending the message.
   *
   * @param dt Date to be set
   */
  def date_(dt: Time): DefaultEmail = copy(date = dt)

  /**
   * Set the subject of the message.
   *
   * @param sbj Subject to be set
   */
  def subject_(sbj: String): DefaultEmail = copy(subject = sbj)

  /*Add part to the body*/
  def addBodyPart(part: MimePart): DefaultEmail = body match {
    case MimePart.empty => setBody(MimeMultipart.wrap(part))
    case multipart: MimeMultipart => setBody(multipart + part)
    case singlepart: MimePart => setBody(MimeMultipart.wrap(singlepart) + part)
  }
  /*Set body to bdy*/
  def setBody(bdy: Mime): DefaultEmail = copy(body = bdy)
  /*Set body to plain text*/
  def text(t: String, encName: String): DefaultEmail = copy(body = Mime.plainText(t, Charset.forName(encName)))
  def text(t: String, enc: Charset): DefaultEmail = copy(body = Mime.plainText(t, enc))
  def text(t: String): DefaultEmail = copy(body = Mime.plainText(t))
  /*Attach a file*/
  def attach(path: String): DefaultEmail = {
    val filename = path.split("/").last
    addBodyPart(Mime.fromFile(path).setContentDisposition(ContentDisposition.attachment(filename)))
  }

  /**
   * Instantiate an [[com.twitter.finagle.smtp.EmailMessage]] from the payload.
   * If the date of sending the message is not set,
   * current date is used. If sender of the message
   * is not specified, the first address in ''From:'' is used.
   */
  def headers: Seq[(String, String)] = {
      from.map(ad => ("From", ad.mailbox)) ++ {
        if (from.length > 1)
          Seq("Sender" -> {
            if (!sender.isEmpty) sender.mailbox
            else from.head.mailbox
          })
        else Seq.empty
      }++
      to.map(ad => ("To", ad.mailbox)) ++
      cc.map(ad => ("Cc", ad.mailbox)) ++
      bcc.map(ad => ("Bcc", ad.mailbox)) ++
      replyTo.map(ad => ("Reply-To", ad.mailbox)) ++
      Seq(
        "Date"     -> EmailMessage.DateFormat.format(date),
        "Subject"  -> subject
      )
  }

}

/**
 * Factory for [[com.twitter.finagle.smtp.DefaultEmail]] instances
 */
object DefaultEmail {

  /**
   * Creates an empty [[com.twitter.finagle.smtp.DefaultEmail]].
   * This method is for Java compatibility.
   */
  def create(): DefaultEmail = DefaultEmail()

  /**
   * Creates an [[com.twitter.finagle.smtp.DefaultEmail]] with payload from given
   * [[com.twitter.finagle.smtp.EmailMessage]].
   *
   * @param msg The message to copy payload from
   */
  def apply(msg: EmailMessage): DefaultEmail = DefaultEmail(
      from = msg.from,
      whoSent = if (msg.sender.isEmpty) None else Some(msg.sender),
      to = msg.to,
      cc = msg.cc,
      bcc = msg.bcc,
      replyTo = msg.replyTo,
      date = msg.date,
      subject = msg.subject,
      body = msg.body
    )
}
