package nl.vroste

import java.time.ZoneId
import java.util.TimeZone

import com.spotify.scio._
import com.spotify.scio.extra.csv._
import com.spotify.scio.values.{SCollection, WindowOptions}
import kantan.csv._
import org.apache.beam.sdk.transforms.windowing.TimestampCombiner
import org.joda.time
import org.joda.time.{DateTimeZone, Instant, LocalDate, LocalTime}

import scala.concurrent.duration._

/*
sbt "runMain [PACKAGE].WordCount
  --project=[PROJECT] --runner=DataflowRunner --zone=[ZONE]
  --input=gs://dataflow-samples/shakespeare/kinglear.txt
  --output=gs://[BUCKET]/[PATH]/wordcount"
 */

object Model {
  sealed trait DataType
  case object ZiekenhuisOpname extends DataType
  case object Totaal extends DataType
  case object Overleden extends DataType

  case class RivmDataRow(
      datum: String,
      gemeenteCode: Int,
      gemeenteNaam: String,
      provincieNaam: String,
      provincieCode: Int,
      `type`: DataType,
      aantal: Option[Int],
      aantalCumulatief: Option[Int]
  )
  case class GemeenteData(
      datum: String,
      gemeenteCode: Int,
      gemeenteNaam: String,
      provincieNaam: String,
      provincieCode: Int,
      ziekenhuisOpnames: Int,
      totaal: Int,
      overleden: Int
  ) {
    def divideBy(nr: Int): GemeenteData =
      copy(
        ziekenhuisOpnames = (ziekenhuisOpnames * 1.0 / nr).toInt,
        totaal = (totaal * 1.0 / nr).toInt,
        overleden = (overleden * 1.0 / nr).toInt
      )
  }

  object GemeenteData {
    def fromRow(r: RivmDataRow): GemeenteData = {
      val (ziekenhuisOpnames, totaal, overleden) = (r.`type`, r.aantal) match {
        case (ZiekenhuisOpname, Some(aantal)) => (aantal, 0, 0)
        case (Totaal, Some(aantal))           => (0, aantal, 0)
        case (Overleden, Some(aantal))        => (0, 0, aantal)
        case _                                => (0, 0, 0)
      }

      GemeenteData(
        r.datum,
        r.gemeenteCode,
        r.gemeenteNaam,
        r.provincieNaam,
        r.provincieCode,
        ziekenhuisOpnames,
        totaal,
        overleden
      )
    }

    def add(d1: GemeenteData, d2: GemeenteData): GemeenteData =
      d1.copy(
        ziekenhuisOpnames = d1.ziekenhuisOpnames + d2.ziekenhuisOpnames,
        totaal = d1.totaal + d2.totaal,
        overleden = d1.overleden + d2.overleden
      )
  }
}

object CsvDecoders {
  import Model._

  implicit val dataTypeDecoder: CellDecoder[Model.DataType] =
    CellDecoder[String].emap {
      case "Totaal"           => Right(Totaal)
      case "Ziekenhuisopname" => Right(ZiekenhuisOpname)
      case "Overleden"        => Right(Overleden)
    }

  implicit val decoder: HeaderDecoder[RivmDataRow] = HeaderDecoder.decoder(
    "Datum",
    "Gemeentecode",
    "Gemeentenaam",
    "Provincienaam",
    "Provinciecode",
    "Type",
    "Aantal",
    "AantalCumulatief"
  )(RivmDataRow.apply)

  implicit val encoder: HeaderEncoder[GemeenteData] = HeaderEncoder.encoder(
    "Datum",
    "Gemeentecode",
    "Gemeentenaam",
    "Provincienaam",
    "Provinciecode",
    "ZiekenhuisOpnames",
    "Totaal",
    "Overleden"
  )(Function.unlift(GemeenteData.unapply))
}

object Main {
  import CsvDecoders._
  import Model._

  def dateToInstant(date: String): Instant =
    LocalDate
      .parse(date)
      .toDateTime(LocalTime.MIDNIGHT)
      .toDateTime(
        DateTimeZone.forTimeZone(TimeZone.getTimeZone(ZoneId.of("GMT+2")))
      )
      .toInstant

  implicit def scalaDurationAsJodaDuration(
      d: FiniteDuration
  ): org.joda.time.Duration =
    org.joda.time.Duration.millis(d.toMillis)

  def main(cmdlineArgs: Array[String]): Unit = {
    val (sc, args) = ContextAndArgs(cmdlineArgs)

    val inputPath = args("input")
    val outputPath = args("output")

    val rows: SCollection[RivmDataRow] = sc.csvFile[RivmDataRow](inputPath)

    val windowSizeDays = 7

    rows
      .timestampBy(r => dateToInstant(r.datum))
      .withSlidingWindows(size = 7.days, period = 1.day)
      .map(GemeenteData.fromRow)
      .groupMapReduce(_.gemeenteCode)(GemeenteData.add)
      .mapValues(_.divideBy(7))
      .map(_._2)
      .saveAsCsvFile(outputPath)

    sc.run().waitUntilFinish()
  }
}
