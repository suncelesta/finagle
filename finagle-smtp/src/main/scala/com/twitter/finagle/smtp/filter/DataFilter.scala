package com.twitter.finagle.smtp.filter

import com.twitter.finagle.{Service, SimpleFilter}
import com.twitter.finagle.smtp._
import com.twitter.finagle.smtp.reply.Reply
import com.twitter.util.Future
import scala.collection.immutable.IndexedSeq

/**
 * Duplicates dots in the beginning of each line of email body for transparency
 * (see [[http://tools.ietf.org/search/rfc5321#section-4.5.2]]) and adds a terminating
 * <CRLF>.<CRLF>
 */
object DataFilter extends SimpleFilter[Request, Reply] {
   override def apply(req: Request, send: Service[Request, Reply]): Future[Reply] = req match {
     case Request.TextData(lines) => {
       //duplicate leading dot
       val shieldedLines = for (line <- lines) yield if (line.head == '.') (".." + line.tail) else line
       //add dot at the end
       val lastLine = "."
       val body = shieldedLines ++ Seq(lastLine)
       send(Request.TextData(body))
     }

     case Request.MimeData(data) => {
       val cr = "\r".getBytes("US-ASCII")
       val lf = "\n".getBytes("US-ASCII")
       val dot = ".".getBytes("US-ASCII")

       val shieldedBytes: Array[Byte] = { for ( i <- data.content.indices drop 2 )
                           yield if (data.content(i-2) == cr && data.content(i-1) == lf && data.content(i) == dot) Seq(dot, dot)
                                 else data.content(i)
                         }.flatten.toArray[Byte]
       val shieldedMime = MimePart(shieldedBytes, data.headers)

       send(Request.MimeData(shieldedMime))
     }

     case other => send(other)
   }
 }
