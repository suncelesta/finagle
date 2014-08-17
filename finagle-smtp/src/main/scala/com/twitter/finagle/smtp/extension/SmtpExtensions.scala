package com.twitter.finagle.smtp.extension

case class SmtpExtensions(supported: Extension*) {
  def lines(): Seq[String] = for {
      Extension(keyword, params) <- supported
    } yield "%s %s".format(keyword, params mkString " ")
}

object SmtpExtensions {
  val EIGHTBITMIME = "8BITMIME"
  val SIZE         = "SIZE"
  val CHUNKING     = "CHUNKING"
  val BINARYMIME   = "BINARYMIME"
  val PIPELINING   = "PIPELINING"
  val AUTH         = "AUTH"
  val EXPN         = "EXPN"
}

case class Extension(keyword: String, params: Seq[String] = Seq.empty)